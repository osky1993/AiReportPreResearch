package com.treasury.nl2sql.report.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中文数词检测器对抗单测（P2-T3）。验证中文语义数字与普通中文词汇分流是否稳定：
 * 中文数量表达触发命中（量级/比例/单位后缀），包含“仅文本含义”不包含数量语义的短语不触发。
 * 通过白名单语料守住误伤边界，防止正常表达被当作违规数字。
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

    /**
     * 输入：比例型中文数量表达。
     * 预期：触发中文数词检测，避免数字词语绕过数字禁写。
     */
    @Test
    void ratio_cheng() {
        assertHit("代发工资支出约占三成");
    }

    /**
     * 输入：非标准比例区间中文数字。
     * 预期：依然命中，覆盖语义变体。
     */
    @Test
    void ratio_chengRange() {
        assertHit("七八成的流出集中在周五");
    }

    /**
     * 输入：倍数语义「翻倍」。
     * 预期：应命中倍数类违规，防止「增长 1 倍」这类表述进入正文。
     */
    @Test
    void multiple_fanBei() {
        assertHit("大额交易笔数翻了一倍");
    }

    /**
     * 输入：倍数语义「增长两倍」。
     * 预期：命中中文倍数词，拒绝放入 ⑤ 的数字自由文本。
     */
    @Test
    void multiple_zengZhangBei() {
        assertHit("支出较上周增长两倍");
    }

    /**
     * 输入：中文大额口径「两千万」。
     * 预期：命中金额量级表达。
     */
    @Test
    void amount_qianWan() {
        assertHit("本周净流入约两千万");
    }

    /**
     * 输入：中文亿级金额口径「五个亿」。
     * 预期：命中金额量级表达。
     */
    @Test
    void amount_geYi() {
        assertHit("账户总余额约五个亿");
    }

    /**
     * 输入：中文金额 + 量级后缀。
     * 预期：命中金额中文数字表达。
     */
    @Test
    void amount_wanYuan() {
        assertHit("单笔支出超过五万元");
    }

    /**
     * 输入：中文精确金额写法「一百五十万」。
     * 预期：命中，保障小写中文数字不漏报。
     */
    @Test
    void amount_baiWuShiWan() {
        assertHit("净流入约一百五十万");
    }

    /**
     * 输入：中文计数「三百笔」。
     * 预期：命中数词数量表达。
     */
    @Test
    void count_baiBi() {
        assertHit("本周共发生三百笔交易");
    }

    /**
     * 输入：折扣比例「八折」。
     * 预期：命中百分位类中文数字，避免被误当作纯语义表达。
     */
    @Test
    void discount_zhe() {
        assertHit("按八折计提");
    }

    /**
     * 输入：模糊数字比例「过半」。
     * 预期：命中非精确数字但有数值语义的比例表达。
     */
    @Test
    void vague_guoBan() {
        assertHit("过半账户处于活跃状态");
    }

    /**
     * 输入：中文数量近似语义「上百」。
     * 预期：命中数量规模语义。
     */
    @Test
    void vague_shangBai() {
        assertHit("上百笔交易待复核");
    }

    /**
     * 输入：中文模糊数「数十户」。
     * 预期：命中非精确但可判定数量语义的表达。
     */
    @Test
    void vague_shuShi() {
        assertHit("数十户企业完成开户");
    }

    /**
     * 输入：中文组合规模表达「成百上千」。
     * 预期：命中高位量级规模词语。
     */
    @Test
    void vague_chengBaiShangQian() {
        assertHit("成百上千的交易涌入");
    }

    /**
     * 输入：中文规模阈值「过万」。
     * 预期：命中万级规模语义。
     */
    @Test
    void vague_guoWan() {
        assertHit("交易量过万笔");
    }

    /**
     * 输入：百分位中文写法「百分之三十」。
     * 预期：命中比例类中文数词。
     */
    @Test
    void percent_baiFenZhi() {
        assertHit("占比达百分之三十");
    }

    /**
     * 输入：百分点写法「两个百分点」。
     * 预期：命中细粒度比例表达。
     */
    @Test
    void percentPoint_liangGe() {
        assertHit("利率上升两个百分点");
    }

    /**
     * 输入：独立小数形式「一笔」。
     * 预期：命中中文计量词条。
     */
    @Test
    void count_yiBi() {
        assertHit("仅有一笔大额交易");
    }

    // ---------- 白名单语料（≥15 条，零误伤） ----------

    /**
     * 输入：「一致」语义语境，不含真实数值。
     * 预期：不命中，确保业务口径表达不过率。
     */
    @Test
    void word_yiZhi() {
        assertClean("与上周口径保持一致");
    }

    /**
     * 输入：「统一」语境。
     * 预期：不命中，避免误判普通形容词。
     */
    @Test
    void word_tongYi() {
        assertClean("资金统一由财务部归集管理");
    }

    /**
     * 输入：常见副词「一般」。
     * 预期：不命中，保护叙述常态。
     */
    @Test
    void word_yiBan() {
        assertClean("一般情况下按周结算");
    }

    /**
     * 输入：「一定」表达，不应作为数值语义处理。
     * 预期：不命中。
     */
    @Test
    void word_yiDing() {
        assertClean("在一定程度上改善了流动性");
    }

    /**
     * 输入：动词「冻结即转」语义不含数值。
     * 预期：不命中，维持白名单。
     */
    @Test
    void word_yiDan() {
        assertClean("账户一旦冻结即转人工处理");
    }

    /**
     * 输入：「以防万一」作为安全用语。
     * 预期：不命中，避免误伤固定短语。
     */
    @Test
    void word_wanYi() {
        assertClean("以防万一，保留应急额度");
    }

    /**
     * 输入：「唯 一」语义。
     * 预期：不命中，减少误报。
     */
    @Test
    void word_weiYi() {
        assertClean("这是唯一的例外情形");
    }

    /**
     * 输入：「进一步」语义。
     * 预期：不命中，避免普通程度副词误报。
     */
    @Test
    void word_jinYiBu() {
        assertClean("资金归集进一步完善");
    }

    /**
     * 输入：「一直保持平稳」语义。
     * 预期：不命中，保留趋势判断表达。
     */
    @Test
    void word_yiZhi2() {
        assertClean("走势与上月一直保持平稳");
    }

    /**
     * 输入：「单一」量化词语义。
     * 预期：不命中，排除误伤。
     */
    @Test
    void word_danYi() {
        assertClean("各账户实行单一币种管理");
    }

    /**
     * 输入：形容词「十分」用于状态描述。
     * 预期：不命中，避免语义副词误拦截。
     */
    @Test
    void word_shiFen() {
        assertClean("本周资金面十分平稳");
    }

    /**
     * 输入：带“千分位”语义短语。
     * 预期：不命中，保护数字展示说明文字。
     */
    @Test
    void word_qianFenWei() {
        assertClean("金额展示采用千分位分隔");
    }

    /**
     * 输入：术语「百分比」本身。
     * 预期：不命中，非具体比例数字。
     */
    @Test
    void word_baiFenBi() {
        assertClean("增长率以百分比口径披露");
    }

    /**
     * 输入：序数词「第一章」。
     * 预期：不命中，避免章节序号误伤。
     */
    @Test
    void ordinal_diYiZhang() {
        assertClean("第一章 总体情况");
    }

    /**
     * 输入：序数 + 周次语义「第二十六周」。
     * 预期：不命中，确保目录/口径语句不误报。
     */
    @Test
    void ordinal_diZhou() {
        assertClean("第二十六周资金运行平稳");
    }

    /**
     * 输入：星期区间写法。
     * 预期：不命中，避免日程表达误报。
     */
    @Test
    void weekday_zhouYi() {
        assertClean("资金计划于周一至周五执行");
    }

    /**
     * 输入：星期中文数字写法「星期三」。
     * 预期：不命中，普通日期词语不属于数值禁写。
     */
    @Test
    void weekday_xingQiSan() {
        assertClean("对账日为星期三");
    }

    /**
     * 输入：平滑叙事文本。
     * 预期：无误报，保证写稿可读性。
     */
    @Test
    void plain_noNumeral() {
        assertClean("本周资金面平稳，收支结构合理，无异常波动。");
    }

    /**
     * 输入：阿拉伯数字 + 中文单位。
     * 预期：不交给中文数词检测器，归属 BARE_NUMBER 管道处理。
     */
    @Test
    void arabicWithUnit() {
        assertClean("余额合计 6,570.00 万元");
    }

    /**
     * 输入：阿拉伯数字 + 亿字单位。
     * 预期：不命中中文数词，交由裸数字路径核查。
     */
    @Test
    void arabicWithYi() {
        assertClean("规模突破 1.2 亿元");
    }

    // ---------- Hit 定位（供审计违规文案带上下文摘录） ----------

    /**
     * 输入：命中语句包含中文数词与普通文本。
     * 预期：拿到唯一命中文本片段与下标，支持告警定位。
     */
    @Test
    void hitCarriesPositionAndText() {
        List<ChineseNumeralDetector.Hit> hits = ChineseNumeralDetector.detect("本周净流入约两千万，环比平稳");
        assertEquals(1, hits.size());
        ChineseNumeralDetector.Hit h = hits.get(0);
        assertEquals("两千万", h.text());
        assertEquals("两千万", "本周净流入约两千万，环比平稳".substring(h.start(), h.end()));
    }

    /**
     * 输入：单文本含三个中文数词片段。
     * 预期：返回 3 条命中，覆盖多命中聚合场景。
     */
    @Test
    void multipleHitsInOneDraft() {
        List<ChineseNumeralDetector.Hit> hits =
                ChineseNumeralDetector.detect("支出约占三成，其中上百笔为大额，合计约五个亿");
        assertEquals(3, hits.size());
    }
}
