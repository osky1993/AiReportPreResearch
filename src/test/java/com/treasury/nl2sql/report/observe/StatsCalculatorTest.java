package com.treasury.nl2sql.report.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 看板统计纯逻辑单测（P6 契约3）：固造行集逐值验证率/分位数/原因分组/用量汇总。 */
class StatsCalculatorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 11, 10, 0, 0);

    private static StatsCalculator.StepRow step(String phase, int attempt, String status, long durMs) {
        return new StatsCalculator.StepRow(phase, attempt, status, T0, T0.plusNanos(durMs * 1_000_000), null);
    }

    @Test
    void phaseStatsRatesAndPercentiles() {
        List<StatsCalculator.StepRow> rows = new ArrayList<>();
        // FETCH：10 次执行，8 OK / 2 BLOCKED，其中 2 次是重试；耗时 100..1000ms
        for (int i = 1; i <= 10; i++) {
            rows.add(step("FETCH", i <= 8 ? 1 : 2, i <= 8 ? "OK" : "BLOCKED", i * 100L));
        }
        // WRITE：1 次进行中（无 finished_at → 不计耗时、不计成败）
        rows.add(new StatsCalculator.StepRow("WRITE", 1, "RUNNING", T0, null, null));

        List<StatsCalculator.PhaseStats> stats = StatsCalculator.phaseStats(rows);
        StatsCalculator.PhaseStats fetch = stats.stream().filter(s -> s.phase().equals("FETCH")).findFirst().orElseThrow();
        assertEquals(10, fetch.executions());
        assertEquals(80.0, fetch.successRate());
        assertEquals(20.0, fetch.blockedRate());
        assertEquals(20.0, fetch.retryRate());
        assertEquals(500L, fetch.p50Ms(), "nearest-rank P50 = 第 5 个（500ms）");
        assertEquals(1000L, fetch.p95Ms(), "nearest-rank P95 = 第 10 个（1000ms）");

        StatsCalculator.PhaseStats write = stats.stream().filter(s -> s.phase().equals("WRITE")).findFirst().orElseThrow();
        assertEquals(1, write.executions());
        assertEquals(0.0, write.successRate());
        assertNull(write.p50Ms(), "无完成步 → 分位数为 null 不冒充 0");
    }

    @Test
    void blockedReasonsGroupByPrefixAndFirstSentence() {
        List<StatsCalculator.ReasonCount> top = StatsCalculator.blockedReasons(java.util.Arrays.asList(
                "[POLICY] 模板召回为空：无法确定报告类型",
                "[POLICY] 模板召回为空：换个说法也不行",
                "[EXCEPTION] HTTP 响应提取错误",
                null, ""), 5);
        assertEquals(2, top.size());
        assertEquals("[POLICY] 模板召回为空", top.get(0).reason());
        assertEquals(2, top.get(0).count());
        assertEquals("[EXCEPTION] HTTP 响应提取错误", top.get(1).reason());
    }

    @Test
    void llmTotalsParseUsageSegmentAndSkipDirtyRows() {
        ObjectMapper m = new ObjectMapper();
        List<StatsCalculator.StepRow> rows = List.of(
                new StatsCalculator.StepRow("OUTLINE", 1, "OK", null, null,
                        "{\"a\":1,\"llmUsage\":{\"calls\":1,\"unmeteredCalls\":0,\"promptTokens\":100,\"completionTokens\":20}}"),
                new StatsCalculator.StepRow("WRITE", 1, "OK", null, null,
                        "{\"llmUsage\":{\"calls\":2,\"unmeteredCalls\":1,\"promptTokens\":300,\"completionTokens\":50}}"),
                new StatsCalculator.StepRow("WRITE", 1, "OK", null, null, "{\"reportMd\":\"无 usage 段（旧 run）\"}"),
                new StatsCalculator.StepRow("AUDIT", 1, "OK", null, null, "not-json"));
        StatsCalculator.LlmTotals t = StatsCalculator.llmTotals(m, rows);
        assertEquals(3, t.calls());
        assertEquals(1, t.unmeteredCalls());
        assertEquals(400, t.promptTokens());
        assertEquals(70, t.completionTokens());
    }
}
