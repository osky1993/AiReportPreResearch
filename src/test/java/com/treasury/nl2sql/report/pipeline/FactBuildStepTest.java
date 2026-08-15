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

/**
 * ④ FactBuildStep 纯逻辑单测（无 DB/LLM）。覆盖本期/比较取数、派生事实计算、展示格式化、
 * 规则校验失败关闭、版本固化与边界场景（空值、行数异常、同比并行等）。
 */
class FactBuildStepTest {

    private final FactBuildStep step = new FactBuildStep(new ObjectMapper());

    private static MetricDefinition metric(String id, String unit, boolean comparable, String nullPolicy) {
        return new MetricDefinition(id, "指标" + id, unit, true, comparable, "v", nullPolicy,
                List.of(MetricDefinition.CHECK_NON_NEGATIVE), null, null, null, null);
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
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), comparison, null, "g", null, null)),
                List.of());
    }

    /**
     * 输入：本期与环比基期两行，指标可比且空值策略为 ZERO。
     * 预期：生成 base+compare+_wow，wow 为 (4-3)/3，并有正确派生来源链与占位位.
     */
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

    /**
     * 输入：环比基期为 0 且 baseline policy 为 ZERO。
     * 预期：不产出 _wow 派生事实，仅写入 note，避免除零污染。
     */
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

    /**
     * 输入：定义了 net 派生指标（in - out）。
     * 预期：从两条 base 输入生成净流入派生，展示值走 CNY 单位逻辑，sqlText 为空。
     */
    @Test
    void netInflowDerivedFromTwoBaseFacts() {
        Map<String, MetricDefinition> defs = Map.of(
                "in", metric("in", "CNY", false, "ZERO"),
                "out", metric("out", "CNY", false, "ZERO"),
                "net", new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO", List.of(),
                        null, null, null, new MetricDefinition.Derived("subtract", "in", "out")));
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

    /**
     * 输入：不同单位渲染。
     * 预期：千元/元/百分比/整数维度展示符合规则，便于 ⑥ NumberAuditor 反解析。
     */
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

    /**
     * 输入：数值违规（负值 + NON_NEGATIVE）时。
     * 预期：抛 PolicyException 并停止生成，避免脏事实下发。
     */
    @Test
    void nonNegativeAssertionFailsClosed() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "CNY", false, "ZERO"));
        PolicyException e = assertThrows(PolicyException.class, () ->
                step.run(outline(null, "m1"), List.of(
                        result(spec("qs1", "m1", "CURRENT", "2026-W26"), new BigDecimal("-1"))), defs));
        assertTrue(e.getMessage().contains("NON_NEGATIVE"));
    }

    /**
     * 输入：取数为 null、不同 nullPolicy 组合。
     * 预期：ZERO policy 转换为 0，BLOCK policy 报错，验证参数化边界。
     */
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

    /**
     * 输入：指标版本 map 提供时。
     * 预期：base/derived/wow 事实均回写 metricVersion；未传版本时应为空。
     */
    @Test
    void metricVersionsPinnedIntoFacts() {
        // 带版本快照：BASE/派生/环比事实均记录各自指标的固化版本；无快照（3 参重载）则为 null
        Map<String, MetricDefinition> defs = Map.of(
                "in", metric("in", "CNY", true, "ZERO"),
                "out", metric("out", "CNY", false, "ZERO"),
                "net", new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO", List.of(),
                        null, null, null, new MetricDefinition.Derived("subtract", "in", "out")));
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

    /**
     * 输入：查询返回 0 行。
     * 预期：抛 PolicyException，失败关闭；不允许将“查不到”静默当 0。
     */
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
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), null, comparisons, "g", null, null)),
                List.of());
    }

    /**
     * 输入：月度同时声明 MOM 与 YOY，两条对比基期均存在。
     * 预期：facts 为本期+两个基期+两个衍生指标，顺序稳定。
     */
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

    /**
     * 输入：同比基期为 0。
     * 预期：只产出 MOM，YOY 跳过并附带对比跳过 note。
     */
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

    /**
     * 输入：仅本期快照无同比基期。
     * 预期：不报错、仅保留 base，兼容缺省基期场景。
     */
    @Test
    void yoyWithoutYoyBaseFactIsSilentlyAbsent() {
        // 声明了同比但取数只有本期与环比基期（如快照指标）→ 不造 _yoy、不报错
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "笔", true, "ZERO"));
        var facts = step.run(monthlyOutline(List.of("year_over_year"), "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-M06"), 6)), defs);
        assertEquals(1, facts.facts().size());
    }

    // ---- Phase03：维度多行（行 fact + 合计 + 占比 + 双上限失败关闭） ----

    private static MetricDefinition dimMetric(String id) {
        return new MetricDefinition(id, "指标" + id, "CNY", true, false, "v", "ZERO",
                List.of(MetricDefinition.CHECK_NON_NEGATIVE), List.of("currency"), null, null, null);
    }

    private static FetchStep.FetchResult dimResult(MetricQuerySpec s, Object[][] currencyValues) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Object[] cv : currencyValues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currency", cv[0]);
            row.put("v", cv[1]);
            rows.add(row);
        }
        return new FetchStep.FetchResult(s, "select dim", "hash", rows, "rhash");
    }

    /**
     * 输入：多币种维度行 + 合计 + 占比需求。
     * 预期：产出 row fact + total + share，且衍生顺序与 factKey 命名规则稳定。
     */
    @Test
    void dimensionalRowsProduceRowTotalAndShareFacts() {
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"));
        var built = step.run(outline(null, "m1"), List.of(
                dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), new Object[][]{
                        {"CNY", new BigDecimal("750000")},
                        {"USD", new BigDecimal("200000")},
                        {"EUR", new BigDecimal("50000")}})), defs);
        // 3 行 + 1 合计 + 3 占比 = 7
        assertEquals(7, built.facts().size());
        FactRecord cny = built.facts().get(0);
        assertEquals("fact_001_cny", cny.factKey());
        assertEquals(Map.of("currency", "CNY"), cny.dimensions());
        assertEquals("指标m1（CNY）", cny.metricName());
        FactRecord total = built.facts().get(3);
        assertEquals("fact_001", total.factKey());
        assertEquals(FactRecord.TYPE_DERIVED, total.factType());
        assertEquals(0, total.value().compareTo(new BigDecimal("1000000")));
        assertEquals("fact_001_cny,fact_001_usd,fact_001_eur", total.derivedFrom());
        FactRecord share = built.facts().get(4);
        assertEquals("fact_001_cny_share", share.factKey());
        assertEquals(0, share.value().compareTo(new BigDecimal("75.0")));
        assertEquals("+75.0%", share.displayValue());
        assertEquals("fact_001_cny,fact_001", share.derivedFrom());
        // 后续单值指标编号顺延不受影响
    }

    /**
     * 输入：维度 slug 非 ASCII 或与已存在 slug 冲突。
     * 预期：按 slug 降级规则生成稳定且唯一的 factKey，避免覆盖。
     */
    @Test
    void dimensionSlugFallsBackOnNonAsciiAndConflict() {
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"));
        var built = step.run(outline(null, "m1"), List.of(
                dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), new Object[][]{
                        {"人民币", new BigDecimal("1")},      // 非 ASCII → r01
                        {"USD", new BigDecimal("2")},
                        {"usd", new BigDecimal("3")}})), defs);   // slug 冲突 → r03
        List<String> keys = built.facts().stream().map(FactRecord::factKey)
                .filter(k -> k.startsWith("fact_001_") && !k.endsWith("_share")).toList();
        assertEquals(List.of("fact_001_r01", "fact_001_usd", "fact_001_r03"), keys);
    }

    /**
     * 输入：维度行数达到 13（> 12 限制）。
     * 预期：直接 fail-closed，提示超上限，避免单指标产物膨胀。
     */
    @Test
    void dimensionRowLimitFailsClosed() {
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"));
        Object[][] rows = new Object[13][];
        for (int i = 0; i < 13; i++) rows[i] = new Object[]{"c" + i, new BigDecimal("1")};
        PolicyException e = assertThrows(PolicyException.class, () ->
                step.run(outline(null, "m1"), List.of(
                        dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), rows)), defs));
        assertTrue(e.getMessage().contains("超上限"));
    }

    /**
     * 输入：两个维度指标各 6 行（章节聚合会超 20 条上限）。
     * 预期：章节级 fail-closed，防止单章节事实爆炸。
     */
    @Test
    void chapterFactLimitFailsClosed() {
        // 两个维度指标 ×（6 行+合计+6 占比）= 26 > 20 → 章节上限失败关闭
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"), "m2", dimMetric("m2"));
        Object[][] rows = new Object[6][];
        for (int i = 0; i < 6; i++) rows[i] = new Object[]{"c" + i, new BigDecimal("1")};
        PolicyException e = assertThrows(PolicyException.class, () ->
                step.run(outline(null, "m1", "m2"), List.of(
                        dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), rows),
                        dimResult(spec("qs2", "m2", "CURRENT", "2026-W26"), rows)), defs));
        assertTrue(e.getMessage().contains("章节"));
        assertTrue(e.getMessage().contains("超上限"));
    }

    /**
     * 输入：维度查询返回 0 行 + ZERO/BLOCK 两种 null 策略对比。
     * 预期：ZERO 产出 0 合计；BLOCK 抛异常，保持策略一致性。
     */
    @Test
    void emptyDimensionRowsHonorNullPolicy() {
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"));
        var built = step.run(outline(null, "m1"), List.of(
                dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), new Object[][]{})), defs);
        assertEquals(1, built.facts().size());   // 仅合计 0
        assertEquals("fact_001", built.facts().get(0).factKey());
        assertEquals(0, built.facts().get(0).value().signum());
        assertTrue(built.notes().get(0).contains("维度拆解为空"));

        MetricDefinition blockDef = new MetricDefinition("m1", "指标m1", "CNY", true, false, "v", "BLOCK",
                List.of(), List.of("currency"), null, null, null);
        assertThrows(PolicyException.class, () -> step.run(outline(null, "m1"), List.of(
                dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), new Object[][]{})),
                Map.of("m1", blockDef)));
    }

    // ---- Phase04：图表序列 fact ----

    private static MetricQuerySpec chartSpec(String id, String metricId, String label) {
        return new MetricQuerySpec(id, metricId, "ch1", MetricQuerySpec.PURPOSE_CHART_SERIES,
                label, "2026-06-01", "2026-06-07");
    }

    /**
     * 输入：主序列 + 2 个图表序列点，序列标签乱序。
     * 预期：图表 fact 独立命名空间，按时间由早到晚排序映射 s1/s2。
     */
    @Test
    void chartSeriesFactsGetOwnNamespaceSortedOldestFirst() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "CNY", true, "ZERO"));
        var built = step.run(outline(null, "m1"), List.of(
                result(spec("qs1", "m1", "CURRENT", "2026-W26"), 100),
                result(chartSpec("qs2", "m1", "2026-W25"), 90),
                result(chartSpec("qs3", "m1", "2026-W24"), 80)), defs);
        assertEquals(3, built.facts().size());
        assertEquals("fact_001", built.facts().get(0).factKey());   // 主序列编号不受序列影响
        FactRecord s1 = built.facts().get(1);
        assertEquals("fact_c01_s1", s1.factKey());
        assertEquals("2026-W24", s1.periodLabel());   // s1 = 最早期（periodLabel 排序）
        assertEquals("fact_c01_s2", built.facts().get(2).factKey());
        assertTrue(FactBuildStep.isChartSeriesFact(s1));
        assertFalse(FactBuildStep.isChartSeriesFact(built.facts().get(0)));
    }

    /**
     * 输入：序列点分别在 21 与 25 条边界。
     * 预期：21 条通过，25 条触发图表序列配额关闭，不影响章节上限。
     */
    @Test
    void chartSeriesQuotaFailsClosedButNotChapterLimit() {
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", "CNY", true, "ZERO"));
        // 21 个序列点 < 24 配额且不占章 20 上限 → 通过
        List<FetchStep.FetchResult> ok = new java.util.ArrayList<>();
        ok.add(result(spec("qs0", "m1", "CURRENT", "2026-W26"), 1));
        for (int i = 1; i <= 21; i++) ok.add(result(chartSpec("qs" + i, "m1", String.format("2026-W%02d", i)), i));
        assertEquals(22, step.run(outline(null, "m1"), ok, defs).facts().size());
        // 25 个序列点 > 24 配额 → BLOCKED
        List<FetchStep.FetchResult> over = new java.util.ArrayList<>();
        over.add(result(spec("qs0", "m1", "CURRENT", "2026-W26"), 1));
        for (int i = 1; i <= 25; i++) over.add(result(chartSpec("qs" + i, "m1", String.format("2026-W%02d", i)), i));
        PolicyException e = assertThrows(PolicyException.class, () -> step.run(outline(null, "m1"), over, defs));
        assertTrue(e.getMessage().contains("图表序列"));
    }

    /**
     * 输入：维度合计为 0 的场景。
     * 预期：行 fact+total 有值，share 不生成并给出跳过占比说明。
     */
    @Test
    void zeroTotalSkipsShareWithNote() {
        Map<String, MetricDefinition> defs = Map.of("m1", dimMetric("m1"));
        var built = step.run(outline(null, "m1"), List.of(
                dimResult(spec("qs1", "m1", "CURRENT", "2026-W26"), new Object[][]{
                        {"CNY", BigDecimal.ZERO}, {"USD", BigDecimal.ZERO}})), defs);
        assertEquals(3, built.facts().size());   // 2 行 + 合计，无占比
        assertTrue(built.notes().get(0).contains("跳过占比"));
    }
}
