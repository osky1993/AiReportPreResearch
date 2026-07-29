package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.service.MqlExplainService;
import com.treasury.nl2sql.service.Nl2SqlService;
import com.treasury.nl2sql.service.NlQueryResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标制作向导第 1/2 步的服务端（P5-T1）。
 * try = 薄包装引擎 query()（LLM 自由生成路径只用于资产制作期，不进报告主流水线）；
 * explain = MQL 反翻译为中文口径描述——委托底座 {@link MqlExplainService}（TEMPLATE 语境），
 * 它只是人工核验辅助（第 2 步人确认才往下走），不进事实链，失败关闭无副作用。
 */
@Service
public class MetricWizardService {

    public record TryResult(JsonNode mql, String sql, List<java.util.Map<String, Object>> rows,
                            List<String> columns, boolean success, List<String> errors,
                            List<String> warnings, String clarifyReason) {}
    public record ExplainResult(String explanation, List<String> caveats) {}

    private final Nl2SqlService nl2sql;
    private final MqlExplainService explainService;
    private final ObjectMapper cleanMapper;   // NON_EMPTY：Mql→JsonNode 时去掉 null 与空数组，模板干净入库

    public MetricWizardService(Nl2SqlService nl2sql, MqlExplainService explainService, ObjectMapper mapper) {
        this.nl2sql = nl2sql;
        this.explainService = explainService;
        this.cleanMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    // ---------- 第 1 步：试查 ----------

    public TryResult tryQuery(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问法不能为空");
        }
        NlQueryResult r = nl2sql.query(question);
        List<String> columns = (r.rows() != null && !r.rows().isEmpty())
                ? new ArrayList<>(r.rows().get(0).keySet()) : List.of();
        List<String> warnings = new ArrayList<>(r.warnings() == null ? List.of() : r.warnings());
        if (r.success()) {
            if (r.rows().size() != 1) {
                warnings.add("结果 " + r.rows().size() + " 行——指标要求恰 1 行 1 值，请改为聚合问法或收窄条件（保存时会硬性拦截）");
            } else if (columns.size() != 1) {
                warnings.add("结果 " + columns.size() + " 列——指标只取其中一列为值，其余列将被忽略");
            }
        }
        JsonNode mqlNode = r.mql() == null ? null : cleanMapper.valueToTree(r.mql());
        return new TryResult(mqlNode, r.sql(), r.rows(), columns, r.success(),
                r.errors(), warnings, r.clarifyReason());
    }

    // ---------- 第 2 步：口径反翻译（委托底座共用实现，TEMPLATE 语境） ----------

    public ExplainResult explain(JsonNode mqlNode) {
        MqlExplainService.Explanation e = explainService.explain(mqlNode, MqlExplainService.Mode.TEMPLATE);
        return new ExplainResult(e.explanation(), e.caveats());
    }
}
