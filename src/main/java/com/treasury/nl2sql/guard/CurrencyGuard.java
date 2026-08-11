package com.treasury.nl2sql.guard;

import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.schema.SchemaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * ①-6 跨币种货币聚合护栏（非阻塞警告）。
 * 当对金额字段(amount/balance)做 sum/avg、而查询既未按 currency 分组、也未过滤 currency 时，
 * 若数据跨币种，汇总值会把不同币种直接相加（无意义）。这里给出**警告**（不阻塞、不破坏单币种查询），
 * 提示按币种分组或限定单一币种。完整汇率折算属 LongTerm。
 */
@Component
public class CurrencyGuard {

    /**
     * Schema 辅助器：用于判定主表/明细表是否具备 currency 列，决定是否存在跨币种聚合风险。
     */
    private final SchemaService schema;
    /**
     * 开关：false 时整个检查短路返回空列表，确保历史兼容的场景可关闭提示。
     */
    private final boolean enabled;
    /**
     * 需参与金额风控的字段集合（从配置注入）。
     */
    private final Set<String> moneyFields;
    /**
     * 可视为汇率字段的名称集合，匹配乘法两侧任一字段。
     */
    private final Set<String> rateFields;
    /**
     * 货币维度字段名，约定全量以 currency 为单一口径键。
     */
    private static final String CURRENCY = "currency";

    /**
     * @param schema Schema 服务
     * @param enabled 是否启用跨币种金额聚合护栏；关闭后返回空告警
     * @param moneyFields 需要守护的金额字段，逗号分隔
     * @param rateFields 可认定为已折算的汇率字段，逗号分隔
     */
    public CurrencyGuard(SchemaService schema,
                         @Value("${guard.currency.enabled:true}") boolean enabled,
                         @Value("${guard.currency.money-fields:amount,balance}") String moneyFields,
                         @Value("${guard.currency.rate-fields:rate_to_cny}") String rateFields) {
        this.schema = schema;
        this.enabled = enabled;
        this.moneyFields = Set.of(moneyFields.split("\\s*,\\s*"));
        this.rateFields = Set.of(rateFields.split("\\s*,\\s*"));
    }

    /**
     * 检查 MQL 是否存在“跨币种金额聚合”风险。
     * <p>规则：仅在开启开关、存在未折算货币聚合、且查询没有按 currency 分组/过滤时返回告警；否则返回空列表。
     * 此方法不阻塞流程，仅作为 Warning，不改变查询执行结果。
     *
     * @param mql 当前待执行查询模型
     * @return 警告文本列表；无风险返回空列表
     */
    public List<String> check(Mql mql) {
        if (!enabled || mql == null) return List.of();
        if (!hasUnconvertedMoneyAggregation(mql)) return List.of(); // 无货币聚合，或全部已折算
        if (!anyTableHasCurrency(mql)) return List.of();          // 表本身无币种维度，无混合风险
        if (groupByHasCurrency(mql) || filterMentionsCurrency(mql)) return List.of(); // 已限定/分组
        return List.of("货币聚合未限定或分组币种(" + CURRENCY + ")：若数据跨币种，汇总结果会把不同币种直接相加。"
                + "建议按 " + CURRENCY + " 分组、过滤到单一币种，或用汇率折算成统一本位币。");
    }

    /**
     * 检查是否存在「未折算」货币聚合。
     * <ul>
     *   <li>sum/avg 直接作用于金额字段；视作未折算。</li>
     *   <li>表达式算子中只要任一操作数是金额字段且非乘汇率也视作未折算。</li>
     * </ul>
     */
    private boolean hasUnconvertedMoneyAggregation(Mql mql) {
        for (Mql.Metric m : nz(mql.metrics)) {
            if (isMoneyAgg(m) && !isConvertedAgg(m)) return true;
            if (m.expr != null) {
                if (isMoneyAgg(m.expr.left) && !isConvertedAgg(m.expr.left)) return true;
                if (isMoneyAgg(m.expr.right) && !isConvertedAgg(m.expr.right)) return true;
            }
        }
        return false;
    }

    /**
     * 判断 metric 是否为金额聚合。
     * @param m 待判断 metric
     * @return op 为 sum/avg 且字段或表达式操作数在 moneyFields 里时返回 true
     */
    private boolean isMoneyAgg(Mql.Metric m) {
        if (m == null || m.op == null) return false;
        String op = m.op.toLowerCase();
        if (!op.equals("sum") && !op.equals("avg")) return false;
        if (m.field != null && moneyFields.contains(unqualified(m.field))) return true;
        // 行级表达式聚合：任一操作数是金额字段也算货币聚合（乘错列/没乘汇率时仍能告警）
        if (m.fieldExpr != null) {
            if (m.fieldExpr.left != null && moneyFields.contains(unqualified(m.fieldExpr.left))) return true;
            return m.fieldExpr.right instanceof String s && moneyFields.contains(unqualified(s));
        }
        return false;
    }

    /**
     * 判断是否为已折算金额表达式。
     * 约定：表达式为乘法，且任一侧是 rateFields 时视作已折算。
     * <p>同时假设字段引用通过校验阶段，说明汇率字段已存在于可访问字段范围内。
     */
    private boolean isConvertedAgg(Mql.Metric m) {
        if (m == null || m.fieldExpr == null || !"*".equals(m.fieldExpr.arith)) return false;
        if (m.fieldExpr.left != null && rateFields.contains(unqualified(m.fieldExpr.left))) return true;
        return m.fieldExpr.right instanceof String s && rateFields.contains(unqualified(s));
    }

    /**
     * 是否有任一参与表含 currency 列（主表或 join 表）。
     */
    private boolean anyTableHasCurrency(Mql mql) {
        if (schema.hasColumn(mql.table, CURRENCY)) return true;
        for (Mql.Join j : nz(mql.joins)) {
            if (j.table != null && schema.hasColumn(j.table, CURRENCY)) return true;
        }
        return false;
    }

    /**
     * 是否在 groupBy 中显式引入 currency 分组键。
     */
    private boolean groupByHasCurrency(Mql mql) {
        for (String g : nz(mql.groupBy)) {
            if (CURRENCY.equals(unqualified(g))) return true;
        }
        return false;
    }

    /**
     * 条件是否命中 currency 过滤（全局 where 或指标 where），命中则降级为已限定币种。
     */
    private boolean filterMentionsCurrency(Mql mql) {
        if (conditionsMentionCurrency(mql.filter)) return true;
        // 条件聚合里限定了币种也算（如 sum(amount where currency='CNY')）
        for (Mql.Metric m : nz(mql.metrics)) {
            if (m.filter != null && conditionsMentionCurrency(m.filter)) return true;
            if (m.expr != null) {
                if (m.expr.left != null && conditionsMentionCurrency(m.expr.left.filter)) return true;
                if (m.expr.right != null && conditionsMentionCurrency(m.expr.right.filter)) return true;
            }
        }
        return false;
    }

    /**
     * 条件树扫描：递归检查 and/or 子句是否出现 currency 字段。
     * 采用深度优先，任何命中即返回 true。
     */
    private boolean conditionsMentionCurrency(List<Mql.Condition> conds) {
        for (Mql.Condition c : nz(conds)) {
            if (c == null) continue;
            if (c.field != null && CURRENCY.equals(unqualified(c.field))) return true;
            if (conditionsMentionCurrency(c.and)) return true;
            if (conditionsMentionCurrency(c.or)) return true;
        }
        return false;
    }

    /**
     * 去掉列的表前缀（如 table.field -> field），便于字段名集合匹配。
     */
    private static String unqualified(String ref) {
        if (ref == null) return null;
        int dot = ref.indexOf('.');
        return dot >= 0 ? ref.substring(dot + 1) : ref;
    }

    /**
     * 空列表兜底，避免频繁的 null-check 分支。
     */
    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
