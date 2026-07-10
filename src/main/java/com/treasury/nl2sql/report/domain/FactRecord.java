package com.treasury.nl2sql.report.domain;

import java.math.BigDecimal;

/**
 * 事实记录契约（ideaV2 两大数据契约之二）：报告可引用的最小事实单元。
 * 由 ④（程序，非 LLM）构建；⑤ 的正文数值只能以 {{factKey}} 占位符引用它，
 * ⑥ 逐一核对文中数字与所引 fact 一致（唯一发布硬门禁）。
 *
 * @param factType BASE=取数事实 / DERIVED=程序派生（环比、净流入等）
 * @param metricVersion 取数时使用的指标定义版本（run 快照固化；DERIVED 记其结果指标的版本；null=未固化存量 run）
 * @param displayValue 程序统一渲染的展示串（如 "280.00 万元"、"+12.5%"），替换占位符时原样写入正文
 * @param sqlText 实际执行的 SQL（DERIVED 为 null）
 * @param derivedFrom DERIVED 时的来源 factKey（逗号分隔）
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
        String specJson,
        String sqlText,
        String sqlHash,
        String resultHash,
        String derivedFrom,
        String qualityStatus,
        String qualityNote) {

    public static final String TYPE_BASE = "BASE";
    public static final String TYPE_DERIVED = "DERIVED";
    public static final String QUALITY_PASSED = "PASSED";
}
