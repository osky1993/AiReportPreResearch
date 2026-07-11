package com.treasury.nl2sql.report.observe;

import com.treasury.nl2sql.llm.LlmClient;

/**
 * 步内 LLM 用量累加器（P6 契约2）：每个含 LLM 的步开始时 reset，调用点经 record() 过账，
 * 落痕前 drain() 取走并清零——用量以 llmUsage 段内嵌该步 output_json（不加列）。
 * ThreadLocal 承载：① 在请求线程同步跑、②~⑥ 在单一守护线程顺序跑，不存在跨线程串账。
 * 观测纪律：供应商不回 usage 时计入 unmeteredCalls——「未计量」与 0 必须可区分。
 */
public final class LlmUsageTally {

    /** 一步内的用量汇总；calls 含未计量调用，metered 口径只看 tokens 两项。 */
    public record Tally(int calls, int unmeteredCalls, long promptTokens, long completionTokens) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() { return calls == 0; }
    }

    private static final ThreadLocal<long[]> ACC = ThreadLocal.withInitial(() -> new long[4]);

    private LlmUsageTally() {}

    public static void reset() {
        ACC.remove();
    }

    /** 过账并透传内容：调用点由 llm.completeJson(...) 改为 record(llm.completeJsonDetail(...))。 */
    public static String record(LlmClient.LlmResult result) {
        long[] a = ACC.get();
        a[0]++;
        LlmClient.Usage u = result.usage();
        if (u == null || (u.promptTokens() == null && u.completionTokens() == null)) {
            a[1]++;
        } else {
            a[2] += u.promptTokens() == null ? 0 : u.promptTokens();
            a[3] += u.completionTokens() == null ? 0 : u.completionTokens();
        }
        return result.content();
    }

    /** 取走当前累计并清零。 */
    public static Tally drain() {
        long[] a = ACC.get();
        ACC.remove();
        return new Tally((int) a[0], (int) a[1], a[2], a[3]);
    }
}
