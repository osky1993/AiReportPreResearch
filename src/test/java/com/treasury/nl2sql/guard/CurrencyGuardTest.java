package com.treasury.nl2sql.guard;

import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.schema.SchemaService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CurrencyGuard 护栏回归（①-6）：跨币种金额聚合风险的非阻塞告警。
 * 验证不告警/告警场景边界：
 * - 分组或过滤 currency、或已乘汇率后的可控场景
 * - 未控制币种、混入裸金额聚合、错误字段表达式的风险场景
 */
class CurrencyGuardTest {

    private final SchemaService schema = mock(SchemaService.class);
    private final CurrencyGuard guard = new CurrencyGuard(schema, true, "amount,balance", "rate_to_cny");

    private Mql moneySum() {
        when(schema.hasColumn("cash_transaction", "currency")).thenReturn(true);
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.field = "amount"; m.alias = "total";
        mql.metrics = new ArrayList<>(List.of(m));
        return mql;
    }

    /**
     * 输入：金额汇总且未按币种限制；
     * 预期：产生告警，阻止混币值无约束聚合。
     */
    @Test
    void warnsWhenMoneySumWithoutCurrencyControl() {
        assertFalse(guard.check(moneySum()).isEmpty(), "应对未限定/分组币种的金额聚合告警");
    }

    /**
     * 输入：同样金额聚合按 currency 分组；
     * 预期：无告警，分组可提供币种闭合边界。
     */
    @Test
    void noWarnWhenGroupedByCurrency() {
        Mql mql = moneySum();
        mql.groupBy = List.of("currency");
        assertTrue(guard.check(mql).isEmpty());
    }

    /**
     * 输入：同样金额聚合加货币过滤；
     * 预期：无告警，过滤条件构成单币种收敛。
     */
    @Test
    void noWarnWhenFilteredByCurrency() {
        Mql mql = moneySum();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "="; c.value = "CNY";
        mql.filter = List.of(c);
        assertTrue(guard.check(mql).isEmpty());
    }

    /**
     * 输入：聚合类型非金额（count）；
     * 预期：无告警，仅对金额类聚合触发本护栏。
     */
    @Test
    void noWarnWhenNoMoneyAggregation() {
        when(schema.hasColumn("account", "currency")).thenReturn(true);
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Metric m = new Mql.Metric();
        m.op = "count"; m.alias = "cnt"; // 计数不涉及金额混合
        mql.metrics = List.of(m);
        assertTrue(guard.check(mql).isEmpty());
    }

    // ---- 折算感知（迭代3） ----
    private Mql.Metric convertedSum() {
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.alias = "total_cny";
        m.fieldExpr = new Mql.FieldExpr();
        m.fieldExpr.left = "cash_transaction.amount";
        m.fieldExpr.arith = "*";
        m.fieldExpr.right = "currency_rate.rate_to_cny";
        return m;
    }

    /**
     * 输入：金额已折算成本位币；
     * 预期：无告警，避免对可解释的折算后聚合误报。
     */
    @Test
    void noWarnWhenConvertedByRate() {
        // SUM(amount * rate_to_cny)：已折算成本位币，跨币种直加风险不存在
        Mql mql = moneySum();
        mql.metrics = List.of(convertedSum());
        assertTrue(guard.check(mql).isEmpty(), "已折算聚合不应告警");
    }

    /**
     * 输入：同时包含已折算与未折算金额；
     * 预期：仍告警，因混合口径将导致结果不一致。
     */
    @Test
    void warnsWhenMixedConvertedAndBare() {
        // 一个已折算 + 一个裸 sum(amount)：仍应告警
        Mql mql = moneySum();
        mql.metrics = List.of(convertedSum(), mql.metrics.get(0));
        assertFalse(guard.check(mql).isEmpty(), "存在未折算的货币聚合仍应告警");
    }

    /**
     * 输入：行级表达式但未乘汇率；
     * 预期：告警，防止“表面行级运算”掩盖跨币风险。
     */
    @Test
    void warnsWhenFieldExprWithoutRate() {
        // SUM(amount * amount)：行级表达式但没乘汇率字段，仍是未折算的货币聚合
        Mql mql = moneySum();
        Mql.Metric m = convertedSum();
        m.fieldExpr.right = "cash_transaction.amount";
        mql.metrics = List.of(m);
        assertFalse(guard.check(mql).isEmpty(), "未乘汇率的表达式聚合仍应告警");
    }
}
