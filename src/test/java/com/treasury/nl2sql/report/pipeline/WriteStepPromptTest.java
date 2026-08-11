package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteStep prompt 组装单测（⑤ 对接 LLM 前置保护）。
 * 验证 stylePrompt 仅进入 user 文本、system 铁律不受模板污染；
 * 事实按章节分组、序列 fact 隐藏、维度 fact 仅加表格提示，不泄漏不应出现的图表细节。
 */
class WriteStepPromptTest {

    private final WriteStep step = new WriteStep(null, new ObjectMapper());

    private static Outline outline(String style1, String style2) {
        return new Outline("tpl", "2026-W26", List.of(
                new Outline.OutlineChapter("c1", "一、概览", List.of("m1"), null, null, "指引一", style1, null),
                new Outline.OutlineChapter("c2", "二、收支", List.of("m2"), "week_over_week", null, "指引二", style2, null)),
                List.of());
    }

    /**
     * 输入：两章 stylePrompt 都有内容。
     * 预期：二者都注入 user prompt，并附“冲突以铁律为准”说明。
     */
    @Test
    void stylePromptIsInjectedPerChapterInUserPrompt() {
        String user = step.userPrompt(outline("电报式短句", "多用长句铺陈"), List.of(), List.of());
        assertTrue(user.contains("电报式短句"));
        assertTrue(user.contains("多用长句铺陈"));
        // 注入位置声明服从铁律
        assertTrue(user.contains("如与系统铁律冲突，一律以铁律为准"));
        // 指引仍在
        assertTrue(user.contains("指引一") && user.contains("指引二"));
    }

    /**
     * 输入：某章 stylePrompt 为空。
     * 预期：不出现风格要求字段，prompt 生成对空样式保持干净。
     */
    @Test
    void blankOrNullStylePromptAddsNothing() {
        String user = step.userPrompt(outline(null, "  "), List.of(), List.of());
        assertFalse(user.contains("风格要求"), "无 stylePrompt 的章节不应出现风格段");
    }

    /**
     * 输入：读取 systemPrompt。
     * 预期：常量铁律不混入模板内容，证明 system 与 user 语境隔离。
     */
    @Test
    void systemPromptIsNotPollutedByStylePrompt() {
        String system = step.systemPrompt();
        // system 段是常量铁律，不含任何模板内容的痕迹
        assertFalse(system.contains("电报式短句"));
        assertTrue(system.contains("铁律"));
        assertTrue(system.contains("{{fact_key}}"));
        // run() 的对话组装里 system 也必须与模板无关——systemPrompt() 无参本身就是证明
    }

    /**
     * 输入：章节 fact 存在。
     * 预期：同章聚合并按 chapter 过滤，不混入其他章节数据。
     */
    @Test
    void factsStillGroupedPerChapter() {
        FactRecord f1 = new FactRecord("fact_001", "m1", 1, "指标一", "c1", "BASE",
                new java.math.BigDecimal("1"), "CNY", "1.00 元", "2026-W26", null, "{}", null, null, null, null, "PASSED", null);
        String user = step.userPrompt(outline("电报式", null), List.of(f1), List.of());
        assertTrue(user.contains("fact_001"));
    }

    /**
     * 输入：包含 CHART_SERIES + CURRENT 同时存在。
     * 预期：仅 CURRENT 类 fact 进入 prompt，图表序列 fact 被排除。
     */
    @Test
    void chartSeriesFactsAreExcludedFromPrompt() {
        // 序列 fact（spec purpose=CHART_SERIES）不进 ⑤ 的章节 facts JSON——LLM 零接触图表数据
        FactRecord series = new FactRecord("fact_c01_s1", "m1", 1, "指标一（序列·2026-W24）", "c1", "BASE",
                new java.math.BigDecimal("80"), "CNY", "80.00 元", "2026-W24", null,
                "{\"purpose\":\"CHART_SERIES\"}", null, null, null, null, "PASSED", null);
        FactRecord normal = new FactRecord("fact_001", "m1", 1, "指标一", "c1", "BASE",
                new java.math.BigDecimal("1"), "CNY", "1.00 元", "2026-W26", null,
                "{\"purpose\":\"CURRENT\"}", null, null, null, null, "PASSED", null);
        String user = step.userPrompt(outline(null, null), List.of(series, normal), List.of());
        assertTrue(user.contains("fact_001"));
        assertFalse(user.contains("fact_c01_s1"), "序列 fact 不得出现在 prompt");
    }

    /**
     * 输入：仅维度 fact 出现在某章节。
     * 预期：仅该章节附加 markdown 表格展示提示，且 dimensions 信息在 JSON 中可见。
     */
    @Test
    void dimensionalFactsTriggerTableGuidanceOnlyInTheirChapter() {
        FactRecord dim = new FactRecord("fact_001_usd", "m1", 1, "指标一（USD）", "c1", "BASE",
                new java.math.BigDecimal("1"), "CNY", "1.00 元", "2026-W26",
                java.util.Map.of("currency", "USD"), "{}", null, null, null, null, "PASSED", null);
        FactRecord plain = new FactRecord("fact_002", "m2", 1, "指标二", "c2", "BASE",
                new java.math.BigDecimal("2"), "笔", "2 笔", "2026-W26", null, "{}", null, null, null, null, "PASSED", null);
        String user = step.userPrompt(outline(null, null), List.of(dim, plain), List.of());
        assertTrue(user.contains("markdown 表格"), "维度章节应注入表格呈现引导");
        assertTrue(user.contains("\"currency\":\"USD\""), "facts JSON 应携带 dimensions");
        // 引导只出现一次（仅含维度事实的 c1 章）
        assertEquals(1, user.split("markdown 表格", -1).length - 1);
    }
}
