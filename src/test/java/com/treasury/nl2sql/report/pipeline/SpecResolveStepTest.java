package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpecResolveStep 语义拆解单测（纯逻辑）。重点校验：
 * 周/月/季周期下 comparison 的 spec 生成顺序、legacy 与 map 窗口签名兼容、
 * 图表序列 spec 的去重与排序，以及缺窗口时 fail-closed 行为。
 * specId 序列是后续 fact 编号的稳定边界，漂移会影响大量回放用例。
 */
class SpecResolveStepTest {

    private final SpecResolveStep step = new SpecResolveStep();

    private static MetricDefinition metric(String id, boolean timeBound, boolean comparable) {
        return new MetricDefinition(id, "指标" + id, "CNY", timeBound, comparable, "v", "ZERO",
                List.of(), null, null, null, null);
    }

    private static Outline.OutlineChapter legacyChapter(String id, String comparison, String... metricIds) {
        return new Outline.OutlineChapter(id, "章" + id, List.of(metricIds), comparison, null, "g", null, null);
    }

    private static Outline.OutlineChapter listChapter(String id, List<String> comparisons, String... metricIds) {
        return new Outline.OutlineChapter(id, "章" + id, List.of(metricIds), null, comparisons, "g", null, null);
    }

    private static final PeriodResolver.Window W26 = PeriodResolver.resolve("2026-W26");
    private static final PeriodResolver.Window M06 = PeriodResolver.resolve("2026-M06");

    /**
     * 输入：legacy 周报 comparison 与 map 入参。
     * 预期：两种签名下输出完全一致，且 qs 编号顺序稳定。
     */
    @Test
    void legacyWeeklyOutputIsIdenticalAcrossSignatures() {
        // 周报形态：ch1 环比章（m1 可比、m2 不可比、m3 快照），ch2 无比较章（m4）
        Outline outline = new Outline("tpl", "2026-W26", List.of(
                legacyChapter("ch1", "week_over_week", "m1", "m2", "m3"),
                legacyChapter("ch2", null, "m4")), List.of());
        Map<String, MetricDefinition> defs = Map.of(
                "m1", metric("m1", true, true),
                "m2", metric("m2", true, false),
                "m3", metric("m3", false, true),
                "m4", metric("m4", true, true));
        PeriodResolver.Window w25 = PeriodResolver.previous(W26);

        List<MetricQuerySpec> legacy = step.run(outline, W26, w25, defs);
        List<MetricQuerySpec> viaMap = step.run(outline, W26,
                Map.of(MetricQuerySpec.PURPOSE_COMPARE, w25), defs);

        assertEquals(legacy, viaMap, "新旧签名对周报输入的产出必须逐条相等（编号可复现地基）");
        // 结构自证：4 个 CURRENT + 1 个 COMPARE（仅 m1：可比且 timeBound 且在比较章）
        assertEquals(5, legacy.size());
        assertEquals("qs_005", legacy.get(4).specId());
        assertEquals(MetricQuerySpec.PURPOSE_COMPARE, legacy.get(4).purpose());
        assertEquals("m1", legacy.get(4).metricId());
    }

    /**
     * 输入：声明/未声明同比的大纲（Gate3 抽取的共享装配方法）。
     * 预期：环比窗口无条件装配（保现行为）；同比窗口仅在大纲声明 yoy 时出现——
     * ReportPipeline 与模板预览共用此方法，行为以此测试锁定。
     */
    @Test
    void buildCompareWindowsAssemblesYoyOnlyWhenDeclared() {
        Outline noYoy = new Outline("tpl", "2026-W26", List.of(
                legacyChapter("ch1", "week_over_week", "m1")), List.of());
        Map<String, PeriodResolver.Window> w1 = SpecResolveStep.buildCompareWindows(noYoy, W26);
        assertEquals(PeriodResolver.previous(W26), w1.get(MetricQuerySpec.PURPOSE_COMPARE));
        assertFalse(w1.containsKey(MetricQuerySpec.PURPOSE_COMPARE_YOY), "未声明同比不得装配同比窗口");

        Outline withYoy = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("month_over_month", "year_over_year"), "m1")), List.of());
        Map<String, PeriodResolver.Window> w2 = SpecResolveStep.buildCompareWindows(withYoy, M06);
        assertEquals(PeriodResolver.previous(M06), w2.get(MetricQuerySpec.PURPOSE_COMPARE));
        assertEquals(PeriodResolver.sameLastYear(M06), w2.get(MetricQuerySpec.PURPOSE_COMPARE_YOY));
    }

    /**
     * 输入：月报同时声明 MOM 与 YOY。
     * 预期：按 CURRENT -> COMPARE -> COMPARE_YOY 阶段化顺序产出，方便下游执行分块。
     */
    @Test
    void yoySpecsAreEmittedAfterCompareBlock() {
        Outline outline = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("month_over_month", "year_over_year"), "m1", "m2")), List.of());
        Map<String, MetricDefinition> defs = Map.of(
                "m1", metric("m1", true, true),
                "m2", metric("m2", true, true));
        Map<String, PeriodResolver.Window> windows = Map.of(
                MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(M06),
                MetricQuerySpec.PURPOSE_COMPARE_YOY, PeriodResolver.sameLastYear(M06));

        List<MetricQuerySpec> specs = step.run(outline, M06, windows, defs);

        // 块序固定：CURRENT(2) → COMPARE(2) → COMPARE_YOY(2)
        assertEquals(6, specs.size());
        assertEquals(List.of("CURRENT", "CURRENT", "COMPARE", "COMPARE", "COMPARE_YOY", "COMPARE_YOY"),
                specs.stream().map(MetricQuerySpec::purpose).toList());
        assertEquals("2026-M05", specs.get(2).periodLabel());
        assertEquals("2025-M06", specs.get(4).periodLabel());
        assertEquals("2025-06-01", specs.get(4).periodStart());
        assertEquals("2025-06-30", specs.get(4).periodEnd());
    }

    /**
     * 输入：派生指标（如 net= in-out）声明同比。
     * 预期：除派生本身，还要为 operands 生成 compare-YOY 取数 spec。
     */
    @Test
    void derivedMetricOperandsGetYoySpecsToo() {
        MetricDefinition derived = new MetricDefinition("net", "净流入", "CNY", true, true, null, "ZERO",
                List.of(), null, null, null, new MetricDefinition.Derived("subtract", "in", "out"));
        Outline outline = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("year_over_year"), "net")), List.of());
        Map<String, MetricDefinition> defs = Map.of(
                "net", derived, "in", metric("in", true, true), "out", metric("out", true, true));

        List<MetricQuerySpec> specs = step.run(outline, M06, Map.of(
                MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(M06),
                MetricQuerySpec.PURPOSE_COMPARE_YOY, PeriodResolver.sameLastYear(M06)), defs);

        // 操作数 in/out：CURRENT ×2 + COMPARE_YOY ×2（本章只声明了同比）
        assertEquals(4, specs.size());
        assertEquals(List.of("CURRENT", "CURRENT", "COMPARE_YOY", "COMPARE_YOY"),
                specs.stream().map(MetricQuerySpec::purpose).toList());
    }

    /**
     * 输入：缺少 required compare window。
     * 预期：抛异常，避免 silent 漏报导致空数据。
     */
    @Test
    void missingWindowForDeclaredComparisonFailsClosed() {
        Outline outline = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("year_over_year"), "m1")), List.of());
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", true, true));
        assertThrows(PolicyException.class, () -> step.run(outline, M06,
                Map.of(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(M06)), defs));
    }

    // ---- Phase04：图表序列取数 ----

    private static Outline.OutlineChapter chartChapter(String id, int periods, String... metricIds) {
        var chart = new com.treasury.nl2sql.report.domain.ChartDef("trend_x", "line", "趋势",
                new com.treasury.nl2sql.report.domain.ChartDef.Binding("series", metricIds[0], periods));
        return new Outline.OutlineChapter(id, "章" + id, List.of(metricIds), null, null, "g", null, List.of(chart));
    }

    /**
     * 输入：包含趋势图定义 + 历史周期。
     * 预期：产出当前值 + CHART_SERIES（历史旧到新）并去重。
     */
    @Test
    void chartSeriesSpecsAreEmittedLastOldestFirst() {
        Outline outline = new Outline("tpl", "2026-W26", List.of(
                chartChapter("ch1", 4, "m1")), List.of());
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", true, true));
        List<MetricQuerySpec> specs = step.run(outline, W26,
                Map.of(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(W26)), defs);
        // CURRENT(1) + 序列历史 3 期（无比较章 → 无 COMPARE 块）；序列旧→新
        assertEquals(4, specs.size());
        assertEquals(List.of("CURRENT", "CHART_SERIES", "CHART_SERIES", "CHART_SERIES"),
                specs.stream().map(MetricQuerySpec::purpose).toList());
        assertEquals(List.of("2026-W23", "2026-W24", "2026-W25"),
                specs.subList(1, 4).stream().map(MetricQuerySpec::periodLabel).toList());
    }

    @Test
    void chartSeriesDedupesAcrossChartsAndSkipsNonSeries() {
        var pie = new com.treasury.nl2sql.report.domain.ChartDef("mix_x", "pie", "构成",
                new com.treasury.nl2sql.report.domain.ChartDef.Binding("dimension", "m1", null));
        var trend = new com.treasury.nl2sql.report.domain.ChartDef("trend_x", "line", "趋势",
                new com.treasury.nl2sql.report.domain.ChartDef.Binding("series", "m1", 3));
        Outline outline = new Outline("tpl", "2026-W26", List.of(
                new Outline.OutlineChapter("ch1", "一", List.of("m1"), null, null, "g", null, List.of(trend, pie)),
                new Outline.OutlineChapter("ch2", "二", List.of("m1"), null, null, "g", null, List.of(trend))),
                List.of());
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", true, true));
        List<MetricQuerySpec> specs = step.run(outline, W26,
                Map.of(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(W26)), defs);
        // CURRENT ×1 + 历史 2 期（两章同图去重；dimension 图不产序列 spec）
        assertEquals(3, specs.size());
    }

    @Test
    void requiredComparePurposesCollectsAcrossChapters() {
        Outline outline = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("month_over_month"), "m1"),
                listChapter("ch2", List.of("year_over_year"), "m1"),
                legacyChapter("ch3", null, "m1")), List.of());
        assertEquals(Set.of(MetricQuerySpec.PURPOSE_COMPARE, MetricQuerySpec.PURPOSE_COMPARE_YOY),
                SpecResolveStep.requiredComparePurposes(outline));
        assertEquals(Set.of(), SpecResolveStep.requiredComparePurposes(
                new Outline("tpl", "2026-W26", List.of(legacyChapter("c", null, "m1")), List.of())));
    }
}
