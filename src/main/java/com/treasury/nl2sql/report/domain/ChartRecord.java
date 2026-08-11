package com.treasury.nl2sql.report.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一张已绑定数据的图表（Phase04 契约2）：由 ChartBuildStep（确定性程序，零 LLM）从 fact 组装，
 * 落 report_run.charts_json；boundFactKeys 与 option.series 数据点一一对应（顺序即对应关系），
 * ⑥ ChartAuditor 逐点回读核对——「每张图有出生证明」。
 *
 * @param optionJson    ECharts option（程序模板化组装，series 数据纯数值）
 * @param boundFactKeys 每个数据点的来源 factKey（审计核对键，顺序对齐 series.data）
 * @param chartId 图表实例唯一标识（chapter 中的 charts 快照）
 * @param chapterId 归属章节 ID（用于失败归因与重放过滤）
 * @param type 图表类型（line/bar/pie）
 * @param title 图表标题（快照固化）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChartRecord(
        String chartId,
        String chapterId,
        String type,
        String title,
        String optionJson,
        List<String> boundFactKeys) {}
