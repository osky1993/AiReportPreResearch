package com.treasury.nl2sql.report.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * ⑥ 证据审计结果（终稿渲染后回读核对；写入 report_run.audit_json 作为发布证据）。
 * consistencyRate 必须 = 100% 才允许进入发布审批。
 */
public record AuditResult(
        boolean passed,
        int totalNumbers,
        int matchedNumbers,
        int rewriteRounds,
        List<String> violations,
        List<NumberCheck> details) {

    /** 单个「文中数字 ↔ 所引 fact」核对明细。 */
    public record NumberCheck(
            String displayText,
            String factKey,
            BigDecimal expected,
            BigDecimal parsed,
            boolean ok) {}

    public double consistencyRate() {
        return totalNumbers == 0 ? 1.0 : (double) matchedNumbers / totalNumbers;
    }
}
