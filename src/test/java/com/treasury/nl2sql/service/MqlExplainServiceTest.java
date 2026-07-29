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
 * 口径反翻译纯逻辑单测（mock 校验器/schema/LLM，零 DB/LLM）：
 * 前置闸失败关闭、fence 剥离、unanswerable 拒绝、坏 JSON 重试一次、同 MQL 缓存不重复调 LLM。
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

    @Test
    void validatorErrors_failClosed_withoutLlmCall() {
        when(validator.validate(any())).thenReturn(List.of("表 t1 不存在"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("白名单校验"));
        verify(llm, never()).completeJson(anyList());
    }

    @Test
    void unstructuredInput_failClosed_withoutLlmCall() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mapper.valueToTree(List.of(1, 2)), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("无法解析"));
        verify(llm, never()).completeJson(anyList());
    }

    @Test
    void unanswerable_rejectedWithReason() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn(
                "{\"unanswerable\":true,\"reason\":\"结构过于复杂\"}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
        assertTrue(ex.getMessage().contains("结构过于复杂"));
    }

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

    @Test
    void missingDescription_failClosed() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("{\"caveats\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC));
    }

    @Test
    void sameMqlSameMode_servedFromCache_singleLlmCall() {
        stubOk();
        when(llm.completeJson(anyList())).thenReturn("{\"description\":\"口径A\",\"caveats\":[]}");
        service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        service.explain(mql("t1"), MqlExplainService.Mode.AD_HOC);
        verify(llm, times(1)).completeJson(anyList());
    }

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
