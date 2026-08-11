package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQL 模板填充单测（无 DB/LLM）。检查 phase03 模板占位符填充流程：
 * 仅替换已识别占位符；任何未解析 token 必须 fail-closed；
 * 无占位符快照模板直接透传，为 ③ 取数零-LLM 通路提供可复现输入。
 */
class MqlTemplateFillerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    /**
     * 输入：含 period_start/period_end 占位符模板和完整参数映射。
     * 预期：转成有效 Mql 对象且字段值正确替换。
     */
    @Test
    void fillsPlaceholdersAndDeserializes() throws Exception {
        JsonNode tpl = json("""
            {"table":"cash_transaction",
             "filter":[{"field":"txn_date","op":">=","value":"{{period_start}}"},
                       {"field":"txn_date","op":"<=","value":"{{period_end}}"}],
             "metrics":[{"op":"count","alias":"cnt"}]}
            """);
        Mql mql = MqlTemplateFiller.fill(mapper, "m1", tpl,
                Map.of("period_start", "2026-06-22", "period_end", "2026-06-28"));
        assertEquals("cash_transaction", mql.table);
        assertEquals("2026-06-22", mql.filter.get(0).value);
        assertEquals("2026-06-28", mql.filter.get(1).value);
        assertEquals("cnt", mql.metrics.get(0).alias);
    }

    /**
     * 输入：拼写错误占位符。
     * 预期：抛 PolicyException，不允许残留 placeholder 继续下游。
     */
    @Test
    void residualPlaceholderFailsClosed() throws Exception {
        // 模板手误 {{period_strat}}：填充不掉 → 必须停下，绝不带占位符去撞校验器
        JsonNode tpl = json("""
            {"table":"cash_transaction",
             "filter":[{"field":"txn_date","op":">=","value":"{{period_strat}}"}],
             "metrics":[{"op":"count","alias":"cnt"}]}
            """);
        PolicyException e = assertThrows(PolicyException.class, () ->
                MqlTemplateFiller.fill(mapper, "m1", tpl,
                        Map.of("period_start", "2026-06-22", "period_end", "2026-06-28")));
        assertTrue(e.getMessage().contains("未填充"));
    }

    /**
     * 输入：快照模板（无任何占位符）。
     * 预期：透传表名与 metric；确认该路径不受参数化依赖影响。
     */
    @Test
    void snapshotTemplateWithoutPlaceholdersPassesThrough() throws Exception {
        JsonNode tpl = json("""
            {"table":"account",
             "filter":[{"field":"status","op":"=","value":"ACTIVE"}],
             "metrics":[{"op":"sum","field":"balance","alias":"total_balance"}]}
            """);
        Mql mql = MqlTemplateFiller.fill(mapper, "m2", tpl, Map.of());
        assertEquals("account", mql.table);
        assertEquals("balance", mql.metrics.get(0).field);
    }

    @Test
    void missingTemplateFailsClosed() {
        assertThrows(PolicyException.class, () ->
                MqlTemplateFiller.fill(mapper, "derived_metric", null, Map.of()));
    }
}
