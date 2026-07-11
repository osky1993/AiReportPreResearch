package com.treasury.nl2sql.report.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 评测比对键单测（P3-T7）：无维度键与 Phase02 逐字节相同；维度行一行一键、取值排序规范化。 */
class ReportEvalServiceTest {

    @Test
    void keyWithoutDimensionsIsByteIdenticalToPhase02() {
        assertEquals("week_txn_count|CURRENT",
                ReportEvalService.expectationKey("week_txn_count", "CURRENT", null, "2026-W26"));
        assertEquals("week_txn_count|COMPARE",
                ReportEvalService.expectationKey("week_txn_count", "COMPARE", Map.of(), "2026-W25"));
    }

    @Test
    void dimensionalRowsGetDistinctKeys() {
        String cny = ReportEvalService.expectationKey("m1", "CURRENT", Map.of("currency", "CNY"), null);
        String usd = ReportEvalService.expectationKey("m1", "CURRENT", Map.of("currency", "USD"), null);
        assertEquals("m1|CURRENT|currency=CNY", cny);
        assertEquals("m1|CURRENT|currency=USD", usd);
        assertNotEquals(cny, usd);
    }

    @Test
    void dimensionKeysAreOrderCanonicalized() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("b", "2");
        ab.put("a", "1");
        assertEquals("m1|CURRENT|a=1,b=2", ReportEvalService.expectationKey("m1", "CURRENT", ab, null));
    }

    @Test
    void chartSeriesKeysArePerPeriod() {
        // 序列点一期一键（P4）；非序列 purpose 不受 periodLabel 影响（键与 Phase02/03 逐字节相同）
        assertEquals("m1|CHART_SERIES|2026-W24",
                ReportEvalService.expectationKey("m1", "CHART_SERIES", null, "2026-W24"));
        assertNotEquals(
                ReportEvalService.expectationKey("m1", "CHART_SERIES", null, "2026-W24"),
                ReportEvalService.expectationKey("m1", "CHART_SERIES", null, "2026-W25"));
    }
}
