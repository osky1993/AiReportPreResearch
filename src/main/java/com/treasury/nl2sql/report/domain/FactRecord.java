package com.treasury.nl2sql.report.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 事实记录契约（ideaV2 两大数据契约之二）：报告可引用的最小事实单元。
 * 由 ④（程序，非 LLM）构建；⑤ 的正文数值只能以 {{factKey}} 占位符引用它，
 * ⑥ 逐一核对文中数字与所引 fact 一致（唯一发布硬门禁）。
 *
 * @param factType BASE=取数事实 / DERIVED=程序派生（环比、净流入等）
 * @param metricVersion 取数时使用的指标定义版本（run 快照固化；DERIVED 记其结果指标的版本；null=未固化存量 run）
 * @param displayValue 程序统一渲染的展示串（如 "280.00 万元"、"+12.5%"），替换占位符时原样写入正文
 * @param dimensions 维度取值（Phase03 填充，如 {"currency":"USD"}）；null=非维度事实（落库 '{}'，回读仍 null）
 * @param sqlText 实际执行的 SQL（DERIVED 为 null）
 * @param derivedFrom DERIVED 时的来源 factKey（逗号分隔）
 * @param factKey 事实主键（审计/引用/回读核对依据）
 * @param metricId 指标定义 id
 * @param metricVersion 指标版本（run 运行时快照；null 表示旧 run 未固化）
 * @param metricName 指标展示名
 * @param chapterId 归属章节
 * @param factType BASE/DERIVED
 * @param value 事实标准值
 * @param unit 单位（CNY/万元/percent 等）
 * @param periodLabel 周期标签
 * @param specJson 查询 spec 快照（JSON）
 * @param sqlHash SQL 执行 hash（防篡改与同源重放）
 * @param resultHash 结果集 hash（重放一致性）
 * @param qualityStatus PASSED 或 BLOCKED 子态
 * @param qualityNote 失败/异常短语
 */
public record FactRecord(
        String factKey,
        String metricId,
        Integer metricVersion,
        String metricName,
        String chapterId,
        String factType,
        BigDecimal value,
        String unit,
        String displayValue,
        String periodLabel,
        Map<String, String> dimensions,
        String specJson,
        String sqlText,
        String sqlHash,
        String resultHash,
        String derivedFrom,
        String qualityStatus,
        String qualityNote) {

    public static final String TYPE_BASE = "BASE";
    /** 程序派生/比较类型事实（环比、同比、贡献、衍生净流入等）。 */
    public static final String TYPE_DERIVED = "DERIVED";
    /** 核对通过口径。 */
    public static final String QUALITY_PASSED = "PASSED";
}
