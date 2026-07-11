package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.domain.ChartDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 模板校验规则单测（纯逻辑，无 DB/Spring）——契约见 README「模板管理 API」。 */
class TemplateValidatorTest {

    private static MetricDefinition metricDef(String id, boolean timeBound, List<String> dims) {
        return new MetricDefinition(id, "指标" + id, "CNY", timeBound, false, "v", "ZERO",
                List.of(), dims, null, null);
    }

    private static final Map<String, MetricDefinition> CATALOG = Map.of(
            "m_balance", metricDef("m_balance", false, null),
            "m_inflow", metricDef("m_inflow", true, null),
            "m_by_ccy", metricDef("m_by_ccy", true, List.of("currency")));

    private static ReportTemplateDef.ChapterDef chapter(String id, String title, List<String> metrics,
                                                        String comparison, String guidance, String stylePrompt) {
        return new ReportTemplateDef.ChapterDef(id, title, metrics, comparison, null, guidance, stylePrompt, null);
    }

    private static ReportTemplateDef okTemplate() {
        return new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇", "外汇周报"), null,
                List.of(chapter("c1", "一、概览", List.of("m_balance"), null, "指引", null),
                        chapter("c2", "二、收支", List.of("m_inflow"), "week_over_week", null, "电报式短句")));
    }

    @Test
    void validTemplatePasses() {
        assertTrue(TemplateValidator.validate(okTemplate(), CATALOG).isEmpty());
    }

    @Test
    void badStructureIsReportedPerField() {
        ReportTemplateDef bad = new ReportTemplateDef("FX weekly!", " ", List.of(" "), null,
                List.of(chapter(null, "", List.of(), "month_over_month", null, null)));
        List<ValidationError> errors = TemplateValidator.validate(bad, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("templateId")), "非法 templateId 应被拦");
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("name")), "空 name 应被拦");
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("keywords[0]")), "空关键词应被拦");
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].chapterId")));
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].title")));
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].metrics")), "空章节应被拦");
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].comparison")));
    }

    @Test
    void unknownMetricReferenceIsRejectedWithLocation() {
        ReportTemplateDef bad = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"), null,
                List.of(chapter("c1", "一、概览", List.of("m_balance", "m_ghost"), null, null, null)));
        List<ValidationError> errors = TemplateValidator.validate(bad, CATALOG);
        assertEquals(1, errors.size());
        assertEquals("chapters[0].metrics[1]", errors.get(0).location());
        assertTrue(errors.get(0).message().contains("m_ghost"));
    }

    @Test
    void emptyTemplateAndEmptyChaptersAreRejected() {
        assertEquals("", TemplateValidator.validate(null, CATALOG).get(0).location());
        ReportTemplateDef noChapters = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"), null, List.of());
        List<ValidationError> errors = TemplateValidator.validate(noChapters, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters")));
    }

    @Test
    void duplicateChapterIdIsRejected() {
        ReportTemplateDef dup = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"), null,
                List.of(chapter("c1", "一", List.of("m_balance"), null, null, null),
                        chapter("c1", "二", List.of("m_inflow"), null, null, null)));
        List<ValidationError> errors = TemplateValidator.validate(dup, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[1].chapterId")
                && e.message().contains("重复")));
    }

    @Test
    void overlongPromptsAreRejected() {
        String long1001 = "风".repeat(1001);
        ReportTemplateDef bad = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"), null,
                List.of(chapter("c1", "一", List.of("m_balance"), null, long1001, long1001)));
        List<ValidationError> errors = TemplateValidator.validate(bad, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].guidance")));
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].stylePrompt")));
    }

    // ---- Phase03：periodTypes 与比较矩阵 ----

    private static ReportTemplateDef withPeriodTypes(List<String> periodTypes,
                                                     ReportTemplateDef.ChapterDef... chapters) {
        return new ReportTemplateDef("fx-report", "外汇报告", List.of("外汇"), periodTypes, List.of(chapters));
    }

    private static ReportTemplateDef.ChapterDef chapterWithList(List<String> comparisons) {
        return new ReportTemplateDef.ChapterDef("c1", "一、概览", List.of("m_balance"),
                null, comparisons, null, null, null);
    }

    @Test
    void monthlyTemplateWithMomAndYoyPasses() {
        ReportTemplateDef tpl = withPeriodTypes(List.of("MONTH"),
                chapterWithList(List.of("month_over_month", "year_over_year")));
        assertTrue(TemplateValidator.validate(tpl, CATALOG).isEmpty());
    }

    @Test
    void quarterlyTemplateWithQoqAndYoyPasses() {
        ReportTemplateDef tpl = withPeriodTypes(List.of("QUARTER"),
                chapterWithList(List.of("quarter_over_quarter", "year_over_year")));
        assertTrue(TemplateValidator.validate(tpl, CATALOG).isEmpty());
    }

    @Test
    void periodTypeValuesAreValidated() {
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of(), chapterWithList(null)), CATALOG)
                .stream().anyMatch(e -> e.location().equals("periodTypes")), "空列表应被拦");
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of("YEAR"), chapterWithList(null)), CATALOG)
                .stream().anyMatch(e -> e.location().equals("periodTypes[0]")), "非法取值应被拦");
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of("WEEK", "WEEK"), chapterWithList(null)), CATALOG)
                .stream().anyMatch(e -> e.location().equals("periodTypes[1]")), "重复取值应被拦");
    }

    @Test
    void comparisonMatrixRejectsMismatches() {
        // WEEK 模板（缺省）声明同比 → 矩阵拦截
        ReportTemplateDef weekYoy = withPeriodTypes(null, chapterWithList(List.of("year_over_year")));
        assertTrue(TemplateValidator.validate(weekYoy, CATALOG).stream()
                .anyMatch(e -> e.location().equals("chapters[0].comparisons[0]")
                        && e.message().contains("不适用")));
        // MONTH 模板声明 wow → 矩阵拦截
        ReportTemplateDef monthWow = withPeriodTypes(List.of("MONTH"), chapterWithList(List.of("week_over_week")));
        assertTrue(TemplateValidator.validate(monthWow, CATALOG).stream()
                .anyMatch(e -> e.location().equals("chapters[0].comparisons[0]")));
    }

    @Test
    void comparisonFieldRulesAreEnforced() {
        // 新旧字段同时填 → 互斥拦截
        ReportTemplateDef both = withPeriodTypes(List.of("MONTH"), new ReportTemplateDef.ChapterDef(
                "c1", "一", List.of("m_balance"), "month_over_month", List.of("year_over_year"), null, null, null));
        assertTrue(TemplateValidator.validate(both, CATALOG).stream()
                .anyMatch(e -> e.message().contains("不得同时填写")));
        // 未知 token
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of("MONTH"),
                        chapterWithList(List.of("day_over_day"))), CATALOG).stream()
                .anyMatch(e -> e.message().contains("非法比较类型")));
        // 重复 token
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of("MONTH"),
                        chapterWithList(List.of("month_over_month", "month_over_month"))), CATALOG).stream()
                .anyMatch(e -> e.message().contains("重复")));
        // 同粒度环比声明两个（mom + qoq）→ 至多一个
        assertTrue(TemplateValidator.validate(withPeriodTypes(List.of("MONTH"),
                        chapterWithList(List.of("month_over_month", "quarter_over_quarter"))), CATALOG).stream()
                .anyMatch(e -> e.message().contains("至多声明一个")));
    }

    // ---- Phase04：图表声明 ----

    private static ReportTemplateDef.ChapterDef chapterWithCharts(List<String> metrics, ChartDef... charts) {
        return new ReportTemplateDef.ChapterDef("c1", "一、概览", metrics, null, null, null, null, List.of(charts));
    }

    private static ReportTemplateDef tplOf(ReportTemplateDef.ChapterDef ch) {
        return new ReportTemplateDef("fx-report", "外汇报告", List.of("外汇"), null, List.of(ch));
    }

    @Test
    void validSeriesAndDimensionChartsPass() {
        ChartDef trend = new ChartDef("trend_inflow", "line", "近六周流入",
                new ChartDef.Binding("series", "m_inflow", 6));
        ChartDef pie = new ChartDef("mix_ccy", "pie", "币种构成",
                new ChartDef.Binding("dimension", "m_by_ccy", null));
        assertTrue(TemplateValidator.validate(
                tplOf(chapterWithCharts(List.of("m_inflow", "m_by_ccy"), trend, pie)), CATALOG).isEmpty());
    }

    @Test
    void chartTypeBindingMatrixIsEnforced() {
        // series→pie 不许；dimension→line 不许
        ChartDef seriesPie = new ChartDef("t1_chart", "pie", "x", new ChartDef.Binding("series", "m_inflow", 6));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_inflow"), seriesPie)), CATALOG)
                .stream().anyMatch(e -> e.location().endsWith(".type")));
        ChartDef dimLine = new ChartDef("t2_chart", "line", "x", new ChartDef.Binding("dimension", "m_by_ccy", null));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_by_ccy"), dimLine)), CATALOG)
                .stream().anyMatch(e -> e.location().endsWith(".type")));
    }

    @Test
    void chartBindingShapeIsEnforced() {
        // series 绑快照指标 → 拦；periods 越界 → 拦；dimension 绑非维度指标 → 拦；dimension 带 periods → 拦
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_balance"),
                        new ChartDef("c_a", "line", "x", new ChartDef.Binding("series", "m_balance", 6)))), CATALOG)
                .stream().anyMatch(e -> e.message().contains("timeBound")));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_inflow"),
                        new ChartDef("c_b", "line", "x", new ChartDef.Binding("series", "m_inflow", 13)))), CATALOG)
                .stream().anyMatch(e -> e.location().endsWith(".binding.periods")));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_inflow"),
                        new ChartDef("c_c", "pie", "x", new ChartDef.Binding("dimension", "m_inflow", null)))), CATALOG)
                .stream().anyMatch(e -> e.message().contains("dimensions")));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_by_ccy"),
                        new ChartDef("c_d", "pie", "x", new ChartDef.Binding("dimension", "m_by_ccy", 6)))), CATALOG)
                .stream().anyMatch(e -> e.location().endsWith(".binding.periods")));
    }

    @Test
    void chartMetricMustBeInChapterMetricsAndIdsUnique() {
        // 绑定指标未挂本章 metrics → 拦
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_balance"),
                        new ChartDef("c_e", "line", "x", new ChartDef.Binding("series", "m_inflow", 6)))), CATALOG)
                .stream().anyMatch(e -> e.message().contains("本章 metrics")));
        // chartId 章内重复 → 拦
        ChartDef a = new ChartDef("dup_id", "line", "x", new ChartDef.Binding("series", "m_inflow", 6));
        ChartDef b = new ChartDef("dup_id", "bar", "y", new ChartDef.Binding("series", "m_inflow", 4));
        assertTrue(TemplateValidator.validate(tplOf(chapterWithCharts(List.of("m_inflow"), a, b)), CATALOG)
                .stream().anyMatch(e -> e.message().contains("重复")));
    }
}
