package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.treasury.nl2sql.report.domain.ChartDef;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表数据绑定（Phase04 契约2，确定性程序零 LLM）：④ 之后按章节 charts 声明从 fact 组装
 * ECharts option——series 绑定取「序列 fact（旧→新）+ 本期 CURRENT fact」，dimension 绑定取
 * 维度行 fact（BASE）。LLM 全程不接触（图表不进 ⑤ prompt，正文无图表占位符，前端按章节渲染）。
 * 绑定不到任何数据点 → 留 note 跳过该图（数据缺失不是安全问题，失败关闭留给取数层）。
 */
@Component
public class ChartBuildStep {

    /** 图表绑定过程日志：用于排查绑定不到数据点/序列退化等可观测问题。 */
    private static final Logger log = LoggerFactory.getLogger(ChartBuildStep.class);

    /** ECharts option 生成器，避免手写字符串拼接导致的 JSON 注入/精度变形。 */
    private final ObjectMapper mapper;

    /** 构造注入 JSON 序列化器；图表数据绑定只读，不参与 LLM、仅读取 fact。 */
    public ChartBuildStep(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按章节的 chart 定义从 fact 池提取绑定点，拼装可渲染的 option 与 fact 引用列表。
     * 空数据点直接记 note 并跳过该图，pipeline 继续产出其余图表；无点属于可接受的数据缺失分支。
     */
    public List<ChartRecord> build(Outline outline, List<FactRecord> facts, List<String> notes) {
        List<ChartRecord> charts = new ArrayList<>();
        for (Outline.OutlineChapter ch : outline.chapters()) {
            if (ch.charts() == null) continue;
            for (ChartDef def : ch.charts()) {
                if (def == null || def.binding() == null) continue;
                List<FactRecord> points = ChartDef.KIND_SERIES.equals(def.binding().kind())
                        ? seriesPoints(def, outline, facts)
                        : dimensionPoints(def, facts);
                if (points.isEmpty()) {
                    notes.add("图表「" + def.title() + "」绑定不到任何数据点，本次跳过");
                    continue;
                }
                charts.add(toRecord(def, ch.chapterId(), points));
            }
        }
        if (!charts.isEmpty()) {
            log.info("[CHART] 绑定图表 {} 张（零 LLM 数据通路）", charts.size());
        }
        return charts;
    }

    /** series：序列 fact（fact_cNN_sK，periodLabel 已旧→新）+ 本期 CURRENT fact 收尾。 */
    private static List<FactRecord> seriesPoints(ChartDef def, Outline outline, List<FactRecord> facts) {
        List<FactRecord> points = new ArrayList<>(facts.stream()
                .filter(f -> f.metricId().equals(def.binding().metricId()))
                .filter(FactBuildStep::isChartSeriesFact)
                .sorted(java.util.Comparator.comparing(FactRecord::periodLabel))
                .toList());
        facts.stream()
                .filter(f -> f.metricId().equals(def.binding().metricId()))
                .filter(f -> !FactBuildStep.isChartSeriesFact(f))
                .filter(f -> f.dimensions() == null)
                .filter(f -> outline.periodLabel().equals(f.periodLabel()))
                .filter(f -> FactRecord.TYPE_BASE.equals(f.factType()))
                .findFirst().ifPresent(points::add);
        return points;
    }

    /** dimension：维度行 fact（BASE 且 dimensions 非空；合计与 _share 不进图）。 */
    private static List<FactRecord> dimensionPoints(ChartDef def, List<FactRecord> facts) {
        return facts.stream()
                .filter(f -> f.metricId().equals(def.binding().metricId()))
                .filter(f -> f.dimensions() != null)
                .filter(f -> FactRecord.TYPE_BASE.equals(f.factType()))
                .toList();
    }

    /** 将同一图的点位序列固定成可回放结构：title/tooltip/series + 绑定 factKey 列表（审计用）。 */
    private ChartRecord toRecord(ChartDef def, String chapterId, List<FactRecord> points) {
        ObjectNode option = mapper.createObjectNode();
        option.putObject("title").put("text", def.title());
        option.putObject("tooltip");
        List<String> boundKeys = points.stream().map(FactRecord::factKey).toList();
        if (ChartDef.TYPE_PIE.equals(def.type())) {
            ObjectNode series = option.putArray("series").addObject();
            series.put("type", "pie");
            ArrayNode data = series.putArray("data");
            for (FactRecord p : points) {
                ObjectNode item = data.addObject();
                item.put("name", labelOf(p));
                item.put("value", p.value());
            }
        } else {
            ArrayNode x = option.putObject("xAxis").put("type", "category").putArray("data");
            for (FactRecord p : points) x.add(labelOf(p));
            option.putObject("yAxis").put("type", "value");
            ObjectNode series = option.putArray("series").addObject();
            series.put("type", def.type());
            ArrayNode data = series.putArray("data");
            for (FactRecord p : points) data.add(p.value());
        }
        return new ChartRecord(def.chartId(), chapterId, def.type(), def.title(),
                option.toString(), boundKeys);
    }

    /** 数据点标签：维度行取维度值，序列/本期取周期标签。 */
    private static String labelOf(FactRecord p) {
        if (p.dimensions() != null && !p.dimensions().isEmpty()) {
            return String.join("/", p.dimensions().values());
        }
        return p.periodLabel();
    }
}
