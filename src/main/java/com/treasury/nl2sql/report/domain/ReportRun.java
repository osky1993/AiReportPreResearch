package com.treasury.nl2sql.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * report_run 表的落库行（最小运行状态）。
 * @param templateVersion 命中模板的固化版本号（① 落大纲时锁定；resume/重跑一律用固化版本，禁止悄悄追新版）
 * @param metricVersionsJson 指标版本快照 JSON {metricId: version}（卡点1 确认时固化，含派生操作数；
 *                           ②③④ 与 resume 一律按快照版本取指标定义；null=Phase02 前存量 run 未固化）
 */
public record ReportRun(
        long runId,
        String requestText,
        String templateId,
        Integer templateVersion,
        String metricVersionsJson,
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
