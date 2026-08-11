package com.treasury.nl2sql.report.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 异常规则声明的形状校验（Phase05 契约1）。启动自检（ReportAssetService.checkMetric，含
 * dimensionMetricId 的目录级校验）与保存五重校验（MetricAdminService）共用——规则只改这一处。
 */
public final class MetricAnomalyRule {

    /** 每次运行 ANOMALY fact 总数上限（T0 拍板：阈值保守起步使触顶罕见；触顶 BLOCKED 不截断）。 */
    public static final int MAX_ANOMALIES_PER_RUN = 6;

    private static final Set<String> OPS = Set.of(">=", "<=", ">", "<");
    private static final Set<String> BASES = Set.of("wow", "mom", "qoq", "yoy");

    private MetricAnomalyRule() {}

    /**
     * 形状校验（不含目录级：dimensionMetricId 的存在性/维度性在 ReportAssetService.checkMetric 追加）。
     * 返回空列表表示规则形状正确，调用方可继续执行语义/运行时判定。
     */
    public static List<String> check(MetricDefinition m) {
        List<String> errors = new ArrayList<>();
        if (m.anomalyRules() == null || m.anomalyRules().isEmpty()) return errors;
        for (int i = 0; i < m.anomalyRules().size(); i++) {
            MetricDefinition.AnomalyRule r = m.anomalyRules().get(i);
            String at = "anomalyRules[" + i + "]";
            if (r == null) {
                errors.add(at + " 为空");
                continue;
            }
            if (MetricDefinition.AnomalyRule.TYPE_THRESHOLD.equals(r.type())) {
                if (r.op() == null || !OPS.contains(r.op()) || r.value() == null) {
                    errors.add(at + " threshold 规则要求 op(>=/<=/>/<) 与 value");
                }
                if (r.basis() != null || r.absPct() != null) {
                    errors.add(at + " threshold 规则不接受 basis/absPct");
                }
            } else if (MetricDefinition.AnomalyRule.TYPE_VOLATILITY.equals(r.type())) {
                if (r.basis() == null || !BASES.contains(r.basis())
                        || r.absPct() == null || r.absPct().signum() <= 0) {
                    errors.add(at + " volatility 规则要求 basis(wow/mom/qoq/yoy) 与正数 absPct");
                }
                if (r.op() != null || r.value() != null) {
                    errors.add(at + " volatility 规则不接受 op/value");
                }
                if (!m.comparable()) {
                    errors.add(at + " volatility 规则要求指标 comparable=true（无比较 fact 则波动率无从计算）");
                }
            } else {
                errors.add(at + " 非法 type: " + r.type() + "（只允许 threshold/volatility）");
            }
            if (r.dimensionMetricId() != null && r.dimensionMetricId().isBlank()) {
                errors.add(at + " dimensionMetricId 不得为空串");
            }
        }
        return errors;
    }
}
