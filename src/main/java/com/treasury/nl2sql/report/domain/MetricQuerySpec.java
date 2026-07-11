package com.treasury.nl2sql.report.domain;

/**
 * 查询规约契约（ideaV2 两大数据契约之一）：一次结构化取数的完整描述。
 * 由 ②（纯程序）从「确认后的大纲 + 周期窗口」实例化；日期为 ISO 字符串（yyyy-MM-dd）。
 * 快照类指标（timeBound=false）periodStart/periodEnd 为 null。
 *
 * @param purpose CURRENT=报告期 / COMPARE=同粒度环比基期（上周/上月/上季）/ COMPARE_YOY=同比基期（去年同期）
 * @param chapterId 首个引用该指标的章节（事实归属展示用；同指标多章共享同一 spec）
 */
public record MetricQuerySpec(
        String specId,
        String metricId,
        String chapterId,
        String purpose,
        String periodLabel,
        String periodStart,
        String periodEnd) {

    public static final String PURPOSE_CURRENT = "CURRENT";
    public static final String PURPOSE_COMPARE = "COMPARE";
    public static final String PURPOSE_COMPARE_YOY = "COMPARE_YOY";
}
