package com.treasury.nl2sql.schema;

import com.treasury.nl2sql.embedding.EmbeddingClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 向量召回：启动时把每张表（表名+注释+列）向量化；查询时把问题向量化，
 * 取余弦相似度 Top-K 表，再做多跳「外键闭包」补全（fk-hops 跳，默认 1，保证 JOIN 能连到名称/中间表），最后只注入这些表。
 * 切换 embedding 模型只需改 embedding.* 配置（见 EmbeddingClient 实现）。
 */
@Component
@ConditionalOnProperty(name = "schema.linking.mode", havingValue = "vector")
public class VectorSchemaLinker implements SchemaLinker {

    private static final Logger log = LoggerFactory.getLogger(VectorSchemaLinker.class);

    private final SchemaService schema;
    private final EmbeddingClient embedding;
    private final int topK;
    private final int fkHops;

    private final Map<String, float[]> tableVectors = new LinkedHashMap<>();

    public VectorSchemaLinker(SchemaService schema, EmbeddingClient embedding,
                              @Value("${schema.linking.top-k:2}") int topK,
                              @Value("${schema.linking.fk-hops:1}") int fkHops) {
        this.schema = schema;
        this.embedding = embedding;
        this.topK = topK;
        this.fkHops = fkHops;
    }

    @PostConstruct
    public void index() {
        tableVectors.clear();
        List<String> tables = new ArrayList<>(schema.tableNames());
        List<float[]> vectors = embedding.embedBatch(tables.stream().map(schema::searchText).toList());
        for (int i = 0; i < tables.size(); i++) {
            tableVectors.put(tables.get(i), vectors.get(i));
        }
        log.info("向量索引完成: {} 张表, topK={}", tableVectors.size(), topK);
    }

    @Override
    public LinkingResult select(String question) {
        float[] qv;
        try {
            qv = embedding.embed(question);
        } catch (Exception e) {
            log.warn("问题向量化失败: {}", e.getMessage());
            qv = null;
        }
        return select(question, qv);
    }

    /** qv=null（embed 失败）→ 降级为全量注入（FullSchemaLinker 行为），链路不断。 */
    @Override
    public LinkingResult select(String question, float[] qv) {
        if (qv == null) {
            log.warn("向量召回不可用，降级为全量 schema 注入");
            LinkedHashSet<String> all = new LinkedHashSet<>(schema.tableNames());
            return new LinkingResult(new ArrayList<>(all), schema.assemble(all), List.of());
        }

        List<Scored> scores = new ArrayList<>();
        for (var e : tableVectors.entrySet()) {
            scores.add(new Scored(e.getKey(), EmbeddingClient.cosine(qv, e.getValue())));
        }
        scores.sort((a, b) -> Double.compare(b.score(), a.score()));

        // Top-K
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            selected.add(scores.get(i).table());
        }
        // 外键闭包（多跳，默认 fk-hops=1）：把与已选表沿外键可达的表补进来，保证可 JOIN
        selected.addAll(schema.fkClosure(selected, fkHops));

        return new LinkingResult(new ArrayList<>(selected), schema.assemble(selected), scores);
    }
}
