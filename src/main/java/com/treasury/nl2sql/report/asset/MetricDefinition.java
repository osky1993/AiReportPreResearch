package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param dimensions  可展开维度（Phase03，如 ["currency"]；MVP 至多 1 个）。声明后 ③ 接受多行、
 *                    ④ 逐行造 fact；mqlTemplate 的 groupBy 必须与之一致（MetricDimensionRule 把关）。
 *                    未声明（null/空）= 单值指标，行为与 Phase03 前完全一致
 * @param anomalyRules 异常规则（Phase05 契约1，程序判定零 LLM）：命中产 ANOMALY fact；
 *                     null/空 = 不做异常检测，行为与 Phase05 前完全一致
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)   // 入库 body_json 不带 null 字段（否则 mqlTemplate:null 会回读成 NullNode）
public record MetricDefinition(
        String metricId,
        String name,
        String unit,
        boolean timeBound,
        boolean comparable,
        String valueColumn,
        String nullPolicy,
        List<String> qualityChecks,
        List<String> dimensions,
        List<AnomalyRule> anomalyRules,
        JsonNode mqlTemplate,
        Derived derived) {

    public static final String NULL_ZERO = "ZERO";
    public static final String NULL_BLOCK = "BLOCK";
    public static final String CHECK_NON_NEGATIVE = "NON_NEGATIVE";

    /** 派生指标：left/right 为其他取数指标的 metricId，op 目前仅 subtract。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Derived(String op, String left, String right) {}

    /**
     * 异常规则（Phase05 契约1）：
     *  - threshold：本期值 op value（op ∈ >=/<=/>/<）——绝对阈值；
     *  - volatility：|对应比较 fact 的变化率| ≥ absPct（basis ∈ wow/mom/qoq/yoy，要求 comparable）。
     * @param dimensionMetricId 可选：异动时做维度贡献拆解的维度指标（P5-T2 消费）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnomalyRule(String type, String op, java.math.BigDecimal value,
                              String basis, java.math.BigDecimal absPct, String dimensionMetricId) {
        public static final String TYPE_THRESHOLD = "threshold";
        public static final String TYPE_VOLATILITY = "volatility";
    }

    /** 命名避开 record 组件 derived 的属性名，配合 JsonIgnore 防止污染入库的 body_json。 */
    @JsonIgnore
    public boolean isDerivedMetric() {
        return derived != null;
    }

    /** 是否声明了可展开维度（Phase03 白名单式增量：未声明的指标走原单行通路）。 */
    @JsonIgnore
    public boolean isDimensional() {
        return dimensions != null && !dimensions.isEmpty();
    }
}
