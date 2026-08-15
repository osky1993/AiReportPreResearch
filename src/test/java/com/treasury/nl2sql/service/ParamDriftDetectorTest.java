package com.treasury.nl2sql.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ParamDriftDetector 参数漂移单测（纯逻辑）。
 * 通过多重集合对比检测问题参数的数字集合是否发生增删或变更，顺序无关但次数敏感；
 * 用于 ASK 阶段 CLARIFY 的误差最小化。
 */
class ParamDriftDetectorTest {

    /**
     * 输入：文本含相同数字集合但顺序不同。
     * 预期：无漂移，避免误将顺序变动当问题变更。
     */
    @Test
    void identicalNumbers_noDrift() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "2026年6月30日余额最高的5个活期账户，列出账户名称和余额",
                "2026年6月30日 余额排前5的活期账户有哪些");
        assertFalse(d.drifted());
        assertTrue(d.assetOnly().isEmpty());
        assertTrue(d.questionOnly().isEmpty());
    }

    /**
     * 验证时间参数不同日期时会判定漂移，且两侧数字集合差异可追踪。
     */
    @Test
    void changedDate_drifts_withBothSides() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "2026年6月30日余额最高的5个活期账户",
                "2026年7月31日余额最高的5个活期账户");
        assertTrue(d.drifted());
        assertEquals(List.of("30", "6"), d.assetOnly());
        assertEquals(List.of("31", "7"), d.questionOnly());
    }

    /**
     * 验证仅问题侧新增数字会触发漂移，触发二次澄清机会。
     */
    @Test
    void extraNumberOnQuestionSide_drifts() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "全省共有多少家区县支库",
                "全省共有多少家区县支库，超过50家吗");
        assertTrue(d.drifted());
        assertTrue(d.assetOnly().isEmpty());
        assertEquals(List.of("50"), d.questionOnly());
    }

    /**
     * 验证双侧均无数字时直接判定无漂移。
     */
    @Test
    void bothSidesNoNumbers_noDrift() {
        assertFalse(ParamDriftDetector.diff("全省共有多少家区县支库", "全省区县支库总数").drifted());
    }

    /**
     * 验证漂移比较顺序无关但多重集敏感，避免误报或漏报重复数字变化。
     */
    @Test
    void orderInsensitive_countSensitive() {
        // 顺序无关：同一组数值换序不算漂移
        assertFalse(ParamDriftDetector.diff("A5B10", "B10A5").drifted());
        // 次数敏感：同一数值出现次数不同算漂移（宁可多问一次）
        ParamDriftDetector.Drift d = ParamDriftDetector.diff("6月6日", "6月");
        assertTrue(d.drifted());
        assertEquals(List.of("6"), d.assetOnly());
    }

    /**
     * 验证 null/空白输入安全边界：空输入不漂移，单边有数值时触发漂移。
     */
    @Test
    void nullAndBlank_safe() {
        assertFalse(ParamDriftDetector.diff(null, null).drifted());
        assertTrue(ParamDriftDetector.diff(null, "前3名").drifted());
        assertTrue(ParamDriftDetector.extract(null).isEmpty());
    }
}
