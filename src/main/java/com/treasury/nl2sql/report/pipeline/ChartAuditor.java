package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ⑥ 图表核对（Phase04 契约2，纯程序死代码，照 NumberAuditor 范式）：
 * 对每张图逐点比对 option.series 数据与 boundFactKeys 所指 fact.value——
 * 点数守恒（series 长度 = 绑定键数）、引用存在、数值严格相等（数据是程序绑定的，零渲染精度问题）。
 * 任何不一致 → 不放行（与数字一致率同级门禁）；理论上只有绑定器 bug 会触发，是程序对自身的自证。
 */
public final class ChartAuditor {

    private ChartAuditor() {}

    public record ChartCheck(String chartId, boolean ok, String detail) {}

    public static List<ChartCheck> audit(ObjectMapper mapper, List<ChartRecord> charts,
                                         Map<String, FactRecord> factsByKey) {
        List<ChartCheck> checks = new ArrayList<>();
        for (ChartRecord chart : charts) {
            checks.add(auditOne(mapper, chart, factsByKey));
        }
        return checks;
    }

    public static boolean passed(List<ChartCheck> checks) {
        return checks.stream().allMatch(ChartCheck::ok);
    }

    private static ChartCheck auditOne(ObjectMapper mapper, ChartRecord chart,
                                       Map<String, FactRecord> factsByKey) {
        List<BigDecimal> data;
        try {
            data = seriesData(mapper.readTree(chart.optionJson()));
        } catch (Exception e) {
            return new ChartCheck(chart.chartId(), false, "option JSON 无法解析: " + e.getMessage());
        }
        if (data.size() != chart.boundFactKeys().size()) {
            return new ChartCheck(chart.chartId(), false, "点数不守恒: series " + data.size()
                    + " 点 ≠ 绑定 " + chart.boundFactKeys().size() + " 个 fact");
        }
        for (int i = 0; i < data.size(); i++) {
            String key = chart.boundFactKeys().get(i);
            FactRecord fact = factsByKey.get(key);
            if (fact == null) {
                return new ChartCheck(chart.chartId(), false, "第 " + (i + 1) + " 点引用了不存在的事实: " + key);
            }
            if (fact.value() == null || data.get(i).compareTo(fact.value()) != 0) {
                return new ChartCheck(chart.chartId(), false, "第 " + (i + 1) + " 点数值 " + data.get(i)
                        + " ≠ 事实 " + key + " 的值 " + fact.value() + "（严格相等）");
            }
        }
        return new ChartCheck(chart.chartId(), true, data.size() + " 点逐点核对一致");
    }

    /** 抽取 series[0] 的数值序列：pie 取 data[].value，line/bar 取 data[]。 */
    private static List<BigDecimal> seriesData(JsonNode option) {
        List<BigDecimal> out = new ArrayList<>();
        JsonNode series = option.path("series").path(0).path("data");
        for (JsonNode point : series) {
            JsonNode v = point.isObject() ? point.path("value") : point;
            if (!v.isNumber()) {
                throw new IllegalStateException("series 数据点非数值: " + point);
            }
            out.add(v.decimalValue());
        }
        return out;
    }
}
