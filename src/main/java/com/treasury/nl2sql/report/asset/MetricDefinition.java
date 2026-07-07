package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 指标语义定义（MVP 最小格式，resources/report/metrics.json）。
 * 两种形态二选一：
 *  - 取数指标：带 mqlTemplate（合法 Mql JSON，占位符仅 {{period_start}}/{{period_end}}），走 ③ 确定性取数；
 *  - 派生指标：带 derived（对两个取数指标的算术），由 ④ 程序从 BASE fact 计算，不取数。
 *
 * @param timeBound   true=按报告期窗口取数（模板含日期占位符）；false=快照类（无占位符）
 * @param comparable  true=对 comparison 章节额外派生对比期查询，④ 计算环比 DERIVED fact
 * @param valueColumn 取数结果（恰 1 行）里承载指标值的列名
 * @param nullPolicy  取数值为 NULL 时的处置：ZERO=按 0（如空窗口的 SUM）/ BLOCK=失败关闭
 * @param qualityChecks 质量断言清单：NON_NEGATIVE（负值即失败关闭）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricDefinition(
        String metricId,
        String name,
        String unit,
        boolean timeBound,
        boolean comparable,
        String valueColumn,
        String nullPolicy,
        List<String> qualityChecks,
        JsonNode mqlTemplate,
        Derived derived) {

    public static final String NULL_ZERO = "ZERO";
    public static final String NULL_BLOCK = "BLOCK";
    public static final String CHECK_NON_NEGATIVE = "NON_NEGATIVE";

    /** 派生指标：left/right 为其他取数指标的 metricId，op 目前仅 subtract。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Derived(String op, String left, String right) {}

    public boolean isDerived() {
        return derived != null;
    }
}
