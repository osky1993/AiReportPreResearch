package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.asset.ReportAssetService.VersionedMetric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标版本快照构造纯逻辑单测（P2-T1，无 DB/Spring）。
 * 快照在卡点1 确认时固化——口径锁死时点；此后指标发新版不影响在跑 run。
 */
class MetricVersionSnapshotTest {

    private static VersionedMetric fetchMetric(String id, int version) {
        return new VersionedMetric(new MetricDefinition(id, "指标" + id, "CNY",
                true, false, "v", "ZERO", List.of(), null, null, null), version);
    }

    private static VersionedMetric derivedMetric(String id, int version, String left, String right) {
        return new VersionedMetric(new MetricDefinition(id, "派生" + id, "CNY",
                true, false, null, "ZERO", List.of(), null, null,
                new MetricDefinition.Derived("subtract", left, right)), version);
    }

    @Test
    void snapshotRecordsPublishedVersions() {
        Map<String, VersionedMetric> catalog = Map.of(
                "a", fetchMetric("a", 3),
                "b", fetchMetric("b", 1));
        Map<String, Integer> snap = ReportAssetService.buildMetricVersionSnapshot(List.of("a", "b"), catalog);
        assertEquals(Map.of("a", 3, "b", 1), snap);
    }

    @Test
    void derivedMetricExpandsOperandsIntoSnapshot() {
        // 大纲只挂派生指标时，其操作数虽不在大纲中、却会被 ② 补进取数清单——快照必须连带固化
        Map<String, VersionedMetric> catalog = Map.of(
                "in", fetchMetric("in", 2),
                "out", fetchMetric("out", 5),
                "net", derivedMetric("net", 1, "in", "out"));
        Map<String, Integer> snap = ReportAssetService.buildMetricVersionSnapshot(List.of("net"), catalog);
        assertEquals(Map.of("net", 1, "in", 2, "out", 5), snap);
    }

    @Test
    void duplicateReferencesAcrossChaptersAreIdempotent() {
        Map<String, VersionedMetric> catalog = Map.of("a", fetchMetric("a", 4));
        Map<String, Integer> snap = ReportAssetService.buildMetricVersionSnapshot(List.of("a", "a", "a"), catalog);
        assertEquals(Map.of("a", 4), snap);
    }

    @Test
    void unknownMetricFailsClosed() {
        Map<String, VersionedMetric> catalog = Map.of("a", fetchMetric("a", 1));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ReportAssetService.buildMetricVersionSnapshot(List.of("a", "ghost"), catalog));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void derivedOperandMissingFailsClosed() {
        // 注册表自检应保证操作数存在；此处是防御性兜底（理论不可能路径也要有明确错误）
        Map<String, VersionedMetric> catalog = Map.of(
                "net", derivedMetric("net", 1, "in", "out"),
                "in", fetchMetric("in", 1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                ReportAssetService.buildMetricVersionSnapshot(List.of("net"), catalog));
        assertTrue(e.getMessage().contains("out"));
    }
}
