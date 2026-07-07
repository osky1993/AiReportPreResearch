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
 * 换供应商：改 application.yml 的 llm.base-url + llm.model + LLM_API_KEY 即可，无需改代码。
 * 若要接 Anthropic / Gemini 等非兼容协议，新增一个 LlmClient 实现替换本 Bean。
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

    @Override
    public String completeJson(List<Message> messages) {
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
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败: " + resp, e);
        }
    }
}
