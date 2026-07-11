package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.Outline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑤ prompt 组装单测（P4-T1）：章节 stylePrompt 只进 user 段材料并声明服从铁律；
 * system 铁律段与模板资产完全解耦、不被任何用户可编辑内容污染。
 */
class WriteStepPromptTest {

    private final WriteStep step = new WriteStep(null, new ObjectMapper());

    private static Outline outline(String style1, String style2) {
        return new Outline("tpl", "2026-W26", List.of(
                new Outline.OutlineChapter("c1", "一、概览", List.of("m1"), null, null, "指引一", style1, null),
                new Outline.OutlineChapter("c2", "二、收支", List.of("m2"), "week_over_week", null, "指引二", style2, null)),
                List.of());
    }

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

    @Test
    void blankOrNullStylePromptAddsNothing() {
        String user = step.userPrompt(outline(null, "  "), List.of(), List.of());
        assertFalse(user.contains("风格要求"), "无 stylePrompt 的章节不应出现风格段");
    }

    @Test
    void systemPromptIsNotPollutedByStylePrompt() {
        String system = step.systemPrompt();
        // system 段是常量铁律，不含任何模板内容的痕迹
        assertFalse(system.contains("电报式短句"));
        assertTrue(system.contains("铁律"));
        assertTrue(system.contains("{{fact_key}}"));
        // run() 的对话组装里 system 也必须与模板无关——systemPrompt() 无参本身就是证明
    }

    @Test
    void factsStillGroupedPerChapter() {
        FactRecord f1 = new FactRecord("fact_001", "m1", 1, "指标一", "c1", "BASE",
                new java.math.BigDecimal("1"), "CNY", "1.00 元", "2026-W26", null, "{}", null, null, null, null, "PASSED", null);
        String user = step.userPrompt(outline("电报式", null), List.of(f1), List.of());
        assertTrue(user.contains("fact_001"));
    }

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
