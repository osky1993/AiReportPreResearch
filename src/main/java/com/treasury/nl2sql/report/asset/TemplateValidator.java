package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.pipeline.ComparisonType;
import com.treasury.nl2sql.report.pipeline.PeriodResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模板校验（P2 契约：保存 / validate 干跑 / 启动自检三处共用同一套规则）。
 * 纯静态、无依赖，逐条返回错误并以 location 定位到章节/字段（前端红字定位靠它）。
 * 规则见 README「模板管理 API」：结构 + 引用存在性 + 每章至少 1 个指标
 * + Phase03 起的周期声明与比较矩阵（periodTypes × ComparisonType）。
 */
public final class TemplateValidator {

    /** @param location 如 chapters[1].metrics[0]，与前端表单结构对齐 */
    public record ValidationError(String location, String message) {}

    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Set<String> PERIOD_TYPES =
            Set.of(PeriodResolver.TYPE_WEEK, PeriodResolver.TYPE_MONTH, PeriodResolver.TYPE_QUARTER);
    private static final int PROMPT_MAX_LEN = 1000;

    private TemplateValidator() {}

    /** @param publishedMetricIds 注册表中 PUBLISHED 指标 id 集（引用存在性以此为准） */
    public static List<ValidationError> validate(ReportTemplateDef tpl, Set<String> publishedMetricIds) {
        List<ValidationError> errors = new ArrayList<>();
        if (tpl == null) {
            errors.add(new ValidationError("", "模板体为空"));
            return errors;
        }
        if (tpl.templateId() == null || !TEMPLATE_ID.matcher(tpl.templateId()).matches()) {
            errors.add(new ValidationError("templateId",
                    "templateId 必须匹配 ^[a-z][a-z0-9-]{2,63}$（当前: " + tpl.templateId() + "）"));
        }
        if (tpl.name() == null || tpl.name().isBlank()) {
            errors.add(new ValidationError("name", "模板名称不能为空"));
        }
        if (tpl.keywords() == null || tpl.keywords().isEmpty()) {
            errors.add(new ValidationError("keywords", "keywords 不能为空（运行期匹配召回依赖，资产治理要求必填）"));
        } else {
            for (int i = 0; i < tpl.keywords().size(); i++) {
                String kw = tpl.keywords().get(i);
                if (kw == null || kw.isBlank()) {
                    errors.add(new ValidationError("keywords[" + i + "]", "关键词不能为空串"));
                }
            }
        }
        // periodTypes：null → 缺省 WEEK（旧资产兼容）；显式给了就必须合法（非空、取值集合、不重复）
        if (tpl.periodTypes() != null) {
            if (tpl.periodTypes().isEmpty()) {
                errors.add(new ValidationError("periodTypes",
                        "periodTypes 不能是空列表（缺省周报请省略该字段，或显式填 [\"WEEK\"]）"));
            } else {
                Set<String> seenTypes = new HashSet<>();
                for (int i = 0; i < tpl.periodTypes().size(); i++) {
                    String pt = tpl.periodTypes().get(i);
                    if (pt == null || !PERIOD_TYPES.contains(pt)) {
                        errors.add(new ValidationError("periodTypes[" + i + "]",
                                "非法周期粒度: " + pt + "（只允许 WEEK/MONTH/QUARTER）"));
                    } else if (!seenTypes.add(pt)) {
                        errors.add(new ValidationError("periodTypes[" + i + "]", "周期粒度重复: " + pt));
                    }
                }
            }
        }
        if (tpl.chapters() == null || tpl.chapters().isEmpty()) {
            errors.add(new ValidationError("chapters", "模板必须至少有一个章节"));
            return errors;
        }
        Set<String> seenChapterIds = new HashSet<>();
        for (int i = 0; i < tpl.chapters().size(); i++) {
            ReportTemplateDef.ChapterDef ch = tpl.chapters().get(i);
            String at = "chapters[" + i + "]";
            if (ch.chapterId() == null || ch.chapterId().isBlank()) {
                errors.add(new ValidationError(at + ".chapterId", "chapterId 不能为空"));
            } else if (!seenChapterIds.add(ch.chapterId())) {
                errors.add(new ValidationError(at + ".chapterId", "chapterId 重复: " + ch.chapterId()));
            }
            if (ch.title() == null || ch.title().isBlank()) {
                errors.add(new ValidationError(at + ".title", "章节标题不能为空"));
            }
            if (ch.metrics() == null || ch.metrics().isEmpty()) {
                errors.add(new ValidationError(at + ".metrics",
                        "每个章节至少要挂 1 个指标（空章节 ⑤ 撰写无事实可写）"));
            } else {
                for (int j = 0; j < ch.metrics().size(); j++) {
                    String metricId = ch.metrics().get(j);
                    if (metricId == null || metricId.isBlank()) {
                        errors.add(new ValidationError(at + ".metrics[" + j + "]", "metricId 不能为空"));
                    } else if (!publishedMetricIds.contains(metricId)) {
                        errors.add(new ValidationError(at + ".metrics[" + j + "]",
                                "引用了不存在（或未发布）的指标: " + metricId));
                    }
                }
            }
            validateComparisons(ch, at, tpl.effectivePeriodTypes(), errors);
            if (ch.guidance() != null && ch.guidance().length() > PROMPT_MAX_LEN) {
                errors.add(new ValidationError(at + ".guidance", "guidance 超长（≤" + PROMPT_MAX_LEN + " 字）"));
            }
            if (ch.stylePrompt() != null && ch.stylePrompt().length() > PROMPT_MAX_LEN) {
                errors.add(new ValidationError(at + ".stylePrompt", "stylePrompt 超长（≤" + PROMPT_MAX_LEN + " 字）"));
            }
        }
        return errors;
    }

    /**
     * 章节比较声明校验：新旧字段互斥 → token 合法 → 不重复 → 同粒度环比至多一个
     * → 每个 token 对模板声明的每个周期粒度过 ComparisonType 矩阵。
     */
    private static void validateComparisons(ReportTemplateDef.ChapterDef ch, String at,
                                            List<String> periodTypes, List<ValidationError> errors) {
        boolean hasNew = ch.comparisons() != null && !ch.comparisons().isEmpty();
        if (ch.comparison() != null && hasNew) {
            errors.add(new ValidationError(at + ".comparison",
                    "comparison（旧单值）与 comparisons（列表）不得同时填写，请只用 comparisons"));
            return;   // 来源歧义，后续逐项校验无意义
        }
        List<String> comps = ch.effectiveComparisons();
        Set<String> seen = new HashSet<>();
        int sameGranularity = 0;
        for (int j = 0; j < comps.size(); j++) {
            String token = comps.get(j);
            String loc = hasNew ? at + ".comparisons[" + j + "]" : at + ".comparison";
            var ct = ComparisonType.of(token);
            if (ct.isEmpty()) {
                errors.add(new ValidationError(loc,
                        "非法比较类型: " + token + "（只允许 " + ComparisonType.legalTokens() + "）"));
                continue;
            }
            if (!seen.add(token)) {
                errors.add(new ValidationError(loc, "比较类型重复: " + token));
                continue;
            }
            if (MetricQuerySpec.PURPOSE_COMPARE.equals(ct.get().purpose()) && ++sameGranularity > 1) {
                errors.add(new ValidationError(loc,
                        "同粒度环比（wow/mom/qoq）至多声明一个（周期粒度已由报告期决定，多声明无意义）"));
            }
            for (String pt : periodTypes) {
                if (!ct.get().allows(pt)) {
                    errors.add(new ValidationError(loc, "比较类型 " + token + " 不适用于模板声明的周期粒度 "
                            + pt + "（矩阵：WEEK→wow；MONTH→mom/yoy；QUARTER→qoq/yoy）"));
                }
            }
        }
    }
}
