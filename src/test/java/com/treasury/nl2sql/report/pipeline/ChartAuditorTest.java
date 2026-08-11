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
 * ⑥ ChartAuditor 稳定性对抗单测（纯逻辑）。验证图表 JSON 与 fact 绑定的一致性校验规则：
 * 点位值必须等于绑定 fact 展开后的数值，点数量必须与绑定列表一一对应，引用必须存在，非法图表应失败关闭。
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

    /**
     * 输入：line/pie 两类合法图表（数据与 fact 对齐）。
     * 预期：audit 检查通过，证明结构和聚合形态下的正常绑定路径正确。
     */
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

    /**
     * 输入：修改某点数值（fact_c01_s2 篡改）。
     * 预期：该条图表失败，返回变更明细定位至异常点，保障数据可追溯闭环。
     */
    @Test
    void tamperedPointValueIsCaught() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,201,300]", List.of("fact_c01_s1", "fact_c01_s2", "fact_001"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("fact_c01_s2"));
    }

    /**
     * 输入：删减点数导致点位数量不足。
     * 预期：被判定为“点数不守恒”，拒绝发布图表以避免视觉误导。
     */
    @Test
    void removedPointBreaksConservation() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,200]", List.of("fact_c01_s1", "fact_c01_s2", "fact_001"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("点数不守恒"));
    }

    /**
     * 输入：使用不存在的 fact 引用。
     * 预期：直接失败，防止“幻影数据点”进入报告。
     */
    @Test
    void ghostFactReferenceIsCaught() {
        var checks = ChartAuditor.audit(mapper,
                List.of(lineChart("[100,200]", List.of("fact_c01_s1", "fact_ghost"))), FACTS);
        assertFalse(ChartAuditor.passed(checks));
        assertTrue(checks.get(0).detail().contains("fact_ghost"));
    }
}
