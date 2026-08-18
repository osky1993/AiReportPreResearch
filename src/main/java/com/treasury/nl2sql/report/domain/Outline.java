package com.treasury.nl2sql.report.domain;

import java.util.List;

/**
 * 报告大纲：① 的输出、HITL 卡点1 的确认对象。口径在这里锁死——
 * 人确认（可增删章节指标）后回传的版本即为后续 ②~⑥ 的唯一输入。
 *
 * @param unresolved 需求中提及但映射不到指标语义定义的表述（软失败：
 *                   不阻断，确认页红字提示，由人决定忽略或改需求重来）
 */
public record Outline(
        String templateId,
        String periodLabel,
        List<OutlineChapter> chapters,
        List<String> unresolved) {

    /**
     * 模板定义 → 大纲的确定性映射（零 LLM）：章节结构原样透传。
     * ① OutlineStep（LLM 只负责选模板与抽报告期，映射交给这里）与模板预览共用本工厂——
     * 两处各自内联会在模板结构演进时口径漂移。
     */
    public static Outline fromTemplate(com.treasury.nl2sql.report.asset.ReportTemplateDef tpl,
                                       String periodLabel, List<String> unresolved) {
        List<OutlineChapter> chapters = tpl.chapters().stream()
                .map(c -> new OutlineChapter(c.chapterId(), c.title(),
                        new java.util.ArrayList<>(c.metrics()), c.comparison(), c.comparisons(),
                        c.guidance(), c.stylePrompt(), c.charts()))
                .toList();
        return new Outline(tpl.templateId(), periodLabel, chapters, List.copyOf(unresolved));
    }

    /**
     * @param chapterId 章节稳定 ID（用于 fact / chart / claims 归属）
     * @param title 章节标题（最终报告中保留）
     * @param metricIds 本章入池指标列表（按顺序作为事实渲染顺序）
     * @param comparison 兼容字段：旧版本 outline 的单一比较声明
     * @param comparisons 有序比较声明列表（新版本主用）
     * @param guidance 指导语（内部可读，不写入最终正文）
     * @param stylePrompt 写作风格约束（随大纲快照固化，⑤ 注入 user 段）
     * @param charts 章节图表定义列表（可为空）
     */
    public record OutlineChapter(
            String chapterId,
            String title,
            List<String> metricIds,
            String comparison,
            List<String> comparisons,
            String guidance,
            String stylePrompt,
            List<ChartDef> charts) {

        /** 归一化视图：新列表优先、旧单值回退、皆空 → 空列表。②④⑤ 只准读这个（旧快照原样解析不改写）。 */
        public List<String> effectiveComparisons() {
            if (comparisons != null && !comparisons.isEmpty()) return List.copyOf(comparisons);
            return comparison != null ? List.of(comparison) : List.of();
        }
    }
}
