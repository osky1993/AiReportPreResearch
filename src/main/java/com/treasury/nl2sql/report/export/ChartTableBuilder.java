package com.treasury.nl2sql.report.export;

import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图表数据表兜底（导出纪律 1：导出不产生任何新数字）：不解析 optionJson，
 * 直接沿 boundFactKeys 取 fact 的 display_value——数据表与 ⑥ ChartAuditor 核对过的
 * 数据点严格同键同源。boundFactKey 查不到 fact 即失败关闭，绝不用 option 里的裸数值兜底。
 */
public final class ChartTableBuilder {

    private ChartTableBuilder() {}

    /**
     * 图表导出行；label 与图例/维度一一对应，displayValue 取 fact.display_value 直接回写，
     * factKey 同步追踪到数字审计来源，禁止在构造层拼接裸数值。
     */
    public record Row(String label, String displayValue, String factKey) {}

    /**
     * 按图表绑定的 factKey 组装导出行。
     * <p>规则：</p>
     * <ul>
     *   <li>boundFactKeys 中每一项都必须在事实仓库命中，否则抛错并 BLOCKED。</li>
     *   <li>维度行优先以 dimensions 拼接；无维度时退回 periodLabel，和图表渲染一致。</li>
     *   <li>不允许直接解析 optionJson 中的数字或文本作为 y 轴值；避免双向口径污染。</li>
     * </ul>
     *
     * @throws IllegalStateException 如果 chart 绑定的 factKey 在给定事实集中不存在
     */
    public static List<Row> build(ChartRecord chart, Map<String, FactRecord> factsByKey) {
        List<Row> rows = new ArrayList<>();
        for (String key : chart.boundFactKeys() == null ? List.<String>of() : chart.boundFactKeys()) {
            FactRecord f = factsByKey.get(key);
            if (f == null) {
                throw new IllegalStateException("图表「" + chart.title() + "」数据点 " + key
                        + " 在本 run 事实集中缺失，导出失败关闭");
            }
            rows.add(new Row(labelOf(f), f.displayValue(), key));
        }
        return rows;
    }

    /** 与 ChartBuildStep.labelOf 同构：维度行取维度值，序列/本期取周期标签。 */
    private static String labelOf(FactRecord f) {
        if (f.dimensions() != null && !f.dimensions().isEmpty()) {
            return String.join("/", f.dimensions().values());
        }
        return f.periodLabel();
    }
}
