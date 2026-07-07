package com.treasury.nl2sql.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地哈希向量（hashing trick）：离线、无外部依赖。
 * 分词策略对中英文都适用：英文按词、中文按字+相邻二字组（bigram），
 * 用 token 哈希落桶 + TF 计数，再 L2 归一化。用于在没有真实 embedding 服务时演示向量召回链路。
 * 配置 embedding.provider=openai 可切换到 {@link OpenAiCompatibleEmbeddingClient}。
 */
@Component
@ConditionalOnProperty(name = "embedding.provider", havingValue = "local", matchIfMissing = true)
public class LocalHashingEmbeddingClient implements EmbeddingClient {

    private static final int DIM = 512;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        if (text == null) return v;
        for (String tok : tokenize(text.toLowerCase())) {
            int h = Math.floorMod(tok.hashCode(), DIM);
            v[h] += 1f;
        }
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < DIM; i++) v[i] /= norm;
        return v;
    }

    private static java.util.List<String> tokenize(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder word = new StringBuilder();
        char[] cs = s.toCharArray();
        String prevHan = null;
        for (char c : cs) {
            if (isHan(c)) {
                if (word.length() > 0) { out.add(word.toString()); word.setLength(0); }
                String cur = String.valueOf(c);
                out.add(cur);                                   // 单字
                if (prevHan != null) out.add(prevHan + cur);    // 相邻二字组
                prevHan = cur;
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
                prevHan = null;
            } else {
                if (word.length() > 0) { out.add(word.toString()); word.setLength(0); }
                prevHan = null;
            }
        }
        if (word.length() > 0) out.add(word.toString());
        return out;
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
