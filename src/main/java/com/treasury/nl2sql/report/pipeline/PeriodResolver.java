package com.treasury.nl2sql.report.pipeline;

import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告期解析：ISO 周标签（如 "2026-W26"）→ 具体日期窗口 [周一, 周日]。
 * ① 的 LLM 只输出标签，日期窗口一律由本类推导——LLM 不产日期，消灭一类幻觉。
 * 解析不了 → PolicyException（失败关闭），当前仅支持 ISO 周（月报/季报后置）。
 */
public final class PeriodResolver {

    private static final Pattern ISO_WEEK = Pattern.compile("^(\\d{4})-W(\\d{2})$");

    private PeriodResolver() {}

    /** 一个已解析的周期窗口（闭区间）。 */
    public record Window(String label, LocalDate start, LocalDate end) {}

    public static Window resolve(String label) {
        if (label == null) {
            throw new PolicyException("报告期标签缺失（期望如 2026-W26 的 ISO 周标签）");
        }
        Matcher m = ISO_WEEK.matcher(label.trim());
        if (!m.matches()) {
            throw new PolicyException("无法解析报告期标签「" + label + "」：当前仅支持 ISO 周（如 2026-W26），月报/季报暂未支持");
        }
        int year = Integer.parseInt(m.group(1));
        int week = Integer.parseInt(m.group(2));
        LocalDate monday;
        try {
            // ISO 8601：1 月 4 日必落在第 1 周；以其为锚定再调周数与星期
            monday = LocalDate.of(year, 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                    .with(ChronoField.DAY_OF_WEEK, 1);
        } catch (Exception e) {
            throw new PolicyException("报告期标签「" + label + "」不是合法的 ISO 周: " + e.getMessage());
        }
        // 53 周防御：52 周年份的 W53 会被 java.time 平滑调整，须回验标签一致
        if (!labelOf(monday).equals(year + "-W" + String.format("%02d", week))) {
            throw new PolicyException("报告期标签「" + label + "」在该年份不存在（该年无第 " + week + " 周）");
        }
        return new Window(labelOf(monday), monday, monday.plusDays(6));
    }

    /** 对比期 = 上一个 ISO 周（环比基期）。 */
    public static Window previous(Window w) {
        LocalDate monday = w.start().minusWeeks(1);
        return new Window(labelOf(monday), monday, monday.plusDays(6));
    }

    private static String labelOf(LocalDate anyDayOfWeek) {
        int y = anyDayOfWeek.get(IsoFields.WEEK_BASED_YEAR);
        int w = anyDayOfWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return y + "-W" + String.format("%02d", w);
    }
}
