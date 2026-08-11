package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 异常识别防线单测（纯逻辑）。验证异常规则如何被触发、命名、去重与失败关闭。
 * <p>
 * 关注点：只有命中规则的异常事实会生成 _anom 派生事实；未命中或缺口数据不应产出噪声；异常条数超限必须抛异常并中断。
 */
class AnomalyDetectorTest {

    private static MetricDefinition metricWithRules(String id, List<MetricDefinition.AnomalyRule> rules) {
        return new MetricDefinition(id, "指标" + id, "CNY", true, true, "v", "ZERO",
                List.of(), null, rules, null, null);
    }

    private static MetricDefinition.AnomalyRule volatility(String basis, String absPct) {
        return new MetricDefinition.AnomalyRule("volatility", null, null, basis, new BigDecimal(absPct), null);
    }

    private static MetricDefinition.AnomalyRule threshold(String op, String value) {
        return new MetricDefinition.AnomalyRule("threshold", op, new BigDecimal(value), null, null, null);
    }

    private static FactRecord fact(String key, String metricId, String value, String unit) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, metricId, 1, "指标" + metricId, "ch1", FactRecord.TYPE_BASE,
                v, unit, FactBuildStep.renderDisplay(v, unit), "2026-W26",
                null, null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private static Outline outline(String... metricIds) {
        return new Outline("tpl", "2026-W26",
                List.of(new Outline.OutlineChapter("ch1", "第一章", List.of(metricIds), null, null, "g", null, null)),
                List.of());
    }

    /**
     * 输入：单一指标有波动规则命中且存在对应 _wow 比较值。
     * 预期：输出 1 条 _anom 派生事实，派生 factKey/来源与规则文本均可回放。
     */
    @Test
    void volatilityHitProducesAnomalyFactWithRuleNote() {
        var defs = Map.of("m1", metricWithRules("m1", List.of(volatility("wow", "30"))));
        List<FactRecord> facts = List.of(
                fact("fact_001", "m1", "3851436", "CNY"),
                fact("fact_001_wow", "m1", "142.2", "percent"));
        List<String> notes = new ArrayList<>();
        List<AnomalyDetector.Anomaly> anomalies = AnomalyDetector.detect(outline("m1"), facts, defs, notes);
        assertEquals(1, anomalies.size());
        assertEquals("wow", anomalies.get(0).rule().basis());
        FactRecord a = anomalies.get(0).fact();
        assertEquals("fact_001_anom", a.factKey());
        assertEquals(FactRecord.TYPE_DERIVED, a.factType());
        assertEquals(0, a.value().compareTo(new BigDecimal("142.2")));
        assertEquals("percent", a.unit());
        assertEquals("fact_001,fact_001_wow", a.derivedFrom());
        assertTrue(a.qualityNote().contains("volatility wow"));
        assertEquals(1, notes.size());
    }

    /**
     * 输入：同一指标只有波动基准低于阈值 + 不存在的基础/比较 basis。
     * 预期：异常规则静默，返回空，避免把“接近阈值”错误放大为异常信号。
     */
    @Test
    void volatilityBelowThresholdOrMissingBasisIsSilent() {
        var defs = Map.of("m1", metricWithRules("m1", List.of(volatility("wow", "30"), volatility("mom", "30"))));
        // wow 只有 +12%（低于 30）；mom 比较 fact 不存在（周报无月环比）→ 双双静默
        List<FactRecord> facts = List.of(
                fact("fact_001", "m1", "100", "CNY"),
                fact("fact_001_wow", "m1", "12.0", "percent"));
        assertTrue(AnomalyDetector.detect(outline("m1"), facts, defs, new ArrayList<>()).isEmpty());
    }

    /**
     * 输入：同一指标有阈值和波动两条规则。
     * 预期：只执行第一条命中规则（first-match），防止重复 _anom 与后续指标异常链被放大。
     */
    @Test
    void thresholdHitAndFirstRuleWins() {
        var defs = Map.of("m1", metricWithRules("m1",
                List.of(threshold(">=", "10000000"), volatility("wow", "30"))));
        List<FactRecord> facts = List.of(
                fact("fact_001", "m1", "11464236", "CNY"),
                fact("fact_001_wow", "m1", "129.6", "percent"));
        List<AnomalyDetector.Anomaly> anomalies = AnomalyDetector.detect(outline("m1"), facts, defs, new ArrayList<>());
        assertEquals(1, anomalies.size(), "每指标至多一条 _anom，首个命中规则胜出");
        assertTrue(anomalies.get(0).fact().qualityNote().startsWith("threshold"));
        assertEquals("CNY", anomalies.get(0).fact().unit());
    }

    /**
     * 输入：规则缺失或当前事实缺失。
     * 预期：按静默策略返回空列表，不抛异常，减少对上游流水线的噪声。
     */
    @Test
    void noRulesOrNoCurrentFactIsSilent() {
        var defs = Map.of("m1", metricWithRules("m1", null));
        assertTrue(AnomalyDetector.detect(outline("m1"),
                List.of(fact("fact_001", "m1", "1", "CNY")), defs, new ArrayList<>()).isEmpty());
        var withRules = Map.of("m1", metricWithRules("m1", List.of(threshold(">=", "1"))));
        assertTrue(AnomalyDetector.detect(outline("m1"), List.of(), withRules, new ArrayList<>()).isEmpty());
    }

    /**
     * 输入：多指标同时命中异常且超过系统上限。
     * 预期：直接抛 PolicyException，进入失败关闭路径，避免异常事实集被过载。
     */
    @Test
    void anomalyCapFailsClosed() {
        Map<String, MetricDefinition> defs = new java.util.LinkedHashMap<>();
        List<FactRecord> facts = new ArrayList<>();
        String[] ids = new String[7];
        for (int i = 0; i < 7; i++) {
            String id = "m" + i;
            ids[i] = id;
            defs.put(id, metricWithRules(id, List.of(threshold(">=", "0"))));
            facts.add(fact("fact_00" + i, id, "1", "CNY"));
        }
        PolicyException e = assertThrows(PolicyException.class,
                () -> AnomalyDetector.detect(outline(ids), facts, defs, new ArrayList<>()));
        assertTrue(e.getMessage().contains("超上限"));
    }
}
