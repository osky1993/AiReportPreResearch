package com.treasury.nl2sql.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * OpenAI 兼容 embedding 客户端协议合规回归（纯本地 Mock，无需外网）：
 * - 验证请求 URL、Header、payload 形态正确
 * - verify 分片归并后按 index 重排，保证输出顺序稳定
 * - 验证重试次数边界与失败放大策略
 */
class OpenAiCompatibleEmbeddingClientTest {

    /** timeout=0（兼容 MockRestServiceServer）、retries、batchSize 可指定的被测对象工厂。 */
    private static OpenAiCompatibleEmbeddingClient client(RestClient.Builder builder, int retries, int batchSize) {
        return new OpenAiCompatibleEmbeddingClient(
                "https://dashscope.example.com", "test-key", "text-embedding-v4",
                0, retries, batchSize, builder, new ObjectMapper());
    }

    /**
     * 输入：单条文本「营收」；
     * 预期：发起兼容协议请求并正确解析返回向量，验证 contract 与字段名不被回归。
     */
    @Test
    void embed_postsToEmbeddingsEndpoint_andParsesVector() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient client = client(builder, 1, 10);

        server.expect(requestTo("https://dashscope.example.com/v1/embeddings"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("text-embedding-v4"))
                .andExpect(jsonPath("$.input[0]").value("营收"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}", APPLICATION_JSON));

        float[] v = client.embed("营收");

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, v, 1e-6f);
        server.verify();
    }

    /**
     * 输入：12 条文本，batchSize 设为 10；
     * 预期：分两批请求且按 data.index 重排后顺序恢复，兼容上游乱序返回。
     */
    @Test
    void embedBatch_shardsByBatchSize_andRestoresOrderByIndex() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient client = client(builder, 0, 10);

        // 12 条、batch=10 → 两次请求；第一片响应故意乱序，靠 data[].index 归位
        StringBuilder firstData = new StringBuilder();
        for (int i = 9; i >= 0; i--) {
            if (firstData.length() > 0) firstData.append(',');
            firstData.append("{\"index\":").append(i).append(",\"embedding\":[").append(i).append(".0]}");
        }
        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(10))
                .andRespond(withSuccess("{\"data\":[" + firstData + "]}", APPLICATION_JSON));
        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(
                        "{\"data\":[{\"index\":1,\"embedding\":[11.0]},{\"index\":0,\"embedding\":[10.0]}]}",
                        APPLICATION_JSON));

        List<String> texts = IntStream.range(0, 12).mapToObj(i -> "文本" + i).toList();
        List<float[]> out = client.embedBatch(texts);

        assertEquals(12, out.size());
        for (int i = 0; i < 12; i++) {
            assertArrayEquals(new float[]{i}, out.get(i), 1e-6f, "第 " + i + " 条顺序错误");
        }
        server.verify();
    }

    /**
     * 输入：首次失败触发重试；
     * 预期：一次重试后成功返回，验证“失败放大”前提和重试闭包有效。
     */
    @Test
    void embed_retriesOnceOnServerError_thenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient client = client(builder, 1, 10);

        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andRespond(withServerError());
        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"embedding\":[0.5]}]}", APPLICATION_JSON));

        assertArrayEquals(new float[]{0.5f}, client.embed("营收"), 1e-6f);
        server.verify();
    }

    /**
     * 输入：重复失败且 retries 达上限；
     * 预期：抛出异常上抛，调用端可据此触发 fail-open 或熔断策略。
     */
    @Test
    void embed_failsAfterRetriesExhausted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient client = client(builder, 1, 10);

        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andRespond(withServerError());
        server.expect(once(), requestTo("https://dashscope.example.com/v1/embeddings"))
                .andRespond(withServerError());

        assertThrows(Exception.class, () -> client.embed("营收"));
        server.verify();
    }
}
