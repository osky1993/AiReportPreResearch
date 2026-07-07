package com.treasury.nl2sql.schema;

import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 向量召回 Top-K + qv 复用/降级。纯逻辑，无 DB/网络。 */
class VectorSchemaLinkerTest {

    private SchemaService mockSchema() {
        SchemaService schema = mock(SchemaService.class);
        when(schema.tableNames()).thenReturn(new LinkedHashSet<>(List.of("account", "transaction", "currency_rate")));
        when(schema.searchText("account")).thenReturn("account 账户表 余额 balance 状态 status");
        when(schema.searchText("transaction")).thenReturn("transaction 交易流水表 金额 amount 方向 direction");
        when(schema.searchText("currency_rate")).thenReturn("currency_rate 汇率表 币种 汇率");
        when(schema.fkClosure(anyCollection(), anyInt())).thenReturn(Set.of());
        when(schema.assemble(anyCollection())).thenAnswer(inv -> String.join(",", (Iterable<String>) inv.getArgument(0)));
        return schema;
    }

    @Test
    void select_topK_hitsRelevantTable() {
        VectorSchemaLinker linker = new VectorSchemaLinker(mockSchema(), new LocalHashingEmbeddingClient(), 1, 1);
        linker.index();

        SchemaLinker.LinkingResult r = linker.select("账户余额还有多少");

        assertEquals(List.of("account"), r.tables(), "Top-1 应命中 account，实际: " + r.tables());
        assertFalse(r.scores().isEmpty(), "应返回全部表的相似度分数");
    }

    @Test
    void select_withNullQv_degradesToAllTables() {
        VectorSchemaLinker linker = new VectorSchemaLinker(mockSchema(), new LocalHashingEmbeddingClient(), 1, 1);
        linker.index();

        SchemaLinker.LinkingResult r = linker.select("账户余额", null);

        assertEquals(List.of("account", "transaction", "currency_rate"), r.tables(),
                "qv=null 应降级为全量注入");
        assertTrue(r.scores().isEmpty(), "降级路径无相似度分数");
    }

    @Test
    void select_whenEmbedThrows_degradesInsteadOfFailing() {
        SchemaService schema = mockSchema();
        // 建索引正常、查询期（对该问题）embed 抛异常的桩
        String question = "账户余额还有多少";
        EmbeddingClient flaky = new EmbeddingClient() {
            private final EmbeddingClient delegate = new LocalHashingEmbeddingClient();
            @Override public float[] embed(String text) {
                if (question.equals(text)) throw new RuntimeException("embedding down");
                return delegate.embed(text);
            }
        };
        VectorSchemaLinker linker = new VectorSchemaLinker(schema, flaky, 1, 1);
        linker.index();

        SchemaLinker.LinkingResult r = assertDoesNotThrow(() -> linker.select(question));

        assertEquals(3, r.tables().size(), "embed 异常应降级为全量注入而非抛出");
    }
}
