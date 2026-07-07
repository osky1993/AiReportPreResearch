package com.treasury.nl2sql.report.pipeline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 周期解析纯逻辑单测：ISO 周标签 → 日期窗口（LLM 不产日期，窗口全靠这里）。 */
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
}
