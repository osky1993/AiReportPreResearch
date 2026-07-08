package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 模板校验规则单测（纯逻辑，无 DB/Spring）——契约见 README「模板管理 API」。 */
class TemplateValidatorTest {

    private static final Set<String> CATALOG = Set.of("m_balance", "m_inflow");

    private static ReportTemplateDef.ChapterDef chapter(String id, String title, List<String> metrics,
                                                        String comparison, String guidance, String stylePrompt) {
        return new ReportTemplateDef.ChapterDef(id, title, metrics, comparison, guidance, stylePrompt);
    }

    private static ReportTemplateDef okTemplate() {
        return new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇", "外汇周报"),
                List.of(chapter("c1", "一、概览", List.of("m_balance"), null, "指引", null),
                        chapter("c2", "二、收支", List.of("m_inflow"), "week_over_week", null, "电报式短句")));
    }

    @Test
    void validTemplatePasses() {
        assertTrue(TemplateValidator.validate(okTemplate(), CATALOG).isEmpty());
    }

    @Test
    void badStructureIsReportedPerField() {
        ReportTemplateDef bad = new ReportTemplateDef("FX weekly!", " ", List.of(" "),
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
        ReportTemplateDef bad = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"),
                List.of(chapter("c1", "一、概览", List.of("m_balance", "m_ghost"), null, null, null)));
        List<ValidationError> errors = TemplateValidator.validate(bad, CATALOG);
        assertEquals(1, errors.size());
        assertEquals("chapters[0].metrics[1]", errors.get(0).location());
        assertTrue(errors.get(0).message().contains("m_ghost"));
    }

    @Test
    void emptyTemplateAndEmptyChaptersAreRejected() {
        assertEquals("", TemplateValidator.validate(null, CATALOG).get(0).location());
        ReportTemplateDef noChapters = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"), List.of());
        List<ValidationError> errors = TemplateValidator.validate(noChapters, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters")));
    }

    @Test
    void duplicateChapterIdIsRejected() {
        ReportTemplateDef dup = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"),
                List.of(chapter("c1", "一", List.of("m_balance"), null, null, null),
                        chapter("c1", "二", List.of("m_inflow"), null, null, null)));
        List<ValidationError> errors = TemplateValidator.validate(dup, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[1].chapterId")
                && e.message().contains("重复")));
    }

    @Test
    void overlongPromptsAreRejected() {
        String long1001 = "风".repeat(1001);
        ReportTemplateDef bad = new ReportTemplateDef("fx-weekly", "外汇周报", List.of("外汇"),
                List.of(chapter("c1", "一", List.of("m_balance"), null, long1001, long1001)));
        List<ValidationError> errors = TemplateValidator.validate(bad, CATALOG);
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].guidance")));
        assertTrue(errors.stream().anyMatch(e -> e.location().equals("chapters[0].stylePrompt")));
    }
}
