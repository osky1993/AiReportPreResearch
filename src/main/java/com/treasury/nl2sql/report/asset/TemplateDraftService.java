package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.pipeline.TemplateMatcher;
import com.treasury.nl2sql.report.store.TemplateAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NL → 模板草案起草服务（P3-T1）。LLM 参照指标目录与既有模板起草，
 * 服务端九步后处理链把关（失败关闭：空泛/无关描述拒绝起草，幻觉指标剔除转 unresolved 不发明）。
 * 草案不落库——前端载入编辑器人工调整后走既有保存端点，治理链路零旁路；
 * 终检用保存同款 TemplateValidator：终检通过 = 编辑器里点保存必过。
 */
@Service
public class TemplateDraftService {

    private static final Logger log = LoggerFactory.getLogger(TemplateDraftService.class);
    private static final String FALLBACK_FEWSHOT = "treasury-weekly";

    public record DraftResult(ReportTemplateDef draft, List<String> unresolved, List<String> notes) {}

    private final LlmClient llm;
    private final ReportAssetService assets;
    private final TemplateMatcher matcher;
    private final TemplateAssetRepository templateRepo;
    private final ObjectMapper mapper;

    public TemplateDraftService(LlmClient llm, ReportAssetService assets, TemplateMatcher matcher,
                                TemplateAssetRepository templateRepo, ObjectMapper mapper) {
        this.llm = llm;
        this.assets = assets;
        this.matcher = matcher;
        this.templateRepo = templateRepo;
        this.mapper = mapper;
    }

    public DraftResult draft(String description) {
        // ⓪ 前置：过短描述不烧 token
        if (description == null || description.strip().length() < 10) {
            throw new IllegalArgumentException("场景描述过短，请用一两句话说明报告的用途、读者与关注内容（至少 10 字）");
        }
        String desc = description.strip();

        // ① LLM 起草（few-shot = 与描述最相近的 PUBLISHED 模板）
        ReportTemplateDef fewShot = pickFewShot(desc);
        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt(fewShot)));
        conversation.add(LlmClient.Message.user("场景描述：" + desc + "\n请输出模板草案 JSON。"));
        JsonNode node = completeWithOneRetry(conversation);

        // ② 失败关闭：空泛 / 无关领域
        if (node.path("unanswerable").asBoolean(false)) {
            throw new IllegalArgumentException("无法起草：" + node.path("reason").asText("描述过于空泛或与资金/司库领域无关")
                    + "。可用指标领域：资金头寸、账户、交易收支、贷款与风险事项");
        }
        ReportTemplateDef raw;
        try {
            raw = mapper.treeToValue(node.path("template"), ReportTemplateDef.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("起草输出的模板结构无法解析: " + e.getMessage());
        }
        if (raw == null || raw.chapters() == null) {
            throw new IllegalArgumentException("起草输出缺少模板章节结构");
        }

        List<String> unresolved = new ArrayList<>();
        for (JsonNode u : node.path("unresolved")) {
            if (u.isTextual() && !u.asText().isBlank()) unresolved.add(u.asText());
        }
        List<String> notes = new ArrayList<>();

        // ③④⑤ chapterId 重排（结构规整）+ 幻觉指标剔除转 unresolved + 剔空章节
        Set<String> catalog = assets.allMetrics().keySet();
        List<ReportTemplateDef.ChapterDef> chapters = new ArrayList<>();
        int idx = 1;
        for (ReportTemplateDef.ChapterDef ch : raw.chapters()) {
            List<String> kept = new ArrayList<>();
            for (String metricId : new LinkedHashSet<>(ch.metrics() == null ? List.<String>of() : ch.metrics())) {
                if (catalog.contains(metricId)) {
                    kept.add(metricId);
                } else {
                    unresolved.add("章节「" + ch.title() + "」：" + metricId);
                    notes.add("已剔除不存在的指标 " + metricId + " 并转入 unresolved（不发明、不猜测）");
                }
            }
            if (kept.isEmpty()) {
                notes.add("章节「" + ch.title() + "」因无可用指标已移除");
                continue;
            }
            chapters.add(new ReportTemplateDef.ChapterDef("ch" + idx++, ch.title(), kept,
                    ch.comparison(), ch.guidance(), ch.stylePrompt()));
        }
        // ⑥ 失败关闭：剔除后起草不出合法草案
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("描述过于空泛或点名的口径均无资产对应，无法起草合法草案"
                    + (unresolved.isEmpty() ? "" : "。未能对应的表述：" + String.join("；", unresolved)));
        }

        // ⑦ templateId slugify + 撞库避让
        String templateId = uniqueSlug(raw.templateId());
        ReportTemplateDef draft = new ReportTemplateDef(templateId, raw.name(), raw.keywords(), chapters);

        // ⑧ 终检（保存同款规则；通过 = 编辑器里点保存必过）
        List<ValidationError> errors = TemplateValidator.validate(draft, catalog);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        log.info("[DRAFT] 起草成功: {} 「{}」 {} 章, unresolved={}", templateId, draft.name(),
                chapters.size(), unresolved);
        return new DraftResult(draft, List.copyOf(new LinkedHashSet<>(unresolved)), notes);
    }

    // ---------- few-shot 与 prompt ----------

    private ReportTemplateDef pickFewShot(String description) {
        return matcher.recall(description).stream().findFirst()
                .flatMap(c -> assets.template(c.templateId()))
                .or(() -> assets.template(FALLBACK_FEWSHOT))
                .orElseGet(() -> assets.allTemplates().get(0));
    }

    private String systemPrompt(ReportTemplateDef fewShot) {
        String fewShotJson;
        try {
            fewShotJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fewShot);
        } catch (Exception e) {
            fewShotJson = "{}";
        }
        return """
            你是报告模板起草器。根据业务人员的场景描述，参照指标目录起草一份报告模板草案。
            只输出一个 JSON 对象，不要解释、不要 markdown 代码块。

            ## 可用指标目录（chapters[].metrics 只允许从中选 id）
            %s
            ## 既有模板示例（结构参照；不要照抄内容）
            %s

            ## 输出 JSON 结构
            {
              "template": {
                "templateId": "小写连字符命名，如 fx-risk-weekly",
                "name": "模板中文名",
                "keywords": ["3~6 个中文匹配关键词（运行期按需求文本召回模板，必填）"],
                "chapters": [
                  { "chapterId": "ch1", "title": "一、…", "metrics": ["指标目录中的 id"],
                    "comparison": null 或 "week_over_week",
                    "guidance": "本章写作指引（必写，≤1000 字）",
                    "stylePrompt": "本章文风要求（可省略）" }
                ]
              },
              "unresolved": ["描述中点名要求、但指标目录里找不到对应项的表述（没有则空数组）"],
              "unanswerable": false,
              "reason": "unanswerable 为 true 时说明原因"
            }

            ## 规则
            - metrics 只允许使用指标目录中的 id，**禁止发明新指标 id**；描述中对应不上的口径放进 unresolved 原样列出。
            - 每个章节至少 1 个指标；凑不出指标的章节不要产出。
            - 章节数建议 2~4 章，结构参照示例：概览在前、明细居中、风险事项在后。
            - 描述与资金/司库领域完全无关（如人事、库存、天气），或空泛到无法确定报告主题时，
              输出 {"unanswerable": true, "reason": "..."}——不要硬编。
            """.formatted(assets.metricCatalogText(), fewShotJson);
    }

    // ---------- 工具 ----------

    /** slugify（草案要人再编辑，不合规修正而非报错）+ 撞库后缀避让（编辑完点新建不至于才撞 400）。 */
    private String uniqueSlug(String rawId) {
        String s = rawId == null ? "" : rawId.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() < 3) {
            s = "draft-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmm"));
        }
        if (!Character.isLetter(s.charAt(0))) s = "t" + s;
        if (s.length() > 60) s = s.substring(0, 60);
        String base = s;
        int i = 2;
        while (templateRepo.existsById(s)) {
            s = base + "-" + i++;
        }
        return s;
    }

    private JsonNode completeWithOneRetry(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        log.info("[DRAFT] LLM 输出: {}", raw);
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception first) {
            conversation.add(LlmClient.Message.assistant(raw));
            conversation.add(LlmClient.Message.user("上面的输出不是合法 JSON：" + first.getMessage() + "。请只输出合法 JSON。"));
            String retry = llm.completeJson(conversation);
            log.info("[DRAFT] LLM 重试输出: {}", retry);
            try {
                return mapper.readTree(stripFence(retry));
            } catch (Exception second) {
                throw new IllegalArgumentException("起草输出无法解析为 JSON（重试 1 次仍失败）");
            }
        }
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
