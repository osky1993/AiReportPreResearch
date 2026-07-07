package com.treasury.nl2sql.embedding;

import java.util.ArrayList;
import java.util.List;

/** 向量化抽象：默认本地实现离线可跑，可配置切换到真实 embedding 模型。 */
public interface EmbeddingClient {
    float[] embed(String text);

    /**
     * 批量向量化，返回与入参同序的向量列表。
     * 默认逐条调 {@link #embed}（本地实现够用）；走远程 API 的实现应覆写为真批量请求，
     * 否则启动建索引会逐条打接口（慢且费钱）。
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embed(t));
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
