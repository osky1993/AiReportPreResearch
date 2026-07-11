package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归因（Phase05 契约3，LLM 智能节点）：对每条异动，LLM **只在程序给的封闭候选内挑选与措辞**
 * （纪律 11）——候选 = 异动/贡献 fact keys + EventMatcher 的 EVT-n 编号包。
 * 服务端把关（不信 LLM，照 OutlineStep 三段式范式）：
 *  - evidenceRefs ⊆ 候选集，越界 → 失败关闭；
 *  - 等级上限由证据类型推导（仅 fact ≤ associated；含 EVT 方可 hypothesis；confirmed 直接拒绝），
 *    越权 → **降级到证据允许的上限**并留 note（软处理不回灌自愈）；
 *  - 每条异动恰一条 claim：LLM 漏掉的由程序补 observed 兜底（「待查」句式）——完整性由程序保证；
 *  - narrative ≤200 字、禁阿拉伯数字裸写（数值走 {{fact_key}}，⑥ 双检覆盖）。
 */
@Component
public class AttributionStep {

    private static final Logger log = LoggerFactory.getLogger(AttributionStep.class);
    private static final int NARRATIVE_MAX = 200;

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public AttributionStep(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** @param candidatesByAnomaly 异动 factKey → EVT 候选（程序生成） */
    public List<ClaimRecord> run(List<AnomalyDetector.Anomaly> anomalies,
                                 List<FactRecord> contributionFacts,
                                 Map<String, List<EventMatcher.Candidate>> candidatesByAnomaly,
                                 List<String> notes) {
        if (anomalies.isEmpty()) return List.of();

        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt()));
        conversation.add(LlmClient.Message.user(userPrompt(anomalies, contributionFacts, candidatesByAnomaly)));
        JsonNode node = completeWithOneRetry(conversation);

        Map<String, ClaimRecord> byAnomaly = new LinkedHashMap<>();
        int seq = 0;
        for (JsonNode c : node.path("claims")) {
            String anomalyKey = c.path("anomalyFactKey").asText(null);
            AnomalyDetector.Anomaly anomaly = anomalies.stream()
                    .filter(a -> a.fact().factKey().equals(anomalyKey)).findFirst().orElse(null);
            if (anomaly == null) {
                throw new PolicyException("归因引用了不存在的异动事实「" + anomalyKey + "」（候选制被绕过，失败关闭）");
            }
            if (byAnomaly.containsKey(anomalyKey)) continue;   // 每异动一条，多余的忽略
            byAnomaly.put(anomalyKey, gate(c, anomaly, contributionFacts,
                    candidatesByAnomaly.getOrDefault(anomalyKey, List.of()), ++seq, notes));
        }
        // 完整性兜底：LLM 漏掉的异动由程序补 observed「待查」claim
        List<ClaimRecord> claims = new ArrayList<>();
        for (AnomalyDetector.Anomaly a : anomalies) {
            ClaimRecord c = byAnomaly.get(a.fact().factKey());
            if (c == null) {
                c = fallbackClaim(a, ++seq);
                notes.add("异动 " + a.fact().factKey() + " 未获归因叙述，程序补 observed「待查」条目");
            }
            claims.add(c);
        }
        log.info("[ATTR] 归因 {} 条（异动 {} 条）", claims.size(), anomalies.size());
        return claims;
    }

    /** 服务端把关：证据越界失败关闭；等级越权降级；长度与裸数字约束。 */
    private ClaimRecord gate(JsonNode c, AnomalyDetector.Anomaly anomaly, List<FactRecord> contributionFacts,
                             List<EventMatcher.Candidate> candidates, int seq, List<String> notes) {
        String anomalyKey = anomaly.fact().factKey();
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(anomalyKey);
        for (String from : (anomaly.fact().derivedFrom() == null ? "" : anomaly.fact().derivedFrom()).split(",")) {
            if (!from.isBlank()) allowed.add(from.trim());
        }
        contributionFacts.stream().map(FactRecord::factKey)
                .filter(k -> k.startsWith(anomalyKey + "_")).forEach(allowed::add);
        candidates.forEach(cd -> allowed.add(cd.ref()));

        List<String> refs = new ArrayList<>();
        for (JsonNode r : c.path("evidenceRefs")) {
            String ref = r.asText();
            if (!allowed.contains(ref)) {
                throw new PolicyException("归因 claim 引用了候选集之外的证据「" + ref + "」（候选: " + allowed
                        + "）——候选由程序给、LLM 只挑选，越界即失败关闭");
            }
            if (!refs.contains(ref)) refs.add(ref);
        }
        boolean hasEvent = refs.stream().anyMatch(r -> r.startsWith("EVT-"));
        String level = c.path("attributionLevel").asText("observed");
        String cap = hasEvent ? ClaimRecord.LEVEL_HYPOTHESIS
                : (refs.size() > 1 ? ClaimRecord.LEVEL_ASSOCIATED : ClaimRecord.LEVEL_OBSERVED);
        if (rank(level) > rank(cap)) {
            notes.add("claim 对 " + anomalyKey + " 申报等级 " + level + " 超出证据上限，已降级为 " + cap);
            level = cap;
        }
        if (!Set.of(ClaimRecord.LEVEL_OBSERVED, ClaimRecord.LEVEL_ASSOCIATED,
                ClaimRecord.LEVEL_HYPOTHESIS).contains(level)) {
            throw new PolicyException("归因等级非法「" + level + "」（confirmed 仅卡点2 人工勾选可达）");
        }
        String narrative = c.path("narrative").asText("").trim();
        if (narrative.isBlank() || narrative.length() > NARRATIVE_MAX) {
            throw new PolicyException("归因叙述为空或超长（≤" + NARRATIVE_MAX + " 字）: " + anomalyKey);
        }
        return new ClaimRecord(String.format("cl_%03d", seq), anomalyKey, level, refs, narrative, null, null);
    }

    private static int rank(String level) {
        return switch (level) {
            case ClaimRecord.LEVEL_OBSERVED -> 0;
            case ClaimRecord.LEVEL_ASSOCIATED -> 1;
            case ClaimRecord.LEVEL_HYPOTHESIS -> 2;
            default -> 3;   // confirmed 或未知：最高秩，必然触发降级或拒绝
        };
    }

    private static ClaimRecord fallbackClaim(AnomalyDetector.Anomaly a, int seq) {
        return new ClaimRecord(String.format("cl_%03d", seq), a.fact().factKey(),
                ClaimRecord.LEVEL_OBSERVED, List.of(a.fact().factKey()),
                "指标出现异动（{{" + a.fact().factKey() + "}}），变动原因待查，未见关联事件记录。",
                null, null);
    }

    /** LLM 可说/不可说清单（契约3 定稿）——纪律 11：这是提示不是防线，防线在服务端把关与 ⑥ 死代码。 */
    private String systemPrompt() {
        return """
            你是司库报告的异动归因助手。只输出一个 JSON 对象：{"claims":[...]}，不要解释。
            每条 claim：{"anomalyFactKey":"...","attributionLevel":"observed|associated|hypothesis",
            "evidenceRefs":["fact_xxx 或 EVT-n"],"narrative":"..."}。

            ## 可说
            - 从候选事件（EVT-n）中挑选与异动在时间/维度上相关的条目，用**缓和措辞**表述假设
              （「可能与…有关」「或与…有关，待验证」）；
            - 引用贡献拆解事实说明哪个维度值贡献了变化的大头（定性转述）。

            ## 不可说（违反会被服务端拦截或降级）
            - 候选之外的任何事件、原因或背景知识——候选为空就写「变动原因待查，未见关联事件记录」；
            - 确定性因果断言（导致/由于/因为…）——你产出的最高等级是 hypothesis，confirmed 只能由人确认；
            - 任何阿拉伯数字与中文数量表达——数值一律写 {{fact_key}} 占位符（与正文同纪律）；
            - 无事件证据（evidenceRefs 不含 EVT-n）时申报 hypothesis。

            ## 等级语义
            observed=仅陈述异动本身（无事件时的唯一选择，narrative 须含「待查」）；
            associated=引用了贡献/关联事实但无事件；hypothesis=引用了 ≥1 个候选事件的假设性解释。
            """;
    }

    private String userPrompt(List<AnomalyDetector.Anomaly> anomalies, List<FactRecord> contributionFacts,
                              Map<String, List<EventMatcher.Candidate>> candidatesByAnomaly) {
        StringBuilder sb = new StringBuilder("## 待归因异动\n");
        for (AnomalyDetector.Anomaly a : anomalies) {
            FactRecord f = a.fact();
            sb.append("- anomalyFactKey=").append(f.factKey())
                    .append("｜").append(f.metricName())
                    .append("｜display=").append(f.displayValue())
                    .append("｜规则=").append(f.qualityNote()).append('\n');
            List<FactRecord> contribs = contributionFacts.stream()
                    .filter(cf -> cf.factKey().startsWith(f.factKey() + "_")).toList();
            if (!contribs.isEmpty()) {
                sb.append("  贡献拆解事实：").append(String.join("，", contribs.stream()
                        .map(cf -> cf.factKey() + "=" + cf.displayValue()).toList())).append('\n');
            }
            List<EventMatcher.Candidate> cands = candidatesByAnomaly.getOrDefault(f.factKey(), List.of());
            sb.append(cands.isEmpty() ? "  候选事件：（无——只许 observed + 待查）\n"
                    : "  候选事件（只许引用这些编号）：\n" + EventMatcher.renderCandidates(cands));
        }
        sb.append("\n请对每条异动各产出一条 claim，输出 {\"claims\":[...]}。");
        return sb.toString();
    }

    private JsonNode completeWithOneRetry(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        log.info("[ATTR] LLM 输出: {}", raw);
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception first) {
            conversation.add(LlmClient.Message.assistant(raw));
            conversation.add(LlmClient.Message.user("上面的输出不是合法 JSON。请只输出合法 JSON。"));
            String retry = llm.completeJson(conversation);
            try {
                return mapper.readTree(stripFence(retry));
            } catch (Exception second) {
                throw new PolicyException("归因输出无法解析为 JSON（重试 1 次仍失败）: " + second.getMessage());
            }
        }
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
