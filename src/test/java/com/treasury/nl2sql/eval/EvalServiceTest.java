package com.treasury.nl2sql.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** EvalService 纯比对函数单测（脱离 DB/LLM）。 */
class EvalServiceTest {

    @Test
    void norm_unifiesNumericForms() {
        assertEquals(EvalService.norm(1), EvalService.norm(new BigDecimal("1.00")));
        assertEquals(EvalService.norm(1L), EvalService.norm(new BigDecimal("1")));
        assertEquals("∅", EvalService.norm(null));
        assertEquals("abc", EvalService.norm("  abc  "));
    }

    @Test
    void multiset_ignoresRowAndColumnOrder() {
        // 列序无关：signature 行内排序
        List<String> a = EvalService.signature(List.of(
                Map.of("x", 1, "y", "USD"),
                Map.of("x", 2, "y", "EUR")));
        List<String> b = EvalService.signature(List.of(
                Map.of("y", "EUR", "x", 2),   // 行序 + 列序都不同
                Map.of("y", "USD", "x", 1)));
        assertTrue(EvalService.multisetEquals(a, b));
    }

    @Test
    void multiset_detectsValueDifference() {
        List<String> a = EvalService.signature(List.of(Map.of("v", 100)));
        List<String> b = EvalService.signature(List.of(Map.of("v", 999)));
        assertFalse(EvalService.multisetEquals(a, b));
    }

    @Test
    void multiset_detectsCardinalityDifference() {
        List<String> a = EvalService.signature(List.of(Map.of("v", 1), Map.of("v", 1)));
        List<String> b = EvalService.signature(List.of(Map.of("v", 1)));
        assertFalse(EvalService.multisetEquals(a, b));
    }

    @Test
    void orderedEquals_isSensitiveToRowOrder() {
        List<String> a = List.of("1", "2", "3");
        assertTrue(EvalService.orderedEquals(a, List.of("1", "2", "3")));
        assertFalse(EvalService.orderedEquals(a, List.of("2", "1", "3")));   // 多重集相等但顺序不同
    }

    @Test
    void isOrdered_detectsOrderBy() {
        assertTrue(EvalService.isOrdered("select * from a ORDER BY b desc"));
        assertFalse(EvalService.isOrdered("select * from a where x=1"));
    }

    @Test
    void classify_byReferenceSql() {
        assertEquals("多表", EvalService.classify("select * from a join b on a.id=b.aid"));
        assertEquals("聚合", EvalService.classify("select currency, sum(amount) from t group by currency"));
        assertEquals("聚合", EvalService.classify("select count(*) from t"));
        assertEquals("单表", EvalService.classify("select name from account where balance > 1"));
    }

    @Test
    void colCount_fromFirstRow() {
        assertEquals(0, EvalService.colCount(List.of()));
        assertEquals(2, EvalService.colCount(List.of(Map.of("a", 1, "b", 2))));
    }
}
