package com.treasury.nl2sql.llm;

import java.util.List;

/**
 * 供应商无关的 LLM 抽象。上层只依赖本接口，
 * 切换 DeepSeek / 通义 / Moonshot / OpenAI / Anthropic 等只需替换实现或改配置。
 */
public interface LlmClient {

    record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c)   { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }

    /**
     * 以 JSON 模式补全：返回模型输出的纯文本（约定为一个 JSON 对象字符串）。
     */
    String completeJson(List<Message> messages);

    /** token 用量（P6 观测；供应商不返回 usage 时字段为 null）。 */
    record Usage(Integer promptTokens, Integer completionTokens) {}

    /** 补全结果 + 用量明细（usage 可为 null——观测层必须把「未计量」与 0 区分开）。 */
    record LlmResult(String content, Usage usage) {}

    /**
     * 带用量明细的 JSON 补全（P6 观测用）。default 委托 {@link #completeJson}、usage=null——
     * 对既有实现与调用方零影响；可解析 usage 的实现应覆写本方法。
     */
    default LlmResult completeJsonDetail(List<Message> messages) {
        return new LlmResult(completeJson(messages), null);
    }
}
