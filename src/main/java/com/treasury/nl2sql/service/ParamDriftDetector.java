package com.treasury.nl2sql.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HIT 参数漂移检测：比对「命中资产的原问法」与「当前问题」中的数值参数是否一致。
 *
 * <p>动机：口径召回是语义相似度，近义改写（只换日期/阈值）的相似度足以越过 tau-hit——
 * 「7月31日」的问题可能命中「6月30日」的资产并按旧参数直取出数。本检测器是纯程序防线：
 * 抽取两侧问法中的全部阿拉伯数字串做<b>多重集</b>比对（保留出现次数、忽略顺序），
 * 任何一侧有对方没有的数值即判定漂移，由编排层降档为澄清、交人确认。
 *
 * <p>已知局限（保守面）：中文数字（「三个」「五亿」）不抽取——两侧都写中文数字时比不出差异；
 * 但常见漂移形态（换日期、换阈值、换 top-N）都是阿拉伯数字，已覆盖主要风险。
 * 误报面：数字顺序无关、次数敏感（「6月6日」vs「6月」会因次数不同判漂移——宁可多问一次）。
 */
public final class ParamDriftDetector {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private ParamDriftDetector() {}

    /** 漂移判定结果：assetOnly=仅资产问法出现的数值；questionOnly=仅当前问题出现的数值。 */
    public record Drift(boolean drifted, List<String> assetOnly, List<String> questionOnly) {}

    /** 抽取问法中全部阿拉伯数字串（按出现顺序，允许重复）。null 安全。 */
    public static List<String> extract(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) return out;
        Matcher m = DIGITS.matcher(question);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 多重集差分：两侧各自独有的数值（保留次数语义）。两侧都为空 → 不漂移。 */
    public static Drift diff(String assetQuestion, String question) {
        Map<String, Integer> assetCount = count(extract(assetQuestion));
        Map<String, Integer> questionCount = count(extract(question));
        List<String> assetOnly = surplus(assetCount, questionCount);
        List<String> questionOnly = surplus(questionCount, assetCount);
        return new Drift(!assetOnly.isEmpty() || !questionOnly.isEmpty(), assetOnly, questionOnly);
    }

    private static Map<String, Integer> count(List<String> tokens) {
        Map<String, Integer> c = new HashMap<>();
        for (String t : tokens) c.merge(t, 1, Integer::sum);
        return c;
    }

    /** a 相对 b 的富余项（含次数：a 出现 2 次、b 出现 1 次 → 富余 1 个）。 */
    private static List<String> surplus(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> out = new ArrayList<>();
        a.forEach((token, n) -> {
            int extra = n - b.getOrDefault(token, 0);
            for (int i = 0; i < extra; i++) out.add(token);
        });
        out.sort(null);
        return out;
    }
}
