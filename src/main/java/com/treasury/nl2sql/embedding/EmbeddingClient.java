package com.treasury.nl2sql.embedding;

import java.util.ArrayList;
import java.util.List;

/** 向量化抽象：默认本地实现离线可跑，可配置切换到真实 embedding 模型。 */
public interface EmbeddingClient {
    /**
     * 将文本编码为稠密向量。
     *
     * <p>不承诺固定维度，约定同一进程同一实例在同一次启动期保持维度一致；
     * 不一致时将导致上层余弦计算不可用，通常以降级策略失败重跑。
     *
     * @param text 待编码文本
     * @return 向量；实现应尽量保持无副作用（例如不缓存写入状态）
     */
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

    /**
     * 余弦相似度，用于检索打分。长度 0 向量按 0 处理，避免出现 Inf/NaN。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 相似度分数 [0,1] 近似区间；对不兼容向量长度按短边计算
     */
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
