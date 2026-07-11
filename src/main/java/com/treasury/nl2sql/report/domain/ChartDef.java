package com.treasury.nl2sql.report.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 章节图表声明（Phase04 契约1）：模板资产 schema 与大纲快照共用的数据契约。
 * 图表数据由程序从 fact 绑定（ChartBuildStep，运行期零 LLM）；声明合法性由 TemplateValidator
 * 把关（型-绑定矩阵：series→line/bar；dimension→pie/bar）。
 *
 * @param type    line / bar / pie
 * @param binding series=指标近 N 期时间序列；dimension=维度指标行组（P3 维度 fact 现成）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartDef(
        String chartId,
        String type,
        String title,
        Binding binding) {

    public static final String TYPE_LINE = "line";
    public static final String TYPE_BAR = "bar";
    public static final String TYPE_PIE = "pie";
    public static final String KIND_SERIES = "series";
    public static final String KIND_DIMENSION = "dimension";

    /** @param periods series 绑定的历史周期数（2~12，含本期）；dimension 绑定必须为 null */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Binding(String kind, String metricId, Integer periods) {}
}
