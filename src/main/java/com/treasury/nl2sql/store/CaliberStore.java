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

    private final EmbeddingClient embedding;
    private final CaliberRepository repo;
    private final MqlValidator validator;
    private final ObjectMapper mapper;
    private final FewShotSelector fewShot;   // 注入即保证其 @PostConstruct 先于本类执行
    private final boolean enabled;
    private final double tauHit;
    private final double tauMiss;

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

    /** 一次召回结果：分档 + 命中资产的最小信息（未命中时 assetId/mqlJson/description 为 null）。 */
    public record Recall(Band band, Long assetId, String matchedQuestion, String mqlJson,
                         String description, double score) {
        public static Recall miss(double score) { return new Recall(Band.MISS, null, null, null, null, score); }
    }

    private record Indexed(long assetId, String question, String mqlJson, String description, float[] vector) {}

    public boolean isEnabled() { return enabled; }

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

    /** 语义召回 + 双档判定。空库或禁用一律 MISS。 */
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

    /** 同上，复用上游已算好的问题向量；qv=null（embed 失败）→ 按 MISS 处理（走生成，不阻断）。 */
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
        return new Recall(band, best.assetId(), best.question(), best.mqlJson(), best.description(), bestScore);
    }

    /** 按 id 取资产（供 CANDIDATE「复用候选」直取）；不存在返回 null。 */
    public Recall byId(long assetId) {
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
        index.add(new Indexed(id, question, mqlJson, description, embedding.embed(question)));
        fewShot.add(question, mqlJson);
        log.info("口径已沉淀: id={} 问题=「{}」描述{} 当前 {} 条资产",
                id, question, description == null ? "缺失" : "已固化", index.size());
        return id;
    }

    /** 作废命中口径（人工驳回命中口径 / schema 漂移降级时使用）：库置 DEPRECATED + 从内存索引移除。 */
    public void deprecate(long assetId) {
        repo.deprecate(assetId);
        index.removeIf(it -> it.assetId() == assetId);
        log.info("口径已作废: id={}", assetId);
    }
}
