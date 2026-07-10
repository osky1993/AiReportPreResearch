package com.treasury.nl2sql.report.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中文数词检测器对抗语料单测（P2-T3，TDD 先行——语料先于实现固化）。
 * 契约2 射程：中文数字**且**构成数量语义（量级/比例/单位后缀）才违规；
 * 白名单（含数字字符的普通词、序数、星期、周期表述）零误伤。
 * 取向：宁漏报不误报——误报烧掉重写轮次，漏报仍有卡点2 人工兜底。
 */
class ChineseNumeralDetectorTest {

    private static void assertHit(String text) {
        List<ChineseNumeralDetector.Hit> hits = ChineseNumeralDetector.detect(text);
        assertFalse(hits.isEmpty(), "应检出中文数量表达: " + text);
    }

    private static void assertClean(String text) {
        List<ChineseNumeralDetector.Hit> hits = ChineseNumeralDetector.detect(text);
        assertTrue(hits.isEmpty(), "不应误伤: " + text + " → " + hits);
    }

    // ---------- 违规语料（≥15 条，逐条命中） ----------

    @Test void ratio_cheng()            { assertHit("代发工资支出约占三成"); }
    @Test void ratio_chengRange()       { assertHit("七八成的流出集中在周五"); }
    @Test void multiple_fanBei()        { assertHit("大额交易笔数翻了一倍"); }
    @Test void multiple_zengZhangBei()  { assertHit("支出较上周增长两倍"); }
    @Test void amount_qianWan()         { assertHit("本周净流入约两千万"); }
    @Test void amount_geYi()            { assertHit("账户总余额约五个亿"); }
    @Test void amount_wanYuan()         { assertHit("单笔支出超过五万元"); }
    @Test void amount_baiWuShiWan()     { assertHit("净流入约一百五十万"); }
    @Test void count_baiBi()            { assertHit("本周共发生三百笔交易"); }
    @Test void discount_zhe()           { assertHit("按八折计提"); }
    @Test void vague_guoBan()           { assertHit("过半账户处于活跃状态"); }
    @Test void vague_shangBai()         { assertHit("上百笔交易待复核"); }
    @Test void vague_shuShi()           { assertHit("数十户企业完成开户"); }
    @Test void vague_chengBaiShangQian(){ assertHit("成百上千的交易涌入"); }
    @Test void vague_guoWan()           { assertHit("交易量过万笔"); }
    @Test void percent_baiFenZhi()      { assertHit("占比达百分之三十"); }
    @Test void percentPoint_liangGe()   { assertHit("利率上升两个百分点"); }
    @Test void count_yiBi()             { assertHit("仅有一笔大额交易"); }

    // ---------- 白名单语料（≥15 条，零误伤） ----------

    @Test void word_yiZhi()        { assertClean("与上周口径保持一致"); }
    @Test void word_tongYi()       { assertClean("资金统一由财务部归集管理"); }
    @Test void word_yiBan()        { assertClean("一般情况下按周结算"); }
    @Test void word_yiDing()       { assertClean("在一定程度上改善了流动性"); }
    @Test void word_yiDan()        { assertClean("账户一旦冻结即转人工处理"); }
    @Test void word_wanYi()        { assertClean("以防万一，保留应急额度"); }
    @Test void word_weiYi()        { assertClean("这是唯一的例外情形"); }
    @Test void word_jinYiBu()      { assertClean("资金归集进一步完善"); }
    @Test void word_yiZhi2()       { assertClean("走势与上月一直保持平稳"); }
    @Test void word_danYi()        { assertClean("各账户实行单一币种管理"); }
    @Test void word_shiFen()       { assertClean("本周资金面十分平稳"); }
    @Test void word_qianFenWei()   { assertClean("金额展示采用千分位分隔"); }
    @Test void word_baiFenBi()     { assertClean("增长率以百分比口径披露"); }
    @Test void ordinal_diYiZhang() { assertClean("第一章 总体情况"); }
    @Test void ordinal_diZhou()    { assertClean("第二十六周资金运行平稳"); }
    @Test void weekday_zhouYi()    { assertClean("资金计划于周一至周五执行"); }
    @Test void weekday_xingQiSan() { assertClean("对账日为星期三"); }
    @Test void plain_noNumeral()   { assertClean("本周资金面平稳，收支结构合理，无异常波动。"); }

    // ---------- Hit 定位（供审计违规文案带上下文摘录） ----------

    @Test
    void hitCarriesPositionAndText() {
        List<ChineseNumeralDetector.Hit> hits = ChineseNumeralDetector.detect("本周净流入约两千万，环比平稳");
        assertEquals(1, hits.size());
        ChineseNumeralDetector.Hit h = hits.get(0);
        assertEquals("两千万", h.text());
        assertEquals("两千万", "本周净流入约两千万，环比平稳".substring(h.start(), h.end()));
    }

    @Test
    void multipleHitsInOneDraft() {
        List<ChineseNumeralDetector.Hit> hits =
                ChineseNumeralDetector.detect("支出约占三成，其中上百笔为大额，合计约五个亿");
        assertEquals(3, hits.size());
    }
}
