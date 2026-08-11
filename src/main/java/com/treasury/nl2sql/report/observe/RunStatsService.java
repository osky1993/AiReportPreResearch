package com.treasury.nl2sql.report.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行看板统计（P6 契约3，观测层，纪律 13 绝对只读）：时间窗内 run 状态分布与 BLOCKED
 * 原因分组、各步成功率/重试率/阻断率与 P50/P95 耗时、LLM 用量与可选成本估算。
 * 聚合走独立只读 SQL + {@link StatsCalculator} 纯函数；缺省窗口近 30 天。
 */
@Service
public class RunStatsService {

    /** LLM 用量段落在这三个 phase 的 output_json 上（OUTLINE / WRITE 含归因 / AUDIT 含重写）。 */
    private static final String LLM_PHASES = "'OUTLINE','WRITE','AUDIT'";

    public record LlmBlock(int calls, int unmeteredCalls, long promptTokens, long completionTokens,
                           Double estCost, String costNote) {}
    public record RunsBlock(int total, Map<String, Integer> byStatus,
                            List<StatsCalculator.ReasonCount> blockedReasons) {}
    public record StatsResponse(String from, String to, RunsBlock runs,
                                List<StatsCalculator.PhaseStats> steps, LlmBlock llm) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final double pricePer1kInput;
    private final double pricePer1kOutput;

    public RunStatsService(JdbcTemplate jdbc, ObjectMapper mapper,
                           @Value("${report.observe.price-per-1k-input:0}") double pricePer1kInput,
                           @Value("${report.observe.price-per-1k-output:0}") double pricePer1kOutput) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.pricePer1kInput = pricePer1kInput;
        this.pricePer1kOutput = pricePer1kOutput;
    }

    /**
     * 按时间窗聚合运行观测数据：
     * <ul>
     *   <li>时间窗边界采用 [from, to) 左闭右开，to 支持当日语义。</li>
     *   <li>from 为空默认最近 30 天；from=all 覆盖历史所有分片。</li>
     *   <li>仅查询已持久化数据库状态，不进行在线重算。</li>
     * </ul>
     * from/to 格式为 ISO 日期，解析失败会抛出运行时解析异常（控制面需对外兜底 400）。
     */
    public StatsResponse stats(String from, String to) {
        LocalDateTime end = (to == null || to.isBlank())
                ? LocalDateTime.now() : LocalDate.parse(to).plusDays(1).atStartOfDay();
        LocalDateTime start;
        if ("all".equalsIgnoreCase(from)) {
            start = LocalDateTime.of(2000, 1, 1, 0, 0);
        } else if (from == null || from.isBlank()) {
            start = end.minusDays(30);
        } else {
            start = LocalDate.parse(from).atStartOfDay();
        }

        // ---- runs（状态分布 + BLOCKED 原因） ----
        // 注意：不压缩去重，每条 run 都贡献一行；统计失败原因有利于区分业务阻断与执行异常。
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<String> blockedReasons = new java.util.ArrayList<>();
        jdbc.query("SELECT status, blocked_reason FROM report_run WHERE created_at >= ? AND created_at < ?",
                rs -> {
                    byStatus.merge(rs.getString("status"), 1, Integer::sum);
                    String reason = rs.getString("blocked_reason");
                    if (reason != null) blockedReasons.add(reason);
                }, start, end);
        int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        RunsBlock runs = new RunsBlock(total, byStatus, StatsCalculator.blockedReasons(blockedReasons, 8));

        // ---- steps（元数据行，不取 output_json） ----
        // 成功率/阻断率以每次 step 记录为口径，attempt>1 计入重试，未完成记录只计时缺失时不计入 p50/p95。
        List<StatsCalculator.StepRow> stepRows = jdbc.query(
                "SELECT phase, attempt, status, started_at, finished_at FROM report_step "
                        + "WHERE started_at >= ? AND started_at < ? ORDER BY step_id",
                (rs, i) -> new StatsCalculator.StepRow(rs.getString("phase"), rs.getInt("attempt"),
                        rs.getString("status"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                        null), start, end);

        // ---- llm（仅三个含 LLM 的 phase 才取 output_json 解析 llmUsage 段） ----
        // 成本估算按可选配置进行，未配置价格时返回 token 总量与提示。
        List<StatsCalculator.StepRow> llmRows = jdbc.query(
                "SELECT phase, attempt, status, output_json FROM report_step "
                        + "WHERE started_at >= ? AND started_at < ? AND phase IN (" + LLM_PHASES + ")",
                (rs, i) -> new StatsCalculator.StepRow(rs.getString("phase"), rs.getInt("attempt"),
                        rs.getString("status"), null, null, rs.getString("output_json")), start, end);
        StatsCalculator.LlmTotals t = StatsCalculator.llmTotals(mapper, llmRows);
        boolean priced = pricePer1kInput > 0 || pricePer1kOutput > 0;
        Double cost = priced
                ? Math.round((t.promptTokens() / 1000.0 * pricePer1kInput
                        + t.completionTokens() / 1000.0 * pricePer1kOutput) * 10000.0) / 10000.0
                : null;
        LlmBlock llm = new LlmBlock(t.calls(), t.unmeteredCalls(), t.promptTokens(), t.completionTokens(),
                cost, priced ? "估算：按 report.observe.price-per-1k-* 配置单价" : "未配置单价，仅展示 token");

        return new StatsResponse(start.toLocalDate().toString(), end.minusDays(1).toLocalDate().toString(),
                runs, StatsCalculator.phaseStats(stepRows), llm);
    }
}
