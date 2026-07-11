package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.ChartDef;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 图表数据绑定单测（确定性程序零 LLM）：series 旧→新+本期收尾、dimension 行组、绑定产物过 ChartAuditor 自证。 */
class ChartBuildStepTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChartBuildStep step = new ChartBuildStep(mapper);

    private static FactRecord seriesFact(String key, String label, String value) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, "m1", 1, "指标m1（序列·" + label + "）", "ch1", FactRecord.TYPE_BASE,
                v, "CNY", FactBuildStep.renderDisplay(v, "CNY"), label,
                null, "{\"purpose\":\"CHART_SERIES\"}", null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private static FactRecord currentFact(String key, String metricId, String value) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, metricId, 1, "指标" + metricId, "ch1", FactRecord.TYPE_BASE,
                v, "CNY", FactBuildStep.renderDisplay(v, "CNY"), "2026-W26",
                null, "{\"purpose\":\"CURRENT\"}", null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private static FactRecord dimFact(String key, String metricId, String currency, String value) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, metricId, 1, "指标（" + currency + "）", "ch1", FactRecord.TYPE_BASE,
                v, "CNY", FactBuildStep.renderDisplay(v, "CNY"), "2026-W26",
                Map.of("currency", currency), "{\"purpose\":\"CURRENT\"}", null, null, null, null,
                FactRecord.QUALITY_PASSED, null);
    }

    private static Outline outlineWith(ChartDef... charts) {
        return new Outline("tpl", "2026-W26", List.of(
                new Outline.OutlineChapter("ch1", "一", List.of("m1", "m2"), null, null, "g", null, List.of(charts))),
                List.of());
    }

    @Test
    void seriesChartBindsHistoryThenCurrentAndPassesAuditor() {
        ChartDef trend = new ChartDef("trend_x", "line", "近三周趋势",
                new ChartDef.Binding("series", "m1", 3));
        List<FactRecord> facts = List.of(
                currentFact("fact_001", "m1", "300"),
                seriesFact("fact_c01_s1", "2026-W24", "100"),
                seriesFact("fact_c01_s2", "2026-W25", "200"));
        List<ChartRecord> charts = step.build(outlineWith(trend), facts, new ArrayList<>());
        assertEquals(1, charts.size());
        ChartRecord c = charts.get(0);
        assertEquals(List.of("fact_c01_s1", "fact_c01_s2", "fact_001"), c.boundFactKeys());
        assertTrue(c.optionJson().contains("\"2026-W24\""));
        // 自证：绑定产物必过 ⑥ 图表核对
        Map<String, FactRecord> byKey = Map.of("fact_001", facts.get(0),
                "fact_c01_s1", facts.get(1), "fact_c01_s2", facts.get(2));
        assertTrue(ChartAuditor.passed(ChartAuditor.audit(mapper, charts, byKey)));
    }

    @Test
    void dimensionChartBindsRowFactsOnly() {
        ChartDef pie = new ChartDef("mix_x", "pie", "币种构成",
                new ChartDef.Binding("dimension", "m2", null));
        FactRecord cny = dimFact("fact_002_cny", "m2", "CNY", "750");
        FactRecord usd = dimFact("fact_002_usd", "m2", "USD", "250");
        // 合计（DERIVED 无维度）与占比（DERIVED 有维度）都不进图
        FactRecord total = new FactRecord("fact_002", "m2", 1, "合计", "ch1", FactRecord.TYPE_DERIVED,
                new BigDecimal("1000"), "CNY", "1,000.00 元", "2026-W26",
                null, null, null, null, null, "fact_002_cny,fact_002_usd", FactRecord.QUALITY_PASSED, null);
        List<ChartRecord> charts = step.build(outlineWith(pie), List.of(cny, usd, total), new ArrayList<>());
        assertEquals(1, charts.size());
        assertEquals(List.of("fact_002_cny", "fact_002_usd"), charts.get(0).boundFactKeys());
        assertTrue(charts.get(0).optionJson().contains("\"CNY\""));
    }

    @Test
    void chartWithNoBindableFactsIsSkippedWithNote() {
        ChartDef trend = new ChartDef("trend_x", "line", "趋势", new ChartDef.Binding("series", "m1", 3));
        List<String> notes = new ArrayList<>();
        assertTrue(step.build(outlineWith(trend), List.of(), notes).isEmpty());
        assertTrue(notes.get(0).contains("跳过"));
    }
}
