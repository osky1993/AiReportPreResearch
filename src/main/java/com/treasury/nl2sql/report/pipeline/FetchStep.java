package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.asset.ReportAssetService;
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
    private final ReportAssetService assets;
    private final MqlValidator validator;
    private final MqlSqlCompiler compiler;
    private final DSLContext dsl;

    public FetchStep(ObjectMapper mapper, ReportAssetService assets, MqlValidator validator,
                     MqlSqlCompiler compiler, DSLContext dsl) {
        this.mapper = mapper;
        this.assets = assets;
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
    }

    /** 一次取数的完整留痕：spec + SQL + 行集 + 双哈希。 */
    public record FetchResult(MetricQuerySpec spec, String sql, String sqlHash,
                              List<Map<String, Object>> rows, String resultHash) {}

    public List<FetchResult> run(List<MetricQuerySpec> specs) {
        List<FetchResult> results = new ArrayList<>();
        for (MetricQuerySpec spec : specs) {
            MetricDefinition def = assets.metric(spec.metricId())
                    .orElseThrow(() -> new PolicyException("指标「" + spec.metricId() + "」没有语义定义"));
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
            String resultHash = sha256(canonicalRows(rows));
            log.info("[FETCH] {} {} → {} 行, sql_hash={}", spec.specId(), spec.metricId(), rows.size(),
                    sha256(sql).substring(0, 12));
            results.add(new FetchResult(spec, sql, sha256(sql), rows, resultHash));
        }
        return results;
    }

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

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m == null ? c.getClass().getSimpleName() : m).replaceAll("\\s+", " ").trim();
    }
}
