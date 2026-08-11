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
 * 人在回路编排层：在纯生成引擎 {@link Nl2SqlService} 之外，承担
 * {@code 召回 → 澄清 → 生成 → 可选复用执行 → 核验沉淀} 的控制流。
 *
 * <p>核心职责边界：
 * <ul>
 *   <li>核心生成/评估链路保持不变：所有模型生成仍由 {@code Nl2SqlService.query()} 出口化执行</li>
 *   <li>口径复用只在本层完成，不改变底座的 {@code EvalService/NlQueryResult} 契约</li>
 *   <li>一旦发生可疑情况，优先降级到纯生成，而不是把异常口径传播到下游</li>
 * </ul>
 * 失败模式：向量化失败、口径回放失败、复用执行失败都不应阻断查询主流程；失败时仅降级、记录告警并尝试下一条路径。
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
    private final MqlExplainService explainService;
    private final ObjectMapper mapper;

    public AssistedQueryService(CaliberStore store, Nl2SqlService nl2sql, EmbeddingClient embedding,
                                MqlValidator validator,
                                MqlSqlCompiler compiler, DSLContext dsl, CurrencyGuard currencyGuard,
                                MqlExplainService explainService, ObjectMapper mapper) {
        this.store = store;
        this.nl2sql = nl2sql;
        this.embedding = embedding;
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
        this.currencyGuard = currencyGuard;
        this.explainService = explainService;
        this.mapper = mapper;
    }

    /**
     * 核验 API 的返回载体：仅用于口径资产沉淀是否成功。
     * <p>说明：description 依赖反翻译说明，若生成失败可为 null，仍视为允许采纳但不显示中文口径。
     */
    public record VerifyResult(boolean precipitated, Long assetId, String message, String description) {}

    /**
     * 一次带人在回路的提问入口。
     * <p>流程：先尝试对问题向量化，然后按命中档位决定：
     * <ul>
     *   <li>HIT：做参数一致性比对，漂移则澄清</li>
     *   <li>CANDIDATE：给用户确认候选口径是否可复用</li>
     *   <li>MISS：直接走纯生成引擎</li>
     * </ul>
     *
     * @param question 用户问题
     * @param bypassCaliber true=跳过口径召回，直接走纯生成（用户声明“命中不符，需要重建口径”时使用）
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
                // 参数漂移防线：语义命中但数值参数不一致（如换了日期/阈值）→ 不直取，降档澄清交人确认
                ParamDriftDetector.Drift drift = ParamDriftDetector.diff(r.matchedQuestion(), question);
                if (drift.drifted()) {
                    log.info("命中口径但参数疑似漂移，降档澄清: id={} 资产侧独有={} 问题侧独有={}",
                            r.assetId(), drift.assetOnly(), drift.questionOnly());
                    return AssistedResponse.clarifyCandidate(r.assetId(), r.matchedQuestion(), r.description(),
                            r.score(), driftPrompt(r, drift));
                }
                AssistedResponse reused = reuseAsset(r, question);
                return reused != null ? reused : generate(question, qv);   // 漂移/异常降级到生成
            }
            case CANDIDATE -> {
                String prompt = "疑似命中已核验口径「" + r.matchedQuestion() + "」(相似度 "
                        + String.format("%.3f", r.score()) + ")。是否复用该口径直接出数？"
                        + "若口径相符可直接复用；口径不符则让模型按原问题生成；仅需微调可补充差异后重新提问。";
                return AssistedResponse.clarifyCandidate(r.assetId(), r.matchedQuestion(), r.description(),
                        r.score(), prompt);
            }
            default -> {
                return generate(question, qv);
            }
        }
    }

    /**
     * 生成参数漂移澄清话术。
     * <p>通过对比口径存量问法与本次提问中抽取到的数值差异，避免“看似命中但参数不一致”导致静默错用历史口径。
     */
    private static String driftPrompt(CaliberStore.Recall r, ParamDriftDetector.Drift drift) {
        StringBuilder sb = new StringBuilder("命中已核验口径「").append(r.matchedQuestion())
                .append("」(相似度 ").append(String.format("%.3f", r.score())).append(")，但参数疑似不同：");
        if (!drift.assetOnly().isEmpty()) {
            sb.append("口径问法中的数值 ").append(String.join("、", drift.assetOnly()));
            sb.append(drift.questionOnly().isEmpty() ? " 未出现在您的问题中" : "");
        }
        if (!drift.questionOnly().isEmpty()) {
            if (!drift.assetOnly().isEmpty()) sb.append(" 与您问题中的 ");
            else sb.append("您问题中的数值 ");
            sb.append(String.join("、", drift.questionOnly()));
            sb.append(drift.assetOnly().isEmpty() ? " 未出现在口径问法中" : " 不一致");
        }
        sb.append("。复用该口径将按口径原参数出数；需按新参数取数请选择重新生成，或补充差异后重新提问。");
        return sb.toString();
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
        AssistedResponse reused = reuseAsset(r, question);
        return reused != null ? reused : generate(question, null);   // reuse 场景本无 qv，交由 query 自算
    }

    /**
     * 核验闸门：采纳则沉淀新口径资产，驳回则仅留痕并不入库。
     * <p>关键安全点：采纳时服务端重跑 {@link MqlValidator}，即使前端已经展示“通过”，也不信任客户端 MQL；
     * 任何反翻译失败仅影响展示描述，不阻断资产写入（fail-open）。
     */
    public VerifyResult verify(String question, String mqlJson, boolean accept, String createdBy) {
        if (!accept) {
            log.info("核验驳回，丢弃候选: 问题=「{}」", question);
            return new VerifyResult(false, null, "已驳回，未沉淀", null);
        }
        try {
            String description = describeQuietly(mqlJson);
            long id = store.precipitate(question, mqlJson, description, createdBy);
            return new VerifyResult(true, id, "已核验采纳并沉淀为口径资产 #" + id, description);
        } catch (IllegalArgumentException e) {
            log.warn("核验采纳失败（MQL 非法）: {}", e.getMessage());
            return new VerifyResult(false, null, "采纳失败: " + e.getMessage(), null);
        }
    }

    /** 反翻译生成口径描述；任何失败返回 null（描述缺失不阻断沉淀，前端回落到“按需生成”）。 */
    private String describeQuietly(String mqlJson) {
        try {
            return explainService.explain(mapper.readTree(mqlJson), MqlExplainService.Mode.AD_HOC).explanation();
        } catch (Exception e) {
            log.warn("沉淀口径描述生成失败（不阻断沉淀）: {}", e.getMessage());
            return null;
        }
    }

    // ---- 内部：直取复用一条资产 MQL（verbatim）。返回 null 表示需降级到生成。----
    private AssistedResponse reuseAsset(CaliberStore.Recall r, String question) {
        long assetId = r.assetId();
        Mql mql;
        try {
            mql = mapper.readValue(r.mqlJson(), Mql.class);
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
            log.info("命中口径直取: id={} 匹配问法=「{}」相似度={}", assetId, r.matchedQuestion(),
                    String.format("%.3f", r.score()));
            return AssistedResponse.hit(assetId, r.matchedQuestion(), r.description(), r.score(), trace);
        } catch (Exception e) {
            log.warn("资产直取执行失败，降级到生成: id={} {}", assetId, e.getMessage());
            return null;   // 执行异常可能是瞬时问题，不作废，仅本次降级
        }
    }

    // ---- 内部：走纯生成引擎，并按结果映射状态 ----
    /**
     * 纯生成回退支路：不改动口径索引、不读取历史资产。
     * <p>返回值保留三态：clarify / generated / failed，供控制器在前端做不同交互。
     */
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
