package com.treasury.nl2sql.service;

/**
 * 有状态交互响应（HIT/候选/生成/澄清/失败）与纯执行追踪的边界承载体。
 *
 * <p>核心目标是让控制链路（是否需人工确认、是否可直接复用资产）和执行链路（NL→MQL→SQL→rows）
 * 解耦，避免把「是否命中」混入评估/日志的统一产物。该对象用于：
 * <ul>
 *   <li>汇总每次用户问题的语义命中状态和来源。</li>
 *   <li>在 HIT/候选时带出口径元信息，支持参数漂移检测和人工复核。</li>
 *   <li>在失败/澄清分支保留可追溯 trace，便于复盘但不误导自动决策。</li>
 * </ul>
 * 失败/降级边界：CLARIFY 与 FAILED 是两种不同状态。前者可继续对话补齐上下文，后者通常终止当前轮次。
 */
public record AssistedResponse(
        /**
         * 查询状态机状态，用于控制主流程下一步。
         */
        QueryState state,
        /**
         * 产生该响应的来源：CALIBER（复用资产）或 LLM（新生成）。保留字段主要用于排障与指标分析。
         */
        String source,
        /**
         * 命中置信度。来源为 CALIBER 时有意义，LLM 失败/生成路径固定为 0，便于上报端过滤。
         */
        double confidence,
        /**
         * HIT 或候选命中的资产 id。可空，空值表示未命中或纯 LLM 路径。
         */
        Long assetId,
        /**
         * 命中资产原始问法。与当前问题一并用于参数漂移检测，减少“文字近似但参数不同”的误复用风险。
         */
        String matchedQuestion,
        /**
         * 命中资产描述（口径说明）。用于给用户和运维解释结果依据；未命中时可空。
         */
        String matchedDescription,
        /**
         * 进入 CLARIFY 时向用户展示的追问文本。空值意味着无需澄清或未触发澄清状态。
         */
        String clarifyPrompt,
        /**
         * 与一次请求执行链路绑定的 trace。对于 HIT 会包含合成 trace，FAILED/CLARIFY 可为空。
         */
        NlQueryResult trace
) {

    /**
     * 命中资产且参数一致时返回的成功响应。
     * @param assetId 命中资产主键
     * @param matchedQuestion 命中资产问法
     * @param matchedDescription 命中资产口径说明
     * @param score 命中分数，供监控和回溯分析
     * @param trace 本次命中对应的可追溯执行痕迹
     * @return HIT 响应
     */
    public static AssistedResponse hit(long assetId, String matchedQuestion, String matchedDescription,
                                       double score, NlQueryResult trace) {
        return new AssistedResponse(QueryState.HIT, "CALIBER", score, assetId, matchedQuestion,
                matchedDescription, null, trace);
    }

    /**
     * 纯 LLM 新生成路径。与数据库命中无关，主要用于“资产池无可用口径”场景。
     * @param trace 生成过程的完整执行信息
     * @return GENERATED 响应
     */
    public static AssistedResponse generated(NlQueryResult trace) {
        return new AssistedResponse(QueryState.GENERATED, "LLM", 0, null, null, null, null, trace);
    }

    /**
     * LLM 无法交付结果时的失败返回。保留 trace 用于排障和可观测性分析。
     * @param trace 失败上下文 trace，可能为空
     * @return FAILED 响应
     */
    public static AssistedResponse failed(NlQueryResult trace) {
        return new AssistedResponse(QueryState.FAILED, "LLM", 0, null, null, null, null, trace);
    }

    /**
     * 召回到候选而非直接 HIT 时，返回人工澄清分支。
     *
     * <p>调用方典型处理逻辑是：先向用户展示匹配候选，确认后可直接走资产复用。
     * 若被拒绝，则应触发新一轮问题请求或转为 LLM 生成，而不是无声降级。
     *
     * @param assetId 候选资产主键
     * @param matchedQuestion 命中资产问法
     * @param matchedDescription 命中资产口径说明
     * @param score 候选分数
     * @param prompt 告知用户的澄清问题
     * @return CLARIFY 响应
     */
    public static AssistedResponse clarifyCandidate(long assetId, String matchedQuestion, String matchedDescription,
                                                    double score, String prompt) {
        return new AssistedResponse(QueryState.CLARIFY, "CALIBER", score, assetId, matchedQuestion,
                matchedDescription, prompt, null);
    }

    /**
     * LLM 拒答或信息不足导致无法输出答案时的澄清分支。
     *
     * <p>设计上不在此处直接抛失败，而是以“可修复状态”返回，鼓励用户补充上下文后重试。
     *
     * @param prompt 提示用户补充的澄清问题
     * @param trace 可选 trace，若存在则用于日志和分析
     * @return CLARIFY 响应
     */
    public static AssistedResponse clarifyReject(String prompt, NlQueryResult trace) {
        return new AssistedResponse(QueryState.CLARIFY, "LLM", 0, null, null, null, prompt, trace);
    }
}
