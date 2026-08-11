package com.treasury.nl2sql.schema;

import com.treasury.nl2sql.schema.LinkingEvalService.CompareReport;
import com.treasury.nl2sql.schema.LinkingEvalService.Probe;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LINK item 5 召回评测单测（无 DB/网络）。
 * 比较 lexical 与 vector 两类命中路径的 Probe 报告结构、命中率与回退行为，检验 schema 搜索质量指标是否可被消费。
 */
class LinkingEvalServiceTest {

    private final SchemaService schema = mock(SchemaService.class);

    /**
     * 构造 23 张合成表：每张表检索文本含唯一关键词，便于 lexical 的字面检验。
     * 仅用于验证命中率与报告结构，不依赖真实数据库。
     */
    private void mockSchema() {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 1; i <= 23; i++) {
            String t = "tbl_" + i;
            names.add(t);
            when(schema.searchText(t)).thenReturn(t + " 关键词" + i + "专属内容");
        }
        when(schema.tableNames()).thenReturn(names);
        // assemble 返回长度可度量的串
        when(schema.assemble(org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(inv -> String.join(",", (java.util.Collection<String>) inv.getArgument(0)));
    }

    /**
     * 输入：两个 query 均可被 lexical 精确命中。
     * 预期：topK=3 时命中率为 1.0，且每条 probe 返回长度为 topK 的召回列表。
     */
    @Test
    void report_hasBothModes_andComputesHitRate() {
        mockSchema();
        // top-k=3：用 lexical（本地哈希）做 vector 客户端也可，这里只验证结构与命中率计算正确性
        LinkingEvalService svc = new LinkingEvalService(schema, new com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient(), 3);

        List<Probe> probes = List.of(
                new Probe("关键词5专属内容", List.of("tbl_5")),     // lexical 应命中
                new Probe("关键词12专属内容", List.of("tbl_12")));   // lexical 应命中

        CompareReport r = svc.run(probes);

        assertEquals(3, r.topK());
        assertEquals(2, r.probeCount());
        assertNotNull(r.lexical());
        assertNotNull(r.vector());
        assertEquals("lexical", r.lexical().mode());
        assertEquals("vector", r.vector().mode());
        assertEquals(2, r.lexical().probes().size());
        // 字面强匹配，lexical 命中率应为 1.0
        assertEquals(1.0, r.lexical().hitRate(), 1e-9);
        // 每条 recalled 长度 = topK
        for (var pr : r.lexical().probes()) {
            assertEquals(3, pr.recalled().size());
            assertTrue(pr.injectedChars() > 0);
        }
    }

    /**
     * 输入：存在目标关键词但召回集合不含目标表。
     * 预期：命中率降为 0，probe.hit=false，覆盖 miss 分支。
     */
    @Test
    void miss_isCountedAsZeroHit() {
        mockSchema();
        LinkingEvalService svc = new LinkingEvalService(schema, new com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient(), 3);
        // 期望一张不存在/不会被召回的表
        List<Probe> probes = List.of(new Probe("关键词5专属内容", List.of("tbl_999")));
        CompareReport r = svc.run(probes);
        assertEquals(0.0, r.lexical().hitRate(), 1e-9);
        assertFalse(r.lexical().probes().get(0).hit());
    }
}
