package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ① 需求 → 大纲（LLM 智能节点，同步）。
 * LLM 只做三件事：识别报告模板、抽取报告期标签（只输出如 2026-W26 的 ISO 周标签，
 * **不产日期**——窗口由 PeriodResolver 推导）、把映射不上的指标表述放进 unresolved。
 * 章节树与推荐指标来自模板（固定），LLM 不发明章节；人确认（HITL 卡点1）后口径锁死。
 */
@Component
public class OutlineStep {

    private static final Logger log = LoggerFactory.getLogger(OutlineStep.class);

    private final LlmClient llm;
    private final ReportAssetService assets;
    private final ObjectMapper mapper;

    public OutlineStep(LlmClient llm, ReportAssetService assets, ObjectMapper mapper) {
        this.llm = llm;
        this.assets = assets;
        this.mapper = mapper;
    }

    /** @param revisionNote HITL 卡点1 打回时带的修改意见（首次为 null）。 */
    public Outline run(String requestText, String revisionNote) {
        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt()));
        String user = "报告需求：" + requestText
                + (revisionNote == null ? "" : "\n业务人员打回意见（必须吸收）：" + revisionNote)
                + "\n请输出解析 JSON。";
        conversation.add(LlmClient.Message.user(user));

        JsonNode node = completeWithOneRetry(conversation);

        if (node.path("unanswerable").asBoolean(false)) {
            throw new PolicyException(node.path("reason").asText("需求无法匹配任何报告模板"));
        }
        String templateId = node.path("templateId").isTextual() ? node.path("templateId").asText() : null;
        ReportTemplateDef tpl = assets.template();
        if (!tpl.templateId().equals(templateId)) {
            throw new PolicyException("无法从需求中识别报告模板（模型输出: " + templateId
                    + "，当前仅支持「" + tpl.name() + "」" + tpl.templateId() + "）");
        }
        String periodLabel = node.path("periodLabel").isTextual() ? node.path("periodLabel").asText() : null;
        // 解析不了直接失败关闭（PolicyException），不猜测补全
        PeriodResolver.Window window = PeriodResolver.resolve(periodLabel);

        List<String> unresolved = new ArrayList<>();
        for (JsonNode u : node.path("unresolved")) {
            if (u.isTextual() && !u.asText().isBlank()) unresolved.add(u.asText());
        }

        List<Outline.OutlineChapter> chapters = tpl.chapters().stream()
                .map(c -> new Outline.OutlineChapter(c.chapterId(), c.title(),
                        new ArrayList<>(c.metrics()), c.comparison(), c.guidance()))
                .toList();
        log.info("[OUTLINE] 模板={}, 报告期={}, unresolved={}", templateId, window.label(), unresolved);
        return new Outline(templateId, window.label(), chapters, unresolved);
    }

    private JsonNode completeWithOneRetry(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        log.info("[OUTLINE] LLM 输出: {}", raw);
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception first) {
            conversation.add(LlmClient.Message.assistant(raw));
            conversation.add(LlmClient.Message.user("上面的输出不是合法 JSON：" + first.getMessage()
                    + "。请只输出合法 JSON。"));
            String retry = llm.completeJson(conversation);
            log.info("[OUTLINE] LLM 重试输出: {}", retry);
            try {
                return mapper.readTree(stripFence(retry));
            } catch (Exception second) {
                throw new PolicyException("需求解析输出无法解析为 JSON（重试 1 次仍失败）: " + second.getMessage());
            }
        }
    }

    private String systemPrompt() {
        ReportTemplateDef tpl = assets.template();
        StringBuilder chapterLines = new StringBuilder();
        for (ReportTemplateDef.ChapterDef c : tpl.chapters()) {
            chapterLines.append("  - ").append(c.title()).append("（推荐指标: ")
                    .append(String.join(", ", c.metrics())).append("）\n");
        }
        return """
            你是报告需求解析器。从业务人员的自然语言需求中识别报告模板与报告期，只输出一个 JSON 对象，
            不要解释、不要 markdown 代码块。

            ## 可用报告模板（当前仅一个）
            - templateId: %s（%s；关键词: %s）
              章节与推荐指标（固定，不需要你改动）：
            %s
            ## 可用指标目录
            %s
            ## 输出 JSON 结构
            {
              "templateId": "命中的模板 id；需求与模板明显无关时为 null",
              "periodLabel": "报告期的 ISO 周标签，如 2026-W26；无法确定为 null",
              "unresolved": ["需求中点名要求、但指标目录里找不到对应项的表述（没有则空数组）"],
              "unanswerable": false,
              "reason": "templateId 或 periodLabel 为 null 时说明原因"
            }

            ## 规则
            - periodLabel 只允许 ISO 周标签（YYYY-Www）。需求说「2026 年第 26 周」→ "2026-W26"。
              需求要求月报/季报/年报，或没有给出可确定的周，则 periodLabel=null 并在 reason 里说明——不要猜。
            - 不要输出任何具体日期，日期窗口由程序推导。
            - 需求里点名的统计口径若能对应指标目录中的某一项，视为已覆盖；对应不上的放进 unresolved 原样列出。
            - 需求与资金/司库报告完全无关时输出 {"unanswerable": true, "reason": "..."}。
            """.formatted(tpl.templateId(), tpl.name(), String.join("、", tpl.keywords()),
                chapterLines, assets.metricCatalogText());
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
