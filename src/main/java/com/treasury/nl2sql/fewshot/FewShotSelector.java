package com.treasury.nl2sql.fewshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.validate.MqlValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 动态 few-shot：维护一个「问题 -> MQL」示例库，启动时给每个示例问题向量化；
 * 查询时按余弦相似度选 Top-N 最相关示例注入 prompt（取代写死的固定示例）。
 * 复用 {@link EmbeddingClient}（local 离线 / openai 可切换）。
 */
@Service
public class FewShotSelector {

    private static final Logger log = LoggerFactory.getLogger(FewShotSelector.class);

    private final EmbeddingClient embedding;
    private final ObjectMapper mapper;
    private final MqlValidator validator;
    private final boolean enabled;
    private final int topN;

    // COW：web 请求/并行评估的并发 select() 与人在回路 add() 回流写入共存，写少读多
    private final List<Indexed> index = new CopyOnWriteArrayList<>();

    public FewShotSelector(EmbeddingClient embedding, ObjectMapper mapper, MqlValidator validator,
                           @Value("${fewshot.enabled:true}") boolean enabled,
                           @Value("${fewshot.top-n:3}") int topN) {
        this.embedding = embedding;
        this.mapper = mapper;
        this.validator = validator;
        this.enabled = enabled;
        this.topN = topN;
    }

    /** 资源文件里的一条示例 */
    public record Example(String question, JsonNode mql) {}

    private record Indexed(String question, String mqlJson, float[] vector) {}

    /** 选中的示例（供调试端点观察） */
    public record Selected(String question, double score, String mqlJson) {}

    @PostConstruct
    public void load() {
        index.clear();
        if (!enabled) {
            log.info("few-shot 已禁用");
            return;
        }
        try (var in = new ClassPathResource("fewshot/examples.json").getInputStream()) {
            Example[] examples = mapper.readValue(in, Example[].class);
            List<Example> valid = filterValid(examples);
            List<float[]> vectors = embedding.embedBatch(valid.stream().map(Example::question).toList());
            List<Indexed> built = new ArrayList<>(valid.size());   // 先建临时表再整体加入，COW 下也避免逐条复制
            for (int i = 0; i < valid.size(); i++) {
                Example e = valid.get(i);
                built.add(new Indexed(e.question(), mapper.writeValueAsString(e.mql()), vectors.get(i)));
            }
            index.addAll(built);
            int skipped = examples.length - valid.size();
            log.info("few-shot 示例库已索引: {} 条, topN={}{}", index.size(), topN,
                    skipped > 0 ? "（跳过 " + skipped + " 条非法示例）" : "");
        } catch (Exception ex) {
            throw new RuntimeException("加载 few-shot 示例库失败", ex);
        }
    }

    /**
     * 运行时回流：把一条被核验采纳的「问题->MQL」追加进内存示例库（人在回路沉淀的软增益出口）。
     * 由 {@code CaliberStore} 在启动回放与采纳沉淀时调用。校验非法/重复者静默跳过，绝不污染 prompt。
     */
    public void add(String question, String mqlJson) {
        if (!enabled || question == null || mqlJson == null) return;
        for (Indexed it : index) {                 // 按问题去重，避免重复沉淀堆积
            if (it.question().equals(question)) return;
        }
        try {
            Mql mql = mapper.readValue(mqlJson, Mql.class);
            List<String> errs = validator.validate(mql);
            if (!errs.isEmpty()) {
                log.warn("few-shot 回流示例非法被跳过: 问题=「{}」错误={}", question, errs);
                return;
            }
            index.add(new Indexed(question, mqlJson, embedding.embed(question)));
            log.info("few-shot 回流新增示例: 问题=「{}」（当前 {} 条）", question, index.size());
        } catch (Exception ex) {
            log.warn("few-shot 回流示例解析失败被跳过: 问题=「{}」: {}", question, ex.getMessage());
        }
    }

    /** 加载期校验：把每条示例的 mql 跑 MqlValidator，非法/解析失败者 log.warn 并剔除，防污染 prompt。 */
    List<Example> filterValid(Example[] examples) {
        List<Example> ok = new ArrayList<>();
        for (Example e : examples) {
            try {
                Mql mql = mapper.treeToValue(e.mql(), Mql.class);
                List<String> errs = validator.validate(mql);
                if (errs.isEmpty()) {
                    ok.add(e);
                } else {
                    log.warn("few-shot 示例非法被跳过: 问题=「{}」错误={}", e.question(), errs);
                }
            } catch (Exception ex) {
                log.warn("few-shot 示例解析失败被跳过: 问题=「{}」: {}", e.question(), ex.getMessage());
            }
        }
        return ok;
    }

    /** 按相似度选 Top-N 示例 */
    public List<Selected> select(String question) {
        return select(question, Double.POSITIVE_INFINITY);
    }

    /**
     * 按相似度选 Top-N 示例，并排除与问题相似度 ≥ maxSim 的「近重复」示例。
     * maxSim=+∞（默认）= 不排除（生产查询：给用户看高相关已解样例是好事）；
     * 评估时传一个阈值（如 0.9）排除近重复示例，避免答案泄漏、衡量真实泛化。
     */
    public List<Selected> select(String question, double maxSim) {
        if (!enabled || index.isEmpty()) return List.of();
        float[] qv;
        try {
            qv = embedding.embed(question);
        } catch (Exception e) {
            log.warn("few-shot 问题向量化失败: {}", e.getMessage());
            qv = null;
        }
        return select(question, maxSim, qv);
    }

    /** 同上，复用上游已算好的问题向量；qv=null（embed 失败）→ 返回空（few-shot 是软增益，缺失可接受）。 */
    public List<Selected> select(String question, double maxSim, float[] qv) {
        if (!enabled || index.isEmpty()) return List.of();
        if (qv == null) {
            log.warn("few-shot 无可用问题向量，本次不注入示例");
            return List.of();
        }
        return index.stream()
                .map(it -> new Selected(it.question(), EmbeddingClient.cosine(qv, it.vector()), it.mqlJson()))
                .filter(s -> s.score() < maxSim)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topN)
                .toList();
    }

    /** 组装成可直接拼进 prompt 的「## 示例」段；无示例则返回空串 */
    public String formatBlock(String question) {
        return formatBlock(question, Double.POSITIVE_INFINITY);
    }

    /** 同上，带近重复排除阈值（评估隔离用）。 */
    public String formatBlock(String question, double maxSim) {
        return render(select(question, maxSim));
    }

    /** 同上，复用上游已算好的问题向量。 */
    public String formatBlock(String question, double maxSim, float[] qv) {
        return render(select(question, maxSim, qv));
    }

    private static String render(List<Selected> sel) {
        if (sel.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 示例（按与当前问题的相似度动态选取）\n");
        for (Selected s : sel) {
            sb.append("问题：").append(s.question()).append('\n')
              .append("输出：").append(s.mqlJson()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}
