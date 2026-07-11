package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.ir.Mql;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标维度声明与 MQL groupBy 的一致性校验（Phase03 契约2）。
 * 启动自检（ReportAssetService.checkMetric）与保存五重校验（MetricAdminService）共用——规则只改这一处。
 * 维度是白名单式增量：未声明 dimensions 的指标必须保持单行形态（不得带 groupBy）。
 */
public final class MetricDimensionRule {

    /** MVP 上限：单指标维度数（多维交叉后置）。 */
    public static final int MAX_DIMENSIONS = 1;

    /** 单指标维度行数上限（T0 拍板：超限 BLOCKED[POLICY] 不静默截断；③ 与保存试执行共用）。 */
    public static final int MAX_DIMENSION_ROWS = 12;

    private MetricDimensionRule() {}

    /** @param filledMql 已填充哨兵参数的 Mql（派生指标传 null） */
    public static List<String> check(MetricDefinition m, Mql filledMql) {
        List<String> errors = new ArrayList<>();
        List<String> dims = m.dimensions();
        boolean declared = dims != null && !dims.isEmpty();
        if (m.isDerivedMetric()) {
            if (declared) {
                errors.add("派生指标不支持 dimensions（维度展开只在取数指标上做）");
            }
            return errors;
        }
        List<String> groupBy = filledMql == null || filledMql.groupBy == null ? List.of() : filledMql.groupBy;
        if (!declared) {
            if (!groupBy.isEmpty()) {
                errors.add("未声明 dimensions 的指标不得含 groupBy " + groupBy
                        + "（③ 单行强校验会失败）——按维度拆解请显式声明 dimensions");
            }
            return errors;
        }
        if (dims.size() > MAX_DIMENSIONS) {
            errors.add("dimensions 至多 " + MAX_DIMENSIONS + " 个（MVP 单维度，多维交叉后置），当前: " + dims);
        }
        for (String d : dims) {
            if (d == null || d.isBlank()) {
                errors.add("dimensions 含空维度名");
            }
        }
        if (m.comparable()) {
            errors.add("维度指标本阶段不支持 comparable=true（维度行不做环比/同比，占比由 ④ 程序派生）");
        }
        if (!groupBy.stream().map(MetricDimensionRule::bareName).toList().equals(dims)) {
            errors.add("dimensions 与 mqlTemplate.groupBy 必须一致（声明 " + dims + "，groupBy " + groupBy
                    + "；多表限定列按裸列名比对）");
        }
        return errors;
    }

    /** 多表 MQL 的 groupBy 带表前缀（cash_transaction.currency），结果集列名与维度声明用裸列名。 */
    public static String bareName(String column) {
        int dot = column == null ? -1 : column.lastIndexOf('.');
        return dot < 0 ? column : column.substring(dot + 1);
    }
}
