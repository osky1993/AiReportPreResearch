package com.treasury.nl2sql.report.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.EventRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血缘装配器（P6 契约1，纯静态可单测）：把一次 run 的全部留痕装配成 run 级血缘单文档
 * （对齐 OpenLineage 的 job / run / dataset / facet 命名概念，不引框架）。
 *
 * 纪律 14——血缘同源、断链失败关闭：每个字段都取自库中留痕，不现算、不补猜；
 * 「引用存在、实体缺失」抛 {@link IllegalStateException}（不出半份血缘）；
 * 「老 run 天然没有的产物」（无版本快照 / 无图表 / 无归因）是合法空，标注 absent。
 */
public final class LineageAssembler {

    // ---- 输出模型 ----
    public record Node(String nodeId, String type, String label, Map<String, Object> facets) {}
    public record Edge(String from, String to, String type) {}
    public record Graph(List<Node> nodes, List<Edge> edges) {}
    public record Dataset(String namespace, String name, Map<String, Object> facets) {}
    public record LineageDoc(Map<String, Object> job, Map<String, Object> run,
                             List<Dataset> inputs, List<Dataset> outputs, Graph graph) {}

    /** 指标输入（服务层按 run 的版本快照解析：version 来自快照、tables 来自该版本 mqlTemplate）。 */
    public record MetricInput(String metricId, int version, String name, List<String> tables) {}

    // 节点类型
    public static final String T_REQUIREMENT = "REQUIREMENT";
    public static final String T_TEMPLATE = "TEMPLATE";
    public static final String T_METRIC = "METRIC";
    public static final String T_SPEC = "SPEC";
    public static final String T_SQL = "SQL";
    public static final String T_FACT = "FACT";
    public static final String T_CHART = "CHART";
    public static final String T_EVENT = "EVENT";
    public static final String T_CLAIM = "CLAIM";
    public static final String T_APPROVAL = "APPROVAL";
    public static final String T_REPORT = "REPORT";

    // 边类型
    public static final String E_MATCHED_TO = "MATCHED_TO";
    public static final String E_RESOLVED_TO = "RESOLVED_TO";
    public static final String E_COMPILED_TO = "COMPILED_TO";
    public static final String E_PRODUCED = "PRODUCED";
    public static final String E_DERIVED_FROM = "DERIVED_FROM";
    public static final String E_BOUND_TO = "BOUND_TO";
    public static final String E_EVIDENCED_BY = "EVIDENCED_BY";
    public static final String E_APPROVED_BY = "APPROVED_BY";

    public static final String ABSENT = "absent";

    private LineageAssembler() {}

    /**
     * 将 run 的主线索引（outline/spec/fact/chart/claim）拼成可读血缘图对象。
     * 规则：任何引用缺失抛 IllegalStateException（断链），拒绝产出半张血缘；缺指标快照/图表等视为合法 absent。
     *
     * @param mapper Jackson 对象映射器。
     * @param run 当前报告运行实例。
     * @param templateName 模板名；空值回退成 placeholder。
     * @param facts 凭证明细，若为空表示 ③~⑥ 未产出事实。
     * @param claims 归因与审计主张，可能为空集合。
     * @param charts 图表清单。
     * @param eventsById 已解析事件实体映射（claims 中的 EVT-id 必须存在，否则 fail-closed）。
     * @param metrics 指标输入清单；null 表示 run 无版本快照（Phase02 前存量），以 {@link #ABSENT} 标注输出端。
     */
    public static LineageDoc assemble(ObjectMapper mapper, ReportRun run, String templateName,
                                      List<FactRecord> facts, List<ClaimRecord> claims,
                                      List<ChartRecord> charts, Map<Long, EventRecord> eventsById,
                                      List<MetricInput> metrics) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();

        // ---- 需求与模板 ----
        nodes.add(new Node("req", T_REQUIREMENT, truncate(run.requestText(), 120),
                Map.of("requestText", nz(run.requestText()))));
        String tplId = "tpl:" + run.templateId() + "@" + (run.templateVersion() == null ? ABSENT : run.templateVersion());
        nodes.add(new Node(tplId, T_TEMPLATE, nz(templateName),
                facets("templateId", run.templateId(), "version",
                        run.templateVersion() == null ? ABSENT : run.templateVersion())));
        edges.add(new Edge("req", tplId, E_MATCHED_TO));

        // ---- 指标（版本快照口径；null 快照 = 合法空） ----
        Map<String, String> metricNodeByMetricId = new HashMap<>();
        if (metrics != null) {
            for (MetricInput m : metrics) {
                String id = "metric:" + m.metricId() + "@" + m.version();
                metricNodeByMetricId.put(m.metricId(), id);
                nodes.add(new Node(id, T_METRIC, nz(m.name()),
                        facets("metricId", m.metricId(), "version", m.version(),
                                "tables", m.tables() == null ? List.of() : m.tables())));
            }
        }

        // ---- 凭证宇宙（断链检查的基准集） ----
        Map<String, FactRecord> factByKey = new LinkedHashMap<>();
        for (FactRecord f : facts) factByKey.put(f.factKey(), f);

        // ---- spec / sql / fact ----
        Set<String> specSeen = new LinkedHashSet<>();
        Set<String> sqlSeen = new LinkedHashSet<>();
        for (FactRecord f : facts) {
            String factNode = "fact:" + f.factKey();
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("metricId", nz(f.metricId()));
            ff.put("factType", nz(f.factType()));
            ff.put("displayValue", nz(f.displayValue()));
            ff.put("periodLabel", nz(f.periodLabel()));
            ff.put("qualityStatus", nz(f.qualityStatus()));
            if (f.dimensions() != null) ff.put("dimensions", f.dimensions());
            if (f.sqlHash() != null) ff.put("sqlHash", f.sqlHash());
            if (f.resultHash() != null) ff.put("resultHash", f.resultHash());
            nodes.add(new Node(factNode, T_FACT, f.factKey(), ff));

            String specNode = specNodeOf(mapper, f, nodes, specSeen, tplId, metricNodeByMetricId, edges);
            if (f.sqlHash() != null) {
                String sqlNode = "sql:" + f.sqlHash();
                if (sqlSeen.add(sqlNode)) {
                    nodes.add(new Node(sqlNode, T_SQL, shortHash(f.sqlHash()),
                            Map.of("sqlHash", f.sqlHash())));
                }
                if (specNode != null) edges.add(new Edge(specNode, sqlNode, E_COMPILED_TO));
                edges.add(new Edge(sqlNode, factNode, E_PRODUCED));
            } else if (specNode != null) {
                edges.add(new Edge(specNode, factNode, E_PRODUCED));
            }
            if (f.derivedFrom() != null && !f.derivedFrom().isBlank()) {
                for (String src : f.derivedFrom().split(",")) {
                    String key = src.trim();
                    if (key.isEmpty()) continue;
                    if (!factByKey.containsKey(key)) {
                        throw new IllegalStateException("血缘断链：fact " + f.factKey()
                                + " 的 derivedFrom 引用了不存在的凭证 " + key);
                    }
                    edges.add(new Edge(factNode, "fact:" + key, E_DERIVED_FROM));
                }
            }
        }

        // ---- 图表（fact -BOUND_TO-> chart） ----
        for (ChartRecord c : charts) {
            String chartNode = "chart:" + c.chartId();
            nodes.add(new Node(chartNode, T_CHART, nz(c.title()),
                    facets("type", c.type(), "chapterId", c.chapterId(),
                            "points", c.boundFactKeys() == null ? 0 : c.boundFactKeys().size())));
            for (String key : c.boundFactKeys() == null ? List.<String>of() : c.boundFactKeys()) {
                if (!factByKey.containsKey(key)) {
                    throw new IllegalStateException("血缘断链：图表 " + c.chartId()
                            + " 绑定了不存在的凭证 " + key);
                }
                edges.add(new Edge("fact:" + key, chartNode, E_BOUND_TO));
            }
        }

        // ---- 归因（claim -EVIDENCED_BY-> fact | event） ----
        Set<Long> eventSeen = new HashSet<>();
        for (ClaimRecord c : claims) {
            String claimNode = "claim:" + c.claimId();
            Map<String, Object> cf = new LinkedHashMap<>();
            cf.put("attributionLevel", nz(c.attributionLevel()));
            cf.put("anomalyFactKey", nz(c.anomalyFactKey()));
            if (c.confirmedBy() != null) {
                cf.put("confirmedBy", c.confirmedBy());
                cf.put("confirmedAt", String.valueOf(c.confirmedAt()));
            }
            nodes.add(new Node(claimNode, T_CLAIM, truncate(c.narrative(), 80), cf));
            for (String ref : c.evidenceRefs() == null ? List.<String>of() : c.evidenceRefs()) {
                if (ref == null || ref.isBlank()) continue;
                if (ref.startsWith("EVT-")) {
                    long eventId = parseEventId(c.claimId(), ref);
                    EventRecord ev = eventsById.get(eventId);
                    if (ev == null) {
                        throw new IllegalStateException("血缘断链：claim " + c.claimId()
                                + " 引用了不存在的事件 " + ref);
                    }
                    String evNode = "event:" + eventId;
                    if (eventSeen.add(eventId)) {
                        nodes.add(new Node(evNode, T_EVENT, nz(ev.title()),
                                facets("eventDate", String.valueOf(ev.eventDate()),
                                        "source", nz(ev.source()), "status", nz(ev.status()))));
                    }
                    edges.add(new Edge(claimNode, evNode, E_EVIDENCED_BY));
                } else {
                    if (!factByKey.containsKey(ref)) {
                        throw new IllegalStateException("血缘断链：claim " + c.claimId()
                                + " 引用了不存在的凭证 " + ref);
                    }
                    edges.add(new Edge(claimNode, "fact:" + ref, E_EVIDENCED_BY));
                }
            }
        }

        // ---- 审批与报告 ----
        if (run.outlineApprovedBy() != null) {
            nodes.add(new Node("approval:outline", T_APPROVAL, "卡点1 口径确认",
                    facets("approver", run.outlineApprovedBy(), "at", String.valueOf(run.outlineApprovedAt()))));
            edges.add(new Edge(tplId, "approval:outline", E_APPROVED_BY));
        }
        if (run.reportMd() != null) {
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("status", nz(run.status()));
            rf.put("audit", auditSummary(mapper, run.auditJson()));
            nodes.add(new Node("report", T_REPORT, "报告终稿（" + nz(run.periodLabel()) + "）", rf));
            if (run.publishApprovedBy() != null) {
                nodes.add(new Node("approval:publish", T_APPROVAL, "卡点2 签发",
                        facets("approver", run.publishApprovedBy(), "at", String.valueOf(run.publishApprovedAt()))));
                edges.add(new Edge("report", "approval:publish", E_APPROVED_BY));
            }
        }

        // ---- job / run / inputs / outputs ----
        Map<String, Object> job = facets("templateId", run.templateId(),
                "templateVersion", run.templateVersion() == null ? ABSENT : run.templateVersion(),
                "templateName", nz(templateName));
        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("runId", run.runId());
        runInfo.put("status", nz(run.status()));
        runInfo.put("periodLabel", nz(run.periodLabel()));
        runInfo.put("periodStart", String.valueOf(run.periodStart()));
        runInfo.put("periodEnd", String.valueOf(run.periodEnd()));
        if (run.compareStart() != null) runInfo.put("compareWindow", run.compareStart() + "~" + run.compareEnd());
        if (run.yoyStart() != null) runInfo.put("yoyWindow", run.yoyStart() + "~" + run.yoyEnd());
        if (run.blockedReason() != null) runInfo.put("blockedReason", run.blockedReason());
        runInfo.put("metricVersionSnapshot", metrics == null ? ABSENT : "present");

        List<Dataset> inputs = new ArrayList<>();
        if (metrics == null) {
            inputs.add(new Dataset("business-table", ABSENT,
                    Map.of("note", "run 无指标版本快照（Phase02 前存量），业务表输入不可考")));
        } else {
            Map<String, List<String>> byTable = new LinkedHashMap<>();
            for (MetricInput m : metrics) {
                for (String t : m.tables() == null ? List.<String>of() : m.tables()) {
                    byTable.computeIfAbsent(t, k -> new ArrayList<>()).add(m.metricId());
                }
            }
            byTable.forEach((table, ids) ->
                    inputs.add(new Dataset("business-table", table, Map.of("usedByMetrics", ids))));
            for (MetricInput m : metrics) {
                inputs.add(new Dataset("asset:metric", m.metricId() + "@" + m.version(), Map.of("name", nz(m.name()))));
            }
        }
        inputs.add(new Dataset("asset:template", run.templateId() + "@"
                + (run.templateVersion() == null ? ABSENT : run.templateVersion()), Map.of("name", nz(templateName))));
        for (Long evId : eventSeen) {
            inputs.add(new Dataset("asset:event", "EVT-" + evId, Map.of("title", nz(eventsById.get(evId).title()))));
        }

        List<Dataset> outputs = new ArrayList<>();
        outputs.add(new Dataset("artifact", "facts", Map.of("count", facts.size())));
        if (!charts.isEmpty()) outputs.add(new Dataset("artifact", "charts", Map.of("count", charts.size())));
        if (!claims.isEmpty()) outputs.add(new Dataset("artifact", "claims", Map.of("count", claims.size())));
        if (run.reportMd() != null) outputs.add(new Dataset("artifact", "report_md", Map.of("status", nz(run.status()))));

        return new LineageDoc(job, runInfo, inputs, outputs, new Graph(nodes, edges));
    }

    /** spec 节点（按 specId 去重）；specJson 缺失 = 合法空（返回 null，不建节点不连边）。 */
    private static String specNodeOf(ObjectMapper mapper, FactRecord f, List<Node> nodes, Set<String> seen,
                                     String tplNode, Map<String, String> metricNodeByMetricId, List<Edge> edges) {
        if (f.specJson() == null || f.specJson().isBlank()) return null;
        try {
            JsonNode spec = mapper.readTree(f.specJson());
            String specId = spec.path("specId").asText(null);
            if (specId == null || specId.isBlank()) return null;
            String nodeId = "spec:" + specId;
            if (seen.add(nodeId)) {
                nodes.add(new Node(nodeId, T_SPEC, specId,
                        facets("purpose", spec.path("purpose").asText(""),
                                "metricId", spec.path("metricId").asText(""),
                                "periodLabel", spec.path("periodLabel").asText(""))));
                edges.add(new Edge(tplNode, nodeId, E_RESOLVED_TO));
                String metricNode = metricNodeByMetricId.get(spec.path("metricId").asText(""));
                if (metricNode != null) edges.add(new Edge(metricNode, nodeId, E_RESOLVED_TO));
            }
            return nodeId;
        } catch (Exception e) {
            throw new IllegalStateException("血缘断链：fact " + f.factKey() + " 的规约快照无法解析", e);
        }
    }

    private static Map<String, Object> auditSummary(ObjectMapper mapper, String auditJson) {
        if (auditJson == null || auditJson.isBlank()) return Map.of("note", ABSENT);
        try {
            JsonNode a = mapper.readTree(auditJson);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("passed", a.path("passed").asBoolean(false));
            s.put("totalNumbers", a.path("totalNumbers").asInt());
            s.put("matchedNumbers", a.path("matchedNumbers").asInt());
            s.put("rewriteRounds", a.path("rewriteRounds").asInt());
            JsonNode cc = a.path("chartChecks");
            if (cc.isArray()) {
                int ok = 0;
                for (JsonNode c : cc) if (c.path("ok").asBoolean(false)) ok++;
                s.put("chartChecks", ok + "/" + cc.size());
            }
            return s;
        } catch (Exception e) {
            throw new IllegalStateException("血缘断链：审计包无法解析", e);
        }
    }

    /** Event 引用必须是 EVT-123 数字后缀，失败视为血缘断链，防止脏引用污染报告。 */
    private static long parseEventId(String claimId, String ref) {
        try {
            return Long.parseLong(ref.substring("EVT-".length()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("血缘断链：claim " + claimId + " 的事件引用格式非法 " + ref);
        }
    }

    private static Map<String, Object> facets(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 处理空值的统一工具：让 facets / 节点字段生成时不出现 NPE。 */
    private static String nz(String s) { return s == null ? "" : s; }

    /** 安全截断：长字符串用于节点标签时只保留前缀，避免 lineage JSON 爆长。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 哈希摘要短化，供 report 节点显示，用于人工快速检查而非完整校验。 */
    private static String shortHash(String hash) {
        return hash == null ? "" : (hash.length() <= 12 ? hash : hash.substring(0, 12));
    }
}
