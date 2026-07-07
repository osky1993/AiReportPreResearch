package com.treasury.nl2sql.fewshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector.Example;
import com.treasury.nl2sql.validate.MqlValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector.Selected;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 加载期校验：非法 few-shot 示例被剔除，合法保留。 */
class FewShotSelectorTest {

    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final MqlValidator validator = mock(MqlValidator.class);
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void invalidExample_isFilteredOut() {
        when(validator.validate(any())).thenReturn(List.of());
        when(validator.validate(argThat(m -> m != null && "bad".equals(m.table))))
                .thenReturn(List.of("表不存在: bad"));

        FewShotSelector sel = new FewShotSelector(embedding, om, validator, true, 3);

        JsonNode good = om.valueToTree(Map.of("table", "account"));
        JsonNode bad = om.valueToTree(Map.of("table", "bad"));
        List<Example> valid = sel.filterValid(new Example[]{
                new Example("好示例", good), new Example("坏示例", bad)});

        assertEquals(1, valid.size());
        assertEquals("好示例", valid.get(0).question());
    }

    @Test
    void leakageExclusion_dropsNearDuplicateExample() {
        // 用真实示例库 + 本地哈希向量；validator 全放行
        when(validator.validate(any())).thenReturn(List.of());
        FewShotSelector sel = new FewShotSelector(new LocalHashingEmbeddingClient(), om, validator, true, 3);
        sel.load();

        String dup = "按币种统计账户数量";   // 与某条示例问题完全相同 → 自身余弦=1.0

        // 不排除：该示例应被选中（相似度最高）
        List<Selected> withDup = sel.select(dup, Double.POSITIVE_INFINITY);
        assertTrue(withDup.stream().anyMatch(s -> s.question().equals(dup)),
                "未排除时应命中完全相同的示例");

        // 排除阈值 0.99：完全相同（cos≈1.0）的示例被剔除，防泄漏
        List<Selected> excluded = sel.select(dup, 0.99);
        assertFalse(excluded.stream().anyMatch(s -> s.question().equals(dup)),
                "阈值排除后不应再含完全相同的示例");
    }

    @Test
    void select_withNullQv_returnsEmpty() {
        when(validator.validate(any())).thenReturn(List.of());
        FewShotSelector sel = new FewShotSelector(new LocalHashingEmbeddingClient(), om, validator, true, 3);
        sel.load();

        assertTrue(sel.select("任意问题", Double.POSITIVE_INFINITY, null).isEmpty(),
                "qv=null（embed 失败）应返回空，few-shot 缺失可接受");
        assertEquals("", sel.formatBlock("任意问题", Double.POSITIVE_INFINITY, null));
    }

    @Test
    void select_withProvidedQv_doesNotEmbedAgain() {
        when(validator.validate(any())).thenReturn(List.of());
        LocalHashingEmbeddingClient real = new LocalHashingEmbeddingClient();
        java.util.concurrent.atomic.AtomicInteger selectEmbeds = new java.util.concurrent.atomic.AtomicInteger();
        boolean[] loaded = {false};
        EmbeddingClient counting = text -> {
            if (loaded[0]) selectEmbeds.incrementAndGet();
            return real.embed(text);
        };
        FewShotSelector sel = new FewShotSelector(counting, om, validator, true, 3);
        sel.load();
        loaded[0] = true;

        String q = "按币种统计账户数量";
        List<Selected> out = sel.select(q, Double.POSITIVE_INFINITY, real.embed(q));

        assertEquals(0, selectEmbeds.get(), "传入 qv 时 select 期不应再 embed");
        assertFalse(out.isEmpty());
    }
}
