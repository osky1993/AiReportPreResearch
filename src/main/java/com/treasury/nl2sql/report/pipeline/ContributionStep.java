package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.asset.MetricDimensionRule;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维度贡献拆解（Phase05 契约1，纯程序零 LLM）：对带 dimensionMetricId 的 volatility 异动，
 * 经 ③ 确定性通路（复用 FetchStep：同校验同哈希留痕）取维度指标的本期行与 basis 基期行，
 * 逐维度算「变化贡献额」与「贡献占比」——哪个维度值贡献了变化的大头，由程序说，不由 LLM 猜。
 * 专属 purpose CONTRIB_CURRENT/CONTRIB_BASE，射程限贡献计算，不触碰 P3「维度指标禁 comparable」约束。
 * 基期无某维度行按 0 计（新出现的维度值贡献=本期全额）；总变化为 0 → 跳过占比留 note。
 */
@Component
public class ContributionStep {

    private static final Logger log = LoggerFactory.getLogger(ContributionStep.class);

    static final String PURPOSE_CONTRIB_CURRENT = "CONTRIB_CURRENT";
    static final String PURPOSE_CONTRIB_BASE = "CONTRIB_BASE";

    private final FetchStep fetchStep;

    public ContributionStep(FetchStep fetchStep) {
        this.fetchStep = fetchStep;
    }

    /**
     * @param current 本期窗口（run 的报告期）
     * @return 贡献 facts（额 + 占比）；notes 就地追加
     */
    public List<FactRecord> build(List<AnomalyDetector.Anomaly> anomalies, PeriodResolver.Window current,
                                  Map<String, MetricDefinition> defs, List<String> notes) {
        List<FactRecord> out = new ArrayList<>();
        int seq = 0;
        for (AnomalyDetector.Anomaly a : anomalies) {
            MetricDefinition.AnomalyRule rule = a.rule();
            if (rule.dimensionMetricId() == null
                    || !MetricDefinition.AnomalyRule.TYPE_VOLATILITY.equals(rule.type())) {
                continue;   // 贡献拆解只对声明了维度指标的波动型异动做
            }
            MetricDefinition dimDef = defs.get(rule.dimensionMetricId());
            if (dimDef == null || !dimDef.isDimensional()) {
                throw new PolicyException("异动「" + a.fact().factKey() + "」的贡献维度指标「"
                        + rule.dimensionMetricId() + "」不存在或未声明维度（资产校验应已拦截）");
            }
            PeriodResolver.Window base = baseWindow(current, rule.basis());
            Map<String, BigDecimal> curRows = fetchRows(dimDef, current, PURPOSE_CONTRIB_CURRENT, a, ++seq);
            Map<String, BigDecimal> baseRows = fetchRows(dimDef, base, PURPOSE_CONTRIB_BASE, a, ++seq);

            BigDecimal totalDelta = BigDecimal.ZERO;
            Map<String, BigDecimal> deltas = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : curRows.entrySet()) {
                BigDecimal delta = e.getValue().subtract(baseRows.getOrDefault(e.getKey(), BigDecimal.ZERO));
                deltas.put(e.getKey(), delta);
                totalDelta = totalDelta.add(delta);
            }
            // 基期有、本期消失的维度值：贡献 = -基期值
            for (Map.Entry<String, BigDecimal> e : baseRows.entrySet()) {
                if (curRows.containsKey(e.getKey())) continue;
                BigDecimal delta = e.getValue().negate();
                deltas.put(e.getKey(), delta);
                totalDelta = totalDelta.add(delta);
            }
            String dimBare = MetricDimensionRule.bareName(dimDef.dimensions().get(0));
            java.util.LinkedHashSet<String> usedSlugs = new java.util.LinkedHashSet<>();
            int idx = 0;
            for (Map.Entry<String, BigDecimal> e : deltas.entrySet()) {
                idx++;
                String slug = slugOf(e.getKey(), idx, usedSlugs);
                String key = a.fact().factKey() + "_" + slug + "_contrib";
                out.add(new FactRecord(key, dimDef.metricId(), a.fact().metricVersion(),
                        dimDef.name() + "（" + e.getKey() + "，变化贡献）", a.fact().chapterId(),
                        FactRecord.TYPE_DERIVED, e.getValue(), dimDef.unit(),
                        FactBuildStep.renderDisplay(e.getValue(), dimDef.unit()),
                        current.label(), Map.of(dimBare, e.getKey()), null, null, null, null,
                        a.fact().factKey(), FactRecord.QUALITY_PASSED,
                        "contribution basis=" + rule.basis() + " base=" + base.label()));
                if (totalDelta.signum() != 0) {
                    BigDecimal share = e.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(totalDelta, 1, RoundingMode.HALF_UP);
                    out.add(new FactRecord(key + "_share", dimDef.metricId(), a.fact().metricVersion(),
                            dimDef.name() + "（" + e.getKey() + "，贡献占比）", a.fact().chapterId(),
                            FactRecord.TYPE_DERIVED, share, "percent",
                            FactBuildStep.renderDisplay(share, "percent"),
                            current.label(), Map.of(dimBare, e.getKey()), null, null, null, null,
                            key, FactRecord.QUALITY_PASSED, null));
                }
            }
            if (totalDelta.signum() == 0) {
                notes.add("异动「" + a.fact().metricName() + "」的维度贡献总变化为 0，跳过贡献占比");
            }
        }
        if (!out.isEmpty()) {
            log.info("[CONTRIB] 贡献拆解产出 {} 条 fact", out.size());
        }
        return out;
    }

    /** 经 ③ 确定性通路取维度行 → 维度值→数值（0 行 = 空 map，按 nullPolicy=ZERO 语义空拆解）。 */
    private Map<String, BigDecimal> fetchRows(MetricDefinition dimDef, PeriodResolver.Window w,
                                              String purpose, AnomalyDetector.Anomaly a, int seq) {
        MetricQuerySpec spec = new MetricQuerySpec("cs_" + String.format("%03d", seq),
                dimDef.metricId(), a.fact().chapterId(), purpose,
                w.label(), w.start().toString(), w.end().toString());
        List<FetchStep.FetchResult> results = fetchStep.run(List.of(spec), Map.of(dimDef.metricId(), dimDef));
        Map<String, BigDecimal> rows = new LinkedHashMap<>();
        String dimBare = MetricDimensionRule.bareName(dimDef.dimensions().get(0));
        for (Map<String, Object> row : results.get(0).rows()) {
            Object dv = row.get(dimBare);
            Object v = row.get(dimDef.valueColumn());
            if (dv == null || v == null) continue;
            rows.put(String.valueOf(dv), v instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(v)));
        }
        return rows;
    }

    private static PeriodResolver.Window baseWindow(PeriodResolver.Window current, String basis) {
        return "yoy".equals(basis) ? PeriodResolver.sameLastYear(current) : PeriodResolver.previous(current);
    }

    private static String slugOf(String value, int idx, java.util.Set<String> used) {
        String fallback = String.format("r%02d", idx);
        if (value == null || !value.chars().allMatch(c -> c < 128)) {
            used.add(fallback);
            return fallback;
        }
        String s = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (s.isBlank() || s.chars().allMatch(c -> c == '_') || !used.add(s)) {
            used.add(fallback);
            return fallback;
        }
        return s;
    }
}
