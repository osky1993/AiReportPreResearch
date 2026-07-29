package com.treasury.nl2sql.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HIT 参数漂移检测的纯逻辑单测：多重集比对（次数敏感、顺序无关）。 */
class ParamDriftDetectorTest {

    @Test
    void identicalNumbers_noDrift() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "2026年6月30日库存余额最高的5个县级国库，列出国库简称和余额",
                "2026年6月30日 库存余额排前5的县级国库有哪些");
        assertFalse(d.drifted());
        assertTrue(d.assetOnly().isEmpty());
        assertTrue(d.questionOnly().isEmpty());
    }

    @Test
    void changedDate_drifts_withBothSides() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "2026年6月30日库存余额最高的5个县级国库",
                "2026年7月31日库存余额最高的5个县级国库");
        assertTrue(d.drifted());
        assertEquals(List.of("30", "6"), d.assetOnly());
        assertEquals(List.of("31", "7"), d.questionOnly());
    }

    @Test
    void extraNumberOnQuestionSide_drifts() {
        ParamDriftDetector.Drift d = ParamDriftDetector.diff(
                "全省共有多少家区县支库",
                "全省共有多少家区县支库，超过50家吗");
        assertTrue(d.drifted());
        assertTrue(d.assetOnly().isEmpty());
        assertEquals(List.of("50"), d.questionOnly());
    }

    @Test
    void bothSidesNoNumbers_noDrift() {
        assertFalse(ParamDriftDetector.diff("全省共有多少家区县支库", "全省区县支库总数").drifted());
    }

    @Test
    void orderInsensitive_countSensitive() {
        // 顺序无关：同一组数值换序不算漂移
        assertFalse(ParamDriftDetector.diff("A5B10", "B10A5").drifted());
        // 次数敏感：同一数值出现次数不同算漂移（宁可多问一次）
        ParamDriftDetector.Drift d = ParamDriftDetector.diff("6月6日", "6月");
        assertTrue(d.drifted());
        assertEquals(List.of("6"), d.assetOnly());
    }

    @Test
    void nullAndBlank_safe() {
        assertFalse(ParamDriftDetector.diff(null, null).drifted());
        assertTrue(ParamDriftDetector.diff(null, "前3名").drifted());
        assertTrue(ParamDriftDetector.extract(null).isEmpty());
    }
}
