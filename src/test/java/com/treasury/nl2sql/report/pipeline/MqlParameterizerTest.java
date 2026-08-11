package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.pipeline.MqlParameterizer.ApplyResult;
import com.treasury.nl2sql.report.pipeline.MqlParameterizer.ScanResult;
import com.treasury.nl2sql.report.pipeline.MqlParameterizer.Suggestion;
import com.treasury.nl2sql.schema.SchemaService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MqlParameterizer 单测（纯逻辑，mock SchemaService）。
 * 核查 scan/apply 的可选参数化策略：仅可参数化合法时间字段与可比对路径；
 * 已参数化 path 不重复建议，非建议 path 禁止 apply，失败路径返回明确提示。
 */
class MqlParameterizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaService schema = mock(SchemaService.class);
    private final MqlParameterizer parameterizer;

    MqlParameterizerTest() {
        lenient().when(schema.isTemporalColumn(anyString(), anyString())).thenReturn(false);
        lenient().when(schema.isTemporalColumn("cash_transaction", "txn_date")).thenReturn(true);
        lenient().when(schema.isTemporalColumn("currency_rate", "rate_date")).thenReturn(true);
        parameterizer = new MqlParameterizer(schema);
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 输入：同一日期字段出现 >= 与 <=。
     * 预期：扫描返回两条可参数化建议且 placeholder 分别为 START/END。
     */
    @Test
    void simpleDateRangeYieldsTwoSuggestionsWithCorrectPaths() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"txn_date","op":">=","value":"2026-06-22"},
              {"field":"txn_date","op":"<=","value":"2026-06-28"},
              {"field":"status","op":"!=","value":"FAILED"}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(2, r.suggestions().size());
        assertEquals("/filter/0/value", r.suggestions().get(0).path());
        assertEquals(MqlParameterizer.PH_START, r.suggestions().get(0).placeholder());
        assertEquals("/filter/1/value", r.suggestions().get(1).path());
        assertEquals(MqlParameterizer.PH_END, r.suggestions().get(1).placeholder());
    }

    /**
     * 输入：数值条件与普通文本。
     * 预期：不命中，防止把非日期字符串误参数化导致查询语义改变。
     */
    @Test
    void numericAndNonDateStringsAreNotTouched() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"amount","op":">=","value":500000},
              {"field":"counterparty","op":"=","value":"2026 供应商"},
              {"field":"status","op":"=","value":"SETTLED"}],
             "metrics":[{"op":"sum","field":"amount","alias":"amt"}]}""");
        assertTrue(parameterizer.scan(mql).suggestions().isEmpty(), "数字与非日期串不得误伤");
    }

    /**
     * 输入：无日期占位符模板。
     * 预期：scan 为空，参数化流程不改变模板。
     */
    @Test
    void snapshotMqlYieldsZeroSuggestions() {
        JsonNode mql = json("""
            {"table":"account","filter":[{"field":"status","op":"=","value":"ACTIVE"}],
             "metrics":[{"op":"sum","field":"balance","alias":"total"}]}""");
        assertTrue(parameterizer.scan(mql).suggestions().isEmpty());
    }

    /**
     * 输入：嵌套 OR/AND JSON 结构。
     * 预期：扫描路径按树结构正确回溯，验证 JsonPath 定位精度。
     */
    @Test
    void nestedOrAndPathsAreCorrect() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"or":[{"and":[{"field":"txn_date","op":">=","value":"2026-06-01"}]},
                     {"field":"status","op":"=","value":"SETTLED"}]}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size());
        assertEquals("/filter/0/or/0/and/0/value", r.suggestions().get(0).path());
    }

    /**
     * 输入：带 join alias 的字段。
     * 预期：按别名解析表名，避免把外表字段误分类或报错。
     */
    @Test
    void qualifiedFieldViaJoinAliasResolvesTable() {
        JsonNode mql = json("""
            {"table":"cash_transaction","alias":"t",
             "joins":[{"table":"currency_rate","alias":"r","type":"inner",
                       "on":[{"left":"t.currency","op":"=","right":"r.currency"}]}],
             "filter":[
               {"field":"t.txn_date","op":">=","value":"2026-06-22"},
               {"field":"r.rate_date","op":"<=","value":"2026-06-28"},
               {"field":"t.amount","op":">=","value":100}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(2, r.suggestions().size());
        assertEquals("t.txn_date", r.suggestions().get(0).field());
        assertEquals("r.rate_date", r.suggestions().get(1).field());
    }

    /**
     * 输入：subquery 作用域含日期过滤。
     * 预期：仅扫描子查询内部路径，不误向外层扩散。
     */
    @Test
    void subqueryHasOwnScope() {
        // 外层 account 无时间列；子查询作用域是 cash_transaction，其中的日期条件应命中
        JsonNode mql = json("""
            {"table":"account","filter":[
              {"field":"account_id","op":"in","subquery":
                {"table":"cash_transaction","columns":["account_id"],
                 "filter":[{"field":"txn_date","op":">=","value":"2026-06-22"}]}}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size());
        assertEquals("/filter/0/subquery/filter/0/value", r.suggestions().get(0).path());
    }

    /**
     * 输入：已有 placeholder 已替换过的条件。
     * 预期：仅剩未替换项被 scan，保证幂等。
     */
    @Test
    void alreadyParameterizedValueIsIdempotentlySkipped() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"txn_date","op":">=","value":"{{period_start}}"},
              {"field":"txn_date","op":"<=","value":"2026-06-28"}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size(), "已参数化的条件不重复建议");
        assertEquals("/filter/1/value", r.suggestions().get(0).path());
    }

    /**
     * 输入：等值日期条件。
     * 预期：可扫描到但 placeholder 为空（提示态），用于前端呈现不可勾选。
     */
    @Test
    void equalityDateBecomesNonAppliableHint() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"txn_date","op":"=","value":"2026-06-25"}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size());
        assertNull(r.suggestions().get(0).placeholder(), "等值日期是提示项，不可勾选");
    }

    /**
     * 输入：指标级条件字段里有日期约束。
     * 预期：扫描路径进入 metrics[*].filter 下，参数化可覆盖。
     */
    @Test
    void conditionalAggregateFilterIsCovered() {
        JsonNode mql = json("""
            {"table":"cash_transaction",
             "metrics":[{"op":"sum","field":"amount","alias":"amt",
                         "filter":[{"field":"txn_date","op":">=","value":"2026-06-22"}]}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size());
        assertEquals("/metrics/0/filter/0/value", r.suggestions().get(0).path());
    }

    /**
     * 输入：选择 start/end 两个合法路径 + 尝试非法 path。
     * 预期：仅替换已扫描 path；非法 path 拒绝并抛可见异常。
     */
    @Test
    void applyReplacesOnlyChosenPathAndRejectsForeignPath() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"txn_date","op":">=","value":"2026-06-22"},
              {"field":"txn_date","op":"<=","value":"2026-06-28"},
              {"field":"amount","op":">=","value":500000}],
             "metrics":[{"op":"sum","field":"amount","alias":"amt"}]}""");
        ApplyResult a = parameterizer.apply(mql, List.of("/filter/0/value", "/filter/1/value"));
        assertEquals(MqlParameterizer.PH_START, a.mqlTemplate().at("/filter/0/value").asText());
        assertEquals(MqlParameterizer.PH_END, a.mqlTemplate().at("/filter/1/value").asText());
        assertEquals(500000, a.mqlTemplate().at("/filter/2/value").asInt(), "未勾选节点不动");
        assertEquals("2026-06-22", mql.at("/filter/0/value").asText(), "原件不被修改");
        assertTrue(a.remaining().isEmpty());
        // 非建议 path（试图篡改表名）→ 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> parameterizer.apply(mql, List.of("/table")));
        // 不可勾选的提示项 path → 拒绝
        JsonNode eq = json("""
            {"table":"cash_transaction","filter":[{"field":"txn_date","op":"=","value":"2026-06-25"}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        assertThrows(IllegalArgumentException.class,
                () -> parameterizer.apply(eq, List.of("/filter/0/value")));
    }

    /**
     * 输入：日期 in 列表条件。
     * 预期：同样扫描为提示项（非可替换），防止语义被错误参数化。
     */
    @Test
    void inListWithDatesBecomesHint() {
        JsonNode mql = json("""
            {"table":"cash_transaction","filter":[
              {"field":"txn_date","op":"in","value":["2026-06-22","2026-06-23"]}],
             "metrics":[{"op":"count","alias":"cnt"}]}""");
        ScanResult r = parameterizer.scan(mql);
        assertEquals(1, r.suggestions().size());
        assertNull(r.suggestions().get(0).placeholder());
    }
}
