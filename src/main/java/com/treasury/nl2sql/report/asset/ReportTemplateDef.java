package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 报告模板（MVP 最小格式：章节树 + 每章推荐指标，resources/report/template-treasury-weekly.json）。
 * 不做模板库平台——单模板、静态文件、启动加载。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportTemplateDef(
        String templateId,
        String name,
        List<String> keywords,
        List<ChapterDef> chapters) {

    /**
     * @param comparison 本章是否要求环比对比（week_over_week / null）
     * @param guidance   给 ⑤ 撰写 LLM 的本章写作指引
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChapterDef(
            String chapterId,
            String title,
            List<String> metrics,
            String comparison,
            String guidance) {}
}
