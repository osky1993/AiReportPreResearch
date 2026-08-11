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

    /**
     * 全局 logger。
     */
    private static final Logger log = LoggerFactory.getLogger(FewShotSelector.class);

    /**
     * 依赖：用于把问题文本向量化（动态 few-shot 的检索核心）。
     */
    private final EmbeddingClient embedding;
    /**
     * 依赖：负责 few-shot 示例 JSON 与入参 mql 的序列化/反序列化。
     */
    private final ObjectMapper mapper;
    /**
     * 依赖：对入库示例与回流示例做统一校验，防止污染 prompt 候选池。
     */
    private final MqlValidator validator;
    /**
     * 运行时开关：true 时参与选择/回流，false 时保持无害空行为以降级。
     */
    private final boolean enabled;
    /**
     * 单次 select 返回的示例条数上限，决定 prompt 中 few-shot 片段规模。
     */
    private final int topN;

    /**
     * COW 索引：满足并发读多写少场景（线上 select 与人工回流 add 并存），并避免读放大时的互斥锁。
     */
    private final List<Indexed> index = new CopyOnWriteArrayList<>();

    /**
     * @param embedding 问题与示例向量化客户端
     * @param mapper Jackson 映射器
     * @param validator MQL 语法与白名单校验器；过滤非法示例
     * @param enabled false 时服务仍可启动，但不会执行向量召回
     * @param topN 返回 Top-N 示例，决定注入 prompt 的上下文长度
     */
    public FewShotSelector(EmbeddingClient embedding, ObjectMapper mapper, MqlValidator validator,
                           @Value("${fewshot.enabled:true}") boolean enabled,
                           @Value("${fewshot.top-n:3}") int topN) {
        this.embedding = embedding;
        this.mapper = mapper;
        this.validator = validator;
        this.enabled = enabled;
        this.topN = topN;
    }

    /**
     * 资源文件中的少样本实例。
     * @param question 用户问题原文
     * @param mql 该问题对应的 MQL JSON 片段
     */
    public record Example(String question, JsonNode mql) {}

    /**
     * 内存索引中的标准化候选项（已落库为字符串和向量，便于热路径检索）。
     */
    private record Indexed(String question, String mqlJson, float[] vector) {}

    /**
     * 选择结果（供调试日志/端点观测），包含相似度分数与可回放的 MQL JSON。
     */
    public record Selected(String question, double score, String mqlJson) {}

    /**
     * 启动期加载 few-shot 示例，并过滤非法示例。失败则启动直接抛错，避免 prompt 注入降级到不可预测状态。
     */
    @PostConstruct
    public void load() {
        index.clear();
        if (!enabled) {
            log.info("few-shot 已禁用");
            return;
        }
        try (var in = new ClassPathResource("fewshot/examples.json").getInputStream()) {
            Example[] examples = mapper.readValue(in, Example[].class);
            List<Example> valid = filterValid(examples); // 先做静态白名单校验，避免将坏样本注入内存
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
     *
     * @param question 问题文本；空/null 会被直接忽略
     * @param mqlJson MQL JSON 字符串；解析失败或校验失败均只打日志并跳过，不抛异常
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

    /**
     * 加载期校验：把每条示例的 mql 跑 {@link MqlValidator}，非法或解析失败者记录日志并剔除，避免把坏样本放进 prompt 候选池。
     * <p>规则：所有通过示例都要可复算且可映射白名单；失败样本直接丢弃、不中断启动（除 JSON 资源文件损坏）。
     *
     * @param examples 类路径资源中的原始示例数组
     * @return 已通过校验可用示例
     */
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

    /**
     * 按问题向量化后选择候选（默认不做近重复排除）。
     *
     * @param question 自然语言问题
     * @return 分数按降序排序的 Top-N 示例；若服务关闭或索引空则返回空列表
     */
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

    /**
     * 按调用方预计算的向量执行候选检索（避免重复 embed）。
     * <p>上游在异常情况下可传 null；此时返回空结果，由调用方直接忽略 few-shot 影响。
     *
     * @param question 当前用户问题
     * @param maxSim 近重复排除阈值，评估场景可设较低值降低泄漏风险
     * @param qv 预计算问题向量
     * @return 已过滤重复与按分数排序后的 Top-N 结果
     */
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

    /**
     * 将 select(question) 的结果拼接为 prompt 可直接粘贴片段。
     * <p>无候选时返回空字符串，调用方可安全拼接，不改变提示词结构。
     */
    public String formatBlock(String question) {
        return formatBlock(question, Double.POSITIVE_INFINITY);
    }

    /**
     * 将 select(question, maxSim) 的结果拼接为 prompt 片段。
     *
     * @param question 自然语言问题
     * @param maxSim 近重复排除阈值
     * @return 示例片段
     */
    public String formatBlock(String question, double maxSim) {
        return render(select(question, maxSim));
    }

    /**
     * 将 select(question, maxSim, qv) 的结果拼接为 prompt 片段。
     *
     * @param question 自然语言问题
     * @param maxSim 近重复排除阈值
     * @param qv 问题向量
     * @return 示例片段
     */
    public String formatBlock(String question, double maxSim, float[] qv) {
        return render(select(question, maxSim, qv));
    }

    /**
     * 将 selected 示例按统一标题格式输出，保留原始 question 与 MQL JSON，供 prompt 与人工排查。
     *
     * @param sel 已排序的候选集合
     * @return 多个示例拼接文本；空列表返回空串
     */
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
