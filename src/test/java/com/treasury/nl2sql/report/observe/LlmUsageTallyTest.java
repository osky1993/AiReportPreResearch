package com.treasury.nl2sql.report.observe;

import com.treasury.nl2sql.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P6 契约2 单测：用量累加/清零、未计量与 0 的区分、default 方法零影响。 */
class LlmUsageTallyTest {

    @BeforeEach
    void clean() {
        LlmUsageTally.reset();
    }

    @Test
    void recordAccumulatesAndDrainResets() {
        assertEquals("a", LlmUsageTally.record(new LlmClient.LlmResult("a", new LlmClient.Usage(100, 20))));
        assertEquals("b", LlmUsageTally.record(new LlmClient.LlmResult("b", new LlmClient.Usage(50, 5))));
        LlmUsageTally.Tally t = LlmUsageTally.drain();
        assertEquals(2, t.calls());
        assertEquals(0, t.unmeteredCalls());
        assertEquals(150, t.promptTokens());
        assertEquals(25, t.completionTokens());
        assertTrue(LlmUsageTally.drain().isEmpty(), "drain 后清零");
    }

    @Test
    void unmeteredCallsAreDistinguishedFromZero() {
        LlmUsageTally.record(new LlmClient.LlmResult("x", null));
        LlmUsageTally.record(new LlmClient.LlmResult("y", new LlmClient.Usage(null, null)));
        LlmUsageTally.Tally t = LlmUsageTally.drain();
        assertEquals(2, t.calls());
        assertEquals(2, t.unmeteredCalls(), "供应商不回 usage 记未计量，不冒充 0");
        assertEquals(0, t.promptTokens());
    }

    @Test
    void defaultDetailMethodDelegatesWithNullUsage() {
        LlmClient legacy = messages -> "{\"ok\":true}";
        LlmClient.LlmResult r = legacy.completeJsonDetail(List.of(LlmClient.Message.user("hi")));
        assertEquals("{\"ok\":true}", r.content());
        assertNull(r.usage(), "default 实现 usage=null——既有实现零改动即兼容");
    }
}
