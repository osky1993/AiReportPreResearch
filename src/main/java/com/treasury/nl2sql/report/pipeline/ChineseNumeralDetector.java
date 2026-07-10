package com.treasury.nl2sql.report.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文数词检测器（P2-T3，纯程序、无依赖，⑥ 审计射程补盲）。
 * 契约2「双条件判定」：中文数字**且**构成数量语义（量级/比例/单位后缀）才违规——
 * 单字数词嵌在普通词汇（一致/统一/十分…）里不触发。
 * 取向：宁漏报不误报——误报烧掉回灌重写轮次并可能把合法文风打成 BLOCKED；
 * 漏报仍有 HITL 卡点2 人工兜底（与 Phase02 前的现状相同，只是射程大幅收窄）。
 * 射程与白名单以 ChineseNumeralDetectorTest 的对抗语料为准（先于实现固化，TDD）。
 */
public final class ChineseNumeralDetector {

    private ChineseNumeralDetector() {}

    /** 一处违规命中：text 为原文片段，start/end 为原文偏移（供审计文案带上下文摘录）。 */
    public record Hit(String text, int start, int end) {}

    private static final String NUM = "[零〇一二两三四五六七八九十百千万亿]";

    /**
     * 白名单（先掩蔽再扫描）：含数字字符的普通词、序数（第X）、星期/周几。
     * 长词在前防止被短词截断；掩蔽用等长填充符保持原文偏移。
     */
    private static final Pattern WHITELIST = Pattern.compile(
            "进一步|千分位|百分比|百分点"
            + "|一致|一定|一般|统一|一起|一旦|万一|唯一|一些|一直|一贯|一律|一体|同一|单一|之一|十分"
            + "|第" + NUM + "+"
            + "|星期[一二三四五六日天]|周[一二三四五六日]");

    /** R1 模糊量级词：本身即数量语义。 */
    private static final Pattern VAGUE = Pattern.compile(
            "成百上千|成千上万|上百|上千|上万|数十|数百|数千|近百|近千|近万|过百|过千|过万|过半|近半|大半");

    /** R2 百分之X。 */
    private static final Pattern PERCENT = Pattern.compile("百分之[零〇一二两三四五六七八九十百千点]+");

    /** R3 数词序列 + 计量/比例单位后缀（长单位在前）。 */
    private static final Pattern NUM_UNIT = Pattern.compile(
            NUM + "+(?:万元|亿元|成|倍|折|元|笔|个|户|单|项|次|家|人)");

    /** R4 金额省略单位形态：数词序列长度≥2 且含量级字（两千万、一百五十）。 */
    private static final Pattern NUM_SCALE = Pattern.compile(NUM + "{2,}");

    /** 扫描文本中的中文数量表达；命中互不重叠（先命中的规则优先）。 */
    public static List<Hit> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String masked = mask(text);
        List<Hit> hits = new ArrayList<>();
        collect(hits, VAGUE.matcher(masked), text, null);
        collect(hits, PERCENT.matcher(masked), text, null);
        // R3/R4 排除「阿拉伯数字 + 中文量级单位」构型（如 6,570.00 万元）——
        // 那是合法渲染形态的单位部分，阿拉伯裸数字自有 NumberAuditor.BARE_NUMBER 负责
        collect(hits, NUM_UNIT.matcher(masked), text, m -> !followsArabicDigit(masked, m.start()));
        collect(hits, NUM_SCALE.matcher(masked), text,
                m -> m.group().matches(".*[百千万亿].*") && !followsArabicDigit(masked, m.start()));
        hits.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return dropOverlaps(hits);
    }

    /** 白名单词等长掩蔽（填充符不在任何数词/单位字符集内），保持原文偏移可回指。 */
    private static String mask(String text) {
        Matcher m = WHITELIST.matcher(text);
        StringBuilder sb = new StringBuilder(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                sb.setCharAt(i, '＿');
            }
        }
        return sb.toString();
    }

    /** 匹配起点前的首个非空白字符是否为阿拉伯数字（含千分位逗号/小数点）。 */
    private static boolean followsArabicDigit(String text, int start) {
        for (int i = start - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '　') continue;
            return (c >= '0' && c <= '9') || c == ',' || c == '.';
        }
        return false;
    }

    private interface Filter { boolean accept(Matcher m); }

    private static void collect(List<Hit> hits, Matcher m, String original, Filter filter) {
        while (m.find()) {
            if (filter != null && !filter.accept(m)) {
                continue;
            }
            hits.add(new Hit(original.substring(m.start(), m.end()), m.start(), m.end()));
        }
    }

    private static List<Hit> dropOverlaps(List<Hit> sorted) {
        List<Hit> result = new ArrayList<>();
        int lastEnd = -1;
        for (Hit h : sorted) {
            if (h.start() >= lastEnd) {
                result.add(h);
                lastEnd = h.end();
            } else if (h.end() > lastEnd) {
                lastEnd = h.end();   // 部分重叠：保留先命中者，吞掉延伸段防止拆出半截词
            }
        }
        return result;
    }
}
