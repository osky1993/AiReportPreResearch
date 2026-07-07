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
}
