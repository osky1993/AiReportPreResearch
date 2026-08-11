package com.treasury.nl2sql.report.domain;

import java.time.LocalDateTime;

/**
 * @param stepId 每次阶段执行的唯一自增 ID（与 runId + phase + attempt 联合定位一个步骤尝试）
 * @param runId 关联 report_run 主体，串联全链路恢复与终态聚合
 * @param phase 当前步骤名（与 Phase 枚举对应，数据库以字符串保存）
 * @param attempt 步骤尝试次数；同一失败后重试递增，历史尝试完整保留
 * @param status 步骤状态文本（成功/失败/进行中），用于重跑与 UI 呈现
 * @param inputJson 步骤输入快照（JSON）——失败复现与对账的主要证据
 * @param outputJson 步骤输出快照（JSON）——发布与重放复核依据
 * @param errorText 失败详情（含 POLICY/EXCEPTION 前缀语义）
 * @param startedAt 步骤启动时间
 * @param finishedAt 步骤完成时间，失败与成功都记录（用于耗时与 SLA）
 */
public record ReportStep(
        long stepId,
        long runId,
        String phase,
        int attempt,
        String status,
        String inputJson,
        String outputJson,
        String errorText,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {}
