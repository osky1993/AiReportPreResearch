package com.treasury.nl2sql.report.export;

import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 图表数据表兜底单测：数值只取 display_value（与审计同源），boundFactKey 缺失失败关闭。 */
class ChartTableBuilderTest {

    private static FactRecord fact(String key, String display, String periodLabel, Map<String, String> dims) {
        return new FactRecord(key, "m1", 1, "指标", "ch1", FactRecord.TYPE_BASE,
                new BigDecimal("1"), "CNY", display, periodLabel,
                dims, null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    @Test
    void seriesChartRowsUsePeriodLabelAndDisplayValue() {
        ChartRecord chart = new ChartRecord("trend", "summary", "line", "近两周趋势", "{}",
                List.of("fact_c01_s1", "fact_002"));
        Map<String, FactRecord> facts = Map.of(
                "fact_c01_s1", fact("fact_c01_s1", "159.01 万元", "2026-W25", null),
                "fact_002", fact("fact_002", "385.14 万元", "2026-W26", null));
        List<ChartTableBuilder.Row> rows = ChartTableBuilder.build(chart, facts);
        assertEquals(2, rows.size());
        assertEquals("2026-W25", rows.get(0).label());
        assertEquals("159.01 万元", rows.get(0).displayValue());
        assertEquals("fact_002", rows.get(1).factKey());
        assertEquals("385.14 万元", rows.get(1).displayValue());
    }

    @Test
    void dimensionChartRowsUseDimensionValueAsLabel() {
        ChartRecord chart = new ChartRecord("mix", "by_currency", "pie", "币种构成", "{}",
                List.of("fact_017_cny"));
        Map<String, FactRecord> facts = Map.of(
                "fact_017_cny", fact("fact_017_cny", "385.00 万元", "2026-W26", Map.of("currency", "CNY")));
        List<ChartTableBuilder.Row> rows = ChartTableBuilder.build(chart, facts);
        assertEquals("CNY", rows.get(0).label());
    }

    @Test
    void missingBoundFactFailsClosed() {
        ChartRecord chart = new ChartRecord("trend", "summary", "line", "趋势", "{}", List.of("fact_gone"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChartTableBuilder.build(chart, Map.of()));
        assertTrue(e.getMessage().contains("fact_gone"));
        assertTrue(e.getMessage().contains("失败关闭"));
    }
}
