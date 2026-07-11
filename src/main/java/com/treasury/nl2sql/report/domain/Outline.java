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
     * @param comparison  本章比较声明（旧单值字段；旧 run 的 outline_json 快照只有它）
     * @param comparisons 本章比较声明列表（Phase03 起，如 ["month_over_month","year_over_year"]）；
     *                    仅 comparable 指标真正派生对比期查询
     * @param stylePrompt 本章风格提示词（随大纲快照固化；⑤ 作为 user 段材料注入，
     *                    不进 system 铁律段；旧 run 的 outline_json 无此字段 → null）
     * @param charts      本章图表声明（Phase04，随大纲快照固化——口径锁死含图表口径；旧快照 → null）
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
