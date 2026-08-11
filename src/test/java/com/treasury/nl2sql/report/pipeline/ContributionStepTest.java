package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * ⑤ 维度贡献拆解单测（纯逻辑）。在存在异常 fact 时，用当前/基期维度行计算贡献增量与占比：
 * 贡献额 = 本期 - 基期；基期缺行按 0 处理；占比在总变化为 0 时跳过并记录说明。
 */
@ExtendWith(MockitoExtension.class)
class ContributionStepTest {

    @Mock
    private FetchStep fetchStep;

    private static MetricDefinition dimMetric() {
        return new MetricDefinition("by_ccy", "按币种拆解", "CNY", true, false, "v", "ZERO",
                List.of(), List.of("currency"), null, null, null);
    }

    private static AnomalyDetector.Anomaly anomaly(String dimensionMetricId) {
        FactRecord anom = new FactRecord("fact_002_anom", "m1", 1, "指标m1（异动）", "ch1",
                FactRecord.TYPE_DERIVED, new BigDecimal("142.2"), "percent", "+142.2%", "2026-W26",
                null, null, null, null, null, "fact_002,fact_002_wow", FactRecord.QUALITY_PASSED, "volatility wow");
        return new AnomalyDetector.Anomaly(anom,
                new MetricDefinition.AnomalyRule("volatility", null, null, "wow", new BigDecimal("30"), dimensionMetricId));
    }

    private static FetchStep.FetchResult rowsOf(String purpose, Object[][] currencyValues) {
        MetricQuerySpec spec = new MetricQuerySpec("cs_001", "by_ccy", "ch1", purpose, "x", "a", "b");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] cv : currencyValues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currency", cv[0]);
            row.put("v", cv[1]);
            rows.add(row);
        }
        return new FetchStep.FetchResult(spec, "select", "h", rows, "rh");
    }

    /**
     * 输入：异常触发指标 + 两期维度值（包含消失维度）。
     * 预期：每个维度都产出 contrib 与 contrib_share；缺失维度可产生负贡献，符合消失成本含义。
     */
    @Test
    void contributionAndShareFactsAreBuilt() {
        // 本期 CNY 385万/USD 1436；基期 CNY 159万/EUR 5000（EUR 本期消失 → 负贡献）
        when(fetchStep.run(anyList(), anyMap())).thenReturn(
                List.of(rowsOf("CONTRIB_CURRENT", new Object[][]{
                        {"CNY", new BigDecimal("3850000")}, {"USD", new BigDecimal("1436")}})),
                List.of(rowsOf("CONTRIB_BASE", new Object[][]{
                        {"CNY", new BigDecimal("1590100")}, {"EUR", new BigDecimal("5000")}})));
        List<String> notes = new ArrayList<>();
        ContributionStep step = new ContributionStep(fetchStep);
        List<FactRecord> out = step.build(List.of(anomaly("by_ccy")),
                PeriodResolver.resolve("2026-W26"), Map.of("by_ccy", dimMetric()), notes);

        // 3 个维度值 ×（贡献额 + 占比）= 6
        assertEquals(6, out.size());
        FactRecord cny = out.get(0);
        assertEquals("fact_002_anom_cny_contrib", cny.factKey());
        assertEquals(0, cny.value().compareTo(new BigDecimal("2259900")));
        assertEquals(Map.of("currency", "CNY"), cny.dimensions());
        assertEquals("fact_002_anom", cny.derivedFrom());
        FactRecord cnyShare = out.get(1);
        assertEquals("fact_002_anom_cny_contrib_share", cnyShare.factKey());
        // 总变化 = 2259900 + 1436 - 5000 = 2256336；CNY 占 100.2%（消失维度负贡献可使占比 >100%）
        assertEquals(0, cnyShare.value().compareTo(new BigDecimal("100.2")));
        FactRecord eur = out.get(4);
        assertEquals("fact_002_anom_eur_contrib", eur.factKey());
        assertEquals(0, eur.value().compareTo(new BigDecimal("-5000")));
    }

    /**
     * 输入：无波动类异常或缺失贡献维度指标。
     * 预期：不执行拆解，返回空集合。
     */
    @Test
    void nonVolatilityOrNoDimensionAnomaliesAreSkipped() {
        ContributionStep step = new ContributionStep(fetchStep);
        assertTrue(step.build(List.of(anomaly(null)),
                PeriodResolver.resolve("2026-W26"), Map.of("by_ccy", dimMetric()), new ArrayList<>()).isEmpty());
    }

    /**
     * 输入：异常引用了不存在的 metricId。
     * 预期：直接抛 PolicyException，保证引用一致性不被静默忽略。
     */
    @Test
    void missingDimensionMetricFailsClosed() {
        ContributionStep step = new ContributionStep(fetchStep);
        assertThrows(PolicyException.class, () -> step.build(List.of(anomaly("ghost")),
                PeriodResolver.resolve("2026-W26"), Map.of("by_ccy", dimMetric()), new ArrayList<>()));
    }

    /**
     * 输入：总变化为 0 时。
     * 预期：仅保留贡献额，不输出占比，避免 0 分母误导比例值。
     */
    @Test
    void zeroTotalDeltaSkipsShareWithNote() {
        when(fetchStep.run(anyList(), anyMap())).thenReturn(
                List.of(rowsOf("CONTRIB_CURRENT", new Object[][]{{"CNY", new BigDecimal("100")}})),
                List.of(rowsOf("CONTRIB_BASE", new Object[][]{{"CNY", new BigDecimal("100")}})));
        List<String> notes = new ArrayList<>();
        ContributionStep step = new ContributionStep(fetchStep);
        List<FactRecord> out = step.build(List.of(anomaly("by_ccy")),
                PeriodResolver.resolve("2026-W26"), Map.of("by_ccy", dimMetric()), notes);
        assertEquals(1, out.size());   // 仅贡献额（0），无占比
        assertTrue(notes.get(0).contains("跳过贡献占比"));
    }
}
