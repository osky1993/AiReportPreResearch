package com.treasury.nl2sql.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 embeddings 接口实现（如 OpenAI text-embedding-3、通义 text-embedding-v4 等）。
 * 启用：embedding.provider=openai，并配置 embedding.base-url / embedding.model / EMBEDDING_API_KEY。
 * （注意：DeepSeek 目前不提供 embedding 接口，需用其它供应商的 embedding 服务。）
 *
 * <p>批量：{@link #embedBatch} 按 embedding.batch-size 分片（默认 10，DashScope text-embedding-v4
 * 单请求 input 上限；OpenAI 官方可调大），单条 {@link #embed} 也委托批量路径，请求/解析/重试只有一条路。
 * 容错：embedding.timeout-seconds 超时 + embedding.retries 次重试（仅网络类异常，解析失败不重试）。
 */
@Component
@ConditionalOnProperty(name = "embedding.provider", havingValue = "openai")
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final RestClient http;
    private final String apiKey;
    private final String model;
    private final int retries;
    private final int batchSize;
    private final ObjectMapper mapper;

    public OpenAiCompatibleEmbeddingClient(
            @Value("${embedding.base-url:https://api.openai.com}") String baseUrl,
            @Value("${embedding.api-key:}") String apiKey,
            @Value("${embedding.model:text-embedding-3-small}") String model,
            @Value("${embedding.timeout-seconds:30}") int timeoutSeconds,
            @Value("${embedding.retries:1}") int retries,
            @Value("${embedding.batch-size:10}") int batchSize,
            RestClient.Builder builder,
            ObjectMapper mapper) {
        // timeoutSeconds<=0 = 不设 requestFactory：MockRestServiceServer.bindTo(builder) 依赖
        // 它塞进 builder 的 mock 工厂，无条件覆盖会打断测试——测试传 0，生产走 yml 默认 30 秒。
        if (timeoutSeconds > 0) {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
            builder = builder.requestFactory(factory);
        }
        this.http = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.retries = Math.max(0, retries);
        this.batchSize = Math.max(1, batchSize);
        this.mapper = mapper;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            out.addAll(requestChunk(texts.subList(from, Math.min(from + batchSize, texts.size()))));
        }
        return out;
    }

    /** 单片请求（带重试）。只重试 RestClientException（网络/超时/5xx），解析失败视为非瞬时直接抛。 */
    private List<float[]> requestChunk(List<String> chunk) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            String resp;
            try {
                resp = http.post()
                        .uri("/v1/embeddings")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("model", model, "input", chunk))
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException e) {
                last = e;
                if (attempt < retries) {
                    log.warn("embedding 请求失败（第 {}/{} 次重试前）: {}", attempt + 1, retries, e.getMessage());
                }
                continue;
            }
            return parse(resp, chunk.size());
        }
        throw last;
    }

    /** 解析响应并按 data[].index 归位——OpenAI 协议不保证 data 顺序与 input 一致。 */
    private List<float[]> parse(String resp, int expected) {
        try {
            JsonNode data = mapper.readTree(resp).path("data");
            float[][] slots = new float[expected][];
            for (int i = 0; i < data.size(); i++) {
                JsonNode item = data.get(i);
                int idx = item.path("index").asInt(i);   // 无 index 字段时按位置兜底
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int j = 0; j < arr.size(); j++) v[j] = (float) arr.get(j).asDouble();
                slots[idx] = v;
            }
            List<float[]> out = new ArrayList<>(expected);
            for (float[] v : slots) {
                if (v == null) throw new IllegalStateException("响应缺少部分条目的 embedding");
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("解析 embedding 响应失败: " + resp, e);
        }
    }
}
