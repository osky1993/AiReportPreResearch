package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ① 需求 → 大纲（LLM 智能节点，同步）。三段式模板匹配：
 *   候选召回（程序：keywords + embedding，见 {@link TemplateMatcher}）
 *   → LLM 在候选 id 中单选（合并进本步唯一一次 LLM 调用，不加时延）
 *   → 服务端把关（选出的 id 必须在候选集内，否则失败关闭并携带候选清单）。
 * LLM 只做三件事：在候选中选模板、抽取报告期标签（只输出如 2026-W26 / 2026-M06 / 2026-Q2 的标签，
 * **不产日期**——窗口由 PeriodResolver 推导，周期粒度须在模板 periodTypes 内，程序把关）、
 * 把映射不上的指标表述放进 unresolved。
 * 章节树与推荐指标来自命中模板（固定），LLM 不发明章节；人确认（HITL 卡点1）后口径锁死。
 */
@Component
public class OutlineStep {

    private static final Logger log = LoggerFactory.getLogger(OutlineStep.class);

    private final LlmClient llm;
    private final ReportAssetService assets;
    private final TemplateMatcher matcher;
    private final ObjectMapper mapper;

    public OutlineStep(LlmClient llm, ReportAssetService assets, TemplateMatcher matcher, ObjectMapper mapper) {
        this.llm = llm;
        this.assets = assets;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    /** @param revisionNote HITL 卡点1 打回时带的修改意见（首次为 null）。 */
    public Outline run(String requestText, String revisionNote) {
        // 第一段：程序召回候选。给不出候选直接失败关闭，LLM 无从下手也不该猜。
        List<TemplateMatcher.Candidate> candidates = matcher.recall(requestText);
        if (candidates.isEmpty()) {
            throw new PolicyException("需求无法匹配任何报告模板。可用模板：" + allTemplatesText());
        }
        log.info("[OUTLINE] 候选召回: {}", TemplateMatcher.describe(candidates));

        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt(candidates)));
        String user = "报告需求：" + requestText
                + (revisionNote == null ? "" : "\n业务人员打回意见（必须吸收）：" + revisionNote)
                + "\n请输出解析 JSON。";
        conversation.add(LlmClient.Message.user(user));

        JsonNode node = completeWithOneRetry(conversation);

        if (node.path("unanswerable").asBoolean(false)) {
            throw new PolicyException(node.path("reason").asText("需求无法匹配任何报告模板")
                    + "；候选模板：" + TemplateMatcher.describe(candidates));
        }
        // 第三段：服务端把关——选出的模板必须在候选集内，不在则失败关闭，不猜测补全。
        String templateId = node.path("templateId").isTextual() ? node.path("templateId").asText() : null;
        String chosen = templateId;
        boolean inCandidates = candidates.stream().anyMatch(c -> c.templateId().equals(chosen));
        if (templateId == null || !inCandidates) {
            throw new PolicyException("无法从需求中确定报告模板（模型输出: " + templateId
                    + "）。候选模板：" + TemplateMatcher.describe(candidates));
        }
        ReportTemplateDef tpl = assets.template(templateId)
                .orElseThrow(() -> new IllegalStateException("候选模板不在注册表中: " + chosen));

        String periodLabel = node.path("periodLabel").isTextual() ? node.path("periodLabel").asText() : null;
        // 解析不了直接失败关闭（PolicyException），不猜测补全
        PeriodResolver.Window window = PeriodResolver.resolve(periodLabel);
        // 周期-模板匹配把关：解析出的粒度必须在模板声明的 periodTypes 内（服务端把关，不信 LLM 选择）
        String granularity = PeriodResolver.granularity(window.label());
        if (!tpl.effectivePeriodTypes().contains(granularity)) {
            throw new PolicyException("模板「" + tpl.name() + "」不支持 " + granularity + " 粒度的报告期（支持: "
                    + String.join("/", tpl.effectivePeriodTypes()) + "，当前报告期 " + window.label()
                    + "）；请调整报告期或换用相应周期的模板");
        }

        List<String> unresolved = new ArrayList<>();
        for (JsonNode u : node.path("unresolved")) {
            if (u.isTextual() && !u.asText().isBlank()) unresolved.add(u.asText());
        }

        List<Outline.OutlineChapter> chapters = tpl.chapters().stream()
                .map(c -> new Outline.OutlineChapter(c.chapterId(), c.title(),
                        new ArrayList<>(c.metrics()), c.comparison(), c.comparisons(), c.guidance(), c.stylePrompt()))
                .toList();
        log.info("[OUTLINE] 模板={}, 报告期={}, unresolved={}", templateId, window.label(), unresolved);
        return new Outline(templateId, window.label(), chapters, unresolved);
    }

    private JsonNode completeWithOneRetry(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        log.info("[OUTLINE] LLM 输出: {}", raw);
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception first) {
            conversation.add(LlmClient.Message.assistant(raw));
            conversation.add(LlmClient.Message.user("上面的输出不是合法 JSON：" + first.getMessage()
                    + "。请只输出合法 JSON。"));
            String retry = llm.completeJson(conversation);
            log.info("[OUTLINE] LLM 重试输出: {}", retry);
            try {
                return mapper.readTree(stripFence(retry));
            } catch (Exception second) {
                throw new PolicyException("需求解析输出无法解析为 JSON（重试 1 次仍失败）: " + second.getMessage());
            }
        }
    }

    private String systemPrompt(List<TemplateMatcher.Candidate> candidates) {
        StringBuilder templateLines = new StringBuilder();
        for (TemplateMatcher.Candidate c : candidates) {
            ReportTemplateDef tpl = assets.template(c.templateId())
                    .orElseThrow(() -> new IllegalStateException("候选模板不在注册表中: " + c.templateId()));
            templateLines.append("- templateId: ").append(tpl.templateId())
                    .append("（").append(tpl.name()).append("；关键词: ")
                    .append(String.join("、", tpl.keywords())).append("）\n")
                    .append("  章节与推荐指标（固定，不需要你改动）：\n");
            for (ReportTemplateDef.ChapterDef ch : tpl.chapters()) {
                templateLines.append("    - ").append(ch.title()).append("（推荐指标: ")
                        .append(String.join(", ", ch.metrics())).append("）\n");
            }
        }
        return """
            你是报告需求解析器。从业务人员的自然语言需求中选出报告模板并抽取报告期，只输出一个 JSON 对象，
            不要解释、不要 markdown 代码块。

            ## 候选报告模板（只允许从中单选一个）
            %s
            ## 可用指标目录
            %s
            ## 输出 JSON 结构
            {
              "templateId": "命中的模板 id；需求与所有候选模板都明显不符时为 null",
              "periodLabel": "报告期标签，如 2026-W26 / 2026-M06 / 2026-Q2；无法确定为 null",
              "unresolved": ["需求中点名要求、但指标目录里找不到对应项的表述（没有则空数组）"],
              "unanswerable": false,
              "reason": "templateId 或 periodLabel 为 null 时说明原因"
            }

            ## 规则
            - templateId 只允许取候选清单中的 id，禁止发明新 id；多个候选都像时选最贴合需求措辞的那个，
              实在无法区分则 templateId=null 并在 reason 里说明——不要猜。
            - periodLabel 只允许三种标签：ISO 周（YYYY-Www）、月（YYYY-Mmm）、季（YYYY-Qn）。
              需求说「2026 年第 26 周」→ "2026-W26"；「2026 年 6 月」→ "2026-M06"；「2026 年二季度」→ "2026-Q2"。
              需求要求年报、或没有给出可确定的周期，则 periodLabel=null 并在 reason 里说明——不要猜。
            - 不要输出任何具体日期，日期窗口由程序推导。
            - 需求里点名的统计口径若能对应指标目录中的某一项，视为已覆盖；对应不上的放进 unresolved 原样列出。
            - 需求与所有候选模板完全无关时输出 {"unanswerable": true, "reason": "..."}。
            """.formatted(templateLines, assets.metricCatalogText());
    }

    private String allTemplatesText() {
        return assets.allTemplates().stream()
                .map(t -> t.templateId() + "(" + t.name() + ")")
                .collect(Collectors.joining("、"));
    }

    private static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t.trim();
    }
}
