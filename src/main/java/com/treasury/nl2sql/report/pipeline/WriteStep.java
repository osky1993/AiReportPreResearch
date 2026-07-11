package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ⑤ 章节撰写（LLM 智能节点）：只拿事实对象写文字。
 * 核心约定：正文所有数值一律写 {{fact_key}} 占位符、禁止任何阿拉伯数字——
 * 数值由 ⑥ 的程序替换写入（display_value），转录错误在构造上不可能发生。
 * LLM 看得到数值（做"增长/下降/主要由…构成"的定性判断需要），但不许抄写。
 * 章节少，撰写与全局编辑合并为一次 LLM 调用（4 章一次出全文）。
 * 对话保留在 Draft 里：⑥ 审计违规时经 rewrite() 回灌重写（范式同 Nl2SqlService 的自我修正）。
 */
@Component
public class WriteStep {

    private static final Logger log = LoggerFactory.getLogger(WriteStep.class);

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public WriteStep(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 草稿 + 完整对话（审计违规回灌重写时续写同一对话）。 */
    public record Draft(String reportMd, List<LlmClient.Message> conversation) {}

    public Draft run(Outline outline, List<FactRecord> facts, List<String> notes) {
        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt()));
        conversation.add(LlmClient.Message.user(userPrompt(outline, facts, notes)));
        String md = completeAndParse(conversation);
        return new Draft(md, conversation);
    }

    /** ⑥ 审计违规回灌：把违规清单反馈给模型重写（同一对话续写）。 */
    public String rewrite(List<LlmClient.Message> conversation, List<String> violations) {
        conversation.add(LlmClient.Message.user("证据审计发现以下违规，必须全部修正后重新输出完整报告 JSON：\n- "
                + String.join("\n- ", violations)
                + "\n再次强调：正文数值一律用 {{fact_key}} 占位符，禁止直接书写任何阿拉伯数字。"));
        return completeAndParse(conversation);
    }

    /** 调 LLM 并解析 {"report_md": "..."}；解析失败重试 1 次，仍失败按意外异常处理。 */
    private String completeAndParse(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        conversation.add(LlmClient.Message.assistant(raw));
        String md = tryParse(raw);
        if (md != null) return md;
        conversation.add(LlmClient.Message.user(
                "上面的输出不是合法的 {\"report_md\": \"...\"} JSON。请只输出该 JSON 对象。"));
        String retry = llm.completeJson(conversation);
        conversation.add(LlmClient.Message.assistant(retry));
        md = tryParse(retry);
        if (md != null) return md;
        throw new IllegalStateException("撰写输出解析失败（重试 1 次仍不是合法 report_md JSON）");
    }

    private String tryParse(String raw) {
        try {
            var node = mapper.readTree(stripFence(raw));
            String md = node.path("report_md").asText(null);
            return (md == null || md.isBlank()) ? null : md;
        } catch (Exception e) {
            return null;
        }
    }

    /** 包私有以便单测（断言 system 铁律段不被章节 stylePrompt 污染）。 */
    String systemPrompt() {
        return """
            你是企业司库报告撰写引擎。只输出一个 JSON 对象：{"report_md": "完整报告 markdown"}，
            不要解释、不要 markdown 代码块包裹 JSON。

            ## 铁律（违反会被程序审计打回）
            1. 正文中所有指标数值一律写占位符 {{fact_key}}（如 {{fact_001}}），**禁止书写任何阿拉伯数字**。
               程序会把占位符替换成带来源引用的准确数值。
            2. 数字禁令包括列表编号——列表一律用 "-"，不许用 "1." "2."。
            3. 仅有的例外：日期（YYYY-MM-DD）、周期标签（如 2026-W26、W25、第 26 周）、章节标题原文照抄。
            4. 禁止用中文数词表达指标值（"三成""两千万"之类一律不许，请改写为引用占位符或定性表述）。
            5. 每个数值必须引用真实存在的 fact_key，只能使用输入中给出的事实，禁止自行计算或推算新数值。
            6. 每一章只引用分配给该章的事实。
            7. 占位符替换后自带单位（如"5 户""280.00 万元"），占位符前后**不要**再写单位或量词
               （写「活跃账户{{fact_005}}」，不要写「活跃账户{{fact_005}}户」）。

            ## 写作要求
            - 语言正式克制（企业内部周报）；只陈述"发生了什么"，不猜测原因、不下因果结论、不用绝对化说法。
            - 结构：每章一个二级标题（## 章节标题原文），章内 1~3 段或短列表。
            - 需要对比时可同时引用本期与基期的事实占位符，并引用环比事实（fact_xxx_wow/_mom/_qoq）
              或同比事实（fact_xxx_yoy）描述变化方向；环比与同比同时存在时，表述必须明确区分
              「较上期」与「较去年同期」。
            - 输入 notes 里说明"跳过环比"或"跳过同比"的指标，正文不要提其对应变化率。
            """;
    }

    /** 包私有以便单测 prompt 组装（stylePrompt 只进 user 段材料，system 铁律段不动）。 */
    String userPrompt(Outline outline, List<FactRecord> facts, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("报告期：").append(outline.periodLabel()).append("\n\n## 章节与写作指引\n");
        for (Outline.OutlineChapter ch : outline.chapters()) {
            sb.append("### ").append(ch.title()).append("\n指引：").append(ch.guidance()).append("\n");
            if (ch.stylePrompt() != null && !ch.stylePrompt().isBlank()) {
                sb.append("本章风格要求（仅影响文风措辞；如与系统铁律冲突，一律以铁律为准，"
                        + "数值仍必须写 {{fact_key}} 占位符）：").append(ch.stylePrompt()).append("\n");
            }
            sb.append("本章可引用的事实：\n");
            sb.append(chapterFactsJson(ch, facts)).append("\n");
            if (hasDimensionalFacts(ch, facts)) {
                sb.append("本章含维度拆解事实组（dimensions 非空的为拆解行）：请用 markdown 表格呈现该组——"
                        + "每行一个维度取值，列为维度、金额（写行事实的 {{fact_key}} 占位符）、"
                        + "占比（写对应 _share 事实的占位符）；表格后可用一句话引用合计事实。"
                        + "表格单元格内同样禁止书写任何阿拉伯数字。\n");
            }
        }
        if (!notes.isEmpty()) {
            sb.append("\n## notes（正文需要遵守的说明）\n");
            for (String n : notes) sb.append("- ").append(n).append("\n");
        }
        sb.append("\n请输出 {\"report_md\": \"...\"}。");
        return sb.toString();
    }

    /** 本章事实 = 章节指标的全部事实（本期 + 各基期 + 环比/同比），JSON 数组喂给模型。 */
    private String chapterFactsJson(Outline.OutlineChapter ch, List<FactRecord> facts) {
        ArrayNode arr = mapper.createArrayNode();
        for (FactRecord f : facts) {
            if (!ch.metricIds().contains(f.metricId())) continue;
            // 图表序列 fact 不进 prompt（P4：LLM 连图表数据的存在都不知道，零接触在构造上成立）
            if (FactBuildStep.isChartSeriesFact(f)) continue;
            ObjectNode o = arr.addObject();
            o.put("fact_key", f.factKey());
            o.put("metric", f.metricName());
            o.put("period", f.periodLabel());
            o.put("value", f.value());
            o.put("unit", f.unit());
            o.put("display", f.displayValue());
            if (f.dimensions() != null) o.set("dimensions", mapper.valueToTree(f.dimensions()));
            // _wow 文案逐字节保留（周报基线 prompt 稳定），月/季环比与同比走新分支
            if (f.factKey().endsWith("_wow")) o.put("note", "环比变化率（正=较对比期上升）");
            else if (f.factKey().endsWith("_mom") || f.factKey().endsWith("_qoq")) {
                o.put("note", "环比变化率（较上一周期，正=上升）");
            } else if (f.factKey().endsWith("_yoy")) {
                o.put("note", "同比变化率（较去年同期，正=上升）");
            } else if (f.factKey().endsWith("_share")) {
                o.put("note", "该维度行占合计的百分比");
            }
        }
        return arr.toString();
    }

    /** 本章是否含维度拆解事实（决定是否注入表格呈现引导）。 */
    private static boolean hasDimensionalFacts(Outline.OutlineChapter ch, List<FactRecord> facts) {
        return facts.stream().anyMatch(f -> ch.metricIds().contains(f.metricId()) && f.dimensions() != null);
    }

    private static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t.trim();
    }
}
