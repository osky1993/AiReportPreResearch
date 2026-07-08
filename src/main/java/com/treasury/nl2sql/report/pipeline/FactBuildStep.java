package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ④ 事实构建（程序，不是 LLM）：查询结果 → FactRecord。
 * 三类产物：BASE（取数值，含对比期）、DERIVED 派生指标（如净流入=流入−流出）、
 * DERIVED 环比（(本期−对比期)/对比期，unit=percent）。
 * display_value 在这里一次性渲染定死——⑤ 的占位符替换与 ⑥ 的回读核对都以它为准。
 * 质量断言失败 → 失败关闭；对比期基数为 0 → 仅跳过该环比并留 note（报告里就不提环比）。
 */
@Component
public class FactBuildStep {

    private static final Logger log = LoggerFactory.getLogger(FactBuildStep.class);

    private final ObjectMapper mapper;

    public FactBuildStep(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record FactBuildResult(List<FactRecord> facts, List<String> notes) {}

    public FactBuildResult run(Outline outline, List<FetchStep.FetchResult> fetched,
                               Map<String, MetricDefinition> metricDefs) {
        List<FactRecord> facts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // metricId → purpose → fact（派生与环比计算的查找索引）
        Map<String, Map<String, FactRecord>> byMetric = new LinkedHashMap<>();
        int[] seq = {0};

        // 1) BASE：每条取数结果一条事实（含对比期）
        for (FetchStep.FetchResult fr : fetched) {
            MetricQuerySpec spec = fr.spec();
            MetricDefinition def = require(metricDefs, spec.metricId());
            BigDecimal value = extractValue(def, fr);
            assertQuality(def, value);
            FactRecord fact = new FactRecord(
                    nextKey(seq), spec.metricId(), def.name(), spec.chapterId(),
                    FactRecord.TYPE_BASE, value, def.unit(), renderDisplay(value, def.unit()),
                    spec.periodLabel(), toJson(spec),
                    fr.sql(), fr.sqlHash(), fr.resultHash(), null,
                    FactRecord.QUALITY_PASSED, null);
            facts.add(fact);
            byMetric.computeIfAbsent(spec.metricId(), k -> new HashMap<>()).put(spec.purpose(), fact);
        }

        // 2) DERIVED 派生指标（净流入等）：对两个 BASE 指标做算术，本期/对比期各算一条
        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String metricId : ch.metricIds()) {
                MetricDefinition def = metricDefs.get(metricId);
                if (def == null || !def.isDerivedMetric() || byMetric.containsKey(metricId)) continue;
                Map<String, FactRecord> left = byMetric.get(def.derived().left());
                Map<String, FactRecord> right = byMetric.get(def.derived().right());
                if (left == null || right == null) {
                    throw new PolicyException("派生指标「" + metricId + "」的操作数事实缺失（"
                            + def.derived().left() + " / " + def.derived().right() + "）");
                }
                for (String purpose : List.of(MetricQuerySpec.PURPOSE_CURRENT, MetricQuerySpec.PURPOSE_COMPARE)) {
                    FactRecord l = left.get(purpose);
                    FactRecord r = right.get(purpose);
                    if (l == null || r == null) continue;   // 对比期未取数（非 comparison 章节）则只算本期
                    BigDecimal value = switch (def.derived().op()) {
                        case "subtract" -> l.value().subtract(r.value());
                        default -> throw new PolicyException("派生指标「" + metricId + "」的运算「"
                                + def.derived().op() + "」不支持");
                    };
                    FactRecord fact = new FactRecord(
                            nextKey(seq), metricId, def.name(), ch.chapterId(),
                            FactRecord.TYPE_DERIVED, value, def.unit(), renderDisplay(value, def.unit()),
                            l.periodLabel(), null, null, null, null,
                            l.factKey() + "," + r.factKey(),
                            FactRecord.QUALITY_PASSED, null);
                    facts.add(fact);
                    byMetric.computeIfAbsent(metricId, k -> new HashMap<>()).put(purpose, fact);
                }
            }
        }

        // 3) DERIVED 环比：comparison 章节里 comparable 指标，(本期−对比期)/对比期 → percent
        for (Outline.OutlineChapter ch : outline.chapters()) {
            if (ch.comparison() == null) continue;
            for (String metricId : ch.metricIds()) {
                MetricDefinition def = metricDefs.get(metricId);
                if (def == null || !def.comparable()) continue;
                Map<String, FactRecord> pair = byMetric.get(metricId);
                if (pair == null) continue;
                FactRecord cur = pair.get(MetricQuerySpec.PURPOSE_CURRENT);
                FactRecord cmp = pair.get(MetricQuerySpec.PURPOSE_COMPARE);
                if (cur == null || cmp == null) continue;
                String wowKey = cur.factKey() + "_wow";
                if (facts.stream().anyMatch(f -> f.factKey().equals(wowKey))) continue;   // 多章共享同一环比事实
                if (cmp.value().signum() == 0) {
                    notes.add("指标「" + def.name() + "」对比期（" + cmp.periodLabel() + "）基数为 0，跳过环比");
                    continue;
                }
                BigDecimal wow = cur.value().subtract(cmp.value())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(cmp.value().abs(), 1, RoundingMode.HALF_UP);
                facts.add(new FactRecord(
                        wowKey, metricId, def.name() + "（环比）", ch.chapterId(),
                        FactRecord.TYPE_DERIVED, wow, "percent", renderDisplay(wow, "percent"),
                        cur.periodLabel(), null, null, null, null,
                        cur.factKey() + "," + cmp.factKey(),
                        FactRecord.QUALITY_PASSED, null));
            }
        }

        log.info("[FACT] 构建事实 {} 条（notes {} 条）", facts.size(), notes.size());
        return new FactBuildResult(facts, notes);
    }

    /** 取数结果必须恰 1 行、含 valueColumn；NULL 按 nullPolicy 处置（ZERO=0 / BLOCK=失败关闭）。 */
    private BigDecimal extractValue(MetricDefinition def, FetchStep.FetchResult fr) {
        if (fr.rows().size() != 1) {
            throw new PolicyException("指标「" + def.metricId() + "」取数结果应恰 1 行，实际 " + fr.rows().size() + " 行");
        }
        Map<String, Object> row = fr.rows().get(0);
        if (!row.containsKey(def.valueColumn())) {
            throw new PolicyException("指标「" + def.metricId() + "」取数结果缺少列「" + def.valueColumn()
                    + "」，实际列: " + row.keySet());
        }
        Object v = row.get(def.valueColumn());
        if (v == null) {
            if (MetricDefinition.NULL_ZERO.equals(def.nullPolicy())) {
                return BigDecimal.ZERO;
            }
            throw new PolicyException("指标「" + def.metricId() + "」取数值为 NULL 且 nullPolicy=BLOCK（数据质量失败关闭）");
        }
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        throw new PolicyException("指标「" + def.metricId() + "」取数值类型非数值: " + v.getClass().getSimpleName());
    }

    private void assertQuality(MetricDefinition def, BigDecimal value) {
        if (def.qualityChecks() == null) return;
        if (def.qualityChecks().contains(MetricDefinition.CHECK_NON_NEGATIVE) && value.signum() < 0) {
            throw new PolicyException("指标「" + def.metricId() + "」质量断言 NON_NEGATIVE 失败: 值为 " + value);
        }
    }

    /**
     * 展示串一次性渲染（⑤ 替换与 ⑥ 回读都以此为准）：
     * CNY 且 |值|≥1万 → "6,570.00 万元"；CNY → "1,234.56 元"；percent → "+12.5%"；计数 → "4 笔"。
     */
    static String renderDisplay(BigDecimal value, String unit) {
        DecimalFormat money = new DecimalFormat("#,##0.00");
        if ("CNY".equals(unit)) {
            if (value.abs().compareTo(BigDecimal.valueOf(10000)) >= 0) {
                return money.format(value.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP)) + " 万元";
            }
            return money.format(value.setScale(2, RoundingMode.HALF_UP)) + " 元";
        }
        if ("percent".equals(unit)) {
            BigDecimal p = value.setScale(1, RoundingMode.HALF_UP);
            return (p.signum() > 0 ? "+" : "") + p.toPlainString() + "%";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " " + unit;
    }

    private static String nextKey(int[] seq) {
        return String.format("fact_%03d", ++seq[0]);
    }

    private static MetricDefinition require(Map<String, MetricDefinition> defs, String metricId) {
        MetricDefinition def = defs.get(metricId);
        if (def == null) throw new PolicyException("指标「" + metricId + "」没有语义定义");
        return def;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("规约序列化失败", e);
        }
    }
}
