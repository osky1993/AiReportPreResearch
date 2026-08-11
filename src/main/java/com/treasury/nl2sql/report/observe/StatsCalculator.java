package com.treasury.nl2sql.report.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行统计纯函数（P6 契约3，可单测无 DB）：步骤成功率/重试率/阻断率、P50/P95 耗时、
 * BLOCKED 原因分组、LLM 用量汇总。观测口径纪律：未完成步不计入成败、未计量不冒充 0。
 */
public final class StatsCalculator {

    /** report_step 的统计投影行（output_json 仅 LLM 相关 phase 需要，可传 null）。 */
    public record StepRow(String phase, int attempt, String status,
                          LocalDateTime startedAt, LocalDateTime finishedAt, String outputJson) {}

    public record PhaseStats(String phase, int executions, int okCount, int blockedCount, int retryCount,
                             double successRate, double retryRate, double blockedRate,
                             Long p50Ms, Long p95Ms) {}

    public record LlmTotals(int calls, int unmeteredCalls, long promptTokens, long completionTokens) {}

    public record ReasonCount(String reason, int count) {}

    private StatsCalculator() {}

    /**
     * 按 phase 聚合步骤执行统计。
     * <ul>
     *   <li>未完成 step（startedAt/finishedAt 任一为空）不计入时延分位数。</li>
     *   <li>attempt&gt;1 的同一 phase 重试记录保留在同一聚合桶，重试率可用于阻塞前置识别。</li>
     *   <li>success/retry/blocked 采用 step 记录计数而非 run 计数，适配失败后的重试回放。</li>
     * </ul>
     */
    public static List<PhaseStats> phaseStats(List<StepRow> rows) {
        Map<String, List<StepRow>> byPhase = new LinkedHashMap<>();
        for (StepRow r : rows) byPhase.computeIfAbsent(r.phase(), k -> new ArrayList<>()).add(r);
        List<PhaseStats> out = new ArrayList<>();
        byPhase.forEach((phase, list) -> {
            int ok = 0, blocked = 0, retry = 0;
            List<Long> durations = new ArrayList<>();
            for (StepRow r : list) {
                if ("OK".equals(r.status())) ok++;
                if ("BLOCKED".equals(r.status())) blocked++;
                if (r.attempt() > 1) retry++;
                if (r.startedAt() != null && r.finishedAt() != null) {
                    durations.add(Duration.between(r.startedAt(), r.finishedAt()).toMillis());
                }
            }
            int n = list.size();
            durations.sort(Comparator.naturalOrder());
            out.add(new PhaseStats(phase, n, ok, blocked, retry,
                    rate(ok, n), rate(retry, n), rate(blocked, n),
                    percentile(durations, 0.50), percentile(durations, 0.95)));
        });
        return out;
    }

    /** BLOCKED 原因分组 TopN：按前缀（[POLICY]/[EXCEPTION]/其他）+ 截断首句归组。 */
    public static List<ReasonCount> blockedReasons(List<String> reasons, int topN) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String r : reasons) {
            if (r == null || r.isBlank()) continue;
            counts.merge(reasonKey(r), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(e -> new ReasonCount(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * LLM 用量汇总：解析各步 output_json 的 llmUsage 段（无段/解析失败 = 该步无有效 LLM 用量，跳过）。
     * 解析失败是脏数据容错路径，观测层不应放大下游报错。
     */
    public static LlmTotals llmTotals(ObjectMapper mapper, List<StepRow> rows) {
        int calls = 0, unmetered = 0;
        long prompt = 0, completion = 0;
        for (StepRow r : rows) {
            if (r.outputJson() == null || r.outputJson().isBlank()) continue;
            try {
                JsonNode u = mapper.readTree(r.outputJson()).path("llmUsage");
                if (u.isMissingNode() || u.isNull()) continue;
                calls += u.path("calls").asInt();
                unmetered += u.path("unmeteredCalls").asInt();
                prompt += u.path("promptTokens").asLong();
                completion += u.path("completionTokens").asLong();
            } catch (Exception ignored) {
                // 观测不因个别脏行中断（纪律 13：观测永不阻断，也不放大故障）
            }
        }
        return new LlmTotals(calls, unmetered, prompt, completion);
    }

    /** 最近邻分位数（nearest-rank）；空集返回 null（「无数据」不冒充 0）。 */
    static Long percentile(List<Long> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) return null;
        int idx = (int) Math.ceil(p * sortedAsc.size()) - 1;
        return sortedAsc.get(Math.max(0, Math.min(idx, sortedAsc.size() - 1)));
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0.0 : Math.round(1000.0 * part / total) / 10.0;
    }

    private static String reasonKey(String reason) {
        String prefix = reason.startsWith("[POLICY]") ? "[POLICY] "
                : reason.startsWith("[EXCEPTION]") ? "[EXCEPTION] " : "";
        String body = reason.substring(prefix.trim().length()).trim();
        // 首句（到第一个句号/冒号/换行），再截断防长尾
        int cut = body.length();
        for (char c : new char[]{'：', ':', '。', '\n'}) {
            int i = body.indexOf(c);
            if (i > 0 && i < cut) cut = i;
        }
        String key = body.substring(0, Math.min(cut, 40));
        return prefix + key;
    }
}
