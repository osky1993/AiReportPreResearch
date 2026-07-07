package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;

import java.util.Map;

/**
 * 参数化 MQL 模板填充：把指标语义定义里的 mqlTemplate（合法 Mql JSON，
 * 占位符只有 {{period_start}}/{{period_end}} 且只出现在字符串值位置）填成可执行 Mql。
 * 确定性通路的第一环：填充后残留任何 {{ 即失败关闭，绝不带着占位符去撞校验器。
 */
public final class MqlTemplateFiller {

    private MqlTemplateFiller() {}

    public static Mql fill(ObjectMapper mapper, String metricId, JsonNode template, Map<String, String> params) {
        if (template == null) {
            throw new PolicyException("指标「" + metricId + "」没有 MQL 模板（派生指标不可直接取数）");
        }
        String json = template.toString();
        for (Map.Entry<String, String> e : params.entrySet()) {
            json = json.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        if (json.contains("{{")) {
            throw new PolicyException("指标「" + metricId + "」的 MQL 模板存在未填充的占位符: "
                    + snippetAround(json, json.indexOf("{{")));
        }
        try {
            return mapper.readValue(json, Mql.class);
        } catch (Exception e) {
            throw new PolicyException("指标「" + metricId + "」的 MQL 模板反序列化失败: " + e.getMessage());
        }
    }

    private static String snippetAround(String json, int idx) {
        int from = Math.max(0, idx - 20);
        int to = Math.min(json.length(), idx + 30);
        return "…" + json.substring(from, to) + "…";
    }
}
