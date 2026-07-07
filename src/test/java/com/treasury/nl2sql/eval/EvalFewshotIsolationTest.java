package com.treasury.nl2sql.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估集与 few-shot 示例库隔离守卫（离线、可进 CI）：
 * 防答案泄漏——评估问题不得与任一 few-shot 示例问题「文本完全相同」或「字面近重复」。
 * 用本地哈希向量（确定性、无网络）做字面相似度；语义级近重复需真实 embedding（见 RoadMapTODO）。
 */
class EvalFewshotIsolationTest {

    // 本地字面相似度上限：抓「复制粘贴级」同题（如同一问题换个数字写法）。
    // 语义级近重复（改写）由评估运行时的 few-shot 近重复排除兜底（见 EvalService.fewshotLeakageThreshold）。
    private static final double THRESHOLD = 0.78;
    private final EmbeddingClient emb = new LocalHashingEmbeddingClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> evalQuestions() throws Exception {
        try (var in = new ClassPathResource("eval/golden-queries.json").getInputStream()) {
            JsonNode arr = mapper.readTree(in);
            List<String> qs = new ArrayList<>();
            arr.forEach(n -> qs.add(n.path("question").asText()));
            return qs;
        }
    }

    private List<String> fewshotQuestions() throws Exception {
        try (var in = new ClassPathResource("fewshot/examples.json").getInputStream()) {
            JsonNode arr = mapper.readTree(in);
            List<String> qs = new ArrayList<>();
            arr.forEach(n -> qs.add(n.path("question").asText()));
            return qs;
        }
    }

    @Test
    void evalSet_isIsolatedFromFewshot() throws Exception {
        List<String> evalQs = evalQuestions();
        List<String> fsQs = fewshotQuestions();
        List<float[]> fsVecs = fsQs.stream().map(emb::embed).toList();

        List<String> violations = new ArrayList<>();
        for (String eq : evalQs) {
            // 文本完全相同（归一化空白）→ 必判泄漏
            for (String fq : fsQs) {
                if (eq.trim().equals(fq.trim())) {
                    violations.add("[完全相同] " + eq);
                }
            }
            // 字面近重复
            float[] v = emb.embed(eq);
            double max = 0; String nearest = "";
            for (int i = 0; i < fsQs.size(); i++) {
                double s = EmbeddingClient.cosine(v, fsVecs.get(i));
                if (s > max) { max = s; nearest = fsQs.get(i); }
            }
            if (max >= THRESHOLD) {
                violations.add(String.format("[近重复 %.3f] 「%s」 ~ 「%s」", max, eq, nearest));
            }
        }
        assertTrue(violations.isEmpty(),
                "评估集与 few-shot 存在泄漏（请改写其一使其互不重叠）:\n" + String.join("\n", violations));
    }
}
