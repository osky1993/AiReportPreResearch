package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 报告模板（MVP 最小格式：章节树 + 每章推荐指标，resources/report/template-treasury-weekly.json）。
 * 不做模板库平台——单模板、静态文件、启动加载。
 *
 * @param periodTypes 适用周期粒度（WEEK/MONTH/QUARTER，取值同 PeriodResolver.TYPE_*）；
 *                    Phase03 前的旧资产无此字段 → null，经 {@link #effectivePeriodTypes()} 缺省为仅 WEEK
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)   // 入库 body_json 不带 null 字段（comparison/stylePrompt 可空）
public record ReportTemplateDef(
        String templateId,
        String name,
        List<String> keywords,
        List<String> periodTypes,
        List<ChapterDef> chapters) {

    /** 归一化视图：旧资产（无 periodTypes 字段）缺省仅支持周报。全链路只准读这个。 */
    public List<String> effectivePeriodTypes() {
        return (periodTypes == null || periodTypes.isEmpty()) ? List.of("WEEK") : List.copyOf(periodTypes);
    }

    /**
     * @param comparison  本章比较声明（旧单值字段，Phase03 前资产用；与 comparisons 二选一）
     * @param comparisons 本章比较声明列表（Phase03 起，如 ["month_over_month","year_over_year"]；
     *                    取值见 ComparisonType，校验器把关与 periodTypes 的匹配矩阵）
     * @param guidance    给 ⑤ 撰写 LLM 的本章写作指引
     * @param stylePrompt 本章风格提示词（P1 仅预留存储，P4 起由 WriteStep 注入 user 段；可空）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChapterDef(
            String chapterId,
            String title,
            List<String> metrics,
            String comparison,
            List<String> comparisons,
            String guidance,
            String stylePrompt) {

        /** 归一化视图：新列表优先、旧单值回退、皆空 → 空列表。全链路只准读这个（不在加载时改写——版本行不可变）。 */
        public List<String> effectiveComparisons() {
            if (comparisons != null && !comparisons.isEmpty()) return List.copyOf(comparisons);
            return comparison != null ? List.of(comparison) : List.of();
        }
    }
}
