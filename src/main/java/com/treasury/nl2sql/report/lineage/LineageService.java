package com.treasury.nl2sql.report.lineage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.EventRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.store.AssetRow;
import com.treasury.nl2sql.report.store.ClaimRepository;
import com.treasury.nl2sql.report.store.EventRepository;
import com.treasury.nl2sql.report.store.MetricAssetRepository;
import com.treasury.nl2sql.report.store.ReportFactRepository;
import com.treasury.nl2sql.report.store.ReportRunRepository;
import com.treasury.nl2sql.report.store.TemplateAssetRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血缘导出服务（P6 契约1，观测层，纪律 13 绝对只读）：从既有留痕回读并解析引用，
 * 交给 {@link LineageAssembler} 装配 run 级血缘单文档。
 * 业务表输入不解析 SQL 文本——按 run 的指标版本快照取该版本 mqlTemplate 的
 * table / joins[].table 结构化提取（同源于 ③ 实际执行的模板）。
 */
@Service
public class LineageService {

    private final ReportRunRepository runRepo;
    private final ReportFactRepository factRepo;
    private final ClaimRepository claimRepo;
    private final EventRepository eventRepo;
    private final MetricAssetRepository metricRepo;
    private final TemplateAssetRepository templateRepo;
    private final ObjectMapper mapper;

    /**
     * @param runRepo 运行主表
     * @param factRepo 事实表
     * @param claimRepo 归因事实表
     * @param eventRepo 事件维表
     * @param metricRepo 指标资产仓库
     * @param templateRepo 模板资产仓库
     * @param mapper JSON 映射器
     */
    public LineageService(ReportRunRepository runRepo, ReportFactRepository factRepo,
                          ClaimRepository claimRepo, EventRepository eventRepo,
                          MetricAssetRepository metricRepo, TemplateAssetRepository templateRepo,
                          ObjectMapper mapper) {
        this.runRepo = runRepo;
        this.factRepo = factRepo;
        this.claimRepo = claimRepo;
        this.eventRepo = eventRepo;
        this.metricRepo = metricRepo;
        this.templateRepo = templateRepo;
        this.mapper = mapper;
    }

    /**
     * 组装单次运行血缘文档：读取 run/fact/claim/chart/events，不做二次计算。
     * 任何实体断链抛 IllegalStateException；设计上保证 fail-closed——血缘要么完整可追溯，要么明确失败。
     */
    public LineageAssembler.LineageDoc export(long runId) {
        ReportRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行不存在: " + runId));
        List<FactRecord> facts = factRepo.findByRun(runId);
        List<ClaimRecord> claims = claimRepo.findByRun(runId);
        List<ChartRecord> charts = parseCharts(run.chartsJson());
        String templateName = templateName(run);
        List<LineageAssembler.MetricInput> metrics = metricInputs(run);
        Map<Long, EventRecord> events = resolveEvents(claims);
        return LineageAssembler.assemble(mapper, run, templateName, facts, claims, charts, events, metrics);
    }

    /**
     * 模板名按 run 固化版本取（版本行缺失 = 断链）。
     * 若模板版本快照缺失是历史老数据（合法 absent）并由上层标注，不在这里硬失败。
     */
    private String templateName(ReportRun run) {
        if (run.templateId() == null) return "";
        if (run.templateVersion() == null) return run.templateId();
        return templateRepo.findByIdAndVersion(run.templateId(), run.templateVersion())
                .map(AssetRow::name)
                .orElseThrow(() -> new IllegalStateException("血缘断链：模板版本行不存在 "
                        + run.templateId() + "@" + run.templateVersion()));
    }

    /** 按版本快照逐指标取定义并提取业务表；无快照返回 null（合法空 absent）。 */
    private List<LineageAssembler.MetricInput> metricInputs(ReportRun run) {
        // 读取 run 固化快照，不读最新指标版本，确保可解释性的“快照可还原”；
        // run.metricVersionsJson 缺失属于历史老数据，返回 null 交给装配器标记 absent。
        if (run.metricVersionsJson() == null || run.metricVersionsJson().isBlank()) return null;
        Map<String, Integer> snapshot;
        try {
            snapshot = mapper.readValue(run.metricVersionsJson(), new TypeReference<LinkedHashMap<String, Integer>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("血缘断链：指标版本快照无法解析", e);
        }
        List<LineageAssembler.MetricInput> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
            AssetRow row = metricRepo.findByIdAndVersion(e.getKey(), e.getValue())
                    .orElseThrow(() -> new IllegalStateException("血缘断链：指标版本行不存在 "
                            + e.getKey() + "@" + e.getValue()));
            out.add(new LineageAssembler.MetricInput(e.getKey(), e.getValue(), row.name(),
                    tablesOf(row.bodyJson(), e.getKey())));
        }
        return out;
    }

    /** 从该版本 mqlTemplate 结构化提取 table + joins[].table（派生指标无模板 → 空表清单）。 */
    private List<String> tablesOf(String bodyJson, String metricId) {
        MetricDefinition def;
        try {
            def = mapper.readValue(bodyJson, MetricDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("血缘断链：指标定义无法解析 " + metricId, e);
        }
        if (def.mqlTemplate() == null || def.mqlTemplate().isNull()) return List.of();
        Set<String> tables = new LinkedHashSet<>();
        String main = def.mqlTemplate().path("table").asText("");
        if (!main.isBlank()) tables.add(main);
        for (JsonNode join : def.mqlTemplate().path("joins")) {
            String t = join.path("table").asText("");
            if (!t.isBlank()) tables.add(t);
        }
        return new ArrayList<>(tables);
    }

    /** 运行图表 JSON 解析失败即 fail-closed：血缘服务不允许静默吞掉结构漂移。 */
    private List<ChartRecord> parseCharts(String chartsJson) {
        if (chartsJson == null || chartsJson.isBlank()) return List.of();
        try {
            return mapper.readValue(chartsJson, new TypeReference<List<ChartRecord>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("血缘断链：charts_json 无法解析", e);
        }
    }

    /** 逐条解析 claims 引用的 EVT-id（实体缺失留给装配器按断链处理——这里只收集存在的）。 */
    private Map<Long, EventRecord> resolveEvents(List<ClaimRecord> claims) {
        Map<Long, EventRecord> out = new HashMap<>();
        for (ClaimRecord c : claims) {
            for (String ref : c.evidenceRefs() == null ? List.<String>of() : c.evidenceRefs()) {
                if (ref != null && ref.startsWith("EVT-")) {
                    try {
                        long id = Long.parseLong(ref.substring(4));
                        eventRepo.findById(id).ifPresent(ev -> out.put(id, ev));
                    } catch (NumberFormatException ignored) {
                        // 格式非法由装配器按断链失败关闭
                    }
                }
            }
        }
        return out;
    }
}
