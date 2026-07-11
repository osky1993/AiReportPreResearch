package com.treasury.nl2sql.report.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.EventRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 血缘装配纯逻辑单测（P6 契约1，纪律 14 对抗先行）：
 * 完整 run 出全图；三类断链（derivedFrom / 图表绑定 / 事件引用）失败关闭；老 run 合法空 absent。
 */
class LineageAssemblerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ReportRun run(String status, String reportMd, String metricVersionsJson) {
        return new ReportRun(37, "生成周报", "treasury-weekly", 7, metricVersionsJson, "2026-W26",
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28),
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21), null, null,
                status, "DONE", "{}", null, reportMd,
                "{\"passed\":true,\"totalNumbers\":3,\"matchedNumbers\":3,\"rewriteRounds\":0,"
                        + "\"chartChecks\":[{\"chartId\":\"trend\",\"ok\":true}]}",
                null, "alice", LocalDateTime.now(), "bob", LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static FactRecord fact(String key, String type, String spec, String sqlHash, String derivedFrom) {
        return new FactRecord(key, "m1", 3, "指标", "ch1", type,
                new BigDecimal("1"), "CNY", "1.00 元", "2026-W26",
                null, spec, sqlHash == null ? null : "SELECT 1", sqlHash,
                sqlHash == null ? null : "rh_" + key, derivedFrom, FactRecord.QUALITY_PASSED, null);
    }

    private static final String SPEC = "{\"specId\":\"spec_001\",\"metricId\":\"m1\","
            + "\"purpose\":\"CURRENT\",\"periodLabel\":\"2026-W26\"}";

    private static boolean hasEdge(LineageAssembler.LineageDoc doc, String from, String to, String type) {
        return doc.graph().edges().stream().anyMatch(e ->
                e.from().equals(from) && e.to().equals(to) && e.type().equals(type));
    }

    private static EventRecord event(long id) {
        return new EventRecord(id, "华东大客户回款", LocalDate.of(2026, 6, 25), null, List.of("m1"),
                "描述", "人工录入", "ACTIVE", "demo", LocalDateTime.now(), null, null);
    }

    @Test
    void fullRunAssemblesCompleteGraph() {
        List<FactRecord> facts = List.of(
                fact("fact_001", FactRecord.TYPE_BASE, SPEC, "sqlhash001", null),
                fact("fact_001_wow", FactRecord.TYPE_DERIVED, null, null, "fact_001"),
                fact("fact_001_anom", FactRecord.TYPE_DERIVED, null, null, "fact_001,fact_001_wow"));
        List<ChartRecord> charts = List.of(
                new ChartRecord("trend", "ch1", "line", "趋势", "{}", List.of("fact_001")));
        List<ClaimRecord> claims = List.of(
                new ClaimRecord("cl_001", "fact_001_anom", "hypothesis",
                        List.of("fact_001_anom", "EVT-9"), "可能与回款有关，待验证。", null, null));
        var metrics = List.of(new LineageAssembler.MetricInput("m1", 3, "交易总额",
                List.of("transaction", "account")));

        LineageAssembler.LineageDoc doc = LineageAssembler.assemble(M, run("PUBLISHED", "## 一", null),
                "司库资金周报", facts, claims, charts, Map.of(9L, event(9)), metrics);

        var types = doc.graph().nodes().stream().map(LineageAssembler.Node::type).distinct().toList();
        assertTrue(types.containsAll(List.of("REQUIREMENT", "TEMPLATE", "METRIC", "SPEC", "SQL",
                "FACT", "CHART", "EVENT", "CLAIM", "APPROVAL", "REPORT")), "节点类型齐全: " + types);
        // 关键边逐条核验
        assertTrue(hasEdge(doc, "req", "tpl:treasury-weekly@7", "MATCHED_TO"));
        assertTrue(hasEdge(doc, "tpl:treasury-weekly@7", "spec:spec_001", "RESOLVED_TO"));
        assertTrue(hasEdge(doc, "metric:m1@3", "spec:spec_001", "RESOLVED_TO"));
        assertTrue(hasEdge(doc, "spec:spec_001", "sql:sqlhash001", "COMPILED_TO"));
        assertTrue(hasEdge(doc, "sql:sqlhash001", "fact:fact_001", "PRODUCED"));
        assertTrue(hasEdge(doc, "fact:fact_001_wow", "fact:fact_001", "DERIVED_FROM"));
        assertTrue(hasEdge(doc, "fact:fact_001_anom", "fact:fact_001_wow", "DERIVED_FROM"));
        assertTrue(hasEdge(doc, "fact:fact_001", "chart:trend", "BOUND_TO"));
        assertTrue(hasEdge(doc, "claim:cl_001", "event:9", "EVIDENCED_BY"));
        assertTrue(hasEdge(doc, "claim:cl_001", "fact:fact_001_anom", "EVIDENCED_BY"));
        assertTrue(hasEdge(doc, "report", "approval:publish", "APPROVED_BY"));
        assertTrue(hasEdge(doc, "tpl:treasury-weekly@7", "approval:outline", "APPROVED_BY"));
        // inputs 业务表与资产
        assertTrue(doc.inputs().stream().anyMatch(d ->
                d.namespace().equals("business-table") && d.name().equals("transaction")));
        assertTrue(doc.inputs().stream().anyMatch(d ->
                d.namespace().equals("asset:metric") && d.name().equals("m1@3")));
        assertTrue(doc.inputs().stream().anyMatch(d ->
                d.namespace().equals("asset:event") && d.name().equals("EVT-9")));
        // 审计摘要 facet
        var report = doc.graph().nodes().stream().filter(n -> n.nodeId().equals("report")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) report.facets().get("audit");
        assertEquals(true, audit.get("passed"));
        assertEquals("1/1", audit.get("chartChecks"));
    }

    @Test
    void brokenDerivedFromFailsClosed() {
        List<FactRecord> facts = List.of(fact("fact_x_wow", FactRecord.TYPE_DERIVED, null, null, "fact_gone"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                LineageAssembler.assemble(M, run("PUBLISHED", "## 一", null), "周报",
                        facts, List.of(), List.of(), Map.of(), null));
        assertTrue(e.getMessage().contains("断链"));
        assertTrue(e.getMessage().contains("fact_gone"));
    }

    @Test
    void brokenChartBindingAndEventRefFailClosed() {
        List<FactRecord> facts = List.of(fact("fact_001", FactRecord.TYPE_BASE, SPEC, "h1", null));
        assertThrows(IllegalStateException.class, () ->
                LineageAssembler.assemble(M, run("PUBLISHED", "## 一", null), "周报", facts, List.of(),
                        List.of(new ChartRecord("c1", "ch1", "line", "t", "{}", List.of("fact_gone"))),
                        Map.of(), null));
        assertThrows(IllegalStateException.class, () ->
                LineageAssembler.assemble(M, run("PUBLISHED", "## 一", null), "周报", facts,
                        List.of(new ClaimRecord("cl", "fact_001", "hypothesis",
                                List.of("EVT-404"), "可能有关，待验证。", null, null)),
                        List.of(), Map.of(), null));
    }

    @Test
    void legacyRunWithoutSnapshotIsLegalAbsent() {
        List<FactRecord> facts = List.of(fact("fact_001", FactRecord.TYPE_BASE, SPEC, "h1", null));
        LineageAssembler.LineageDoc doc = LineageAssembler.assemble(M,
                run("BLOCKED", null, null), "周报", facts, List.of(), List.of(), Map.of(), null);
        assertEquals("absent", doc.run().get("metricVersionSnapshot"));
        assertTrue(doc.inputs().stream().anyMatch(d -> d.name().equals("absent")), "业务表输入不可考标 absent");
        assertTrue(doc.graph().nodes().stream().noneMatch(n -> n.type().equals("METRIC")));
        assertTrue(doc.graph().nodes().stream().noneMatch(n -> n.type().equals("REPORT")), "无终稿则无 REPORT 节点");
    }
}
