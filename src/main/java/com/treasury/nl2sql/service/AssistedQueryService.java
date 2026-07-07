package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.guard.CurrencyGuard;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.store.CaliberStore;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 人在回路编排层：在纯生成引擎 {@link Nl2SqlService} 之上叠加「召回—(澄清)—生成—核验—沉淀」闭环。
 *
 * <p>关键分层：生成核心 {@code Nl2SqlService.query()} 与 {@code NlQueryResult} 保持纯净（评估契约），
 * 召回/命中短路只发生在本层，不污染金标准评估。
 */
@Service
public class AssistedQueryService {

    private static final Logger log = LoggerFactory.getLogger(AssistedQueryService.class);

    private final CaliberStore store;
    private final Nl2SqlService nl2sql;
    private final EmbeddingClient embedding;
    private final MqlValidator validator;
    private final MqlSqlCompiler compiler;
    private final DSLContext dsl;
    private final CurrencyGuard currencyGuard;
    private final ObjectMapper mapper;

    public AssistedQueryService(CaliberStore store, Nl2SqlService nl2sql, EmbeddingClient embedding,
                                MqlValidator validator,
                                MqlSqlCompiler compiler, DSLContext dsl, CurrencyGuard currencyGuard,
                                ObjectMapper mapper) {
        this.store = store;
        this.nl2sql = nl2sql;
        this.embedding = embedding;
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
        this.currencyGuard = currencyGuard;
        this.mapper = mapper;
    }

    /** 核验采纳/驳回的结果。 */
    public record VerifyResult(boolean precipitated, Long assetId, String message) {}

    /**
     * 一次带人在回路的提问。
     * @param bypassCaliber true=跳过口径召回、强制走 LLM 生成（人工判定「命中口径不符」后重生成用）。
     */
    public AssistedResponse ask(String question, boolean bypassCaliber) {
        // 请求内只 embed 一次：召回与生成链路(linking/few-shot/术语)共享同一个问题向量
        float[] qv = safeEmbed(question);
        if (bypassCaliber || !store.isEnabled()) {
            return generate(question, qv);
        }
        CaliberStore.Recall r = store.recall(question, qv);
        switch (r.band()) {
            case HIT -> {
                AssistedResponse reused = reuseAsset(r.assetId(), r.matchedQuestion(), r.mqlJson(), r.score(), question);
                return reused != null ? reused : generate(question, qv);   // 漂移/异常降级到生成
            }
            case CANDIDATE -> {
                String prompt = "疑似命中已核验口径「" + r.matchedQuestion() + "」(相似度 "
                        + String.format("%.3f", r.score()) + ")。是否复用该口径直接出数？"
                        + "若口径相符可直接复用；口径不符则让模型按原问题生成；仅需微调可补充差异后重新提问。";
                return AssistedResponse.clarifyCandidate(r.assetId(), r.matchedQuestion(), r.score(), prompt);
            }
            default -> {
                return generate(question, qv);
            }
        }
    }

    /** 问题向量化；失败返回 null（召回按 MISS、生成链路自行降级），不阻断请求。 */
    private float[] safeEmbed(String question) {
        try {
            return embedding.embed(question);
        } catch (Exception e) {
            log.warn("问题向量化失败，交由下游降级处理: {}", e.getMessage());
            return null;
        }
    }

    /** CANDIDATE「复用该候选口径」：按 assetId 直取执行。资产不存在或执行失败则回落到生成。 */
    public AssistedResponse reuse(long assetId, String question) {
        CaliberStore.Recall r = store.byId(assetId);
        if (r == null) {
            log.warn("复用的口径不存在（可能已作废）: id={}，回落到生成", assetId);
            return generate(question, null);   // reuse 场景本无 qv，交由 query 自算
        }
        AssistedResponse reused = reuseAsset(r.assetId(), r.matchedQuestion(), r.mqlJson(), r.score(), question);
        return reused != null ? reused : generate(question, null);   // reuse 场景本无 qv，交由 query 自算
    }

    /**
     * 核验闸门：采纳则沉淀为口径资产，驳回则丢弃（仅留痕）。
     * 采纳时服务端重跑校验，绝不信任前端回传的 MQL。
     */
    public VerifyResult verify(String question, String mqlJson, boolean accept, String createdBy) {
        if (!accept) {
            log.info("核验驳回，丢弃候选: 问题=「{}」", question);
            return new VerifyResult(false, null, "已驳回，未沉淀");
        }
        try {
            long id = store.precipitate(question, mqlJson, createdBy);
            return new VerifyResult(true, id, "已核验采纳并沉淀为口径资产 #" + id);
        } catch (IllegalArgumentException e) {
            log.warn("核验采纳失败（MQL 非法）: {}", e.getMessage());
            return new VerifyResult(false, null, "采纳失败: " + e.getMessage());
        }
    }

    // ---- 内部：直取复用一条资产 MQL（verbatim）。返回 null 表示需降级到生成。----
    private AssistedResponse reuseAsset(long assetId, String matchedQuestion, String mqlJson,
                                        double score, String question) {
        Mql mql;
        try {
            mql = mapper.readValue(mqlJson, Mql.class);
        } catch (Exception e) {
            log.warn("资产 MQL 解析失败，作废并降级: id={} {}", assetId, e.getMessage());
            store.deprecate(assetId);
            return null;
        }
        // 重校验防 schema 漂移：失败 → 作废该资产 + 降级到生成
        List<String> errs = validator.validate(mql);
        if (!errs.isEmpty()) {
            log.warn("资产 MQL 校验未通过（疑似 schema 漂移），作废并降级: id={} 错误={}", assetId, errs);
            store.deprecate(assetId);
            return null;
        }
        try {
            SelectQuery<Record> q = compiler.compile(mql);
            String sql = compiler.renderSql(q);
            List<Map<String, Object>> rows = dsl.fetch(q).intoMaps();
            List<String> warnings = currencyGuard.check(mql);
            // 合成一个 NlQueryResult 作为 trace（fixRounds=0：直取无需自愈）
            NlQueryResult trace = new NlQueryResult(question, mql, sql, rows, List.of(), 0, true, warnings, null);
            log.info("命中口径直取: id={} 匹配问法=「{}」相似度={}", assetId, matchedQuestion, String.format("%.3f", score));
            return AssistedResponse.hit(assetId, matchedQuestion, score, trace);
        } catch (Exception e) {
            log.warn("资产直取执行失败，降级到生成: id={} {}", assetId, e.getMessage());
            return null;   // 执行异常可能是瞬时问题，不作废，仅本次降级
        }
    }

    // ---- 内部：走纯生成引擎，并按结果映射状态 ----
    private AssistedResponse generate(String question, float[] qv) {
        NlQueryResult out = nl2sql.query(question, Double.POSITIVE_INFINITY, qv);
        if (out.clarifyReason() != null) {
            String prompt = "无法直接回答：" + out.clarifyReason() + "。请补充或改写问题后重新提问。";
            return AssistedResponse.clarifyReject(prompt, out);
        }
        if (out.success()) {
            return AssistedResponse.generated(out);
        }
        return AssistedResponse.failed(out);
    }
}
