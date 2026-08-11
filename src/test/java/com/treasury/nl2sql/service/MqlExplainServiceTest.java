package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.schema.SchemaService;
import com.treasury.nl2sql.validate.MqlValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqlExplainService 口径反翻译单测（纯逻辑）。
 * 校验：fence/无结构体直接 fail-close、unanswerable 快速拦截、非法 JSON 重试一次、同 MQL 同 mode 缓存复用，
 * 并验证 schema 与 validator 在 fail-open 之前的门禁先行。
 */
class MqlExplainServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MqlValidator validator = mock(MqlValidator.class);
    private final SchemaService schema = mock(SchemaService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final MqlExplainService service = new MqlExplainService(validator, schema, llm, mapper);

    private JsonNode mql(String table) {
        try {
            return mapper.readTree("""
                {"table":"%s","metrics":[{"op":"sum","field":"amount","alias":"amt"}]}""".formatted(table));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubOk() {
        when(validator.validate(any())).thenReturn(List.of());
        when(schema.assemble(anyCollection())).thenReturn("(表结构)");
    }

    /**
     * 输入：正常 MQL 与规范化 schema。
     * 预期：完成 fence 清洗后返回 explanation 与 caveats，满足 describe 生成链路。
     */
    @Test
    void happyPath_stripsFence_andCollectsCaveats() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("""
            ```json
            {"description":"统计报告期内金额合计","caveats":["amount 列含义未确认"]}
            ```""");
        MqlExplainService.Explanation e = service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        assertEquals("统计报告期内金额合计", e.explanation());
        assertEquals(List.of("amount 列含义未确认"), e.caveats());
    }

    /**
     * 输入：validator 返回错误。
     * 预期：不调用 LLM，直接 fail-closed。
     */
    @Test
    void validatorErrors_failClosed_withoutLlmCall() {
        when(validator.validate(any())).thenReturn(List.of("表 t1 不存在"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("白名单校验"));
        verify(llm, never()).completeJson(anyList());
    }

    /**
     * 输入：非结构化 JsonNode。
     * 预期：不进入 LLM，避免把不合法结构交给模型解释。
     */
    @Test
    void unstructuredInput_failClosed_withoutLlmCall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mapper.valueToTree(List.of(1, 2)), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("无法解析"));
        verify(llm, never()).completeJson(anyList());
    }

    /**
     * 验证模型返回 unanswerable 时直接 fail-closed，并透传 reason 供上游提示。
     */
    @Test
    void unanswerable_rejectedWithReason() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn(
                "{\"unanswerable\":true,\"reason\":\"结构过于复杂\"}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("结构过于复杂"));
    }

    /**
     * 验证非法 JSON 的重试策略：失败后只重试一次，成功后返回结果。
     */
    @Test
    void badJson_retriedOnce_thenSucceeds() {
        stubOk();
        when(llm.completeJson(anyList()))
                .thenReturn("这不是 JSON")
                .thenReturn("{\"description\":\"重试后成功\",\"caveats\":[]}");
        MqlExplainService.Explanation e = service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        assertEquals("重试后成功", e.explanation());
        verify(llm, times(2)).completeJson(anyList());
    }

    /**
     * 验证缺少 description 语义字段直接 fail-closed，避免无意义 explanation 入库。
     */
    @Test
    void missingDescription_failClosed() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("{\"caveats\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
    }

    /**
     * 验证同一 mode 与同一 MQL 复用缓存，防止重复 LLM 调用放大成本。
     */
    @Test
    void sameMqlSameMode_servedFromCache_singleLlmCall() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("{\"description\":\"口径A\",\"caveats\":[]}");
        service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        verify(llm, times(1)).completeJson(anyList());
    }

    /**
     * 验证缓存键包含 mode 维度，避免不同上下文误用同一解释结果。
     */
    @Test
    void cacheKey_distinguishesModeAndMql() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("{\"description\":\"口径\",\"caveats\":[]}");
        service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        service.explain(mql("t1"), MqlExplainService.Mode.TEMPLATE);   // 语境不同 prompt 不同，不得复用
        service.explain(mql("t2"), MqlExplainService.Mode.AD_HOC);
        verify(llm, times(3)).completeJson(anyList());
    }
}
