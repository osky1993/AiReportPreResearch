package com.treasury.nl2sql.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.schema.SchemaService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务术语 / 指标语义层：把"可用余额""在途资金"等业务黑话映射到表/字段/过滤口径。
 * 把口径注入 prompt，让 LLM 按统一口径生成 MQL，避免对术语的字段/条件理解各异。
 *
 * <p>相关性选取（RoadMap G1）：与 Schema Linking / few-shot 同一套向量召回——
 * 启动时把每条术语（term + aliases + definition）向量化，查询时按问题相似度只注入 Top-N。
 * 术语数 ≤ glossary.top-n 时短路为全量注入（零 embed 调用，行为同旧版；调 top-n 需重启）。
 * 向量不可用（qv=null，上游 embed 失败）时降级为全量注入——口径信息零丢失，绝不静默丢口径。
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    private final ObjectMapper mapper;
    private final SchemaService schema;
    private final EmbeddingClient embedding;
    private final boolean enabled;
    private final int topN;
    private final List<Term> terms = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();   // 与 terms 同序；仅 terms>topN 时填充

    public GlossaryService(ObjectMapper mapper, SchemaService schema, EmbeddingClient embedding,
                           @Value("${glossary.enabled:true}") boolean enabled,
                           @Value("${glossary.top-n:5}") int topN) {
        this.mapper = mapper;
        this.schema = schema;
        this.embedding = embedding;
        this.enabled = enabled;
        this.topN = topN;
    }

    public record Term(String term, List<String> aliases, String definition, List<String> tables) {}

    /** 选中的术语（供调试端点观察；全量短路/降级时 score=1.0 占位）。 */
    public record Selected(String term, double score) {}

    @PostConstruct
    public void load() {
        terms.clear();
        vectors.clear();
        if (!enabled) {
            log.info("业务术语层已禁用");
            return;
        }
        try (var in = new ClassPathResource("glossary/terms.json").getInputStream()) {
            Term[] arr = mapper.readValue(in, Term[].class);
            terms.addAll(List.of(arr));
            List<String> bad = unknownTableRefs(terms);
            if (!bad.isEmpty()) {
                log.warn("业务术语引用了 schema 中不存在的表: {}", bad);
            }
            if (terms.size() > topN) {
                vectors.addAll(embedding.embedBatch(terms.stream().map(GlossaryService::searchText).toList()));
                log.info("业务术语字典已加载并建向量索引: {} 条, topN={}", terms.size(), topN);
            } else {
                log.info("业务术语字典已加载: {} 条（≤ topN={}，全量注入不建索引）", terms.size(), topN);
            }
        } catch (Exception e) {
            throw new RuntimeException("加载业务术语字典失败", e);
        }
    }

    /** 术语的检索文本：term + 别名 + 口径定义（定义里含表/字段名，对字面与语义召回都友好）。 */
    private static String searchText(Term t) {
        StringBuilder sb = new StringBuilder(t.term());
        if (t.aliases() != null) sb.append(' ').append(String.join(" ", t.aliases()));
        if (t.definition() != null) sb.append(' ').append(t.definition());
        return sb.toString();
    }

    /** 加载期校验：找出术语 tables 字段里在 schema 中不存在的表引用（格式「术语 -> 表」）。 */
    List<String> unknownTableRefs(List<Term> ts) {
        List<String> bad = new ArrayList<>();
        for (Term t : ts) {
            if (t.tables() == null) continue;
            for (String tb : t.tables()) {
                if (tb != null && !schema.hasTable(tb)) bad.add(t.term() + " -> " + tb);
            }
        }
        return bad;
    }

    public List<Term> all() {
        return terms;
    }

    /** 按问题相关性选 Top-N 术语（自算问题向量；embed 失败降级全量）。 */
    public List<Selected> select(String question) {
        if (shortCircuit()) return allSelected();
        float[] qv;
        try {
            qv = embedding.embed(question);
        } catch (Exception e) {
            log.warn("术语选取 embed 失败，降级全量注入: {}", e.getMessage());
            qv = null;
        }
        return select(question, qv);
    }

    /** 同上，复用已算好的问题向量；qv=null（上游 embed 失败）时降级为全量注入。 */
    public List<Selected> select(String question, float[] qv) {
        if (shortCircuit()) return allSelected();
        if (qv == null) {
            log.warn("术语选取无可用问题向量，降级全量注入");
            return allSelected();
        }
        List<Selected> scored = new ArrayList<>(terms.size());
        for (int i = 0; i < terms.size(); i++) {
            scored.add(new Selected(terms.get(i).term(), EmbeddingClient.cosine(qv, vectors.get(i))));
        }
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))   // 稳定排序，同分按加载顺序
                .limit(topN)
                .toList();
    }

    private boolean shortCircuit() {
        return terms.size() <= topN;   // 含空库/禁用（terms 为空）；短路=全量注入，零 embed
    }

    private List<Selected> allSelected() {
        return terms.stream().map(t -> new Selected(t.term(), 1.0)).toList();
    }

    /** 组装成可拼进 prompt 的「业务术语口径」段（按问题选取 Top-N）；无术语则返回空串 */
    public String formatBlock(String question) {
        return render(select(question));
    }

    /** 同上，复用已算好的问题向量。 */
    public String formatBlock(String question, float[] qv) {
        return render(select(question, qv));
    }

    /**
     * 选中术语声明的 tables 并集（过滤掉 schema 中不存在的）。
     * 供编排层强制并入 schema linking：口径涉及的表（如折算的 currency_rate 无外键、
     * 纯向量召回可能被裁掉）不能缺席，否则模型会因"看不到表"而拒答。
     */
    public java.util.Set<String> tablesOfSelected(String question, float[] qv) {
        if (!enabled || terms.isEmpty()) return java.util.Set.of();
        var picked = select(question, qv).stream().map(Selected::term)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Term t : terms) {
            if (!picked.contains(t.term()) || t.tables() == null) continue;
            for (String tb : t.tables()) {
                if (schema.hasTable(tb)) out.add(tb);
            }
        }
        return out;
    }

    private String render(List<Selected> selected) {
        if (!enabled || selected.isEmpty()) return "";
        var picked = selected.stream().map(Selected::term).collect(java.util.stream.Collectors.toSet());
        StringBuilder sb = new StringBuilder("## 业务术语口径（遇到这些术语/别名，请按口径映射到字段与过滤条件）\n");
        for (Term t : terms) {                     // 按加载顺序渲染，输出顺序稳定
            if (!picked.contains(t.term())) continue;
            sb.append("- ").append(t.term());
            if (t.aliases() != null && !t.aliases().isEmpty()) {
                sb.append("（别名：").append(String.join("、", t.aliases())).append("）");
            }
            sb.append("：").append(t.definition()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
