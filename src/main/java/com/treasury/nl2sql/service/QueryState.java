package com.treasury.nl2sql.service;

/** 人在回路一次交互的状态。 */
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
