package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验专用的 OpenAI 兼容调用器，与底座 {@link com.treasury.nl2sql.llm.OpenAiCompatibleLlmClient} 刻意分离：
 * <ul>
 *   <li>温度逐请求传入（实验变量，不用 llm.temperature 全局值）；</li>
 *   <li>每次请求携带随机 user_id（当前实现按 DeepSeek 约定的 user_id 字段传递）并回读
 *       usage.prompt_cache_hit_tokens，用于验证供应商缓存隔离是否生效；</li>
 *   <li>不强制 response_format=json_object——实验约定「首次输出解析失败直接计入失败」，
 *       由评分环节裁决，调用层不得替模型兜底；</li>
 *   <li>完整保留原始响应报文（证据包要求），由调用方落盘。</li>
 * </ul>
 * 供应商三元组（base-url/model/api-key）仍复用 llm.* 配置，切供应商无需改实验代码。
 */
@Component
public class ExpeLlmCaller {

    private static final Logger log = LoggerFactory.getLogger(ExpeLlmCaller.class);

    /** content=模型输出正文；usage 供应商未返回时为 null；cacheHitTokens=命中前缀缓存的输入 token（应恒为 0/null，否则隔离失效）；rawResponse=原始响应 JSON 全文 */
    public record CallResult(String content, Integer promptTokens, Integer completionTokens,
                             Integer cacheHitTokens, String rawResponse) {}

    private final LlmProperties llmProps;
    private final ObjectMapper mapper;
    private final RestClient http;

    /** 组装实验专用 LLM 客户端；与业务超时一致，避免调用层出现长期阻塞。 */
    public ExpeLlmCaller(LlmProperties llmProps, ExpeProperties expeProps, ObjectMapper mapper) {
        this.llmProps = llmProps;
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(expeProps.getTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(expeProps.getTimeoutSeconds()).toMillis());
        this.http = RestClient.builder()
                .baseUrl(llmProps.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 返回当前实验主模型名，随同结果记录用于“同组对比实验”统计面板对齐。
     */
    public String model() { return llmProps.getModel(); }

    /**
     * 返回 LLM 基础地址。实验页会把该值回传到结果页，用于人工复现与供应商追踪，不作本方法行为开关。
     */
    public String baseUrl() { return llmProps.getBaseUrl(); }

    /**
     * 单次全新会话调用：单条 user 消息，无历史上下文，无自动重试。
     *
     * 调用失败不在本层兜底修复，异常直接抛出，交由调用方按“失败样本计数”处理。
     * 返回值包含 rawResponse，供评估任务落盘复盘。
     *
     * @param maxTokens 为 null 时不传 max_tokens，使用供应商默认值
     */
    public CallResult call(String prompt, double temperature, Integer maxTokens, String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llmProps.getModel());
        body.put("temperature", temperature);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        // 部分供应商（如 DeepSeek）使用自定义 user_id 做缓存隔离。该字段格式按 [a-zA-Z0-9\\-_]+、<=512 约束处理。
        body.put("user_id", userId);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        log.info("[expe-llm] >>> 生成请求 model={} temperature={} maxTokens={} user_id={}\n----- 输入全文 -----\n{}\n----- 输入结束 -----",
                llmProps.getModel(), temperature, maxTokens, userId, prompt);

        String resp = http.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + llmProps.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(resp);
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode() || !message.hasNonNull("content")) {
                throw new IllegalStateException("响应中缺少 choices[0].message.content");
            }
            JsonNode u = root.path("usage");
            Integer promptTokens = u.hasNonNull("prompt_tokens") ? u.get("prompt_tokens").asInt() : null;
            Integer completionTokens = u.hasNonNull("completion_tokens") ? u.get("completion_tokens").asInt() : null;
            Integer cacheHitTokens = u.hasNonNull("prompt_cache_hit_tokens") ? u.get("prompt_cache_hit_tokens").asInt() : null;
            String content = message.get("content").asText();
            log.info("[expe-llm] <<< 生成响应 user_id={} promptTokens={} completionTokens={} cacheHit={}\n----- 输出全文 -----\n{}\n----- 输出结束 -----",
                    userId, promptTokens, completionTokens, cacheHitTokens, content);
            return new CallResult(content, promptTokens, completionTokens, cacheHitTokens, resp);
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败: " + resp, e);
        }
    }
}
