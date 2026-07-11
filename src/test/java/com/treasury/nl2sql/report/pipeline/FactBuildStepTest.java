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

/** ④ 事实构建纯逻辑单测（无 DB/LLM）：取值断言、display 渲染、环比/同比与净流入派生。 */
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
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), comparison, null, "g", null)),
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
    void metricVersionsPinnedIntoFacts() {
        // 带版本快照：BASE/派生/环比事实均记录各自指标的固化版本；无快照（3 参重载）则为 null
        Map<String, MetricDefinition> defs = Map.of(
                "in", metric("in", "CNY", true, "ZERO"),
                "out", metric("out", "CNY", false, "ZERO"),
                "net", new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO", List.of(),
                        null, new MetricDefinition.Derived("subtract", "in", "out")));
        Map<String, Integer> versions = Map.of("in", 3, "out", 1, "net", 2);
        var facts = step.run(outline("week_over_week", "in", "out", "net"), List.of(
                result(spec("qs1", "in", "CURRENT", "2026-W26"), new BigDecimal("3000000")),
                result(spec("qs2", "in", "COMPARE", "2026-W25"), new BigDecimal("2000000")),
                result(spec("qs3", "out", "CURRENT", "2026-W26"), new BigDecimal("2800000"))), defs, versions);
        for (FactRecord f : facts.facts()) {
            assertEquals(versions.get(f.metricId()), f.metricVersion(),
                    "fact " + f.factKey() + " 应记录指标 " + f.metricId() + " 的固化版本");
        }
        var unpinned = step.run(outline(null, "in"), List.of(
                result(spec("qs1", "in", "CURRENT", "2026-W26"), new BigDecimal("1"))), defs);
        assertNull(unpinned.facts().get(0).metricVersion());
    }

    @Test
    void wrongRowCountFailsClosed() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", false, "ZERO"));
        MetricQuerySpec s = spec("qs1", "m1", "CURRENT", "2026-W26");
        FetchStep.FetchResult empty = new FetchStep.FetchResult(s, "select 1", "h", List.of(), "rh");
        assertThrows(PolicyException.class, () -> step.run(outline(null, "m1"), List.of(empty), defs));
    }

    // ---- Phase03：多比较（环比 _mom + 同比 _yoy 同章并存） ----

    private static Outline monthlyOutline(List<String> comparisons, String... metricIds) {
        return new Outline("tpl", "2026-M06",
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), null, comparisons, "g", null)),
                List.of());
    }

    @Test
    void momAndYoyCoexistInOneChapter() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(monthlyOutline(List.of("month_over_month", "year_over_year"), "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-M06"), 6),
                result(spec("qs2", "m1", "COMPARE", "2026-M05"), 4),
                result(spec("qs3", "m1", "COMPARE_YOY", "2025-M06"), 3)), defs);
        assertEquals(5, facts.facts().size());   // 本期 + 两基期 + _mom + _yoy
        FactRecord mom = facts.facts().get(3);
        assertEquals("fact_001_mom", mom.factKey());
        assertEquals("指标m1（环比）", mom.metricName());
        assertEquals(0, mom.value().compareTo(new BigDecimal("50.0")));   // (6-4)/4
        assertEquals("fact_001,fact_002", mom.derivedFrom());
        FactRecord yoy = facts.facts().get(4);
        assertEquals("fact_001_yoy", yoy.factKey());
        assertEquals("指标m1（同比）", yoy.metricName());
        assertEquals(0, yoy.value().compareTo(new BigDecimal("100.0")));   // (6-3)/3
        assertEquals("+100.0%", yoy.displayValue());
        assertEquals("fact_001,fact_003", yoy.derivedFrom());
    }

    @Test
    void zeroYoyBaselineSkipsOnlyYoyWithDistinctNote() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(monthlyOutline(List.of("month_over_month", "year_over_year"), "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-M06"), 6),
                result(spec("qs2", "m1", "COMPARE", "2026-M05"), 4),
                result(spec("qs3", "m1", "COMPARE_YOY", "2025-M06"), 0)), defs);
        // _mom 照常，_yoy 跳过并留同比措辞的 note
        assertTrue(facts.facts().stream().anyMatch(f -> f.factKey().equals("fact_001_mom")));
        assertTrue(facts.facts().stream().noneMatch(f -> f.factKey().equals("fact_001_yoy")));
        assertEquals(1, facts.notes().size());
        assertTrue(facts.notes().get(0).contains("同比基期"));
        assertTrue(facts.notes().get(0).contains("跳过同比"));
    }

    @Test
    void yoyWithoutYoyBaseFactIsSilentlyAbsent() {
        // 声明了同比但取数只有本期与环比基期（如快照指标）→ 不造 _yoy、不报错
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(monthlyOutline(List.of("year_over_year"), "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-M06"), 6)), defs);
        assertEquals(1, facts.facts().size());
    }
}
