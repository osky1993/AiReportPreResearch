package com.treasury.nl2sql.service;

/**
 * 人在回路一次交互的状态（辅助状态机，承接 /api/query 的 HITL 交互）。
 * 该枚举不承载进度，只描述单次请求当前可见的业务态：
 * <ul>
 *   <li>HIT: 口径已回填，用户只需确认否决；后续可直接复用或更新资产。</li>
 *   <li>GENERATED: 新增口径待人工确认；通常对应本轮 LLM 结果。</li>
 *   <li>CLARIFY: 模型拒答或规则未清晰命中时走补充问题路径。</li>
 *   <li>FAILED: 解析/编译/执行及重试都失败，交由人工兜底。</li>
 * </ul>
 */
public enum QueryState {
    /** 命中已核验口径：直取复用资产 MQL（不调 LLM），等人确认/否决。 */
    HIT,
    /** 新生成：LLM 生成成功，等人核验采纳（沉淀）或驳回。 */
    GENERATED,
    /** 需澄清：候选口径待确认，或模型拒答需用户补充后重问。 */
    CLARIFY,
    /** 失败：机械错误（解析/编译/执行）在自愈耗尽后仍未成功。 */
    FAILED
}
