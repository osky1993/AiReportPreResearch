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

/** ①-6：跨币种货币聚合护栏（非阻塞警告）。 */
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

    @Test
    void warnsWhenMoneySumWithoutCurrencyControl() {
        assertFalse(guard.check(moneySum()).isEmpty(), "应对未限定/分组币种的金额聚合告警");
    }

    @Test
    void noWarnWhenGroupedByCurrency() {
        Mql mql = moneySum();
        mql.groupBy = List.of("currency");
        assertTrue(guard.check(mql).isEmpty());
    }

    @Test
    void noWarnWhenFilteredByCurrency() {
        Mql mql = moneySum();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "="; c.value = "CNY";
        mql.filter = List.of(c);
        assertTrue(guard.check(mql).isEmpty());
    }

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

    @Test
    void noWarnWhenConvertedByRate() {
        // SUM(amount * rate_to_cny)：已折算成本位币，跨币种直加风险不存在
        Mql mql = moneySum();
        mql.metrics = List.of(convertedSum());
        assertTrue(guard.check(mql).isEmpty(), "已折算聚合不应告警");
    }

    @Test
    void warnsWhenMixedConvertedAndBare() {
        // 一个已折算 + 一个裸 sum(amount)：仍应告警
        Mql mql = moneySum();
        mql.metrics = List.of(convertedSum(), mql.metrics.get(0));
        assertFalse(guard.check(mql).isEmpty(), "存在未折算的货币聚合仍应告警");
    }

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
