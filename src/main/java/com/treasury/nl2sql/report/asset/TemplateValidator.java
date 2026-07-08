package com.treasury.nl2sql.report.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模板校验（P2 契约：保存 / validate 干跑 / 启动自检三处共用同一套规则）。
 * 纯静态、无依赖，逐条返回错误并以 location 定位到章节/字段（前端红字定位靠它）。
 * 规则见 README「模板管理 API」：结构 + 引用存在性 + 每章至少 1 个指标。
 */
public final class TemplateValidator {

    /** @param location 如 chapters[1].metrics[0]，与前端表单结构对齐 */
    public record ValidationError(String location, String message) {}

    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final String COMPARISON_WEEK = "week_over_week";
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
            if (ch.comparison() != null && !COMPARISON_WEEK.equals(ch.comparison())) {
                errors.add(new ValidationError(at + ".comparison",
                        "comparison 只允许 null 或 " + COMPARISON_WEEK + "（当前: " + ch.comparison() + "）"));
            }
            if (ch.guidance() != null && ch.guidance().length() > PROMPT_MAX_LEN) {
                errors.add(new ValidationError(at + ".guidance", "guidance 超长（≤" + PROMPT_MAX_LEN + " 字）"));
            }
            if (ch.stylePrompt() != null && ch.stylePrompt().length() > PROMPT_MAX_LEN) {
                errors.add(new ValidationError(at + ".stylePrompt", "stylePrompt 超长（≤" + PROMPT_MAX_LEN + " 字）"));
            }
        }
        return errors;
    }
}
