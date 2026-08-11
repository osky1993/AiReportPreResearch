package com.treasury.nl2sql.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import com.treasury.nl2sql.glossary.GlossaryService.Term;
import com.treasury.nl2sql.schema.SchemaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlossaryService 术语库加载与召回策略回归（纯逻辑，无 DB/LLM/网络）：
 * - unknownTableRefs 发现悬空表名
 * - select/topN 命中率与短路行为
 * - qv 缺失时的全量退化不丢信息
 */
class GlossaryServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SchemaService schema = mock(SchemaService.class);

    /**
     * 输入：包含已知/未知表名的术语定义；
     * 预期：unknownTableRefs 仅返回悬空表引用，支撑术语库加载时 fail-fast 行为。
     */
    @Test
    void unknownTableRef_isReported() {
        when(schema.hasTable("account")).thenReturn(true);
        // 其余表默认 false（含 ghost_table）

        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 5);
        List<String> bad = g.unknownTableRefs(List.of(
                new Term("可用余额", null, "account.balance ...", List.of("account")),
                new Term("幽灵口径", null, "...", List.of("ghost_table"))));

        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("ghost_table"), bad.toString());
        assertTrue(bad.get(0).contains("幽灵口径"), bad.toString());
    }

    /**
     * 输入：真实 terms 与相关问题；
     * 预期：Top-N 命中相关术语，防止检索退化。
     */
    @Test
    void select_picksRelevantTerm() {
        when(schema.hasTable(any())).thenReturn(true);
        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 3);
        g.load();

        List<GlossaryService.Selected> sel = g.select("国库库存月末余额还有多少");

        assertEquals(3, sel.size());
        assertTrue(sel.stream().anyMatch(s -> s.term().equals("国库库存")),
                "Top-3 应含「国库库存」，实际: " + sel);
    }

    /**
     * 输入：term 数少于 top-n；
     * 预期：全量短路，不触发 embed，保持旧有回退语义一致。
     */
    @Test
    void shortCircuit_whenTermsNotExceedTopN() {
        when(schema.hasTable(any())).thenReturn(true);
        AtomicInteger embeds = new AtomicInteger();
        EmbeddingClient counting = text -> { embeds.incrementAndGet(); return new float[]{1}; };

        GlossaryService g = new GlossaryService(om, schema, counting, true, 100);
        g.load();
        String block = g.formatBlock("任意问题");

        assertEquals(0, embeds.get(), "短路场景不应有任何 embed 调用");
        assertEquals(12, g.all().size());
        for (Term t : g.all()) {
            assertTrue(block.contains("- " + t.term()), "全量注入应含「" + t.term() + "」");
        }
    }

    /**
     * 输入：top-n 配置为 2；
     * 预期：返回块中术语数严格为 2，验证可控 token 预算。
     */
    @Test
    void formatBlock_containsOnlyTopN() {
        when(schema.hasTable(any())).thenReturn(true);
        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 2);
        g.load();

        String block = g.formatBlock("本月净流入是多少");

        long count = block.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(2, count, "应只注入 2 条术语，实际:\n" + block);
    }

    /**
     * 输入：qv 为空（embed 前置失败）；
     * 预期：退化为全量术语注入，避免检索链路阻断。
     */
    @Test
    void nullQv_degradesToFullInjection() {
        when(schema.hasTable(any())).thenReturn(true);
        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 2);
        g.load();

        List<GlossaryService.Selected> sel = g.select("在途资金还有多少", null);

        assertEquals(12, sel.size(), "降级应全量注入");
    }
}
