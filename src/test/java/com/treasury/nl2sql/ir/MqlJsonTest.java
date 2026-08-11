package com.treasury.nl2sql.ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR 的 Jackson 绑定回归：
 * 通过固定 JSON 用例兜底关键字段反序列化，防止字段名错配（如 else）静默失效。
 * 覆盖 caseColumn、timeColumns、字段表达式、join 常量、窗口 offset、distinct flags 等。
 */
class MqlJsonTest {

    private final ObjectMapper om = new ObjectMapper();

    /**
     * 输入：json 使用 else 关键字；
     * 预期：反序列化到 elseValue 字段而非丢弃或混淆到其他字段。
     */
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

    /**
     * 输入：timeColumns 定义 month + ym；
     * 预期：func/field/alias 全部命中映射，防止 schema 计算时字段名错位。
     */
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

    /**
     * 输入：fieldExpr 和 expr 里有常量/表达式；
     * 预期：表达式树（左、右、算子）完整反序列化到对象模型。
     */
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

    /**
     * 输入：join on 常量值、window offset；
     * 预期：on.value 与 offset 正常落地，保证版本兼容与 SQL 渲染输入完整性。
     */
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

    /**
     * 输入：distinct 与 metric distinct 同时为 true；
     * 预期：双重布尔均保留，防止 JSON 键名错配导致聚合行为变化。
     */
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
