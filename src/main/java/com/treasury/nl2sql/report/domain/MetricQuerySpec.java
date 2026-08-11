package com.treasury.nl2sql.report.domain;

/**
 * 查询规约契约（ideaV2 两大数据契约之一）：一次结构化取数的完整描述。
 * 由 ②（纯程序）从「确认后的大纲 + 周期窗口」实例化；日期为 ISO 字符串（yyyy-MM-dd）。
 * 快照类指标（timeBound=false）periodStart/periodEnd 为 null。
 *
 * @param specId 查询 spec 的唯一标识（run 内不重复，便于 step 输入审计）
 * @param metricId 目标 metricDefinition.metricId
 * @param chapterId 本次取数归属章节 ID（图表/事实展示 trace）
 * @param purpose CURRENT=报告期 / COMPARE=同粒度环比基期（上周/上月/上季）/ COMPARE_YOY=同比基期（去年同期）
 * @param periodLabel 取数窗口标签（如 2026-M06），用于日志和 fact key 前缀语义
 * @param periodStart 闭区间起始日期（ISO，YYYY-MM-DD），snapshot 指标可为 null
 * @param periodEnd 闭区间结束日期（ISO，YYYY-MM-DD），snapshot 指标可为 null
 */
public record MetricQuerySpec(
        String specId,
        String metricId,
        String chapterId,
        String purpose,
        String periodLabel,
        String periodStart,
        String periodEnd) {

    /** 取报告期当前值的 purpose。 */
    public static final String PURPOSE_CURRENT = "CURRENT";
    /** 取同粒度上周期（环比）比对值的 purpose。 */
    public static final String PURPOSE_COMPARE = "COMPARE";
    /** 取同比基期（去年同期）比对值的 purpose。 */
    public static final String PURPOSE_COMPARE_YOY = "COMPARE_YOY";
    /** 图表趋势序列的历史期取数（Phase04）：序列 fact 不进 ⑤ prompt、不进评测比对射程。 */
    public static final String PURPOSE_CHART_SERIES = "CHART_SERIES";
}
