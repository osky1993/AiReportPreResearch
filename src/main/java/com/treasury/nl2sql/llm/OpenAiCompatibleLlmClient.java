package com.treasury.nl2sql.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议（/chat/completions）的实现，覆盖 DeepSeek / 通义千问 / Moonshot / OpenAI 等。
 * 设计意图：把“对话构造、序列化、调用与解析”固化为单一实现，便于在运行参数变化时零改代码切换服务。
 * 换供应商仅调整配置：llm.base-url / llm.model / LLM_API_KEY；对业务不产生侵入。
 * <p>失败与观测边界：响应体解析失败会直接抛运行时异常并回滚到上层重试策略；usage 仅作为监控字段，不影响主流程。</p>
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;
    private final RestClient http;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public OpenAiCompatibleLlmClient(LlmProperties props,
                                     com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 兼容旧调用约定的最小返回：仅返回 content 文本。
     * 失败不会吞掉异常，交给上层的重试/降级链路统一处理。
     */
    @Override
    public String completeJson(List<Message> messages) {
        return completeJsonDetail(messages).content();
    }

    /**
     * 在既有解析之上多取一段 usage（供应商省略 usage 时返回 null），供观测与计费留痕。
     * 即便 usage 缺失也不影响主流程：completeJson 行为对上保持不变。
     */
    @Override
    public LlmResult completeJsonDetail(List<Message> messages) {
        List<Map<String, String>> msgs = new ArrayList<>();
        for (Message m : messages) {
            msgs.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "temperature", props.getTemperature(),
                "messages", msgs,
                "response_format", Map.of("type", "json_object")
        );

        String resp = http.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode u = root.path("usage");
            Usage usage = (u.isMissingNode() || u.isNull()) ? null
                    : new Usage(u.hasNonNull("prompt_tokens") ? u.get("prompt_tokens").asInt() : null,
                                u.hasNonNull("completion_tokens") ? u.get("completion_tokens").asInt() : null);
            return new LlmResult(content, usage);
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败: " + resp, e);
        }
    }
}
