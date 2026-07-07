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

    private final SchemaService schema;
    private final boolean enabled;
    private final Set<String> moneyFields;
    private final Set<String> rateFields;
    private static final String CURRENCY = "currency";

    public CurrencyGuard(SchemaService schema,
                         @Value("${guard.currency.enabled:true}") boolean enabled,
                         @Value("${guard.currency.money-fields:amount,balance}") String moneyFields,
                         @Value("${guard.currency.rate-fields:rate_to_cny}") String rateFields) {
        this.schema = schema;
        this.enabled = enabled;
        this.moneyFields = Set.of(moneyFields.split("\\s*,\\s*"));
        this.rateFields = Set.of(rateFields.split("\\s*,\\s*"));
    }

    public List<String> check(Mql mql) {
        if (!enabled || mql == null) return List.of();
        if (!hasUnconvertedMoneyAggregation(mql)) return List.of(); // 无货币聚合，或全部已折算
        if (!anyTableHasCurrency(mql)) return List.of();          // 表本身无币种维度，无混合风险
        if (groupByHasCurrency(mql) || filterMentionsCurrency(mql)) return List.of(); // 已限定/分组
        return List.of("货币聚合未限定或分组币种(" + CURRENCY + ")：若数据跨币种，汇总结果会把不同币种直接相加。"
                + "建议按 " + CURRENCY + " 分组、过滤到单一币种，或用汇率折算成统一本位币。");
    }

    /** 存在「未折算」的货币聚合：裸 sum/avg(amount)；fieldExpr 里乘了汇率字段的视为已折算 */
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

    /** 已折算判定：fieldExpr 为乘法且一侧是汇率字段（能过校验即说明汇率表在本查询作用域内） */
    private boolean isConvertedAgg(Mql.Metric m) {
        if (m == null || m.fieldExpr == null || !"*".equals(m.fieldExpr.arith)) return false;
        if (m.fieldExpr.left != null && rateFields.contains(unqualified(m.fieldExpr.left))) return true;
        return m.fieldExpr.right instanceof String s && rateFields.contains(unqualified(s));
    }

    private boolean anyTableHasCurrency(Mql mql) {
        if (schema.hasColumn(mql.table, CURRENCY)) return true;
        for (Mql.Join j : nz(mql.joins)) {
            if (j.table != null && schema.hasColumn(j.table, CURRENCY)) return true;
        }
        return false;
    }

    private boolean groupByHasCurrency(Mql mql) {
        for (String g : nz(mql.groupBy)) {
            if (CURRENCY.equals(unqualified(g))) return true;
        }
        return false;
    }

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

    private boolean conditionsMentionCurrency(List<Mql.Condition> conds) {
        for (Mql.Condition c : nz(conds)) {
            if (c == null) continue;
            if (c.field != null && CURRENCY.equals(unqualified(c.field))) return true;
            if (conditionsMentionCurrency(c.and)) return true;
            if (conditionsMentionCurrency(c.or)) return true;
        }
        return false;
    }

    private static String unqualified(String ref) {
        if (ref == null) return null;
        int dot = ref.indexOf('.');
        return dot >= 0 ? ref.substring(dot + 1) : ref;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
