package com.treasury.nl2sql.report.observe;

import com.treasury.nl2sql.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmUsageTally 单测：
 * 覆盖 record/drian 的计数累加与清零语义；
 * 未计量调用与 0 token 区分（用于准确告警）；
 * default 客户端兼容路径返回 usage=null 不影响累积。
 */
class LlmUsageTallyTest {

    /**
     * 每个测试前清空全局计数，避免前序测试泄漏影响计数断言。
     */
    @BeforeEach
    void clean() {
        LlmUsageTally.reset();
    }

    /**
     * 验证可计量调用的调用次数与 token 分量可正确累加，drain 后全局计数归零。
     */
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

    /**
     * 验证未返回 usage 与 usage 字段为 0 的语义分离：前者计入未计量调用。
     */
    @Test
    void unmeteredCallsAreDistinguishedFromZero() {
        LlmUsageTally.record(new LlmClient.LlmResult("x", null));
        LlmUsageTally.record(new LlmClient.LlmResult("y", new LlmClient.Usage(null, null)));
        LlmUsageTally.Tally t = LlmUsageTally.drain();
        assertEquals(2, t.calls());
        assertEquals(2, t.unmeteredCalls(), "供应商不回 usage 记未计量，不冒充 0");
        assertEquals(0, t.promptTokens());
    }

    /**
     * 验证默认客户端适配路径不要求 provider usage，兼容 legacy 返回模型并保持 usage=null。
     */
    @Test
    void defaultDetailMethodDelegatesWithNullUsage() {
        LlmClient legacy = messages -> "{\"ok\":true}";
        LlmClient.LlmResult r = legacy.completeJsonDetail(List.of(LlmClient.Message.user("hi")));
        assertEquals("{\"ok\":true}", r.content());
        assertNull(r.usage(), "default 实现 usage=null——既有实现零改动即兼容");
    }
}
