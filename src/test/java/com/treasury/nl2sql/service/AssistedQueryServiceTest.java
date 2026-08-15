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
 * AssistedQueryService 核验闸门与沉淀链路单测（纯逻辑，无 DB/LLM）。
 * 覆盖 verify 接受/拒绝路径、LLM 描述失败的 fail-open 行为、参数漂移触发澄清降级与
 * ask() 无状态时的执行行为，确保既保护安全又不影响资产复用闭环。
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

    /**
     * 输入：verify 同意采纳。
     * 预期：生成资产沉淀并尝试写入口径描述，描述可为主诉求追溯语义。
     */
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

    /**
     * 输入：描述生成超时。
     * 预期：沉淀仍应成功，描述置空，不影响资产落库（fail-open）。
     */
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

    /**
     * 输入：MQL 校验失败。
     * 预期：不沉淀资产并返回失败提示，避免脏查询入库。
     */
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

    /**
     * 输入：疑似参数漂移问题。
     * 预期：返回 CLARIFY 并禁止直接编译执行，交由用户确认。
     */
    @Test
    void askHitWithParamDrift_downgradesToClarify_withoutExecution() {
        when(store.isEnabled()).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(new float[]{1f});
        when(store.recall(anyString(), any())).thenReturn(new CaliberStore.Recall(
                CaliberStore.Band.HIT, 7L, "2026年6月30日余额最高的5个活期账户",
                MQL, "口径描述", 0.93));

        AssistedResponse resp = service.ask("2026年7月31日余额最高的5个活期账户", false);

        assertEquals(QueryState.CLARIFY, resp.state());
        assertEquals(7L, resp.assetId());
        assertEquals("口径描述", resp.matchedDescription());
        assertTrue(resp.clarifyPrompt().contains("参数疑似不同"), "澄清话术应指明参数漂移");
        assertTrue(resp.clarifyPrompt().contains("30") && resp.clarifyPrompt().contains("31"));
        assertNull(resp.trace(), "降档澄清不得执行取数");
        verify(compiler, never()).compile(any());
    }

    /**
     * 输入：用户明确拒绝采纳（verify=false）；
     * 预期：仅返回不落库结果，不走 explain/trial，无副作用执行。
     */
    @Test
    void verifyReject_noExplainNoPrecipitate() {
        AssistedQueryService.VerifyResult r = service.verify("问题", MQL, false, "张三");

        assertFalse(r.precipitated());
        assertNull(r.description());
        verify(explain, never()).explain(any(), any());
        verify(store, never()).precipitate(anyString(), anyString(), any(), anyString());
    }
}
