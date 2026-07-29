package com.treasury.nl2sql.service;

/**
 * 人在回路的「信封」响应体：把有状态的交互层与纯生成产物分层。
 *
 * <p>刻意不把状态塞进 {@link NlQueryResult}——后者是 {@code EvalService} 的评估契约，
 * 命中/召回短路绝不能污染纯 LLM 生成质量的度量。这里用 {@link #trace} 内嵌复用它承载 mql/sql/rows。
 */
public record AssistedResponse(
        QueryState state,
        String source,             // "CALIBER"（命中/复用口径） | "LLM"（新生成） | "NONE"
        double confidence,         // 召回分数；纯 LLM 生成为 0
        Long assetId,              // HIT/CANDIDATE 命中的资产 id，可空
        String matchedQuestion,    // 命中资产的原始问法（供人工比对参数漂移），可空
        String matchedDescription, // 命中资产的中文口径描述（核验时 AI 反翻译固化；旧资产/生成失败为 null）
        String clarifyPrompt,      // CLARIFY 时的反问语，可空
        NlQueryResult trace        // 生成/执行 trace（HIT 时为合成结果），CLARIFY/无结果时可空
) {

    public static AssistedResponse hit(long assetId, String matchedQuestion, String matchedDescription,
                                       double score, NlQueryResult trace) {
        return new AssistedResponse(QueryState.HIT, "CALIBER", score, assetId, matchedQuestion,
                matchedDescription, null, trace);
    }

    public static AssistedResponse generated(NlQueryResult trace) {
        return new AssistedResponse(QueryState.GENERATED, "LLM", 0, null, null, null, null, trace);
    }

    public static AssistedResponse failed(NlQueryResult trace) {
        return new AssistedResponse(QueryState.FAILED, "LLM", 0, null, null, null, null, trace);
    }

    /** 候选口径待确认（召回落在中间档）。 */
    public static AssistedResponse clarifyCandidate(long assetId, String matchedQuestion, String matchedDescription,
                                                    double score, String prompt) {
        return new AssistedResponse(QueryState.CLARIFY, "CALIBER", score, assetId, matchedQuestion,
                matchedDescription, prompt, null);
    }

    /** 模型拒答，需用户补充后重问。 */
    public static AssistedResponse clarifyReject(String prompt, NlQueryResult trace) {
        return new AssistedResponse(QueryState.CLARIFY, "LLM", 0, null, null, null, prompt, trace);
    }
}
