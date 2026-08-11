package com.treasury.nl2sql.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * report_run 表的落库行（最小运行状态）。
 * @param runId 运行主键
 * @param requestText 用户原始请求文本
 * @param templateId 选定模板 ID
 * @param templateVersion 命中模板的固化版本号（① 落大纲时锁定；resume/重跑一律用固化版本，禁止悄悄追新版）
 * @param metricVersionsJson 指标版本快照 JSON {metricId: version}（卡点1 确认时固化，含派生操作数；
 *                           ②③④ 与 resume 一律按快照版本取指标定义；null=Phase02 前存量 run 未固化）
 * @param periodLabel 报告期标签（如 2026-M06）
 * @param periodStart 报告期开始（闭区间）
 * @param periodEnd 报告期结束（闭区间）
 * @param compareStart 环比基期起（与 periodLabel 对应的上期）
 * @param compareEnd 环比基期结束（与 periodLabel 对应的上期）
 * @param yoyStart 同比基期起（去年同期；模板未声明同比 → null）
 * @param yoyEnd 同比基期结束（去年同期；模板未声明同比 → null）
 * @param status 当前 run 状态（AWAITING... / RUNNING / BLOCKED / PUBLISHED / REJECTED）
 * @param phase 当前执行阶段（Phase 枚举值）
 * @param outlineJson 大纲快照（卡点1 锁定，resume 由此恢复）
 * @param chartsJson 图表声明快照（含绑定定义）
 * @param reportMd 草稿/发布稿正文（Markdown）
 * @param auditJson ⑥ 审计明细快照（一致率与详细明细）
 * @param blockedReason 失败关闭原因；要求带 [POLICY]/[EXCEPTION]
 * @param outlineApprovedBy 卡点1 审批人（签署口径确认）
 * @param outlineApprovedAt 卡点1 审批时间
 * @param publishApprovedBy 卡点2 审批人
 * @param publishApprovedAt 卡点2 审批时间
 * @param createdAt 记录创建时间
 * @param updatedAt 记录更新时间
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
        LocalDate yoyStart,
        LocalDate yoyEnd,
        String status,
        String phase,
        String outlineJson,
        String chartsJson,
        String reportMd,
        String auditJson,
        String blockedReason,
        String outlineApprovedBy,
        LocalDateTime outlineApprovedAt,
        String publishApprovedBy,
        LocalDateTime publishApprovedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
