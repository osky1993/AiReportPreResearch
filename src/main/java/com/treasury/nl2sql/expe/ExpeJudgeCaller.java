package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI judge 调用器：对单份输出的全部语义（AI 型）规则一次调用逐条判定。
 * 可信度约束（方案 §10.7 + 已确认设计）：
 * <ul>
 *   <li>judge 模型 ≠ 被测模型（qwen3.7-max vs deepseek-v4-pro，跨模型家族）；</li>
 *   <li>temperature=0、强制 JSON 输出；FAIL/PARTIAL 必须附原文引用证据；</li>
 *   <li>judge 结果只作辅助指标；原始响应全量落盘可复查；</li>
 *   <li>judge 调用失败不重试——相关规则记 ERROR（基础设施故障，与样本结论分开）。</li>
 * </ul>
 */
@Component
public class ExpeJudgeCaller {

    private static final Logger log = LoggerFactory.getLogger(ExpeJudgeCaller.class);

    /** 统一业务质量盲评（四组同一把尺子，与规则清单无关；judge 不知道组别）：四维 1—5 整数 */
    public record QualityScore(Integer structure, Integer analysis, Integer expression, Integer usability,
                               String comment) {}

    public record JudgeResult(List<RuleVerdict> verdicts, QualityScore quality, String rawResponse) {}

    private final ExpeProperties.Judge props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public ExpeJudgeCaller(ExpeProperties expeProps, ObjectMapper mapper) {
        this.props = expeProps.getJudge();
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public String model() { return props.getModel(); }

    /** 逐条判定 aiRules；返回按传入顺序对齐的 verdicts（judge 遗漏的规则补 ERROR） */
    public JudgeResult judge(String dataJson, String outputContent, List<Rule> aiRules) {
        String prompt = buildPrompt(dataJson, outputContent, aiRules);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", 0);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("response_format", Map.of("type", "json_object"));
        log.info("[expe-judge] >>> 评审请求 model={} 规则数={}\n----- 输入全文 -----\n{}\n----- 输入结束 -----",
                props.getModel(), aiRules.size(), prompt);

        String resp = http.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            log.info("[expe-judge] <<< 评审响应 model={}\n----- 输出全文 -----\n{}\n----- 输出结束 -----",
                    props.getModel(), content);
            JsonNode verdictArr = mapper.readTree(content).path("verdicts");

            Map<String, RuleVerdict> byId = new LinkedHashMap<>();
            for (JsonNode v : verdictArr) {
                String id = v.path("rule_id").asText();
                String verdict = v.path("verdict").asText();
                if (!List.of("PASS", "PARTIAL", "FAIL").contains(verdict)) verdict = "ERROR";
                byId.put(id, new RuleVerdict(id, verdict,
                        v.path("evidence").asText(null), v.path("reason").asText(null)));
            }
            List<RuleVerdict> ordered = new ArrayList<>();
            for (Rule r : aiRules) {
                ordered.add(byId.getOrDefault(r.ruleId(),
                        new RuleVerdict(r.ruleId(), "ERROR", null, "judge 响应未覆盖该规则")));
            }
            return new JudgeResult(ordered, parseQuality(mapper.readTree(content).path("quality")), resp);
        } catch (Exception e) {
            throw new RuntimeException("解析 judge 响应失败: " + e.getMessage(), e);
        }
    }

    /** 解析统一质量盲评；judge 未返回或维度非法时整体记 null（辅助指标缺失不影响规则判定） */
    private static QualityScore parseQuality(JsonNode q) {
        if (q == null || !q.isObject()) return null;
        Integer structure = dim(q, "structure"), analysis = dim(q, "analysis"),
                expression = dim(q, "expression"), usability = dim(q, "usability");
        if (structure == null && analysis == null && expression == null && usability == null) return null;
        return new QualityScore(structure, analysis, expression, usability, q.path("comment").asText(null));
    }

    private static Integer dim(JsonNode q, String field) {
        JsonNode v = q.get(field);
        if (v == null || !v.canConvertToInt()) return null;
        int n = v.asInt();
        return (n >= 1 && n <= 5) ? n : null;
    }

    private String buildPrompt(String dataJson, String outputContent, List<Rule> aiRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一名严格、公正的政务报告评审员。请逐条判定下列规则是否被待评报告满足。

                【判定纪律】
                - 只依据下方给定的数据包与待评报告原文判定，不得引入任何外部知识；
                - 每条规则独立判定，verdict 只能是 PASS / PARTIAL / FAIL 之一（PARTIAL=大体满足但存在轻微违背）；
                - verdict 为 FAIL 或 PARTIAL 时，必须在 evidence 中引用报告原文片段作为证据，并在 reason 中简述理由；
                - 标注为【冲突对】的规则：若报告明确识别了规则间冲突并说明取舍或请求澄清，应判 PASS；
                - 必须逐条覆盖全部规则，不得遗漏、不得新增。

                【统一业务质量盲评】
                完成规则判定后，另以政务简报的通用业务标准对报告整体打分。该打分与上述规则无关，
                无论报告依据何种要求生成，评分标准完全一致，四个维度各给 1—5 的整数：
                - structure：结构清晰度（层次与脉络是否一目了然）；
                - analysis：分析价值（是否给出有业务含义的判断而非罗列数字）；
                - expression：政务表达规范（书面、客观、克制）；
                - usability：可直接使用程度（5=不修改即可上报，1=需要重写）。

                【输出格式】严格按如下 JSON 结构输出，不输出其他内容：
                {"verdicts":[{"rule_id":"...","verdict":"PASS|PARTIAL|FAIL","evidence":"...","reason":"..."}],
                 "quality":{"structure":1,"analysis":1,"expression":1,"usability":1,"comment":"一句话总评"}}

                【数据包（事实核对的唯一依据）】
                """);
        sb.append(dataJson).append("\n\n【待评报告原文】\n").append(outputContent).append("\n\n【规则清单】\n");
        for (Rule r : aiRules) {
            sb.append("- ").append(r.ruleId());
            if (!r.conflictWith().isEmpty()) {
                sb.append("【冲突对，与 ").append(String.join("、", r.conflictWith())).append("】");
            }
            sb.append("：").append(r.passRule()).append('\n');
        }
        return sb.toString();
    }
}
