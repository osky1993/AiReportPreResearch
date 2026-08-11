package com.treasury.nl2sql.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvalService 纯比对函数单测（脱离 DB/LLM）。
 * 覆盖规范化、集合比较、顺序敏感和 SQL 分类四类纯函数行为；用于保证评估契约可复现。
 */
class EvalServiceTest {

    /**
     * 输入：数字类型的多种表示（int、BigDecimal、long、null、字符串）；
     * 预期：最终统一为去空白、可读且稳定的“规约字符串”，便于评估结果比对。
     */
    @Test
    void norm_unifiesNumericForms() {
        assertEquals(EvalService.norm(1), EvalService.norm(new BigDecimal("1.00")));
        assertEquals(EvalService.norm(1L), EvalService.norm(new BigDecimal("1")));
        assertEquals("∅", EvalService.norm(null));
        assertEquals("abc", EvalService.norm("  abc  "));
    }

    /**
     * 输入：两批行列顺序不同的结果；
     * 预期：signature 先行列再行排序，multiset 比较在逻辑层面等价时返回 true。
     */
    @Test
    void multiset_ignoresRowAndColumnOrder() {
        List<String> a = EvalService.signature(List.of(
                Map.of("x", 1, "y", "USD"),
                Map.of("x", 2, "y", "EUR")));
        List<String> b = EvalService.signature(List.of(
                Map.of("y", "EUR", "x", 2),   // 行序 + 列序都不同
                Map.of("y", "USD", "x", 1)));
        assertTrue(EvalService.multisetEquals(a, b));
    }

    /**
     * 输入：仅数值不同的两组签名；
     * 预期：多集合比较判定不同，覆盖数据级差异边界。
     */
    @Test
    void multiset_detectsValueDifference() {
        List<String> a = EvalService.signature(List.of(Map.of("v", 100)));
        List<String> b = EvalService.signature(List.of(Map.of("v", 999)));
        assertFalse(EvalService.multisetEquals(a, b));
    }

    /**
     * 输入：内容相同但重复次数不同（2,1）；
     * 预期：cardinality 不一致时返回 false，避免“去重后误判”。
     */
    @Test
    void multiset_detectsCardinalityDifference() {
        List<String> a = EvalService.signature(List.of(Map.of("v", 1), Map.of("v", 1)));
        List<String> b = EvalService.signature(List.of(Map.of("v", 1)));
        assertFalse(EvalService.multisetEquals(a, b));
    }

    /**
     * 输入：同值异位数组序；
     * 预期：orderedEquals 对顺序敏感，只有完全一致序列才通过。
     */
    @Test
    void orderedEquals_isSensitiveToRowOrder() {
        List<String> a = List.of("1", "2", "3");
        assertTrue(EvalService.orderedEquals(a, List.of("1", "2", "3")));
        assertFalse(EvalService.orderedEquals(a, List.of("2", "1", "3")));   // 多重集相等但顺序不同
    }

    /**
     * 输入：含 order by 与不含 order by 的 SQL；
     * 预期：仅识别排序语句命中，作为分类测试的边界支撑。
     */
    @Test
    void isOrdered_detectsOrderBy() {
        assertTrue(EvalService.isOrdered("select * from a ORDER BY b desc"));
        assertFalse(EvalService.isOrdered("select * from a where x=1"));
    }

    /**
     * 输入：join、聚合、单表三类 SQL；
     * 预期：分类输出与语义一致，用于评估指标分类器可解释性与离线报告统计。
     */
    @Test
    void classify_byReferenceSql() {
        assertEquals("多表", EvalService.classify("select * from a join b on a.id=b.aid"));
        assertEquals("聚合", EvalService.classify("select currency, sum(amount) from t group by currency"));
        assertEquals("聚合", EvalService.classify("select count(*) from t"));
        assertEquals("单表", EvalService.classify("select name from account where balance > 1"));
    }

    /**
     * 输入：空行集和两列单行；
     * 预期：列数以首行为准，空集合返回 0，避免空值空指针。
     */
    @Test
    void colCount_fromFirstRow() {
        assertEquals(0, EvalService.colCount(List.of()));
        assertEquals(2, EvalService.colCount(List.of(Map.of("a", 1, "b", 2))));
    }
}
