package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ④ 事实构建纯逻辑单测（无 DB/LLM）：取值断言、display 渲染、环比与净流入派生。 */
class FactBuildStepTest {

    private final FactBuildStep step = new FactBuildStep(new ObjectMapper());

    private static MetricDefinition metric(String id, String unit, boolean comparable, String nullPolicy) {
        return new MetricDefinition(id, "指标" + id, unit, true, comparable, "v", nullPolicy,
                List.of(MetricDefinition.CHECK_NON_NEGATIVE), null, null);
    }

    private static MetricQuerySpec spec(String id, String metricId, String purpose, String label) {
        return new MetricQuerySpec(id, metricId, "ch1", purpose, label, "2026-06-22", "2026-06-28");
    }

    private static FetchStep.FetchResult result(MetricQuerySpec s, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", value);
        return new FetchStep.FetchResult(s, "select 1", "hash", List.of(row), "rhash");
    }

    private static Outline outline(String comparison, String... metricIds) {
        return new Outline("tpl", "2026-W26",
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), comparison, "g")),
                List.of());
    }

    @Test
    void buildsBaseFactAndWeekOverWeek() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(outline("week_over_week", "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-W26"), 4),
                result(spec("qs2", "m1", "COMPARE", "2026-W25"), 3)), defs);
        assertEquals(3, facts.facts().size());   // 本期 + 对比期 + 环比
        FactRecord wow = facts.facts().get(2);
        assertEquals("fact_001_wow", wow.factKey());
        assertEquals(FactRecord.TYPE_DERIVED, wow.factType());
        // (4-3)/3*100 = 33.3%
        assertEquals(0, wow.value().compareTo(new BigDecimal("33.3")));
        assertEquals("+33.3%", wow.displayValue());
        assertEquals("fact_001,fact_002", wow.derivedFrom());
    }

    @Test
    void zeroBaselineSkipsWowWithNote() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(outline("week_over_week", "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-W26"), 4),
                result(spec("qs2", "m1", "COMPARE", "2026-W25"), 0)), defs);
        assertEquals(2, facts.facts().size());   // 无环比 fact
        assertEquals(1, facts.notes().size());
        assertTrue(facts.notes().get(0).contains("基数为 0"));
    }

    @Test
    void netInflowDerivedFromTwoBaseFacts() {
        Map<String, MetricDefinition> defs = Map.of(
                "in", metric("in", "CNY", false, "ZERO"),
                "out", metric("out", "CNY", false, "ZERO"),
                "net", new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO", List.of(),
                        null, new MetricDefinition.Derived("subtract", "in", "out")));
        var facts = step.run(outline(null, "in", "out", "net"), List.of(
                result(spec("qs1", "in", "CURRENT", "2026-W26"), new BigDecimal("3000000")),
                result(spec("qs2", "out", "CURRENT", "2026-W26"), new BigDecimal("2800000"))), defs);
        FactRecord net = facts.facts().get(2);
        assertEquals("net", net.metricId());
        assertEquals(0, net.value().compareTo(new BigDecimal("200000")));
        assertEquals("20.00 万元", net.displayValue());
        assertEquals("fact_001,fact_002", net.derivedFrom());
        assertNull(net.sqlText());
    }

    @Test
    void displayRendering() {
        assertEquals("6,570.00 万元", FactBuildStep.renderDisplay(new BigDecimal("65700000"), "CNY"));
        assertEquals("1,234.56 元", FactBuildStep.renderDisplay(new BigDecimal("1234.56"), "CNY"));
        assertEquals("-1.50 万元", FactBuildStep.renderDisplay(new BigDecimal("-15000"), "CNY"));
        assertEquals("+12.5%", FactBuildStep.renderDisplay(new BigDecimal("12.45"), "percent"));
        assertEquals("-8.3%", FactBuildStep.renderDisplay(new BigDecimal("-8.31"), "percent"));
        assertEquals("0.0%", FactBuildStep.renderDisplay(BigDecimal.ZERO, "percent"));
        assertEquals("4 笔", FactBuildStep.renderDisplay(new BigDecimal("4"), "笔"));
    }

    @Test
    void nonNegativeAssertionFailsClosed() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "CNY", false, "ZERO"));
        PolicyException e = assertThrows(PolicyException.class, () ->
                step.run(outline(null, "m1"), List.of(
                        result(spec("qs1", "m1", "CURRENT", "2026-W26"), new BigDecimal("-1"))), defs));
        assertTrue(e.getMessage().contains("NON_NEGATIVE"));
    }

    @Test
    void nullValueHonorsNullPolicy() {
        Map<String, MetricDefinition> zeroDefs = Map.of("m1", metric("m1", "CNY", false, "ZERO"));
        var facts = step.run(outline(null, "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-W26"), null)), zeroDefs);
        assertEquals(0, facts.facts().get(0).value().signum());

        Map<String, MetricDefinition> blockDefs = Map.of("m1", metric("m1", "CNY", false, "BLOCK"));
        assertThrows(PolicyException.class, () ->
                step.run(outline(null, "m1"), List.of(
                        result(spec("qs1", "m1", "CURRENT", "2026-W26"), null)), blockDefs));
    }

    @Test
    void wrongRowCountFailsClosed() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", false, "ZERO"));
        MetricQuerySpec s = spec("qs1", "m1", "CURRENT", "2026-W26");
        FetchStep.FetchResult empty = new FetchStep.FetchResult(s, "select 1", "h", List.of(), "rh");
        assertThrows(PolicyException.class, () -> step.run(outline(null, "m1"), List.of(empty), defs));
    }
}
