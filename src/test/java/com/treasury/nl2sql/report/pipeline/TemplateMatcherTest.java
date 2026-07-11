package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板匹配召回单测（纯逻辑：LocalHashingEmbeddingClient 离线确定性，无 DB/LLM/Spring）。
 * 只断言候选集与相对顺序，不断言 cosine 绝对值（哈希向量的分数无业务含义）。
 */
class TemplateMatcherTest {

    private final TemplateMatcher matcher =
            new TemplateMatcher(new LocalHashingEmbeddingClient(), null, 0.60);

    private static ReportTemplateDef tpl(String id, String name, List<String> keywords, String... chapterTitles) {
        List<ReportTemplateDef.ChapterDef> chapters = java.util.Arrays.stream(chapterTitles)
                .map(t -> new ReportTemplateDef.ChapterDef("ch_" + t, t, List.of("m1"), null, null, null, null))
                .toList();
        return new ReportTemplateDef(id, name, keywords, null, chapters);
    }

    private final ReportTemplateDef weekly = tpl("treasury-weekly", "司库资金周报",
            List.of("周报", "资金周报", "司库周报"), "核心结论", "账户与头寸");
    private final ReportTemplateDef flash = tpl("treasury-flash", "资金快报",
            List.of("快报", "资金快报", "资金速览"), "头寸速览", "当期收支");
    private final List<ReportTemplateDef> both = List.of(weekly, flash);

    @Test
    void weeklyRequestHitsWeeklyFirst() {
        List<TemplateMatcher.Candidate> c = matcher.recall("生成 2026 年第 26 周的司库资金周报，和上周对比", both);
        assertFalse(c.isEmpty());
        assertEquals("treasury-weekly", c.get(0).templateId());
        assertTrue(c.get(0).keywordHits() > 0, "周报关键词应命中");
    }

    @Test
    void flashRequestHitsFlashFirst() {
        List<TemplateMatcher.Candidate> c = matcher.recall("出一份 2026 年第 26 周的资金快报", both);
        assertFalse(c.isEmpty());
        assertEquals("treasury-flash", c.get(0).templateId());
        assertTrue(c.get(0).keywordHits() > 0, "快报关键词应命中");
    }

    @Test
    void unrelatedRequestRecallsNothing() {
        List<TemplateMatcher.Candidate> c = matcher.recall("生成 HR 月度盘点", both);
        assertTrue(c.isEmpty(), "无关需求不应召回任何候选，应由上层失败关闭");
    }

    @Test
    void orderingIsDeterministicWhenBothHit() {
        // 同时点名两个模板的关键词：快报命中 2 个关键词（快报、资金快报）、周报 1 个 → flash 排前
        List<TemplateMatcher.Candidate> c = matcher.recall("把周报改成资金快报", both);
        assertEquals(2, c.size());
        assertEquals("treasury-flash", c.get(0).templateId());
        assertTrue(c.get(0).keywordHits() > c.get(1).keywordHits());
    }

    @Test
    void blankKeywordsDoNotParticipate() {
        // 防御性：keywords 含空串/空列表的模板不因关键词进候选（治理上 selfCheck 已拦，运行侧兜底）
        ReportTemplateDef bad = tpl("bad", "空关键词模板", List.of(" "), "某章");
        List<TemplateMatcher.Candidate> c = matcher.recall("空关键词模板相关需求", List.of(bad));
        assertTrue(c.stream().noneMatch(x -> x.keywordHits() > 0));
    }

    @Test
    void describeIsHumanReadable() {
        List<TemplateMatcher.Candidate> c = matcher.recall("出一份资金快报", both);
        String text = TemplateMatcher.describe(c);
        assertTrue(text.contains("treasury-flash"));
        assertTrue(text.contains("资金快报"));
    }
}
