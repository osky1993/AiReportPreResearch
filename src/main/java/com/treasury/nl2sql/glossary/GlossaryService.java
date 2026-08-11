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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务术语/口径语义层。
 *
 * <p>把“可用余额”“在途资金”等业务黑话映射为具体 schema 提示语，减少术语歧义导致的字段/过滤偏差。
 * 典型职责是：术语入库与向量建模、问题相关术语召回、将口径与相关表名并入提示上下文。
 *
 * <p>失败策略：
 * <ul>
 *   <li>向量化失败不阻断主链路，直接降级全量口径注入。</li>
 *   <li>术语关联表指向不存在的物理表只记录告警，不中断启动；依赖方再决定是否拒绝生成。</li>
 * </ul>
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** JSON 反序列化器，用于加载 glossary/terms.json。 */
    private final ObjectMapper mapper;
    /** Schema 白名单来源，用于校验术语声明的 tables 合法性。 */
    private final SchemaService schema;
    /** 术语向量化能力；可为本地哈希或远端 embedding。 */
    private final EmbeddingClient embedding;
    /** 开关：关闭后不再注入任何术语口径。 */
    private final boolean enabled;
    /** Top-N 截断阈值；低于阈值采用全量注入。 */
    private final int topN;
    /** 已加载术语，保持加载顺序。 */
    private final List<Term> terms = new ArrayList<>();
    /**
     * 与 {@link #terms} 同序的向量库。只有 {@code terms.size()>topN} 时建立索引；
     * 该特例可以避免少量术语场景下的向量调用。
     */
    private final List<float[]> vectors = new ArrayList<>();

    /**
     * 构造器。
     * @param mapper ObjectMapper
     * @param schema schema 校验服务
     * @param embedding embedding 客户端
     * @param enabled 是否开启术语召回
     * @param topN Top-N 阈值；小于或等于 0 时退化为全量注入行为
     */
    public GlossaryService(ObjectMapper mapper, SchemaService schema, EmbeddingClient embedding,
                           @Value("${glossary.enabled:true}") boolean enabled,
                           @Value("${glossary.top-n:5}") int topN) {
        this.mapper = mapper;
        this.schema = schema;
        this.embedding = embedding;
        this.enabled = enabled;
        this.topN = topN;
    }

    /**
     * 术语定义：
     * <ul>
     *   <li>term：术语主词</li>
     *   <li>aliases：同义别名</li>
     *   <li>definition：中文口径解释，含表/字段线索可增强召回</li>
     *   <li>tables：术语涉及的表名，用于强制补表</li>
     * </ul>
     */
    public record Term(String term, List<String> aliases, String definition, List<String> tables) {}

    /** 术语选中记录：仅用于观测与调试，分数非业务判断依据。 */
    public record Selected(String term, double score) {}

    /**
     * PostConstruct 生命周期：加载 terms.json、校验表引用、决定是否建立向量索引。
     *
     * <p>规则：
     * <ul>
     *   <li>未开启术语模块则仅记录日志并返回。</li>
     *   <li>术语文件加载失败直接抛异常，阻断启动（避免缺口口径默默丢失）。</li>
     *   <li>术语数超 topN 时预建向量索引；否则保留全量注入、零 embed 调用。</li>
     * </ul>
     */
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

    /**
     * 构造语义检索文本：term + aliases + definition。
     */
    private static String searchText(Term t) {
        StringBuilder sb = new StringBuilder(t.term());
        if (t.aliases() != null) sb.append(' ').append(String.join(" ", t.aliases()));
        if (t.definition() != null) sb.append(' ').append(t.definition());
        return sb.toString();
    }

    /**
     * 检查术语中显式 tables 的存在性，返回无效引用，供启动日志预警。
     * 返回格式 {@code term -> table}。
     */
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

    /**
     * 取所有已加载术语（含 score 前置计算前）。
     * 返回当前实例持有对象，调用方不应修改。
     */
    public List<Term> all() {
        return terms;
    }

    /**
     * 问题级别选术语入口。优先计算问题向量，再按分数 Top-N。
     * 向量异常会降级到全量注入，保证口径安全。
     */
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

    /**
     * 复用外部算好的问题向量进行评分；qv 为空时等价全量注入。
     *
     * <p>稳定性说明：
     * <ul>
     *   <li>scores 按降序，score 相同保留加载顺序（Stream.toList 下游稳定顺序）。</li>
     *   <li>vectors 与 terms 长度必须对应，向量模型切换要保证顺序不变。</li>
     * </ul>
     */
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
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topN)
                .toList();
    }

    /**
     * 决定是否短路为全量注入。
     * 术语为空、关闭特性、术语数小于阈值时都走全量，避免无谓 embed 成本。
     */
    private boolean shortCircuit() {
        return !enabled || terms.size() <= topN;
    }

    /**
     * 全量术语的占位打分列表（score=1），仅用于降级路径与调试。
     */
    private List<Selected> allSelected() {
        return terms.stream().map(t -> new Selected(t.term(), 1.0)).toList();
    }

    /**
     * 组装 prompt 片段（按问题选取 Top-N）；空结果返回空字符串。
     */
    public String formatBlock(String question) {
        return render(select(question));
    }

    /**
     * 组装 prompt 片段（复用问题向量路径）。
     */
    public String formatBlock(String question, float[] qv) {
        return render(select(question, qv));
    }

    /**
     * 输出全部选中术语涉及的 tables 去重集，供编排层 Schema Linking 强制并表。
     * 仅在术语命中后触发，过滤掉不存在表。
     */
    public Set<String> tablesOfSelected(String question, float[] qv) {
        if (!enabled || terms.isEmpty()) return Set.of();
        var picked = select(question, qv).stream().map(Selected::term)
                .collect(Collectors.toSet());
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Term t : terms) {
            if (!picked.contains(t.term()) || t.tables() == null) continue;
            for (String tb : t.tables()) {
                if (schema.hasTable(tb)) out.add(tb);
            }
        }
        return out;
    }

    /**
     * 将选中术语渲染成稳定顺序的文本块，保持加载顺序决定稳定输出，便于排障复现。
     */
    private String render(List<Selected> selected) {
        if (!enabled || selected.isEmpty()) return "";
        var picked = selected.stream().map(Selected::term).collect(Collectors.toSet());
        StringBuilder sb = new StringBuilder("## 业务术语口径（遇到这些术语/别名，请按口径映射到字段与过滤条件）\n");
        for (Term t : terms) {
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
