package com.treasury.nl2sql.report.pipeline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 周期解析纯逻辑单测：周/月/季标签 → 日期窗口（LLM 不产日期，窗口全靠这里）。 */
class PeriodResolverTest {

    @Test
    void resolvesDemoWeek2026W26() {
        PeriodResolver.Window w = PeriodResolver.resolve("2026-W26");
        assertEquals(LocalDate.of(2026, 6, 22), w.start());
        assertEquals(LocalDate.of(2026, 6, 28), w.end());
        assertEquals("2026-W26", w.label());
    }

    @Test
    void previousWeekIsW25() {
        PeriodResolver.Window w26 = PeriodResolver.resolve("2026-W26");
        PeriodResolver.Window w25 = PeriodResolver.previous(w26);
        assertEquals("2026-W25", w25.label());
        assertEquals(LocalDate.of(2026, 6, 15), w25.start());
        assertEquals(LocalDate.of(2026, 6, 21), w25.end());
    }

    @Test
    void crossYearW01StartsInPreviousCalendarYear() {
        // ISO 2026-W01 = 2025-12-29 ~ 2026-01-04（1 月 4 日必落第 1 周）
        PeriodResolver.Window w = PeriodResolver.resolve("2026-W01");
        assertEquals(LocalDate.of(2025, 12, 29), w.start());
        assertEquals(LocalDate.of(2026, 1, 4), w.end());
    }

    @Test
    void previousOfW01CrossesIntoPriorWeekBasedYear() {
        PeriodResolver.Window w01 = PeriodResolver.resolve("2026-W01");
        PeriodResolver.Window prev = PeriodResolver.previous(w01);
        assertEquals("2025-W52", prev.label());
    }

    @Test
    void week53OnlyExistsInLongYears() {
        // 2026 年是 53 周年份（2026-12-31 为周四）；2025 年只有 52 周
        assertDoesNotThrow(() -> PeriodResolver.resolve("2026-W53"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2025-W53"));
    }

    @Test
    void invalidLabelsFailClosed() {
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve(null));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-06"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026年6月"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-W99"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-W00"));
    }

    // ---- Phase03：月 ----

    @Test
    void resolvesMonth2026M06() {
        PeriodResolver.Window w = PeriodResolver.resolve("2026-M06");
        assertEquals("2026-M06", w.label());
        assertEquals(LocalDate.of(2026, 6, 1), w.start());
        assertEquals(LocalDate.of(2026, 6, 30), w.end());
    }

    @Test
    void monthWindowsHonorCalendarLengths() {
        assertEquals(LocalDate.of(2026, 7, 31), PeriodResolver.resolve("2026-M07").end());   // 31 天月
        assertEquals(LocalDate.of(2026, 2, 28), PeriodResolver.resolve("2026-M02").end());   // 平年 2 月
        assertEquals(LocalDate.of(2028, 2, 29), PeriodResolver.resolve("2028-M02").end());   // 闰年 2 月
    }

    @Test
    void previousMonthOfM01CrossesYear() {
        PeriodResolver.Window prev = PeriodResolver.previous(PeriodResolver.resolve("2026-M01"));
        assertEquals("2025-M12", prev.label());
        assertEquals(LocalDate.of(2025, 12, 1), prev.start());
        assertEquals(LocalDate.of(2025, 12, 31), prev.end());
    }

    @Test
    void invalidMonthsFailClosed() {
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-M00"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-M13"));
    }

    // ---- Phase03：季 ----

    @Test
    void resolvesQuarter2026Q2() {
        PeriodResolver.Window w = PeriodResolver.resolve("2026-Q2");
        assertEquals("2026-Q2", w.label());
        assertEquals(LocalDate.of(2026, 4, 1), w.start());
        assertEquals(LocalDate.of(2026, 6, 30), w.end());
    }

    @Test
    void previousQuarterOfQ1CrossesYear() {
        PeriodResolver.Window prev = PeriodResolver.previous(PeriodResolver.resolve("2026-Q1"));
        assertEquals("2025-Q4", prev.label());
        assertEquals(LocalDate.of(2025, 10, 1), prev.start());
        assertEquals(LocalDate.of(2025, 12, 31), prev.end());
    }

    @Test
    void invalidQuartersFailClosed() {
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-Q0"));
        assertThrows(PolicyException.class, () -> PeriodResolver.resolve("2026-Q5"));
    }

    // ---- Phase03：同比基期 ----

    @Test
    void sameLastYearForMonthAndQuarter() {
        PeriodResolver.Window m = PeriodResolver.sameLastYear(PeriodResolver.resolve("2026-M06"));
        assertEquals("2025-M06", m.label());
        assertEquals(LocalDate.of(2025, 6, 1), m.start());
        assertEquals(LocalDate.of(2025, 6, 30), m.end());

        PeriodResolver.Window q = PeriodResolver.sameLastYear(PeriodResolver.resolve("2026-Q2"));
        assertEquals("2025-Q2", q.label());
        assertEquals(LocalDate.of(2025, 4, 1), q.start());
        assertEquals(LocalDate.of(2025, 6, 30), q.end());
    }

    @Test
    void sameLastYearForWeekIncludingMissingW53() {
        PeriodResolver.Window w = PeriodResolver.sameLastYear(PeriodResolver.resolve("2026-W26"));
        assertEquals("2025-W26", w.label());
        // 2026 有 W53、2025 没有：同比基期不存在 → 失败关闭（矩阵禁 WEEK+同比，此为死代码兜底）
        assertThrows(PolicyException.class,
                () -> PeriodResolver.sameLastYear(PeriodResolver.resolve("2026-W53")));
    }

    // ---- Phase03：粒度识别 ----

    @Test
    void granularityOfLabels() {
        assertEquals(PeriodResolver.TYPE_WEEK, PeriodResolver.granularity("2026-W26"));
        assertEquals(PeriodResolver.TYPE_MONTH, PeriodResolver.granularity("2026-M06"));
        assertEquals(PeriodResolver.TYPE_QUARTER, PeriodResolver.granularity("2026-Q2"));
        assertThrows(PolicyException.class, () -> PeriodResolver.granularity("2026年6月"));
        assertThrows(PolicyException.class, () -> PeriodResolver.granularity(null));
    }
}
