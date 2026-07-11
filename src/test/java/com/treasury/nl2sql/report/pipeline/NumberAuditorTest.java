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
        return new FactRecord(key, "m_" + key, 1, "指标" + key, "ch1", FactRecord.TYPE_BASE,
                v, unit, FactBuildStep.renderDisplay(v, unit), "2026-W26",
                null, null, null, null, null, null, FactRecord.QUALITY_PASSED, null);
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

    // ---------- 中文数词射程（P2-T4：stylePrompt 注入对抗变体，固化为回归项） ----------

    @Test
    void chineseNumeralInDraftIsCaught() {
        // 对抗变体：stylePrompt 诱导「用中文数字把金额写成两千万」——绕过阿拉伯数字扫描的老盲区
        String draft = "本周净流入约两千万，明细见{{fact_001}}。";
        List<String> v = NumberAuditor.checkDraft(draft, facts);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("两千万"));
        assertTrue(v.get(0).contains("中文数量表达"));
    }

    @Test
    void chineseRatioInDraftIsCaught() {
        List<String> v = NumberAuditor.checkDraft("代发工资约占三成，余额{{fact_001}}。", facts);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("三成"));
    }

    @Test
    void chineseNumeralInRenderedResidualIsCaught() {
        // 检查2 兜底：替换后的残余文本里出现中文数量表达同样拦截（与裸数字同罪）
        String rendered = "余额合计6,570.00 万元[fact_001]，其中过半来自代发工资。";
        AuditResult audit = NumberAuditor.verifyRendered(rendered, facts, 0);
        assertFalse(audit.passed());
        assertTrue(audit.violations().stream().anyMatch(s -> s.contains("过半")));
    }

    @Test
    void ordinaryWordsWithNumeralCharsStillPass() {
        // 白名单不误伤：普通词、序数、星期——文风正常的草稿不该被烧掉重写轮次
        String draft = "## 一、总体情况\n本周口径与上周保持一致，资金统一归集，整体十分平稳；"
                + "第 26 周计划于周一至周五执行，余额{{fact_001}}。";
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

    // ---------- 量词去重后处理（P2-T5：「4 笔笔」文风瑕疵根治） ----------

    @Test
    void duplicatedUnitAfterRefIsDeduped() {
        assertEquals("本周交易4 笔[fact_002]，环比持平。",
                NumberAuditor.dedupeUnitAfterRef("本周交易4 笔[fact_002]笔，环比持平。"));
        assertEquals("余额合计6,570.00 万元[fact_001]。",
                NumberAuditor.dedupeUnitAfterRef("余额合计6,570.00 万元[fact_001]万元。"));
    }

    @Test
    void dedupeThenVerifyStillConsistent() {
        String rendered = NumberAuditor.dedupeUnitAfterRef("交易4 笔[fact_002]笔，余额6,570.00 万元[fact_001]。");
        AuditResult audit = NumberAuditor.verifyRendered(rendered, facts, 0);
        assertTrue(audit.passed(), () -> audit.violations().toString());
        assertEquals(2, audit.matchedNumbers());
    }

    @Test
    void dedupeDoesNotTouchLegitimateText() {
        // 引用后跟的不是同一单位字 / 外币码后跟中文 / 正常行文——零改动
        String s1 = "本周交易4 笔[fact_002]交易额平稳。";
        assertEquals(s1, NumberAuditor.dedupeUnitAfterRef(s1));
        String s2 = "本期支出200.00 USD[fact_010]美元账户。";
        assertEquals(s2, NumberAuditor.dedupeUnitAfterRef(s2));
        String s3 = "余额6,570.00 万元[fact_001]，其中活期占比上升。";
        assertEquals(s3, NumberAuditor.dedupeUnitAfterRef(s3));
    }

    // ---------- 外币指标（GF 演练 run#22 暴露的渲染器/审计器格式对齐回归项） ----------

    @Test
    void foreignCurrencyFactRendersAndVerifiesConsistently() {
        Map<String, FactRecord> fx = Map.of(
                "fact_010", fact("fact_010", "200", "USD"),
                "fact_011", fact("fact_011", "40000", "USD"));
        assertEquals("200.00 USD", fx.get("fact_010").displayValue());
        assertEquals("40,000.00 USD", fx.get("fact_011").displayValue());

        String rendered = NumberAuditor.substitute("本期{{fact_010}}，上期{{fact_011}}。", fx);
        AuditResult audit = NumberAuditor.verifyRendered(rendered, fx, 0);
        assertTrue(audit.passed(), "外币「数值 USD[fact]」必须被回读核对而非判为裸数字: " + audit.violations());
        assertEquals(2, audit.matchedNumbers());
    }

    @Test
    void tamperedForeignCurrencyValueIsCaught() {
        Map<String, FactRecord> fx = Map.of("fact_010", fact("fact_010", "200", "USD"));
        AuditResult audit = NumberAuditor.verifyRendered("本期220.00 USD[fact_010]。", fx, 0);
        assertFalse(audit.passed(), "外币数值被篡改必须被拦");
    }
}
