package com.treasury.nl2sql.report.asset;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 异常规则形状校验单测（启动自检与保存五重校验共用，正反例固化）。 */
class MetricAnomalyRuleTest {

    private static MetricDefinition metric(boolean comparable, List<MetricDefinition.AnomalyRule> rules) {
        return new MetricDefinition("m1", "指标", "CNY", true, comparable, "v", "ZERO",
                List.of(), null, rules, null, null);
    }

    @Test
    void validRulesPass() {
        assertTrue(MetricAnomalyRule.check(metric(true, List.of(
                new MetricDefinition.AnomalyRule("threshold", ">=", new BigDecimal("10000000"), null, null, null),
                new MetricDefinition.AnomalyRule("volatility", null, null, "wow", new BigDecimal("30"), null),
                new MetricDefinition.AnomalyRule("volatility", null, null, "yoy", new BigDecimal("50"), "m_by_ccy")
        ))).isEmpty());
        assertTrue(MetricAnomalyRule.check(metric(false, null)).isEmpty());
    }

    @Test
    void malformedRulesAreRejected() {
        // 非法 type
        assertFalse(MetricAnomalyRule.check(metric(true, List.of(
                new MetricDefinition.AnomalyRule("spike", null, null, null, null, null)))).isEmpty());
        // threshold 缺 op/value、带 basis
        assertFalse(MetricAnomalyRule.check(metric(true, List.of(
                new MetricDefinition.AnomalyRule("threshold", "==", null, "wow", null, null)))).isEmpty());
        // volatility 非法 basis / 非正 absPct
        assertFalse(MetricAnomalyRule.check(metric(true, List.of(
                new MetricDefinition.AnomalyRule("volatility", null, null, "dod", new BigDecimal("30"), null)))).isEmpty());
        assertFalse(MetricAnomalyRule.check(metric(true, List.of(
                new MetricDefinition.AnomalyRule("volatility", null, null, "wow", BigDecimal.ZERO, null)))).isEmpty());
    }

    @Test
    void volatilityRequiresComparable() {
        List<String> errors = MetricAnomalyRule.check(metric(false, List.of(
                new MetricDefinition.AnomalyRule("volatility", null, null, "wow", new BigDecimal("30"), null))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("comparable")));
    }
}
