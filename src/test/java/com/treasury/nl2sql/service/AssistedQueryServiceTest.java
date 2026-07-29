package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.guard.CurrencyGuard;
import com.treasury.nl2sql.store.CaliberStore;
import com.treasury.nl2sql.validate.MqlValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核验闸门沉淀链路的纯逻辑单测（mock 全部依赖，零 DB/LLM）：
 * 采纳时口径描述随资产固化；描述生成失败 fail-open（沉淀照常、描述为 null）；驳回不触发任何生成。
 */
class AssistedQueryServiceTest {

    private static final String MQL = "{\"table\":\"t1\"}";

    private final CaliberStore store = mock(CaliberStore.class);
    private final Nl2SqlService nl2sql = mock(Nl2SqlService.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final MqlValidator validator = mock(MqlValidator.class);
    private final MqlSqlCompiler compiler = mock(MqlSqlCompiler.class);
    private final CurrencyGuard currencyGuard = mock(CurrencyGuard.class);
    private final MqlExplainService explain = mock(MqlExplainService.class);
    private final AssistedQueryService service = new AssistedQueryService(
            store, nl2sql, embedding, validator, compiler, null, currencyGuard, explain, new ObjectMapper());

    @Test
    void verifyAccept_precipitatesWithGeneratedDescription() {
        when(explain.explain(any(), eq(MqlExplainService.Mode.AD_HOC)))
                .thenReturn(new MqlExplainService.Explanation("统计 t1 全量", List.of()));
        when(store.precipitate(anyString(), anyString(), anyString(), anyString())).thenReturn(42L);

        AssistedQueryService.VerifyResult r = service.verify("问题", MQL, true, "张三");

        assertTrue(r.precipitated());
        assertEquals(42L, r.assetId());
        assertEquals("统计 t1 全量", r.description());
        verify(store).precipitate("问题", MQL, "统计 t1 全量", "张三");
    }

    @Test
    void verifyAccept_explainFails_failOpenWithNullDescription() {
        when(explain.explain(any(), any())).thenThrow(new IllegalStateException("LLM 超时"));
        when(store.precipitate(anyString(), anyString(), isNull(), anyString())).thenReturn(7L);

        AssistedQueryService.VerifyResult r = service.verify("问题", MQL, true, "张三");

        assertTrue(r.precipitated(), "描述生成失败不得阻断沉淀");
        assertEquals(7L, r.assetId());
        assertNull(r.description());
        verify(store).precipitate("问题", MQL, null, "张三");
    }

    @Test
    void verifyAccept_invalidMql_notPrecipitated() {
        when(explain.explain(any(), any())).thenThrow(new IllegalArgumentException("白名单不通过"));
        when(store.precipitate(anyString(), anyString(), isNull(), anyString()))
                .thenThrow(new IllegalArgumentException("MQL 校验未通过: 表不存在"));

        AssistedQueryService.VerifyResult r = service.verify("问题", MQL, true, "张三");

        assertFalse(r.precipitated());
        assertNull(r.assetId());
        assertTrue(r.message().contains("采纳失败"));
    }

    @Test
    void askHitWithParamDrift_downgradesToClarify_withoutExecution() {
        when(store.isEnabled()).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(new float[]{1f});
        when(store.recall(anyString(), any())).thenReturn(new CaliberStore.Recall(
                CaliberStore.Band.HIT, 7L, "2026年6月30日库存余额最高的5个县级国库",
                MQL, "口径描述", 0.93));

        AssistedResponse resp = service.ask("2026年7月31日库存余额最高的5个县级国库", false);

        assertEquals(QueryState.CLARIFY, resp.state());
        assertEquals(7L, resp.assetId());
        assertEquals("口径描述", resp.matchedDescription());
        assertTrue(resp.clarifyPrompt().contains("参数疑似不同"), "澄清话术应指明参数漂移");
        assertTrue(resp.clarifyPrompt().contains("30") && resp.clarifyPrompt().contains("31"));
        assertNull(resp.trace(), "降档澄清不得执行取数");
        verify(compiler, never()).compile(any());
    }

    @Test
    void verifyReject_noExplainNoPrecipitate() {
        AssistedQueryService.VerifyResult r = service.verify("问题", MQL, false, "张三");

        assertFalse(r.precipitated());
        assertNull(r.description());
        verify(explain, never()).explain(any(), any());
        verify(store, never()).precipitate(anyString(), anyString(), any(), anyString());
    }
}
