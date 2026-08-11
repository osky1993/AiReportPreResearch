package com.treasury.nl2sql.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EmbeddingClient 纯函数层回归：
 * - embedBatch 默认行为必须按输入顺序逐条落库（即使默认实现是循环调用单条）
 * - cosine 相似度在 3 个边界上保持语义一致：同向 1、正交 0、零向量 0
 */
class EmbeddingClientTest {

    /**
     * 输入：长度不同的文本列表；
     * 预期：默认实现按顺序逐条调用 embed 并保留顺序，验证批处理的顺序闭包。
     */
    @Test
    void defaultEmbedBatch_callsEmbedPerText_inOrder() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient c = text -> new float[]{calls.incrementAndGet(), text.length()};
        List<float[]> out = c.embedBatch(List.of("a", "bb", "ccc"));
        assertEquals(3, calls.get());
        assertArrayEquals(new float[]{1, 1}, out.get(0));
        assertArrayEquals(new float[]{2, 2}, out.get(1));
        assertArrayEquals(new float[]{3, 3}, out.get(2));
    }

    /**
     * 输入：共线向量；
     * 预期：相似度应为 1，校验向量归一化/点积边界。
     */
    @Test
    void sameDirection_isOne() {
        assertEquals(1.0, EmbeddingClient.cosine(new float[]{1, 2, 3}, new float[]{2, 4, 6}), 1e-6);
    }

    /**
     * 输入：正交向量；
     * 预期：相似度约等于 0，覆盖语义上互斥案例。
     */
    @Test
    void orthogonal_isZero() {
        assertEquals(0.0, EmbeddingClient.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
    }

    /**
     * 输入：零向量与非零向量；
     * 预期：避免除 0 陷阱，返回 0 作为安全降级。
     */
    @Test
    void zeroVector_isZero() {
        assertEquals(0.0, EmbeddingClient.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-6);
    }
}
