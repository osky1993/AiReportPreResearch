package com.treasury.nl2sql.report.pipeline;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告期解析：周期标签 → 具体日期窗口（闭区间）。
 * 支持三种粒度：ISO 周（"2026-W26" → [周一, 周日]）、自然月（"2026-M06" → [首日, 末日]）、
 * 自然季（"2026-Q2" → [季首日, 季末日]）。
 * ① 的 LLM 只输出标签，日期窗口一律由本类推导——LLM 不产日期，消灭一类幻觉。
 * 解析不了 → PolicyException（失败关闭）；「模板是否支持该粒度」的把关在 ① 第三段（不在本类）。
 */
public final class PeriodResolver {

    private static final Pattern ISO_WEEK = Pattern.compile("^(\\d{4})-W(\\d{2})$");
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})-M(\\d{2})$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q(\\d)$");

    /** 周期粒度常量——与模板 periodTypes 声明取值一致。 */
    public static final String TYPE_WEEK = "WEEK";
    public static final String TYPE_MONTH = "MONTH";
    public static final String TYPE_QUARTER = "QUARTER";

    private PeriodResolver() {}

    /** 一个已解析的周期窗口（闭区间）。 */
    public record Window(String label, LocalDate start, LocalDate end) {}

    /**
     * 标签主解析入口。只返回可执行窗口，不允许返回 null。
     * 失败时立即抛 PolicyException，统一由上层阻断，不给下游带“空窗口”。
     */
    public static Window resolve(String label) {
        if (label == null) {
            throw new PolicyException("报告期标签缺失（期望如 2026-W26 / 2026-M06 / 2026-Q2）");
        }
        String t = label.trim();
        Matcher m = ISO_WEEK.matcher(t);
        if (m.matches()) {
            return resolveWeek(t, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = MONTH.matcher(t);
        if (m.matches()) {
            return resolveMonth(t, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = QUARTER.matcher(t);
        if (m.matches()) {
            return resolveQuarter(t, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        throw new PolicyException("无法解析报告期标签「" + label
                + "」：支持 ISO 周（2026-W26）、月（2026-M06）、季（2026-Q2）");
    }

    /** 标签的周期粒度（WEEK/MONTH/QUARTER）——模板 periodTypes 匹配把关用。 */
    public static String granularity(String label) {
        if (label != null) {
            String t = label.trim();
            if (ISO_WEEK.matcher(t).matches()) return TYPE_WEEK;
            if (MONTH.matcher(t).matches()) return TYPE_MONTH;
            if (QUARTER.matcher(t).matches()) return TYPE_QUARTER;
        }
        throw new PolicyException("无法识别报告期标签「" + label + "」的周期粒度");
    }

    /**
     * 以锚定日期推「最近一个完整周期」的标签（Gate3 预览推荐周期用）：
     * 锚定日所在周期若已整期结束（end ≤ anchor）取之，否则退上一周期。
     * 锚定日应取业务数据的最大日期而非系统当前日——演示库数据有覆盖边界，
     * 用真实 now() 推导很可能落到空数据周期。
     */
    public static String latestCompleteLabel(LocalDate anchor, String periodType) {
        Window w = switch (periodType) {
            case TYPE_WEEK -> {
                LocalDate monday = anchor.with(ChronoField.DAY_OF_WEEK, 1);
                yield new Window(weekLabelOf(monday), monday, monday.plusDays(6));
            }
            case TYPE_MONTH -> monthWindow(YearMonth.from(anchor));
            case TYPE_QUARTER -> quarterWindow(anchor);
            default -> throw new PolicyException("未知周期粒度: " + periodType);
        };
        return (w.end().isAfter(anchor) ? previous(w) : w).label();
    }

    /**
     * 计算对比期窗口：上一个同粒度周期。
     * 周期回退后不影响原始报告期定义，返回 label/start/end 三元组与原窗口同形态。
     */
    public static Window previous(Window w) {
        return switch (granularity(w.label())) {
            case TYPE_WEEK -> {
                LocalDate monday = w.start().minusWeeks(1);
                yield new Window(weekLabelOf(monday), monday, monday.plusDays(6));
            }
            case TYPE_MONTH -> monthWindow(YearMonth.from(w.start()).minusMonths(1));
            default -> quarterWindow(w.start().minusMonths(3));
        };
    }

    /**
     * 同比基期 = 去年同周期（M06→上年 M06、Q2→上年 Q2、W26→上年 W26）。
     * 周同比遇 W53 缺失年份 → PolicyException（校验矩阵禁 WEEK+同比，此为死代码兜底）。
     */
    public static Window sameLastYear(Window w) {
        String label = w.label();
        int dash = label.indexOf('-');
        int year = Integer.parseInt(label.substring(0, dash));
        return resolve((year - 1) + label.substring(dash));
    }

    /**
     * 按 ISO 周解析，先用 year/week 定位，再做 round-trip 回验。
     * round-trip 的目的是拦截一些年份无 W53 的边界输入。
     */
    private static Window resolveWeek(String label, int year, int week) {
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
        if (!weekLabelOf(monday).equals(year + "-W" + String.format("%02d", week))) {
            throw new PolicyException("报告期标签「" + label + "」在该年份不存在（该年无第 " + week + " 周）");
        }
        return new Window(weekLabelOf(monday), monday, monday.plusDays(6));
    }

    private static Window resolveMonth(String label, int year, int month) {
        if (month < 1 || month > 12) {
            throw new PolicyException("报告期标签「" + label + "」的月份不存在（合法范围 M01~M12）");
        }
        return monthWindow(YearMonth.of(year, month));
    }

    /**
     * 按自然季输入定位三个月窗口。季度越界会直接拒绝，避免将非法值静默归一。
     */
    private static Window resolveQuarter(String label, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new PolicyException("报告期标签「" + label + "」的季度不存在（合法范围 Q1~Q4）");
        }
        return quarterWindow(LocalDate.of(year, (quarter - 1) * 3 + 1, 1));
    }

    /** 月窗口生成器：月内 [首日, 末日] 且标签标准化为两位月份。 */
    private static Window monthWindow(YearMonth ym) {
        String label = ym.getYear() + "-M" + String.format("%02d", ym.getMonthValue());
        return new Window(label, ym.atDay(1), ym.atEndOfMonth());
    }

    /** 以季内任一天定位该自然季的窗口。 */
    private static Window quarterWindow(LocalDate anyDayInQuarter) {
        int q = (anyDayInQuarter.getMonthValue() - 1) / 3 + 1;
        LocalDate start = LocalDate.of(anyDayInQuarter.getYear(), (q - 1) * 3 + 1, 1);
        return new Window(anyDayInQuarter.getYear() + "-Q" + q, start, start.plusMonths(3).minusDays(1));
    }

    /** 以周内任意一天反推 ISO 标签，用于窗口与外部标签闭环一致性校验。 */
    private static String weekLabelOf(LocalDate anyDayOfWeek) {
        int y = anyDayOfWeek.get(IsoFields.WEEK_BASED_YEAR);
        int w = anyDayOfWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return y + "-W" + String.format("%02d", w);
    }
}
