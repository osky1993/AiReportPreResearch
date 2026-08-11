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

    /** 试查返回片段：可用于前端“起草预览”，含原始 SQL、结果列定义与校验提示。 */
    public record TryResult(JsonNode mql, String sql, List<java.util.Map<String, Object>> rows,
                           List<String> columns, boolean success, List<String> errors,
                           List<String> warnings, String clarifyReason) {}
    /** 口径反翻译返回片段：说明性文字 + 风险提示（caveat），用于前端展示给资产制作者确认。 */
    public record ExplainResult(String explanation, List<String> caveats) {}

    private final Nl2SqlService nl2sql;
    private final MqlExplainService explainService;
    private final ObjectMapper cleanMapper;   // NON_EMPTY：Mql→JsonNode 时去掉 null 与空数组，模板干净入库

    /**
     * 依赖注入：复用底座 Nl2SqlService 与 MqlExplainService，避免“试查/反翻译”走重复实现。
     * cleanMapper 使用 NON_EMPTY 保证入库模板无冗余字段，便于后续参数识别稳定。
     */
    public MetricWizardService(Nl2SqlService nl2sql, MqlExplainService explainService, ObjectMapper mapper) {
        this.nl2sql = nl2sql;
        this.explainService = explainService;
        this.cleanMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    // ---------- 第 1 步：试查 ----------

    /**
     * 第1步“试查”执行入口：保持与 Nl2SqlService.query() 一致参数，失败/异常直接透传；
     * 该入口只做辅助发现，不落库，失败关闭由上层按钮触发人工处理。
     * 约束：仅用于资产制作，不参与报告主流水线（防止评测和主链路引用不确定路径）。
     */
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
        // mql 可空时返回空节点，便于前端“模板化显示”与下一步 explain 继续接力
        JsonNode mqlNode = r.mql() == null ? null : cleanMapper.valueToTree(r.mql());
        return new TryResult(mqlNode, r.sql(), r.rows(), columns, r.success(),
                r.errors(), warnings, r.clarifyReason());
    }

    // ---------- 第 2 步：口径反翻译（委托底座共用实现，TEMPLATE 语境） ----------

    /**
     * 第2步“口径反翻译”执行入口：固定 TEMPLATE 语境，输出用于审批页和资产录入的中文解释。
     * explain 本身不变更任何状态，失败/歧义通过异常上抛给控制层显示。
     */
    public ExplainResult explain(JsonNode mqlNode) {
        MqlExplainService.Explanation e = explainService.explain(mqlNode, MqlExplainService.Mode.TEMPLATE);
        return new ExplainResult(e.explanation(), e.caveats());
    }
}
