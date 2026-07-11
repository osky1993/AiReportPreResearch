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
 * ② 语义解析纯逻辑单测（Phase03 多比较管线）。
 * 特征测试（护 47 条评测基线）：旧单窗口签名与新 Map 窗口签名对周报输入的输出必须逐条相等——
 * spec 编号（qs_%03d）是 fact 编号的地基，漂移即打碎全部既有期望值。
 */
class SpecResolveStepTest {

    private final SpecResolveStep step = new SpecResolveStep();

    private static MetricDefinition metric(String id, boolean timeBound, boolean comparable) {
        return new MetricDefinition(id, "指标" + id, "CNY", timeBound, comparable, "v", "ZERO",
                List.of(), null, null, null);
    }

    private static Outline.OutlineChapter legacyChapter(String id, String comparison, String... metricIds) {
        return new Outline.OutlineChapter(id, "章" + id, List.of(metricIds), comparison, null, "g", null, null);
    }

    private static Outline.OutlineChapter listChapter(String id, List<String> comparisons, String... metricIds) {
        return new Outline.OutlineChapter(id, "章" + id, List.of(metricIds), null, comparisons, "g", null, null);
    }

    private static final PeriodResolver.Window W26 = PeriodResolver.resolve("2026-W26");
    private static final PeriodResolver.Window M06 = PeriodResolver.resolve("2026-M06");

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

    @Test
    void derivedMetricOperandsGetYoySpecsToo() {
        MetricDefinition derived = new MetricDefinition("net", "净流入", "CNY", true, true, null, "ZERO",
                List.of(), null, null, new MetricDefinition.Derived("subtract", "in", "out"));
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

    @Test
    void missingWindowForDeclaredComparisonFailsClosed() {
        Outline outline = new Outline("tpl", "2026-M06", List.of(
                listChapter("ch1", List.of("year_over_year"), "m1")), List.of());
        Map<String, MetricDefinition> defs = Map.of("m1", metric("m1", true, true));
        assertThrows(PolicyException.class, () -> step.run(outline, M06,
                Map.of(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(M06)), defs));
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
