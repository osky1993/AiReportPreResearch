package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.AuditResult;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 审计器纯逻辑单测（无 DB/LLM）——「数字一致率 100%」硬门禁的证据：
 * 含「故意注入不一致数字被拦截」的断言（端到端演练 c 的单测化）。
 */
class NumberAuditorTest {

    private static FactRecord fact(String key, String value, String unit) {
        BigDecimal v = new BigDecimal(value);
        return new FactRecord(key, "m_" + key, "指标" + key, "ch1", FactRecord.TYPE_BASE,
                v, unit, FactBuildStep.renderDisplay(v, unit), "2026-W26",
                null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
    }

    private final Map<String, FactRecord> facts = Map.of(
            "fact_001", fact("fact_001", "65700000", "CNY"),
            "fact_002", fact("fact_002", "4", "笔"),
            "fact_003", fact("fact_003", "12.5", "percent"),
            "fact_004", fact("fact_004", "1234.56", "CNY"));

    // ---------- 检查1：草稿禁数扫描 ----------

    @Test
    void draftWithOnlyPlaceholdersPasses() {
        String draft = "## 一、核心结论\n本周（2026-W26，2026-06-22 至 2026-06-28）人民币活跃账户余额合计为{{fact_001}}，"
                + "交易{{fact_002}}，环比{{fact_003}}。与 2026 年第 25 周相比无重大异常。";
        assertEquals(List.of(), NumberAuditor.checkDraft(draft, facts));
    }

    @Test
    void bareNumberInDraftIsCaught() {
        String draft = "本周余额合计 6570 万元，交易{{fact_002}}。";
        List<String> v = NumberAuditor.checkDraft(draft, facts);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("6570"));
        assertTrue(v.get(0).contains("裸数字"));
    }

    @Test
    void unknownFactRefInDraftIsCaught() {
        List<String> v = NumberAuditor.checkDraft("余额为{{fact_999}}。", facts);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("fact_999"));
    }

    @Test
    void whitelistedPeriodExpressionsPass() {
        String draft = "报告期 2026-W26（第 26 周，2026-06-22 至 2026-06-28），对比 W25；2026 年趋势平稳。";
        assertEquals(List.of(), NumberAuditor.checkDraft(draft, facts));
    }

    // ---------- 替换 + 检查2：渲染回读 ----------

    @Test
    void substituteThenVerifyIsFullyConsistent() {
        String draft = "余额合计{{fact_001}}，交易{{fact_002}}，环比{{fact_003}}，零头{{fact_004}}。";
        String rendered = NumberAuditor.substitute(draft, facts);
        assertTrue(rendered.contains("6,570.00 万元[fact_001]"));
        assertTrue(rendered.contains("4 笔[fact_002]"));
        assertTrue(rendered.contains("+12.5%[fact_003]"));
        assertTrue(rendered.contains("1,234.56 元[fact_004]"));

        AuditResult audit = NumberAuditor.verifyRendered(rendered, facts, 0);
        assertTrue(audit.passed());
        assertEquals(4, audit.totalNumbers());
        assertEquals(4, audit.matchedNumbers());
        assertEquals(1.0, audit.consistencyRate());
    }

    @Test
    void tamperedNumberIsCaught() {
        // 故意注入不一致：6,570 篡改为 6,571 —— 审计必须拦截（唯一发布硬门禁）
        String tampered = "余额合计6,571.00 万元[fact_001]，交易4 笔[fact_002]。";
        AuditResult audit = NumberAuditor.verifyRendered(tampered, facts, 0);
        assertFalse(audit.passed());
        assertEquals(2, audit.totalNumbers());
        assertEquals(1, audit.matchedNumbers());
        assertTrue(audit.violations().get(0).contains("fact_001"));
    }

    @Test
    void bareNumberInRenderedTextIsCaught() {
        String rendered = "余额合计6,570.00 万元[fact_001]，另有 3 笔未入账交易。";
        AuditResult audit = NumberAuditor.verifyRendered(rendered, facts, 0);
        assertFalse(audit.passed());
        assertEquals(2, audit.totalNumbers());   // 1 个已核对 + 1 个裸数字
        assertEquals(1, audit.matchedNumbers());
        assertTrue(audit.violations().get(0).contains("裸数字"));
    }

    @Test
    void unknownFactRefInRenderedIsCaught() {
        AuditResult audit = NumberAuditor.verifyRendered("余额6,570.00 万元[fact_999]。", facts, 0);
        assertFalse(audit.passed());
        assertTrue(audit.violations().get(0).contains("fact_999"));
    }

    @Test
    void unitMismatchIsCaught() {
        // fact_002 单位是笔，文中写成"个"——反解析判不相容
        AuditResult audit = NumberAuditor.verifyRendered("交易4 个[fact_002]。", facts, 0);
        assertFalse(audit.passed());
    }

    @Test
    void negativeAndThousandSeparatorParse() {
        Map<String, FactRecord> f = Map.of(
                "fact_neg", fact("fact_neg", "-8.3", "percent"),
                "fact_big", fact("fact_big", "123456789", "CNY"));
        String rendered = "环比-8.3%[fact_neg]，余额12,345.68 万元[fact_big]。";
        AuditResult audit = NumberAuditor.verifyRendered(rendered, f, 0);
        assertTrue(audit.passed(), () -> audit.violations().toString());
        assertEquals(2, audit.matchedNumbers());
    }

    @Test
    void toleranceIsHalfLastDisplayedDigit() {
        // 12,345.68 万元 = 123,456,800；与真值 123,456,789 相差 11 元 < 容差 50 元 → 一致
        // 但若真值差 100 元则超容差 → 不一致
        Map<String, FactRecord> f = Map.of("fact_x", fact("fact_x", "123456700", "CNY"));
        AuditResult audit = NumberAuditor.verifyRendered("余额12,345.68 万元[fact_x]。", f, 0);
        assertFalse(audit.passed());
    }
}
