package com.treasury.nl2sql.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** EmbeddingClient.cosine 纯函数校验 + embedBatch 默认实现。 */
class EmbeddingClientTest {

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

    @Test
    void sameDirection_isOne() {
        assertEquals(1.0, EmbeddingClient.cosine(new float[]{1, 2, 3}, new float[]{2, 4, 6}), 1e-6);
    }

    @Test
    void orthogonal_isZero() {
        assertEquals(0.0, EmbeddingClient.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
    }

    @Test
    void zeroVector_isZero() {
        assertEquals(0.0, EmbeddingClient.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-6);
    }
}
