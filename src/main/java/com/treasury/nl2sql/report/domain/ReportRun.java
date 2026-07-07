package com.treasury.nl2sql.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** report_run 表的落库行（最小运行状态）。 */
public record ReportRun(
        long runId,
        String requestText,
        String templateId,
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
