package com.treasury.nl2sql.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 口径召回双档判定的纯逻辑单测（无 DB/LLM）：
 * 用内联字符哈希 embedding + 覆写 repo，验证「命中直取 / 未命中生成」的分档。
 */
class CaliberStoreTest {

    /** 自足的字符哈希向量：相同文本→相同向量→cosine=1.0（HIT）；差异越大 cosine 越低。 */
    private final EmbeddingClient emb = text -> {
        float[] v = new float[64];
        for (int i = 0; i < text.length(); i++) v[text.charAt(i) % 64]++;
        return v;
    };

    private CaliberStore build(List<CaliberAsset> assets) {
        // 覆写 findAllActive 避免依赖真实 JdbcTemplate；recall/load 不触碰 validator。
        CaliberRepository repo = new CaliberRepository(null) {
            @Override public List<CaliberAsset> findAllActive() { return assets; }
        };
        FewShotSelector fewShot = new FewShotSelector(emb, new ObjectMapper(), null, false, 3); // 禁用，add() 空转
        CaliberStore store = new CaliberStore(emb, repo, null, new ObjectMapper(), fewShot, true, 0.92, 0.75);
        store.load();
        return store;
    }

    @Test
    void hit_onIdenticalQuestion() {
        CaliberAsset a = new CaliberAsset(7, "余额最高的3个账户，列出账户名称和余额", "{}", "按余额降序取前3个账户", "demo", null, "ACTIVE");
        CaliberStore store = build(List.of(a));
        CaliberStore.Recall r = store.recall("余额最高的3个账户，列出账户名称和余额");
        assertEquals(CaliberStore.Band.HIT, r.band());
        assertEquals(7L, r.assetId());
        assertEquals("{}", r.mqlJson());
        assertEquals("按余额降序取前3个账户", r.description(), "固化的口径描述应随召回透传");
        assertTrue(r.score() >= 0.92);
    }

    @Test
    void miss_onUnrelatedQuestion() {
        CaliberAsset a = new CaliberAsset(7, "余额最高的3个账户，列出账户名称和余额", "{}", "按余额降序取前3个账户", "demo", null, "ACTIVE");
        CaliberStore store = build(List.of(a));
        CaliberStore.Recall r = store.recall("abcdefg hijklmn opqrst uvwxyz");
        assertEquals(CaliberStore.Band.MISS, r.band());
        assertNull(r.assetId());
    }

    @Test
    void miss_onEmptyStore() {
        CaliberStore store = build(List.of());
        assertEquals(CaliberStore.Band.MISS, store.recall("任意问题").band());
    }

    @Test
    void byId_returnsHitForKnownAsset_nullForUnknown() {
        CaliberAsset a = new CaliberAsset(7, "余额最高的3个账户", "{}", null, "demo", null, "ACTIVE");
        CaliberStore store = build(List.of(a));
        assertEquals(CaliberStore.Band.HIT, store.byId(7).band());
        assertNull(store.byId(7).description(), "无描述的旧资产 description 应为 null");
        assertNull(store.byId(999));
    }

    @Test
    void recall_withNullQv_isMiss() {
        CaliberAsset a = new CaliberAsset(7, "余额最高的3个账户", "{}", null, "demo", null, "ACTIVE");
        CaliberStore store = build(List.of(a));
        CaliberStore.Recall r = store.recall("余额最高的3个账户", null);
        assertEquals(CaliberStore.Band.MISS, r.band(), "qv=null（embed 失败）应按 MISS 走生成");
        assertNull(r.assetId());
    }

    @Test
    void recall_withProvidedQv_skipsEmbed() {
        CaliberAsset a = new CaliberAsset(7, "余额最高的3个账户", "{}", null, "demo", null, "ACTIVE");
        String q = "余额最高的3个账户";
        float[] qv = emb.embed(q);
        // 构造一个 load 之后就拒绝 embed 的 store：传入 qv 的 recall 不应触碰 embedding
        CaliberRepository repo = new CaliberRepository(null) {
            @Override public List<CaliberAsset> findAllActive() { return List.of(a); }
        };
        boolean[] loaded = {false};
        EmbeddingClient strict = text -> {
            if (loaded[0]) throw new AssertionError("传入 qv 时 recall 不应再 embed");
            return emb.embed(text);
        };
        FewShotSelector fewShot = new FewShotSelector(strict, new ObjectMapper(), null, false, 3);
        CaliberStore store = new CaliberStore(strict, repo, null, new ObjectMapper(), fewShot, true, 0.92, 0.75);
        store.load();
        loaded[0] = true;

        CaliberStore.Recall r = store.recall(q, qv);
        assertEquals(CaliberStore.Band.HIT, r.band());
    }
}
