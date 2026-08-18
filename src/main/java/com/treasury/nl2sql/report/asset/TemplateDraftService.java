package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.pipeline.ComparisonType;
import com.treasury.nl2sql.report.pipeline.PeriodResolver;
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
import java.util.Map;
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

    /**
     * 模板草稿端到端处理：few-shot 检索 -> LLM 起草 -> 结构修复 -> 异常指标剔除 -> 规则复核 -> 入参返回。
     * 任何环节失败都 fail-closed 到人工确认，不返回“猜测模板”。
     */
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
            // 将 LLM JSON 先转成草稿类型，若字段缺失则在后续链路补充最小可运行结构（缺结构则直接拒绝）。
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
        //     + 非法 periodTypes/comparisons token 剔除转 notes（降噪，失败关闭仍由 ⑧ 终检兜底）
        List<String> periodTypes = sanitizePeriodTypes(raw.periodTypes(), notes);
        List<String> effectivePeriods = (periodTypes == null) ? List.of(PeriodResolver.TYPE_WEEK) : periodTypes;
        Map<String, MetricDefinition> catalog = assets.allMetrics();
        List<ReportTemplateDef.ChapterDef> chapters = new ArrayList<>();
        int idx = 1;
        for (ReportTemplateDef.ChapterDef ch : raw.chapters()) {
            List<String> kept = new ArrayList<>();
            // 去重并过滤不存在的指标：同一章节重复的 id 会造成 renderer 重复渲染，故在起草期先规整。
            for (String metricId : new LinkedHashSet<>(ch.metrics() == null ? List.<String>of() : ch.metrics())) {
                if (catalog.containsKey(metricId)) {
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
            List<String> comps = sanitizeComparisons(ch, effectivePeriods, notes);
            // 草案只写新字段 comparisons，旧单值 comparison 一律置 null（校验器禁止双字段同填）
            chapters.add(new ReportTemplateDef.ChapterDef("ch" + idx++, ch.title(), kept,
                    null, comps.isEmpty() ? null : comps, ch.guidance(), ch.stylePrompt(), ch.charts()));
        }
        // ⑥ 失败关闭：剔除后起草不出合法草案
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("描述过于空泛或点名的口径均无资产对应，无法起草合法草案"
                    + (unresolved.isEmpty() ? "" : "。未能对应的表述：" + String.join("；", unresolved)));
        }

        // ⑦ templateId slugify + 撞库避让
        String templateId = uniqueSlug(raw.templateId());
        ReportTemplateDef draft = new ReportTemplateDef(templateId, raw.name(), raw.keywords(),
                periodTypes, chapters);

        // ⑧ 终检（保存同款规则；通过 = 编辑器里点保存必过）
        List<ValidationError> errors = TemplateValidator.validate(draft, catalog);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        // 统一校验通过即表示草案可直接进入编辑器保存流；unresolved 作为人工解释，不会阻断草案落盘（因为草案不落库）。
        log.info("[DRAFT] 起草成功: {} 「{}」 {} 章, unresolved={}", templateId, draft.name(),
                chapters.size(), unresolved);
        return new DraftResult(draft, List.copyOf(new LinkedHashSet<>(unresolved)), notes);
    }

    // ---------- few-shot 与 prompt ----------

    /**
     * few-shot 召回策略：优先语义匹配历史模板；无匹配回退固定样例，再无则取首条种子模板。
     * 目的是“给写作风格锚点”，不用于业务事实约束。
     */
    private ReportTemplateDef pickFewShot(String description) {
        // few-shot 只服务生成风格，不参与事实正确性校验；最终结构与内容仍需服务端验证。
        return matcher.recall(description).stream().findFirst()
                .flatMap(c -> assets.template(c.templateId()))
                .or(() -> assets.template(FALLBACK_FEWSHOT))
                .orElseGet(() -> assets.allTemplates().get(0));
    }

    /**
     * 系统提示词：强制“指标 id 约束 + 禁止发明 + 必写指标来源”
     * + 空值判定（unanswerable）以减少幻觉，避免上线后大量 invalid template.
     */
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
                "periodTypes": ["适用周期粒度数组，取值 WEEK/MONTH/QUARTER；由描述中的周期措辞推断（周报→WEEK、月报→MONTH、季报→QUARTER），推断不出则 null"],
                "chapters": [
                  { "chapterId": "ch1", "title": "一、…", "metrics": ["指标目录中的 id"],
                    "comparisons": ["本章比较声明数组，可空；合法 token 仅限：%s"],
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
            - comparisons 必须与 periodTypes 粒度匹配：WEEK 只能用 week_over_week；MONTH 可用
              month_over_month/year_over_year；QUARTER 可用 quarter_over_quarter/year_over_year；
              同一章节同粒度环比至多一个。拿不准就留空数组——比较方式可由人工在编辑器里补。
            - 描述与资金/司库领域完全无关（如人事、库存、天气），或空泛到无法确定报告主题时，
              输出 {"unanswerable": true, "reason": "..."}——不要硬编。
            """.formatted(assets.metricCatalogText(), fewShotJson, ComparisonType.legalTokens());
    }

    // ---------- 工具 ----------

    /**
     * periodTypes 降噪：剔除 WEEK/MONTH/QUARTER 之外的幻觉值并去重；剔完为空 → null
     * （保持「旧资产无此字段 → effectivePeriodTypes() 缺省 WEEK」的既有语义，不显式化缺省）。
     */
    private static List<String> sanitizePeriodTypes(List<String> rawTypes, List<String> notes) {
        if (rawTypes == null || rawTypes.isEmpty()) return null;
        Set<String> legal = Set.of(PeriodResolver.TYPE_WEEK, PeriodResolver.TYPE_MONTH, PeriodResolver.TYPE_QUARTER);
        List<String> kept = new ArrayList<>();
        for (String t : new LinkedHashSet<>(rawTypes)) {
            if (legal.contains(t)) {
                kept.add(t);
            } else {
                notes.add("已剔除非法周期粒度 " + t + "（合法值 WEEK/MONTH/QUARTER）");
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    /**
     * 章节比较声明降噪（与幻觉指标剔除同型：剔除转 notes，不整案拒绝）：
     * ① 未知 token 剔除；② 与模板周期粒度矩阵不符的剔除（须对每个粒度都适用，同校验器规则）；
     * ③ 同粒度环比（purpose=COMPARE）至多保留第一个。终检 TemplateValidator 仍是最后闸门。
     */
    private static List<String> sanitizeComparisons(ReportTemplateDef.ChapterDef ch,
                                                    List<String> effectivePeriods, List<String> notes) {
        List<String> kept = new ArrayList<>();
        boolean hasCompare = false;
        for (String token : new LinkedHashSet<>(ch.effectiveComparisons())) {
            var ct = ComparisonType.of(token).orElse(null);
            if (ct == null) {
                notes.add("章节「" + ch.title() + "」：已剔除未知比较类型 " + token
                        + "（合法值 " + ComparisonType.legalTokens() + "）");
                continue;
            }
            if (!effectivePeriods.stream().allMatch(ct::allows)) {
                notes.add("章节「" + ch.title() + "」：已剔除与周期粒度不匹配的比较 " + token);
                continue;
            }
            boolean isCompare = MetricQuerySpec.PURPOSE_COMPARE.equals(ct.purpose());
            if (isCompare && hasCompare) {
                notes.add("章节「" + ch.title() + "」：同粒度环比只保留一个，已剔除 " + token);
                continue;
            }
            hasCompare |= isCompare;
            kept.add(token);
        }
        return kept;
    }

    /**
     * 命名归一化策略：
     * 先 slugify（小写+连字符+长度约束），再做撞库后缀 +1 直到唯一，确保新建不会与现有模板冲突。
     */
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

    /**
     * LLM 输出容错路径：首轮 JSON 解析失败，自动加“请只输出 JSON”重试一次；
     * 二次失败即人工化成闭环，拒绝吞掉不结构化文本。
     */
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
