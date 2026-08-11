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

    /** 哨兵日期（启动自检与指标保存链共用，防两处漂移）：填充后能过校验即模板健康。 */
    public static final Map<String, String> SENTINEL_PARAMS =
            Map.of("period_start", "2026-06-22", "period_end", "2026-06-28");

    private MqlTemplateFiller() {}

    /**
     * 按参数替换占位符并反序列化为 Mql。
     * <p>行为：
     * <ul>
     *   <li>template 为空直接失败</li>
     *   <li>逐项替换 metricId 级占位符</li>
     *   <li>剩余占位符存在即失败关闭（不允许带着占位符进入校验/编译）</li>
     *   <li>反序列化失败直接失败关闭</li>
     * </ul>
     */
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

    /**
     * 报错片段提取，避免日志刷屏只打印整个 SQL/JSON。
     * @param json MQL 文本
     * @param idx 未填充标记起始索引
     */
    private static String snippetAround(String json, int idx) {
        int from = Math.max(0, idx - 20);
        int to = Math.min(json.length(), idx + 30);
        return "…" + json.substring(from, to) + "…";
    }
}
