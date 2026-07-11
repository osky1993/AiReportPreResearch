package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 图表核对对抗单测（P4 契约2 三件，纪律 6 对抗先行）：
 * 篡改单点值 / 删点（点数不守恒）/ 幻引用（绑定不存在的 fact）逐条被拦；合法图零误伤。
 */
class ChartAuditorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static FactRecord fact(String key, String value) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, "m1", 1, "指标m1", "ch1", FactRecord.TYPE_BASE,
                v, "CNY", FactBuildStep.renderDisplay(v, "CNY"), "2026-W26",
                null, null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private static final Map<String, FactRecord> FACTS = Map.of(
            "fact_c01_s1", fact("fact_c01_s1", "100"),
            "fact_c01_s2", fact("fact_c01_s2", "200"),
            "fact_001", fact("fact_001", "300"));

    private static ChartRecord lineChart(String data, List<String> keys) {
        return new ChartRecord("trend_x", "ch1", "line", "趋势",
                "{\"series\":[{\"type\":\"line\",\"data\":" + data + "}]}", keys);
    }

    @Test
    void consistentChartPasses() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,200,300]", List.of("fact_c01_s1", "fact_c01_s2", "fact_001"))), FACTS);
        assertTrue(ChartAuditor.passed(checks), checks.toString());
        // pie 形态同样核对
        ChartRecord pie = new ChartRecord("mix_x", "ch1", "pie", "构成",
                "{\"series\":[{\"type\":\"pie\",\"data\":[{\"name\":\"a\",\"value\":100},{\"name\":\"b\",\"value\":200}]}]}",
                List.of("fact_c01_s1", "fact_c01_s2"));
        assertTrue(ChartAuditor.passed(ChartAuditor.audit(mapper, List.of(pie), FACTS)));
    }

    @Test
    void tamperedPointValueIsCaught() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,201,300]", List.of("fact_c01_s1", "fact_c01_s2", "fact_001"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("fact_c01_s2"));
    }

    @Test
    void removedPointBreaksConservation() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,200]", List.of("fact_c01_s1", "fact_c01_s2", "fact_001"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("点数不守恒"));
    }

    @Test
    void ghostFactReferenceIsCaught() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,200]", List.of("fact_c01_s1", "fact_ghost"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("fact_ghost"));
    }
}
