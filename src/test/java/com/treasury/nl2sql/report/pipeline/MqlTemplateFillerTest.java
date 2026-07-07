package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** MQL 模板填充纯逻辑单测（无 DB/LLM）：确定性取数通路的第一环。 */
class MqlTemplateFillerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

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
