package com.treasury.nl2sql.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.embedding.LocalHashingEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * LINK item 5：lexical（本地哈希词袋）vs vector（当前激活 embedding，provider=openai 时即真实语义）
 * 的召回质量对比。对一组探针计算：Top-K 命中率（expectedTables 是否全在 Top-K 召回集）+ 平均注入文本量
 * （近似 token，用 assemble(Top-K) 的字符数）。lexical 基线在内部直接 new，无需让两个 embedding Bean 共存。
 */
@Service
public class LinkingEvalService {

    private final SchemaService schema;
    private final EmbeddingClient vectorClient;          // 当前激活的 embedding（openai/local）
    private final EmbeddingClient lexicalClient = new LocalHashingEmbeddingClient();
    private final int topK;
    private final ObjectMapper mapper = new ObjectMapper();

    public LinkingEvalService(SchemaService schema, EmbeddingClient vectorClient,
                              @Value("${schema.linking.top-k:6}") int topK) {
        this.schema = schema;
        this.vectorClient = vectorClient;
        this.topK = topK;
    }

    public record Probe(String question, List<String> expectedTables) {}
    public record ProbeResult(String question, List<String> expected, List<String> recalled,
                              boolean hit, int injectedChars) {}
    public record ModeReport(String mode, double hitRate, double avgInjectedChars, List<ProbeResult> probes) {}
    public record CompareReport(int topK, int probeCount, ModeReport lexical, ModeReport vector) {}

    public CompareReport run() {
        return run(loadProbes());
    }

    CompareReport run(List<Probe> probes) {
        ModeReport lex = score("lexical", lexicalClient, probes);
        ModeReport vec = score("vector", vectorClient, probes);
        return new CompareReport(topK, probes.size(), lex, vec);
    }

    private ModeReport score(String mode, EmbeddingClient client, List<Probe> probes) {
        // 一次性建表向量（按检索文本，批量请求）
        Map<String, float[]> tableVecs = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>(schema.tableNames());
        List<float[]> vecs = client.embedBatch(tables.stream().map(schema::searchText).toList());
        for (int i = 0; i < tables.size(); i++) tableVecs.put(tables.get(i), vecs.get(i));

        List<ProbeResult> results = new ArrayList<>();
        int hits = 0;
        long totalChars = 0;
        for (Probe p : probes) {
            List<String> recalled = topKTables(client, tableVecs, p.question());
            boolean hit = recalled.containsAll(p.expectedTables());
            int chars = schema.assemble(recalled).length();
            if (hit) hits++;
            totalChars += chars;
            results.add(new ProbeResult(p.question(), p.expectedTables(), recalled, hit, chars));
        }
        int n = probes.size();
        double hitRate = n == 0 ? 0 : (double) hits / n;
        double avgChars = n == 0 ? 0 : (double) totalChars / n;
        return new ModeReport(mode, hitRate, avgChars, results);
    }

    private List<String> topKTables(EmbeddingClient client, Map<String, float[]> tableVecs, String question) {
        float[] qv = client.embed(question);
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (var e : tableVecs.entrySet())
            scored.add(Map.entry(e.getKey(), EmbeddingClient.cosine(qv, e.getValue())));
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) out.add(scored.get(i).getKey());
        return out;
    }

    List<Probe> loadProbes() {
        try (var in = new ClassPathResource("eval/linking-probes.json").getInputStream()) {
            return List.of(mapper.readValue(in, Probe[].class));
        } catch (Exception e) {
            throw new RuntimeException("加载 linking-probes.json 失败", e);
        }
    }
}
