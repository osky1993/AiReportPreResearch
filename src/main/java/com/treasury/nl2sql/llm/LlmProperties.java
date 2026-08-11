package com.treasury.nl2sql.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 客户端运行时配置模型。
 * <p>
 * 所有属性由 {@code llm.*} 前缀读取，可由配置文件或环境变量替代。
 * 该类仅持有配置，不进行网络调用或连接建立；真正的可用性检测发生在 LLM Client 被调用时。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>baseUrl：兼容 OpenAI API 协议服务端地址</li>
 *   <li>apiKey：服务鉴权密钥（运行时注入，禁止提交到代码库）</li>
 *   <li>model：目标模型名（如 gpt-4o、gpt-4.1 等）</li>
 *   <li>temperature：采样温度，0 表示更确定性输出，>0 会提高多样性但放大波动</li>
 *   <li>timeoutSeconds：每次 LLM 请求的超时阈值（秒）</li>
 *   <li>maxFixRounds：报告审计修复（NumberAuditor 失败回灌）最大重试轮次</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    /** LLM 服务地址，默认未显式配置时由下游调用方处理为空或缺失配置错误。 */
    private String baseUrl;
    /** LLM 鉴权密钥；敏感字段，优先从环境变量或密钥管理系统注入。 */
    private String apiKey;
    /** 目标模型名，必须与服务端兼容的模型标识一致。 */
    private String model;
    /** 采样温度，默认 0 以降低随机性，保障报告关键链路的稳定性。 */
    private double temperature = 0;
    /**
     * 单次 LLM 请求超时秒数，默认 60 秒。
     * <p>
     * 超时会触发上层失败分支，避免占用异步线程池并让流水线进入 BLOCKED/可回放状态。
     */
    private int timeoutSeconds = 60;
    /**
     * NumberAuditor 的 rewrite 阶段允许的最大重写轮次，默认 2。
     * <p>
     * 该值过大将放大不确定性；过小可能导致可自动修复的明显模板输出问题被立即转人工。
     */
    private int maxFixRounds = 2;

    /** 获取 LLM 接入点 URL。 */
    public String getBaseUrl() { return baseUrl; }
    /** 设置 LLM 接入点 URL。 */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    /** 获取 API Key。 */
    public String getApiKey() { return apiKey; }
    /** 设置 API Key。 */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    /** 获取模型名。 */
    public String getModel() { return model; }
    /** 设置模型名。 */
    public void setModel(String model) { this.model = model; }
    /** 获取采样温度。 */
    public double getTemperature() { return temperature; }
    /** 设置采样温度。 */
    public void setTemperature(double temperature) { this.temperature = temperature; }
    /** 获取超时秒数。 */
    public int getTimeoutSeconds() { return timeoutSeconds; }
    /** 设置超时秒数。 */
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    /** 获取最大修复轮次。 */
    public int getMaxFixRounds() { return maxFixRounds; }
    /** 设置最大修复轮次。 */
    public void setMaxFixRounds(int maxFixRounds) { this.maxFixRounds = maxFixRounds; }
}
