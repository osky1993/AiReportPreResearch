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
     * @param comparison  本章是否要求环比（week_over_week / null）；
     *                    仅 comparable 指标真正派生对比期查询
     * @param stylePrompt 本章风格提示词（随大纲快照固化；⑤ 作为 user 段材料注入，
     *                    不进 system 铁律段；旧 run 的 outline_json 无此字段 → null）
     */
    public record OutlineChapter(
            String chapterId,
            String title,
            List<String> metricIds,
            String comparison,
            String guidance,
            String stylePrompt) {}
}
