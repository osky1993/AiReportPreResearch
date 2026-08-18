package com.treasury.nl2sql.report.domain;

import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Outline.fromTemplate 单测（Gate3 抽取重构）：模板→大纲的确定性映射由 ① OutlineStep 与
 * 模板预览共用，映射语义以此锁定——章节字段逐项透传、新旧 comparison 字段原样保留（不归一化改写）。
 */
class OutlineFromTemplateTest {

    /** 章节全字段透传：id/标题/指标/双 comparison 字段/指引/文风/图表。 */
    @Test
    void chaptersMapFieldByField() {
        ChartDef chart = new ChartDef("c1", "line", "近六周",
                new ChartDef.Binding(ChartDef.KIND_SERIES, "m1", 6));
        ReportTemplateDef tpl = new ReportTemplateDef("t1", "模板", List.of("kw"), List.of("MONTH"), List.of(
                new ReportTemplateDef.ChapterDef("ch1", "一、总览", List.of("m1", "m2"),
                        null, List.of("month_over_month", "year_over_year"), "指引", "文风", List.of(chart)),
                new ReportTemplateDef.ChapterDef("ch2", "二、明细", List.of("m3"),
                        "week_over_week", null, "g2", null, null)));

        Outline o = Outline.fromTemplate(tpl, "2026-M06", List.of("未映射表述"));

        assertEquals("t1", o.templateId());
        assertEquals("2026-M06", o.periodLabel());
        assertEquals(List.of("未映射表述"), o.unresolved());
        assertEquals(2, o.chapters().size());

        Outline.OutlineChapter c1 = o.chapters().get(0);
        assertEquals("ch1", c1.chapterId());
        assertEquals("一、总览", c1.title());
        assertEquals(List.of("m1", "m2"), c1.metricIds());
        assertNull(c1.comparison());
        assertEquals(List.of("month_over_month", "year_over_year"), c1.comparisons());
        assertEquals("指引", c1.guidance());
        assertEquals("文风", c1.stylePrompt());
        assertEquals(List.of(chart), c1.charts());

        // 旧单值字段原样保留（不归一化改写——旧快照语义由 effectiveComparisons 统一解释）
        Outline.OutlineChapter c2 = o.chapters().get(1);
        assertEquals("week_over_week", c2.comparison());
        assertNull(c2.comparisons());
        assertEquals(List.of("week_over_week"), c2.effectiveComparisons());
    }

    /** metricIds 是可变副本：卡点1 人工增删章节指标不得写穿模板定义。 */
    @Test
    void metricIdsAreMutableCopies() {
        List<String> source = List.of("m1");
        ReportTemplateDef tpl = new ReportTemplateDef("t1", "模板", List.of("kw"), null, List.of(
                new ReportTemplateDef.ChapterDef("ch1", "一", source, null, null, "g", null, null)));
        Outline o = Outline.fromTemplate(tpl, "2026-W26", List.of());
        o.chapters().get(0).metricIds().add("m2");   // 不抛（ArrayList 副本）
        assertEquals(List.of("m1"), source, "修改大纲不得影响模板定义");
    }
}
