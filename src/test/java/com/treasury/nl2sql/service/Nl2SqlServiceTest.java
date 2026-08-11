package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector;
import com.treasury.nl2sql.glossary.GlossaryService;
import com.treasury.nl2sql.guard.CurrencyGuard;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.llm.LlmProperties;
import com.treasury.nl2sql.schema.SchemaLinker;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectQuery;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Nl2SqlService 主流程单测（含异常回退）。
 * 验证编译/执行异常触发修正重试、unanswerable 直接拒答不重试、embedding 失败降级；
 * 所有分支返回统一结果模型，不抛出 HTTP 异常。
 */
class Nl2SqlServiceTest {

    /** 离线向量化桩：固定返回单位向量。 */
    private static final EmbeddingClient EMB = q -> new float[]{1};

    /**
     * 输入：查询可被模型解析并编译，但执行异常。
     * 预期：返回 success=false，保留执行错误并触发修正重试。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executionError_isGracefulAndRetried() {
        SchemaLinker linker = mock(SchemaLinker.class);
        when(linker.select(any(), any())).thenReturn(new SchemaLinker.LinkingResult(List.of(), "", List.of()));
        FewShotSelector fewShot = mock(FewShotSelector.class);
        when(fewShot.formatBlock(any(), anyDouble(), any())).thenReturn("");
        GlossaryService glossary = mock(GlossaryService.class);
        when(glossary.formatBlock(any(), any())).thenReturn("");
        CurrencyGuard currencyGuard = mock(CurrencyGuard.class);
        when(currencyGuard.check(any())).thenReturn(List.of());

        // 模型每轮都返回一个「校验能过、但执行会报错」的 MQL
        LlmClient llm = mock(LlmClient.class);
        when(llm.completeJson(any())).thenReturn("{\"table\":\"account\"}");

        MqlValidator validator = mock(MqlValidator.class);
        when(validator.validate(any())).thenReturn(List.of()); // 校验通过

        MqlSqlCompiler compiler = mock(MqlSqlCompiler.class);
        SelectQuery<Record> query = mock(SelectQuery.class);
        when(compiler.compile(any())).thenReturn(query);
        when(compiler.renderSql(query)).thenReturn("select 1 from account");

        // 执行阶段抛出（模拟 SQL 执行失败）
        DSLContext dsl = mock(DSLContext.class);
        when(dsl.fetch(query)).thenThrow(new DataAccessException("SQLSyntaxError: boom"));

        LlmProperties props = new LlmProperties();
        props.setMaxFixRounds(1); // 共 2 轮（round 0、1）

        Nl2SqlService service = new Nl2SqlService(linker, fewShot, glossary, EMB, currencyGuard, llm, props,
                validator, compiler, dsl, new ObjectMapper(), mock(com.treasury.nl2sql.schema.SchemaService.class));

        NlQueryResult r = service.query("随便问一句");

        assertFalse(r.success(), "执行异常应优雅返回 success=false，而非抛异常");
        assertNull(r.rows(), "失败时不应有结果行");
        assertFalse(r.errors().isEmpty(), "应带回执行错误信息");
        assertTrue(r.errors().get(0).contains("执行") || r.errors().get(0).contains("boom"),
                "错误信息应包含根因，实际: " + r.errors());
        verify(llm, times(2)).completeJson(any()); // 执行失败触发了自我修正重试
    }

    /**
     * 输入：模型判定 unanswerable。
     * 预期：直接拒答，无 retry，validator/compiler 不被触发。
     */
    @Test
    @SuppressWarnings("unchecked")
    void unanswerable_isRefusedWithoutRetry() {
        SchemaLinker linker = mock(SchemaLinker.class);
        when(linker.select(any(), any())).thenReturn(new SchemaLinker.LinkingResult(List.of(), "", List.of()));
        FewShotSelector fewShot = mock(FewShotSelector.class);
        when(fewShot.formatBlock(any(), anyDouble(), any())).thenReturn("");
        GlossaryService glossary = mock(GlossaryService.class);
        when(glossary.formatBlock(any(), any())).thenReturn("");
        CurrencyGuard currencyGuard = mock(CurrencyGuard.class);

        // 模型判定无法回答 → 输出 unanswerable
        LlmClient llm = mock(LlmClient.class);
        when(llm.completeJson(any())).thenReturn(
                "{\"unanswerable\":true,\"reason\":\"字段不存在\"}");

        MqlValidator validator = mock(MqlValidator.class);
        MqlSqlCompiler compiler = mock(MqlSqlCompiler.class);
        DSLContext dsl = mock(DSLContext.class);
        LlmProperties props = new LlmProperties();
        props.setMaxFixRounds(2);

        Nl2SqlService service = new Nl2SqlService(linker, fewShot, glossary, EMB, currencyGuard, llm, props,
                validator, compiler, dsl, new ObjectMapper(), mock(com.treasury.nl2sql.schema.SchemaService.class));

        NlQueryResult r = service.query("列出每个账户的开户人身份证号");

        assertFalse(r.success(), "拒答应返回 success=false");
        assertTrue(r.errors().get(0).contains("拒答"), "应带拒答原因: " + r.errors());
        verify(llm, times(1)).completeJson(any());       // 拒答不重试
        verify(validator, never()).validate(any());      // 不进入校验/编译
        verifyNoInteractions(compiler, dsl);
    }

    /**
     * 输入：embedding 抛异常。
     * 预期：降级走 null qv，保留 warnings 并完成查询执行。
     */
    @Test
    @SuppressWarnings("unchecked")
    void embeddingFailure_degradesGracefully() {
        EmbeddingClient broken = q -> { throw new RuntimeException("embedding down"); };

        SchemaLinker linker = mock(SchemaLinker.class);
        when(linker.select(any(), any())).thenReturn(new SchemaLinker.LinkingResult(List.of(), "", List.of()));
        FewShotSelector fewShot = mock(FewShotSelector.class);
        when(fewShot.formatBlock(any(), anyDouble(), any())).thenReturn("");
        GlossaryService glossary = mock(GlossaryService.class);
        when(glossary.formatBlock(any(), any())).thenReturn("");
        CurrencyGuard currencyGuard = mock(CurrencyGuard.class);
        when(currencyGuard.check(any())).thenReturn(List.of());

        LlmClient llm = mock(LlmClient.class);
        when(llm.completeJson(any())).thenReturn("{\"table\":\"account\"}");
        MqlValidator validator = mock(MqlValidator.class);
        when(validator.validate(any())).thenReturn(List.of());

        MqlSqlCompiler compiler = mock(MqlSqlCompiler.class);
        SelectQuery<Record> query = mock(SelectQuery.class);
        when(compiler.compile(any())).thenReturn(query);
        when(compiler.renderSql(query)).thenReturn("select 1 from account");
        DSLContext dsl = mock(DSLContext.class);
        Result<Record> rows = mock(Result.class);
        when(rows.intoMaps()).thenReturn(List.of());
        when(dsl.fetch(query)).thenReturn(rows);

        LlmProperties props = new LlmProperties();
        props.setMaxFixRounds(1);

        Nl2SqlService service = new Nl2SqlService(linker, fewShot, glossary, broken, currencyGuard, llm, props,
                validator, compiler, dsl, new ObjectMapper(), mock(com.treasury.nl2sql.schema.SchemaService.class));

        NlQueryResult r = service.query("账户余额是多少");

        assertTrue(r.success(), "embedding 故障应降级而非整链路失败，实际 errors=" + r.errors());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("降级")),
                "warnings 应留痕降级说明，实际: " + r.warnings());
        verify(linker).select(any(), isNull());   // 降级时下游收到的 qv 为 null
        verify(fewShot).formatBlock(any(), anyDouble(), isNull());
        verify(glossary).formatBlock(any(), isNull());
    }
}
