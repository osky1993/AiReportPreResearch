package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricAnomalyRule;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异常检测（Phase05 契约1，纯程序零 LLM）：④ 之后按指标声明的 anomalyRules 评估，
 * 命中产 ANOMALY fact（`<currentFactKey>_anom`，TYPE_DERIVED，qualityNote 记规则原文）——
 * **异动是程序判定的事实，不是 LLM 的观点**。每指标至多一条 _anom（首个命中规则胜出）；
 * 每次运行 ANOMALY 总数超上限 → BLOCKED（纪律 9 不截断）。规则声明缺比较 fact（如周报无 mom）
 * → 该规则静默不适用（不是失败：粒度天然不匹配）。
 */
public final class AnomalyDetector {

    private static final Set<String> COMPARISON_SUFFIXES = Set.of("_wow", "_mom", "_qoq", "_yoy");

    private AnomalyDetector() {}

    /** @return 追加的 ANOMALY facts；notes 就地追加说明（与 FactBuild notes 同流入 ⑤）。 */
    public static List<FactRecord> detect(Outline outline, List<FactRecord> facts,
                                          Map<String, MetricDefinition> defs, List<String> notes) {
        List<FactRecord> anomalies = new ArrayList<>();
        LinkedHashSet<String> metricIds = new LinkedHashSet<>();
        for (Outline.OutlineChapter ch : outline.chapters()) {
            metricIds.addAll(ch.metricIds());
        }
        for (String metricId : metricIds) {
            MetricDefinition def = defs.get(metricId);
            if (def == null || def.anomalyRules() == null || def.anomalyRules().isEmpty()) continue;
            FactRecord cur = currentFactOf(metricId, facts);
            if (cur == null) continue;   // 维度指标等无单值本期 fact 的形态：规则不适用
            for (MetricDefinition.AnomalyRule rule : def.anomalyRules()) {
                FactRecord anomaly = evaluate(rule, def, cur, facts);
                if (anomaly != null) {
                    anomalies.add(anomaly);
                    notes.add("指标「" + def.name() + "」触发异常规则（" + anomaly.qualityNote() + "），"
                            + "已产异动事实 " + anomaly.factKey());
                    break;   // 每指标至多一条 _anom，首个命中规则胜出
                }
            }
        }
        if (anomalies.size() > MetricAnomalyRule.MAX_ANOMALIES_PER_RUN) {
            throw new PolicyException("本次运行异动事实 " + anomalies.size() + " 条超上限 "
                    + MetricAnomalyRule.MAX_ANOMALIES_PER_RUN + "（失败关闭转人工：请检查异常规则阈值是否过敏）");
        }
        return anomalies;
    }

    private static FactRecord evaluate(MetricDefinition.AnomalyRule rule, MetricDefinition def,
                                       FactRecord cur, List<FactRecord> facts) {
        if (MetricDefinition.AnomalyRule.TYPE_THRESHOLD.equals(rule.type())) {
            boolean hit = switch (rule.op()) {
                case ">=" -> cur.value().compareTo(rule.value()) >= 0;
                case "<=" -> cur.value().compareTo(rule.value()) <= 0;
                case ">" -> cur.value().compareTo(rule.value()) > 0;
                case "<" -> cur.value().compareTo(rule.value()) < 0;
                default -> throw new PolicyException("异常规则非法 op（校验应已拦截）: " + rule.op());
            };
            if (!hit) return null;
            String note = "threshold: 本期值 " + cur.value().stripTrailingZeros().toPlainString()
                    + " " + rule.op() + " " + rule.value().stripTrailingZeros().toPlainString();
            return anomalyFact(cur, def, cur.value(), cur.unit(), cur.factKey(), note);
        }
        // volatility：找对应比较 fact（粒度不匹配 → 静默不适用）
        FactRecord cmp = facts.stream()
                .filter(f -> f.factKey().equals(cur.factKey() + "_" + rule.basis()))
                .findFirst().orElse(null);
        if (cmp == null) return null;
        if (cmp.value().abs().compareTo(rule.absPct()) < 0) return null;
        String note = "volatility " + rule.basis() + ": |" + cmp.displayValue() + "| ≥ "
                + rule.absPct().stripTrailingZeros().toPlainString() + "%";
        return anomalyFact(cur, def, cmp.value(), "percent", cur.factKey() + "," + cmp.factKey(), note);
    }

    private static FactRecord anomalyFact(FactRecord cur, MetricDefinition def, BigDecimal value,
                                          String unit, String derivedFrom, String ruleNote) {
        return new FactRecord(
                cur.factKey() + "_anom", cur.metricId(), cur.metricVersion(),
                def.name() + "（异动）", cur.chapterId(),
                FactRecord.TYPE_DERIVED, value, unit, FactBuildStep.renderDisplay(value, unit),
                cur.periodLabel(), null, null, null, null, null,
                derivedFrom, FactRecord.QUALITY_PASSED, ruleNote);
    }

    /** 指标的本期单值 fact：首个「无比较后缀、无维度、非 _anom」且 periodLabel=报告期的 fact。 */
    private static FactRecord currentFactOf(String metricId, List<FactRecord> facts) {
        for (FactRecord f : facts) {
            if (!f.metricId().equals(metricId) || f.dimensions() != null) continue;
            String key = f.factKey();
            if (key.endsWith("_anom") || key.endsWith("_share")) continue;
            boolean comparisonFact = COMPARISON_SUFFIXES.stream().anyMatch(key::endsWith);
            if (comparisonFact) continue;
            // facts 构建顺序 CURRENT 在前（SpecResolve 块序保证），首个即本期
            return f;
        }
        return null;
    }
}
