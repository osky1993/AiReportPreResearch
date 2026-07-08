package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 报告模板（MVP 最小格式：章节树 + 每章推荐指标，resources/report/template-treasury-weekly.json）。
 * 不做模板库平台——单模板、静态文件、启动加载。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)   // 入库 body_json 不带 null 字段（comparison/stylePrompt 可空）
public record ReportTemplateDef(
        String templateId,
        String name,
        List<String> keywords,
        List<ChapterDef> chapters) {

    /**
     * @param comparison  本章是否要求环比对比（week_over_week / null）
     * @param guidance    给 ⑤ 撰写 LLM 的本章写作指引
     * @param stylePrompt 本章风格提示词（P1 仅预留存储，P4 起由 WriteStep 注入 user 段；可空）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChapterDef(
            String chapterId,
            String title,
            List<String> metrics,
            String comparison,
            String guidance,
            String stylePrompt) {}
}
