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

/** 加载期校验 + 术语相关性选取（G1）。纯逻辑，无 DB/LLM/网络。 */
class GlossaryServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SchemaService schema = mock(SchemaService.class);

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

    /** 真实 terms.json（12 条）+ 本地哈希向量：相关术语应进 Top-N。 */
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

    /** 术语数 ≤ top-n 时全量短路：零 embed 调用，输出与旧全量行为一致。 */
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

    /** Top-N 生效：输出块只含 N 条术语。 */
    @Test
    void formatBlock_containsOnlyTopN() {
        when(schema.hasTable(any())).thenReturn(true);
        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 2);
        g.load();

        String block = g.formatBlock("本月净流入是多少");

        long count = block.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(2, count, "应只注入 2 条术语，实际:\n" + block);
    }

    /** qv=null（上游 embed 失败）→ 降级为全量注入，口径信息零丢失。 */
    @Test
    void nullQv_degradesToFullInjection() {
        when(schema.hasTable(any())).thenReturn(true);
        GlossaryService g = new GlossaryService(om, schema, new LocalHashingEmbeddingClient(), true, 2);
        g.load();

        List<GlossaryService.Selected> sel = g.select("在途资金还有多少", null);

        assertEquals(12, sel.size(), "降级应全量注入");
    }
}
