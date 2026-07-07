package com.treasury.nl2sql.report.domain;

/**
 * 报告运行状态（刻意不做状态机类，编排器里 if/switch 推进）。
 * 主线：AWAITING_OUTLINE_APPROVAL → RUNNING → AWAITING_PUBLISH_APPROVAL → PUBLISHED。
 * 任一步失败关闭 → BLOCKED（blocked_reason 前缀 [POLICY]=业务性停止 / [EXCEPTION]=意外异常）。
 */
public enum RunStatus {
    /** ① 大纲已生成，等待人工确认口径（HITL 卡点1） */
    AWAITING_OUTLINE_APPROVAL,
    /** ②~⑥ 异步流水线执行中 */
    RUNNING,
    /** 失败关闭：停止并转人工，不猜测补全 */
    BLOCKED,
    /** ⑥ 审计通过，等待人工审批发布（HITL 卡点2） */
    AWAITING_PUBLISH_APPROVAL,
    /** 已签发（终态） */
    PUBLISHED,
    /** 发布审批驳回（终态） */
    REJECTED
}
