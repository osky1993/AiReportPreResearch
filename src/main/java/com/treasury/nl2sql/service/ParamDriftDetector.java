package com.treasury.nl2sql.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HIT 参数漂移检测器。
 *
 * <p>用于对比命中资产问法与当前用户问题中抽取到的数值参数是否一致，防止“近义语义但参数变更”导致的
 * 错误复用。该检测仅对阿拉伯数字做多重集合比对，是主流程中的防误命中校验线之一。
 *
 * <p>执行逻辑：
 * <ul>
 *   <li>从两段文本中抽取 {@code \\d+} 形式的 token。</li>
 *   <li>使用计数映射实现多重集比较（保留重复次数）。</li>
 *   <li>任何单侧富余 token 即视为漂移，并返回差异列表供日志与提示。</li>
 * </ul>
 */
public final class ParamDriftDetector {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private ParamDriftDetector() {}

    /**
     * 漂移判定返回值：记录两侧各自独有且保留次数语义的 token。
     */
    public record Drift(boolean drifted, List<String> assetOnly, List<String> questionOnly) {}

    /**
     * 抽取输入中的阿拉伯数字 token，保留出现顺序和重复。
     *
     * <p>约束：该方法仅识别 {@code 0-9} 串，不处理中文数字或单位换算。对「中文」语义改写的识别能力由
     * 上游 LLM/检索兜底机制承担。
     *
     * @param question 问题文本，允许空
     * @return token 列表（可重复、按顺序）
     */
    public static List<String> extract(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) return out;
        Matcher m = DIGITS.matcher(question);
        while (m.find()) out.add(m.group());
        return out;
    }

    /**
     * 返回两侧数值 token 的差异。
     *
     * <p>业务语义：assetOnly 非空表示历史口径有更多数值，questionOnly 非空表示当前问题新增/改动了数值。
     * 任一侧不为空都判定为漂移，需走人工澄清。
     *
     * @param assetQuestion 命中资产原始问法
     * @param question 当前用户问题
     * @return 包含对称差异和 drifted 标记的结果
     */
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

    /**
     * 计算 a 相对 b 的富余 tokens。
     * 例如 a= [1,1,2], b=[1] => 结果 [1]（说明参数 1 在 a 中多出一个）。
     * 排序后返回是为了让日志对比更稳定、便于审计比对。
     */
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
