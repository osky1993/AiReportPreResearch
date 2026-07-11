package com.treasury.nl2sql.report.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 归因记录契约（Phase05 契约3）：对一条异动 fact 的解释，**结论强度分级、无证据不升级**。
 * 候选由程序给（EventMatcher）、LLM 只挑选与措辞（纪律 11）；等级上限由证据类型服务端推导：
 * 仅 fact 证据 ≤ associated；含事件证据（EVT-n）方可 hypothesis；confirmed 永远不可由 LLM 产出——
 * 仅卡点2 审批人对单条 claim 显式勾选（confirmed_by/at 留痕）后由系统升级。
 *
 * @param evidenceRefs factKey 或 EVT-n；服务端把关 ⊆ 程序生成的候选集
 * @param narrative    解释叙述（进 ⑤ 的章节材料；数值仍走 {{fact_key}} 占位符纪律，⑥ 双检覆盖；
 *                     hypothesis 级必须含缓和措辞——CausalityAuditor 死代码兜底）
 */
public record ClaimRecord(
        String claimId,
        String anomalyFactKey,
        String attributionLevel,
        List<String> evidenceRefs,
        String narrative,
        String confirmedBy,
        LocalDateTime confirmedAt) {

    public static final String LEVEL_OBSERVED = "observed";
    public static final String LEVEL_ASSOCIATED = "associated";
    public static final String LEVEL_HYPOTHESIS = "hypothesis";
    /** 仅人工勾选可达（LLM 产出即为越权，服务端拒绝）。 */
    public static final String LEVEL_CONFIRMED = "confirmed";

    public boolean hasEventEvidence() {
        return evidenceRefs != null && evidenceRefs.stream().anyMatch(r -> r != null && r.startsWith("EVT-"));
    }
}
