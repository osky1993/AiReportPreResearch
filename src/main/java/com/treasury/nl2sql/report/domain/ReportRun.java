package com.treasury.nl2sql.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * report_run 表的落库行（最小运行状态）。
 * @param templateVersion 命中模板的固化版本号（① 落大纲时锁定；resume/重跑一律用固化版本，禁止悄悄追新版）
 */
public record ReportRun(
        long runId,
        String requestText,
        String templateId,
        Integer templateVersion,
        String periodLabel,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate compareStart,
        LocalDate compareEnd,
        String status,
        String phase,
        String outlineJson,
        String reportMd,
        String auditJson,
        String blockedReason,
        String outlineApprovedBy,
        LocalDateTime outlineApprovedAt,
        String publishApprovedBy,
        LocalDateTime publishApprovedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
