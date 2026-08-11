package com.treasury.nl2sql.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.validate.MqlValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 口径资产库（人在回路 / 可演进沉淀的状态中枢）。
 *
 * <p>仿 {@link FewShotSelector} 维护一个「问题 -> 已核验 MQL」的内存向量索引，复用 {@link EmbeddingClient}。
 * 双档召回：score≥tau-hit=命中(HIT，直取复用) | [tau-miss,tau-hit)=候选(CANDIDATE，转澄清) | 否则未命中(MISS)。
 *
 * <p>回流耦合是单向的 {@code CaliberStore -> FewShotSelector}（无环）：
 * <ul>
 *   <li>启动 {@link #load()}：从表加载 ACTIVE 资产建召回索引，并逐条回放进 few-shot（重启后软增益不丢）；</li>
 *   <li>{@link #precipitate}：insert + 入召回索引 + 通知 few-shot（刚沉淀即时生效）。</li>
 * </ul>
 */
@Service
public class CaliberStore {

    private static final Logger log = LoggerFactory.getLogger(CaliberStore.class);

    /** 口径问题与 mql 的向量化服务，供召回与沉淀时共享特征空间。 */
    private final EmbeddingClient embedding;
    /** 持久化仓库：唯一事实源。 */
    private final CaliberRepository repo;
    /** 入库/重放前强制校验，避免 schema 漂移或坏 JSON 入库导致复用链路直接报错。 */
    private final MqlValidator validator;
    /** JSON 反序列化器：沉淀时验证输入结构完整性。 */
    private final ObjectMapper mapper;
    /** 回流目标：把采纳资产同步进 FewShotSelector，供系统内通用检索组件复用。 */
    private final FewShotSelector fewShot;   // 注入即保证其 @PostConstruct 先于本类执行
    /** 总开关：便于联调/压测临时关闭口径资产召回。 */
    private final boolean enabled;
    /** HIT 分档阈值：达到该值则可直接复用，避免重复生成。 */
    private final double tauHit;
    /** CANDIDATE 下限：用于“疑似命中”场景，仅提示用户确认。 */
    private final double tauMiss;

    /** 内存索引：服务启动后从 ACTIVE 资产构建，支持 O(n) 最近似匹配。 */
    private final List<Indexed> index = new ArrayList<>();

    public CaliberStore(EmbeddingClient embedding, CaliberRepository repo, MqlValidator validator,
                        ObjectMapper mapper, FewShotSelector fewShot,
                        @Value("${caliber.enabled:true}") boolean enabled,
                        @Value("${caliber.tau-hit:0.92}") double tauHit,
                        @Value("${caliber.tau-miss:0.75}") double tauMiss) {
        this.embedding = embedding;
        this.repo = repo;
        this.validator = validator;
        this.mapper = mapper;
        this.fewShot = fewShot;
        this.enabled = enabled;
        this.tauHit = tauHit;
        this.tauMiss = tauMiss;
    }

    public enum Band { HIT, CANDIDATE, MISS }

    /**
     * 一次召回结果：分档 + 命中资产最小信息（未命中时 assetId/mqlJson/description 为 null）。
     * score 始终返回检索相似度，便于调参与异常归因。
     */
    public record Recall(Band band, Long assetId, String matchedQuestion, String mqlJson,
                         String description, double score) {
        public static Recall miss(double score) { return new Recall(Band.MISS, null, null, null, null, score); }
    }

    /** 索引项仅保留服务运行所需的最小字段，避免重复反序列化开销。 */
    private record Indexed(long assetId, String question, String mqlJson, String description, float[] vector) {}

    /**
     * 全局开关查询。
     * 关闭后所有 recall 都直接 MISS，用于联调/压测时快速隔离语义召回。
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 启动时加载 ACTIVE 资产并重建索引。
     * <p>失败策略：口径表不存在或查询异常时不阻断启动；返回空索引并沿用生成路径。该设计保留“系统基本可用”优先级。</p>
     */
    @PostConstruct
    public void load() {
        index.clear();
        if (!enabled) {
            log.info("口径资产库已禁用");
            return;
        }
        List<CaliberAsset> assets;
        try {
            assets = repo.findAllActive();
        } catch (Exception ex) {
            // 表不存在/库未初始化时不阻断启动，退化为「零沉淀」初态
            log.warn("加载口径资产失败（可能是 caliber_asset 表未建），按零沉淀启动: {}", ex.getMessage());
            return;
        }
        List<float[]> vectors = embedding.embedBatch(assets.stream().map(CaliberAsset::question).toList());
        for (int i = 0; i < assets.size(); i++) {
            CaliberAsset a = assets.get(i);
            index.add(new Indexed(a.id(), a.question(), a.mqlJson(), a.description(), vectors.get(i)));
            fewShot.add(a.question(), a.mqlJson());   // 回放软增益
        }
        log.info("口径资产库已加载: {} 条 ACTIVE 资产（tau-hit={}, tau-miss={}）", index.size(), tauHit, tauMiss);
    }

    /**
     * 语义召回 + 双档判定。
     * <p>输入空白、服务禁用、索引为空都会返回 MISS，避免因异常或低质输入污染主流程。</p>
     */
    public Recall recall(String question) {
        if (!enabled || index.isEmpty() || question == null || question.isBlank()) {
            return Recall.miss(0);
        }
        float[] qv;
        try {
            qv = embedding.embed(question);
        } catch (Exception e) {
            log.warn("口径召回问题向量化失败: {}", e.getMessage());
            qv = null;
        }
        return recall(question, qv);
    }

    /**
     * 同上，复用已计算问题向量版本。
     * <p>qv == null 表示上游 embed 失败，直接 MISS，交给主流程走 LLM 生成，不进行额外重试。</p>
     */
    public Recall recall(String question, float[] qv) {
        if (!enabled || index.isEmpty() || question == null || question.isBlank()) {
            return Recall.miss(0);
        }
        if (qv == null) {
            log.warn("口径召回无可用问题向量，按 MISS 处理");
            return Recall.miss(0);
        }
        Indexed best = null;
        double bestScore = -1;
        for (Indexed it : index) {
            double s = EmbeddingClient.cosine(qv, it.vector());
            if (s > bestScore) { bestScore = s; best = it; }
        }
        if (best == null) return Recall.miss(0);
        Band band = bestScore >= tauHit ? Band.HIT
                : bestScore >= tauMiss ? Band.CANDIDATE
                : Band.MISS;
        if (band == Band.MISS) return Recall.miss(bestScore);
        // HIT/CANDIDATE 分档必须复用计算出的得分，便于前端展示与失败复盘。
        return new Recall(band, best.assetId(), best.question(), best.mqlJson(), best.description(), bestScore);
    }

    /**
     * 按 id 取资产（候选命中兜底）。
     * <p>用于前端点击候选项时的“零 SQL 延迟”复用；线性扫描仅在内存索引上执行，找不到返回 null。</p>
     */
    public Recall byId(long assetId) {
        // O(n) 在内存索引内查找；避免二次数据库查询，保证 CANDIDATE 直复用按钮实时性。
        for (Indexed it : index) {
            if (it.assetId() == assetId) {
                return new Recall(Band.HIT, it.assetId(), it.question(), it.mqlJson(), it.description(), 1.0);
            }
        }
        return null;
    }

    /**
     * 沉淀一条被核验采纳的口径：重校验 → 落库 → 入召回索引 → 回流 few-shot。
     * @param description 中文口径描述（AI 反翻译生成，可为 null——生成失败不阻断沉淀）
     * @return 新资产 id
     * @throws IllegalArgumentException MQL 非法（防止把坏资产写进库）
     */
    public long precipitate(String question, String mqlJson, String description, String createdBy) {
        Mql mql;
        try {
            mql = mapper.readValue(mqlJson, Mql.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("MQL JSON 解析失败: " + e.getMessage());
        }
        List<String> errs = validator.validate(mql);
        if (!errs.isEmpty()) {
            throw new IllegalArgumentException("MQL 校验未通过: " + String.join("; ", errs));
        }
        long id = repo.insert(question, mqlJson, description, createdBy == null ? "demo" : createdBy);
        // 与库插入一致成功时，立即补齐内存索引与 FewShot 回流，确保“刚沉淀可复用”。
        index.add(new Indexed(id, question, mqlJson, description, embedding.embed(question)));
        fewShot.add(question, mqlJson);
        log.info("口径已沉淀: id={} 问题=「{}」描述{} 当前 {} 条资产",
                id, question, description == null ? "缺失" : "已固化", index.size());
        return id;
    }

    /**
     * 下线资产并移除缓存。
     * <p>先沉库后清索引，避免短暂窗口内并发线程读取到尚未失效的内存命中。</p>
     * <p>语义为逻辑下线，不物理删记录；调用方应通过状态变更实现审计可追溯。</p>
     */
    public void deprecate(long assetId) {
        // 先沉库状态，再清内存索引：两阶段可避免并发线程拿到“刚作废但仍可召回”的旧快照。
        repo.deprecate(assetId);
        index.removeIf(it -> it.assetId() == assetId);
        log.info("口径已作废: id={}", assetId);
    }
}
