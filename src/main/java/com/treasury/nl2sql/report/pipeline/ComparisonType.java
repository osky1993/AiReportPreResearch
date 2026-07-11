package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.MetricQuerySpec;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 章节比较类型的单一权威定义：token（模板/大纲声明用）→ 取数用途（spec purpose）→
 * fact 后缀 → 中文措辞 → 允许的周期粒度矩阵。四处消费共用本枚举，规则只改这一处：
 * TemplateValidator（校验矩阵）、SpecResolveStep（窗口需求）、FactBuildStep（配对与命名）、WriteStep（措辞）。
 *
 * wow/mom/qoq 共享 purpose=COMPARE——语义都是「与上一同粒度周期比」，窗口由
 * {@link PeriodResolver#previous}推导，且这正是既有评测期望值的匹配键（不可漂移）；
 * 同比独占 COMPARE_YOY，窗口由 {@link PeriodResolver#sameLastYear} 推导。
 */
public enum ComparisonType {

    WEEK_OVER_WEEK("week_over_week", MetricQuerySpec.PURPOSE_COMPARE, "_wow", "环比",
            Set.of(PeriodResolver.TYPE_WEEK)),
    MONTH_OVER_MONTH("month_over_month", MetricQuerySpec.PURPOSE_COMPARE, "_mom", "环比",
            Set.of(PeriodResolver.TYPE_MONTH)),
    QUARTER_OVER_QUARTER("quarter_over_quarter", MetricQuerySpec.PURPOSE_COMPARE, "_qoq", "环比",
            Set.of(PeriodResolver.TYPE_QUARTER)),
    YEAR_OVER_YEAR("year_over_year", MetricQuerySpec.PURPOSE_COMPARE_YOY, "_yoy", "同比",
            Set.of(PeriodResolver.TYPE_MONTH, PeriodResolver.TYPE_QUARTER));

    private final String token;
    private final String purpose;
    private final String factSuffix;
    private final String label;
    private final Set<String> allowedPeriodTypes;

    ComparisonType(String token, String purpose, String factSuffix, String label, Set<String> allowedPeriodTypes) {
        this.token = token;
        this.purpose = purpose;
        this.factSuffix = factSuffix;
        this.label = label;
        this.allowedPeriodTypes = allowedPeriodTypes;
    }

    public String token() { return token; }

    /** 该比较的取数用途（MetricQuerySpec purpose：COMPARE / COMPARE_YOY）。 */
    public String purpose() { return purpose; }

    /** 比较 fact 的 key 后缀（_wow/_mom/_qoq/_yoy）。 */
    public String factSuffix() { return factSuffix; }

    /** 中文措辞（环比/同比）——fact 名与 ⑤ 的事实 note 用。 */
    public String label() { return label; }

    /** 该比较是否适用于某周期粒度（WEEK/MONTH/QUARTER）——校验矩阵。 */
    public boolean allows(String periodType) { return allowedPeriodTypes.contains(periodType); }

    /** 校验用：未知 token → empty，由校验器报字段级错误。 */
    public static Optional<ComparisonType> of(String token) {
        return Arrays.stream(values()).filter(t -> t.token.equals(token)).findFirst();
    }

    /** 管线用：未知 token 走到运行期说明资产校验被绕过 → 失败关闭。 */
    public static ComparisonType require(String token) {
        return of(token).orElseThrow(() -> new PolicyException("未知的章节比较类型: " + token));
    }

    /** 全部合法 token（校验错误消息用）。 */
    public static String legalTokens() {
        return Arrays.stream(values()).map(ComparisonType::token).collect(Collectors.joining("/"));
    }
}
