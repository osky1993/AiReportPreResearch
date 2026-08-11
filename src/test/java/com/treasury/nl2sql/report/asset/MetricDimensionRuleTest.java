package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.ir.Mql;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 维度声明与 groupBy 一致性规则单测（启动自检与保存五重校验共用，正反例固化）：
 * 验证派生指标/可比较指标/多维限制等边界在静态校验期就失败。
 */
class MetricDimensionRuleTest {

    private static MetricDefinition metric(List<String> dimensions, boolean comparable) {
        return new MetricDefinition("m1", "指标", "CNY", true, comparable, "v", "ZERO",
                List.of(), dimensions, null, null, null);
    }

    private static Mql mqlWithGroupBy(String... groupBy) {
        Mql mql = new Mql();
        mql.groupBy = List.of(groupBy);
        return mql;
    }

    /**
     * 输入：指标声明 dimensions 与 groupBy 一致；
     * 预期：放行，维度约束在静态层面成立。
     */
    @Test
    void dimensionalMetricWithMatchingGroupByPasses() {
        assertTrue(MetricDimensionRule.check(metric(List.of("currency"), false),
                mqlWithGroupBy("currency")).isEmpty());
    }

    /**
     * 输入：非维度指标无 groupBy；
     * 预期：可通过校验，避免对无维度指标的额外约束误杀。
     */
    @Test
    void plainMetricWithoutGroupByPasses() {
        assertTrue(MetricDimensionRule.check(metric(null, true), new Mql()).isEmpty());
        assertTrue(MetricDimensionRule.check(metric(List.of(), true), new Mql()).isEmpty());
    }

    /**
     * 输入：非维度指标却带 groupBy；
     * 预期：应拒绝，体现 groupBy 与维度契约的一一对应关系。
     */
    @Test
    void plainMetricWithGroupByIsRejected() {
        List<String> errors = MetricDimensionRule.check(metric(null, false), mqlWithGroupBy("currency"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("不得含 groupBy"));
    }

    /**
     * 输入：指标 dimensions 与 groupBy 不一致；
     * 预期：拒绝并返回错误，覆盖匹配失败与空维度两类失败路径。
     */
    @Test
    void dimensionMismatchWithGroupByIsRejected() {
        assertFalse(MetricDimensionRule.check(metric(List.of("currency"), false),
                mqlWithGroupBy("category")).isEmpty());
        assertFalse(MetricDimensionRule.check(metric(List.of("currency"), false), new Mql()).isEmpty());
    }

    /**
     * 输入：同一指标申明多于一个维度（MVP 约束）；
     * 预期：拒绝并给出“至多”约束错误，防止维度爆炸。
     */
    @Test
    void multiDimensionIsRejectedInMvp() {
        List<String> errors = MetricDimensionRule.check(metric(List.of("currency", "category"), false),
                mqlWithGroupBy("currency", "category"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("至多")));
    }

    /**
     * 输入：comparable 指标却带 dimensions；
     * 预期：拒绝，保持 comparable 与维度规则的互斥约束。
     */
    @Test
    void comparableDimensionalMetricIsRejected() {
        List<String> errors = MetricDimensionRule.check(metric(List.of("currency"), true),
                mqlWithGroupBy("currency"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("comparable")));
    }

    /**
     * 输入：派生指标同时声明 dimensions；
     * 预期：拒绝，并返回派生类维度约束错误。
     */
    @Test
    void derivedMetricWithDimensionsIsRejected() {
        MetricDefinition derived = new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO",
                List.of(), List.of("currency"), null, null, new MetricDefinition.Derived("subtract", "a", "b"));
        assertTrue(MetricDimensionRule.check(derived, null).stream()
                .anyMatch(e -> e.contains("派生指标")));
    }
}
