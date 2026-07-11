package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.ir.Mql;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 维度声明↔groupBy 一致性规则单测（启动自检与保存五重校验共用，正反例固化）。 */
class MetricDimensionRuleTest {

    private static MetricDefinition metric(List<String> dimensions, boolean comparable) {
        return new MetricDefinition("m1", "指标", "CNY", true, comparable, "v", "ZERO",
                List.of(), dimensions, null, null);
    }

    private static Mql mqlWithGroupBy(String... groupBy) {
        Mql mql = new Mql();
        mql.groupBy = List.of(groupBy);
        return mql;
    }

    @Test
    void dimensionalMetricWithMatchingGroupByPasses() {
        assertTrue(MetricDimensionRule.check(metric(List.of("currency"), false),
                mqlWithGroupBy("currency")).isEmpty());
    }

    @Test
    void plainMetricWithoutGroupByPasses() {
        assertTrue(MetricDimensionRule.check(metric(null, true), new Mql()).isEmpty());
        assertTrue(MetricDimensionRule.check(metric(List.of(), true), new Mql()).isEmpty());
    }

    @Test
    void plainMetricWithGroupByIsRejected() {
        List<String> errors = MetricDimensionRule.check(metric(null, false), mqlWithGroupBy("currency"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("不得含 groupBy"));
    }

    @Test
    void dimensionMismatchWithGroupByIsRejected() {
        assertFalse(MetricDimensionRule.check(metric(List.of("currency"), false),
                mqlWithGroupBy("category")).isEmpty());
        assertFalse(MetricDimensionRule.check(metric(List.of("currency"), false), new Mql()).isEmpty());
    }

    @Test
    void multiDimensionIsRejectedInMvp() {
        List<String> errors = MetricDimensionRule.check(metric(List.of("currency", "category"), false),
                mqlWithGroupBy("currency", "category"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("至多")));
    }

    @Test
    void comparableDimensionalMetricIsRejected() {
        List<String> errors = MetricDimensionRule.check(metric(List.of("currency"), true),
                mqlWithGroupBy("currency"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("comparable")));
    }

    @Test
    void derivedMetricWithDimensionsIsRejected() {
        MetricDefinition derived = new MetricDefinition("net", "净流入", "CNY", true, false, null, "ZERO",
                List.of(), List.of("currency"), null, new MetricDefinition.Derived("subtract", "a", "b"));
        assertTrue(MetricDimensionRule.check(derived, null).stream()
                .anyMatch(e -> e.contains("派生指标")));
    }
}
