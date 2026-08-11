package com.treasury.nl2sql.report.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评测比对键单测（P3-T7）：
 * 验证 expectationKey 在无维度、维度存在、chart_series 分期三类场景的幂等、可逆和规范化行为；
 * 该键是评估缓存与多版本比较的主索引，长度/顺序错误会导致误报或漏报。
 */
class ReportEvalServiceTest {

    /**
     * 验证无维度场景 expectationKey 与历史 phase02 格式兼容，保留可逆幂等。
     */
    @Test
    void keyWithoutDimensionsIsByteIdenticalToPhase02() {
        assertEquals("week_txn_count|CURRENT",
                ReportEvalService.expectationKey("week_txn_count", "CURRENT", null, "2026-W26"));
        assertEquals("week_txn_count|COMPARE",
                ReportEvalService.expectationKey("week_txn_count", "COMPARE", Map.of(), "2026-W25"));
    }

    /**
     * 验证不同维度值会生成不同 key，避免不同分组误合并统计。
     */
    @Test
    void dimensionalRowsGetDistinctKeys() {
        String cny = ReportEvalService.expectationKey("m1", "CURRENT", Map.of("currency", "CNY"), null);
        String usd = ReportEvalService.expectationKey("m1", "CURRENT", Map.of("currency", "USD"), null);
        assertEquals("m1|CURRENT|currency=CNY", cny);
        assertEquals("m1|CURRENT|currency=USD", usd);
        assertNotEquals(cny, usd);
    }

    /**
     * 验证维度参数按字典序归一化，避免输入顺序差异导致缓存 miss。
     */
    @Test
    void dimensionKeysAreOrderCanonicalized() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("b", "2");
        ab.put("a", "1");
        assertEquals("m1|CURRENT|a=1,b=2", ReportEvalService.expectationKey("m1", "CURRENT", ab, null));
    }

    /**
     * 验证 CHART_SERIES 使用 periodLabel 参与键空间，且仅该用途受期次影响。
     */
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
