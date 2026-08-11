package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.asset.MetricDimensionRule;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ③ 安全取数（纯程序，零 LLM）：MetricQuerySpec → 填充 MQL 模板 → 白名单校验 →
 * jOOQ 确定性编译 → 只读执行；留存 SQL 与结果哈希。
 * 与 Nl2SqlService 的关键差异：校验/执行失败**不回灌 LLM 自愈**，直接失败关闭——
 * 同输入必同 SQL，sql_hash 可复现，这是「数字一致率 100%」硬门禁的地基。
 */
@Component
public class FetchStep {

    private static final Logger log = LoggerFactory.getLogger(FetchStep.class);

    private final ObjectMapper mapper;
    private final MqlValidator validator;
    private final MqlSqlCompiler compiler;
    private final DSLContext dsl;

    /**
     * @param mapper json 序列化器
     * @param validator MQL 白名单校验器
     * @param compiler MQL→SQL 编译器（JOOQ）
     * @param dsl 只读执行上下文
     */
    public FetchStep(ObjectMapper mapper, MqlValidator validator,
                     MqlSqlCompiler compiler, DSLContext dsl) {
        this.mapper = mapper;
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
    }

    /** 一次取数的完整留痕：spec + SQL + 行集 + 双哈希。 */
    public record FetchResult(MetricQuerySpec spec, String sql, String sqlHash,
                              List<Map<String, Object>> rows, String resultHash) {}

    /** defs 由编排器按 run 固化的指标版本快照装配（不直读注册表 PUBLISHED 缓存——发新版不影响在跑 run）。 */
    public List<FetchResult> run(List<MetricQuerySpec> specs, Map<String, MetricDefinition> defs) {
        List<FetchResult> results = new ArrayList<>();
        for (MetricQuerySpec spec : specs) {
            MetricDefinition def = defs.get(spec.metricId());
            if (def == null) {
                throw new PolicyException("指标「" + spec.metricId() + "」没有语义定义");
            }
            if (def.isDerivedMetric()) {
                throw new PolicyException("派生指标「" + spec.metricId() + "」不应出现在取数规约中（应由 ④ 程序计算）");
            }
            Map<String, String> params = new HashMap<>();
            if (def.timeBound()) {
                if (spec.periodStart() == null || spec.periodEnd() == null) {
                    throw new PolicyException("期间指标「" + spec.metricId() + "」的规约缺少周期窗口");
                }
                params.put("period_start", spec.periodStart());
                params.put("period_end", spec.periodEnd());
            }
            Mql mql = MqlTemplateFiller.fill(mapper, spec.metricId(), def.mqlTemplate(), params);
            List<String> errors = validator.validate(mql);
            if (!errors.isEmpty()) {
                throw new PolicyException("指标「" + spec.metricId() + "」的 MQL 校验失败: " + errors);
            }
            String sql;
            List<Map<String, Object>> rows;
            try {
                SelectQuery<Record> query = compiler.compile(mql);
                sql = compiler.renderSql(query);
                rows = dsl.fetch(query).intoMaps();
            } catch (Exception e) {
                throw new PolicyException("指标「" + spec.metricId() + "」的 SQL 编译/执行失败（确定性通路不自愈）: "
                        + rootMessage(e));
            }
            // 维度指标行数上限（T0 拍板：触顶失败关闭，不静默截断 Top-N）
            if (def.isDimensional() && rows.size() > MetricDimensionRule.MAX_DIMENSION_ROWS) {
                throw new PolicyException("指标「" + spec.metricId() + "」维度行数 " + rows.size()
                        + " 超上限 " + MetricDimensionRule.MAX_DIMENSION_ROWS
                        + "（失败关闭转人工，请收窄口径或换单值指标）");
            }
            // 行集保留原始列名和原始顺序，只用于 hash 对账；不做二次聚合，避免“等值不同”被静默归一化。
            String resultHash = sha256(canonicalRows(rows));
            log.info("[FETCH] {} {} → {} 行, sql_hash={}", spec.specId(), spec.metricId(), rows.size(),
                    sha256(sql).substring(0, 12));
            results.add(new FetchResult(spec, sql, sha256(sql), rows, resultHash));
        }
        return results;
    }

    /**
     * 结果行集标准化序列化，仅用于哈希。
     * 约束：输入不复制，仅依赖 mapper 顺序和列名稳定性；列顺序变化将改变哈希。
     */
    private String canonicalRows(List<Map<String, Object>> rows) {
        try {
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("结果行集序列化失败", e);
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 取最底层异常消息，去空白以便日志入库稳定比对。
     */
    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m == null ? c.getClass().getSimpleName() : m).replaceAll("\\s+", " ").trim();
    }
}
