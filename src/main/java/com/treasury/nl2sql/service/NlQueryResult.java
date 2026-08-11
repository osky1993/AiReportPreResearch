package com.treasury.nl2sql.service;

import com.treasury.nl2sql.ir.Mql;

import java.util.List;
import java.util.Map;

/**
 * 一次 NL→MQL→SQL→执行结果的可观测载体。
 *
 * <p>该记录用于端到端日志和前端展示，覆盖从自然语言问题、生成 AST、落地 SQL、
 * 查询结果到告警/失败原因的所有中间产物。关键约束：
 * <ul>
 *   <li>不承载控制状态（状态机语义由 {@link AssistedResponse} 承担）。</li>
 *   <li>成功与失败都应完整记录，便于回放和指标复盘。</li>
 *   <li>任何上层业务“是否允许继续”判断以 {@code success} + 错误列表/clarifyReason 为依据。</li>
 * </ul>
 */
public record NlQueryResult(
        /**
         * 用户输入问题，作为 trace 的首段上下文。多轮追踪时用于判定前后上下文污染。
         */
        String question,
        /**
         * 解析/生成得到的 MQL，空值意味着语义生成失败或中断。
         */
        Mql mql,
        /**
         * 编译后的 SQL。用于排障、安全审计和结果复现。
         */
        String sql,
        /**
         * 执行返回行。成功路径通常非空；失败或拒答可为空。
         */
        List<Map<String, Object>> rows,
        /**
         * 聚合错误信息：翻译失败、编译失败、执行失败时会写入；空列表表示无错误。
         */
        List<String> errors,
        /**
         * 生成修正/重写轮次统计；通常在 LLM 自愈链路中递增，用于诊断可控性。
         */
        int fixRounds,
        /**
         * 是否成功返回可用结果。仅凭 success=false 并不足以判定是否可澄清，需结合 clarifyReason。
         */
        boolean success,
        /**
         * 警告集合（非致命）。例如 SQL 可执行但结果偏差、边界回退、兼容性警告等。
         */
        List<String> warnings,
        /**
         * 用户可补齐的问题方向；当模型无法在本体系回答时（而非纯技术故障）给出引导。
         * 非空表示应转向 CLARIFY 分支；技术异常通常应通过 errors + success 传递。
         */
        String clarifyReason
) {}
