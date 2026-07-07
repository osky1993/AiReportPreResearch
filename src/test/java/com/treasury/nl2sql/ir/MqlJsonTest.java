package com.treasury.nl2sql.ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** IR 的 Jackson 绑定回归：重点兜住 "else" 关键字（@JsonProperty 漏了会被 ignoreUnknown 静默吞掉）。 */
class MqlJsonTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void caseColumn_elseKeyword_bindsToElseValue() throws Exception {
        String json = """
            {"table":"account",
             "caseColumns":[{"alias":"tier",
               "cases":[{"when":[{"field":"balance","op":">=","value":10000000}],"then":"高"}],
               "else":"低"}],
             "groupBy":["tier"],
             "metrics":[{"op":"count","alias":"cnt"}]}
            """;
        Mql mql = om.readValue(json, Mql.class);
        assertEquals(1, mql.caseColumns.size());
        Mql.CaseColumn cc = mql.caseColumns.get(0);
        assertEquals("tier", cc.alias);
        assertEquals("低", cc.elseValue, "JSON 的 else 字段必须绑定到 elseValue");
        assertEquals("高", cc.cases.get(0).then);
    }

    @Test
    void timeColumns_bind() throws Exception {
        String json = """
            {"table":"cash_transaction",
             "timeColumns":[{"func":"month","field":"txn_date","alias":"ym"}],
             "groupBy":["ym"],
             "metrics":[{"op":"sum","field":"amount","alias":"total"}]}
            """;
        Mql mql = om.readValue(json, Mql.class);
        assertEquals(1, mql.timeColumns.size());
        assertEquals("month", mql.timeColumns.get(0).func);
        assertEquals("txn_date", mql.timeColumns.get(0).field);
        assertEquals("ym", mql.timeColumns.get(0).alias);
    }

    @Test
    void fieldExprAndConstantOperand_bind() throws Exception {
        String json = """
            {"table":"account",
             "metrics":[
               {"op":"sum","fieldExpr":{"left":"balance","arith":"*","right":"rate_to_cny"},"alias":"cny"},
               {"expr":{"left":{"op":"sum","field":"balance"},"arith":"*","right":{"value":100}},"alias":"pct"}]}
            """;
        Mql mql = om.readValue(json, Mql.class);
        assertEquals("balance", mql.metrics.get(0).fieldExpr.left);
        assertEquals("*", mql.metrics.get(0).fieldExpr.arith);
        assertEquals("rate_to_cny", mql.metrics.get(0).fieldExpr.right);
        assertEquals(100, mql.metrics.get(1).expr.right.value);
    }

    @Test
    void onValueAndWindowOffset_bind() throws Exception {
        String json = """
            {"table":"account",
             "joins":[{"table":"cash_transaction","type":"left",
               "on":[{"left":"account.account_id","right":"cash_transaction.account_id"},
                     {"left":"cash_transaction.direction","op":"=","value":"OUT"}]}],
             "windows":[{"op":"lag","field":"amount","offset":2,
                         "orderBy":[{"field":"txn_date"}],"alias":"prev2"}],
             "columns":["account.account_name"]}
            """;
        Mql mql = om.readValue(json, Mql.class);
        assertEquals("OUT", mql.joins.get(0).on.get(1).value);
        assertEquals(2, mql.windows.get(0).offset);
    }

    @Test
    void distinctFlags_bind() throws Exception {
        String json = """
            {"table":"cash_transaction","distinct":true,"columns":["currency"],
             "metrics":[{"op":"count","field":"account_id","distinct":true,"alias":"cnt"}]}
            """;
        Mql mql = om.readValue(json, Mql.class);
        assertEquals(Boolean.TRUE, mql.distinct);
        assertEquals(Boolean.TRUE, mql.metrics.get(0).distinct);
    }
}
