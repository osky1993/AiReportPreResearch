package com.treasury.nl2sql.compile;

import com.treasury.nl2sql.ir.Mql;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编译器纯渲染测试（无需数据库）：用 DSL.using(MYSQL) 渲染 SQL 做断言。 */
class MqlSqlCompilerTest {

    private final MqlSqlCompiler compiler = new MqlSqlCompiler(DSL.using(SQLDialect.MYSQL));

    private String render(Mql mql) {
        return compiler.renderSql(compiler.compile(mql)).toLowerCase();
    }

    // ---- 便捷构造 ----
    private static Mql.Condition leaf(String field, String op, Object value) {
        Mql.Condition c = new Mql.Condition();
        c.field = field; c.op = op; c.value = value;
        return c;
    }

    private static Mql.CaseColumn tierCase() {
        Mql.CaseColumn cc = new Mql.CaseColumn();
        cc.alias = "tier";
        Mql.CaseWhen high = new Mql.CaseWhen();
        high.when = List.of(leaf("balance", ">=", 10000000));
        high.then = "高";
        Mql.CaseWhen mid = new Mql.CaseWhen();
        mid.when = List.of(leaf("balance", ">=", 1000000));
        mid.then = "中";
        cc.cases = List.of(high, mid);
        cc.elseValue = "低";
        return cc;
    }

    private static Mql unionOfNames() {
        Mql seg = new Mql();
        seg.table = "cash_transaction";
        seg.columns = List.of("counterparty");

        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("account_name");
        mql.union = List.of(seg);
        return mql;
    }

    @Test
    void union_rendersUnionDedup() {
        String sql = render(unionOfNames());
        assertTrue(sql.contains(" union "), sql);
        assertTrue(!sql.contains("union all"), sql);
        assertTrue(sql.contains("from ("), "整体应包派生表: " + sql);
    }

    @Test
    void unionAll_renders() {
        Mql mql = unionOfNames();
        mql.unionAll = true;
        String sql = render(mql);
        assertTrue(sql.contains("union all"), sql);
    }

    @Test
    void unionWithOrderByAndLimit_appliesToWhole() {
        Mql mql = unionOfNames();
        Mql.Sort s = new Mql.Sort();
        s.field = "account_name";
        mql.orderBy = List.of(s);
        mql.limit = 5;

        String sql = render(mql);
        int unionPos = sql.indexOf(" union ");
        int orderPos = sql.indexOf("order by `account_name`");
        assertTrue(unionPos > 0 && orderPos > unionPos, "orderBy 应作用于合并后的整体（外层）: " + sql);
        assertTrue(sql.contains("limit"), sql);
    }

    private static Mql.WindowSpec winRowNumber() {
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "row_number";
        w.partitionBy = List.of("currency");
        Mql.Sort s = new Mql.Sort();
        s.field = "balance"; s.direction = "desc";
        w.orderBy = List.of(s);
        w.alias = "rn";
        return w;
    }

    @Test
    void windowRowNumber_rendersOverPartition() {
        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("currency", "account_name", "balance");
        mql.windows = List.of(winRowNumber());

        String sql = render(mql);
        assertTrue(sql.contains("row_number() over (partition by `currency` order by `balance` desc)"), sql);
        assertTrue(sql.contains("as `rn`"), sql);
    }

    @Test
    void windowRunningSum_renders() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.columns = List.of("txn_date", "amount");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "sum"; w.field = "amount"; w.alias = "running_total";
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        mql.windows = List.of(w);

        String sql = render(mql);
        assertTrue(sql.contains("sum(`amount`) over (order by `txn_date`"), sql);
    }

    @Test
    void lagOnRawRows_renders() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.columns = List.of("txn_date", "amount");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "amount"; w.alias = "prev_amount";
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        mql.windows = List.of(w);

        String sql = render(mql);
        assertTrue(sql.contains("lag(`amount`, 1) over (order by `txn_date` asc)"), sql);
    }

    @Test
    void windowAliasInColumns_notDuplicated() {
        // columns 里混入窗口别名：只输出一次窗口列（columns 中跳过、窗口统一追加）
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.columns = List.of("txn_date", "amount", "prev_amount");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "amount"; w.alias = "prev_amount";
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        mql.windows = List.of(w);

        String sql = render(mql);
        int first = sql.indexOf("prev_amount");
        int second = sql.indexOf("prev_amount", first + 1);
        assertTrue(first > 0 && second < 0, "窗口列只应出现一次: " + sql);
        assertTrue(sql.contains("lag(`amount`, 1)"), sql);
    }

    @Test
    void aggWindow_wrapsAggregateInDerivedTable() {
        // 环比：按月聚合（内层）+ lag（外层），orderBy 落最外层
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.TimeColumn tc = new Mql.TimeColumn();
        tc.func = "month"; tc.field = "txn_date"; tc.alias = "ym";
        mql.timeColumns = List.of(tc);
        mql.groupBy = List.of("ym");
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.field = "amount"; m.alias = "total";
        mql.metrics = List.of(m);
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "total"; w.alias = "prev_total";
        Mql.Sort ws = new Mql.Sort();
        ws.field = "ym";
        w.orderBy = List.of(ws);
        mql.windows = List.of(w);
        Mql.Sort top = new Mql.Sort();
        top.field = "ym";
        mql.orderBy = List.of(top);

        String sql = render(mql);
        int groupPos = sql.indexOf("group by");
        int lagPos = sql.indexOf("lag(`total`, 1) over (order by `ym` asc)");
        assertTrue(sql.contains("from (select"), "聚合应包进派生表: " + sql);
        // 外层 SELECT 的窗口列在 SQL 文本里先于内层的 group by 出现
        assertTrue(lagPos > 0 && groupPos > 0 && lagPos < groupPos, "窗口应在聚合外层: " + sql);
        assertTrue(sql.lastIndexOf("order by `ym`") > groupPos, "顶层 orderBy 应落在派生表外层: " + sql);
    }

    @Test
    void aggWindowWithQualify_doubleWrap() {
        // 聚合后开窗 + qualify：聚合内层 → 窗口层 → qualify 过滤层
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.groupBy = List.of("category");
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.field = "amount"; m.alias = "total";
        mql.metrics = List.of(m);
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "row_number"; w.alias = "rn";
        Mql.Sort ws = new Mql.Sort();
        ws.field = "total"; ws.direction = "desc";
        w.orderBy = List.of(ws);
        mql.windows = List.of(w);
        mql.qualify = List.of(leaf("rn", "<=", 2));

        String sql = render(mql);
        int first = sql.indexOf("from (select");
        int second = sql.indexOf("from (select", first + 1);
        assertTrue(first > 0 && second > first, "应有两层派生表包裹: " + sql);
        assertTrue(sql.contains("`rn` <= 2"), sql);
    }

    @Test
    void qualify_wrapsDerivedTable() {
        // 每个币种余额前2:窗口 + qualify → SELECT * FROM (...) t WHERE rn <= 2
        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("currency", "account_name", "balance");
        mql.windows = List.of(winRowNumber());
        mql.qualify = List.of(leaf("rn", "<=", 2));
        Mql.Sort s = new Mql.Sort();
        s.field = "currency";
        mql.orderBy = List.of(s);

        String sql = render(mql);
        assertTrue(sql.contains("from (select"), sql);
        assertTrue(sql.contains("`rn` <= 2"), sql);
        int fromPos = sql.indexOf("from (select");
        assertTrue(sql.indexOf("order by `currency`", fromPos) > sql.indexOf(") ", fromPos),
                "整体 orderBy 应在派生表外层: " + sql);
    }

    @Test
    void scalarSubquery_rendersComparison() {
        // 余额高于全表平均值的账户
        Mql sub = new Mql();
        sub.table = "account";
        Mql.Metric avg = new Mql.Metric();
        avg.op = "avg"; avg.field = "balance"; avg.alias = "avg_bal";
        sub.metrics = List.of(avg);

        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("account_name", "balance");
        Mql.Condition c = leaf("balance", ">", null);
        c.subquery = sub;
        mql.filter = List.of(c);

        String sql = render(mql);
        assertTrue(sql.contains("`balance` > (select avg("), sql);
    }

    @Test
    void inSubquery_renders() {
        // 有 SALES 流水的账户
        Mql sub = new Mql();
        sub.table = "cash_transaction";
        sub.columns = List.of("account_id");
        sub.filter = List.of(leaf("category", "=", "SALES"));

        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("account_name");
        Mql.Condition c = leaf("account_id", "in", null);
        c.subquery = sub;
        mql.filter = List.of(c);

        String sql = render(mql);
        assertTrue(sql.contains("`account_id` in (select"), sql);
        assertTrue(sql.contains("where `category` = 'sales'"), sql);
    }

    @Test
    void distinct_rendersSelectDistinct() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.distinct = true;
        mql.columns = List.of("currency");

        String sql = render(mql);
        assertTrue(sql.contains("select distinct `currency`"), sql);
    }

    @Test
    void countDistinct_renders() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.Metric m = new Mql.Metric();
        m.op = "count"; m.field = "account_id"; m.distinct = true; m.alias = "cnt";
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("count(distinct `account_id`)"), sql);
    }

    @Test
    void caseColumn_groupByExpandsExpression() {
        // 按余额分档统计账户数:SELECT 与 GROUP BY 均展开为同一 CASE 表达式(非裸别名)
        Mql mql = new Mql();
        mql.table = "account";
        mql.caseColumns = List.of(tierCase());
        mql.groupBy = List.of("tier");
        Mql.Metric m = new Mql.Metric();
        m.op = "count"; m.alias = "cnt";
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("case when `balance` >= 10000000"), sql);
        assertTrue(sql.contains("as `tier`"), sql);
        assertTrue(sql.contains("else '低'"), sql);
        int groupByPos = sql.indexOf("group by");
        assertTrue(groupByPos > 0 && sql.indexOf("case when", groupByPos) > groupByPos,
                "group by 应展开为 case 表达式而非裸别名: " + sql);
    }

    @Test
    void caseColumn_withoutElse_andPlainProjection_renders() {
        // 非聚合投影:columns + caseColumn 同时出现;无 else 时不渲染 else 常量
        Mql mql = new Mql();
        mql.table = "account";
        mql.columns = List.of("account_name");
        Mql.CaseColumn cc = tierCase();
        cc.elseValue = null;
        mql.caseColumns = List.of(cc);

        String sql = render(mql);
        assertTrue(sql.contains("`account_name`"), sql);
        assertTrue(sql.contains("case when"), sql);
        assertTrue(!sql.contains("else '低'"), sql);
    }

    @Test
    void nonEquiJoin_rendersComparisonOperator() {
        // ①-2：非等值连接 on a.x >= b.y
        Mql mql = new Mql();
        mql.table = "a";
        Mql.Join j = new Mql.Join();
        j.table = "b";
        Mql.On on = new Mql.On();
        on.left = "a.amount";
        on.op = ">=";
        on.right = "b.threshold";
        j.on = List.of(on);
        mql.joins = List.of(j);

        String sql = render(mql);
        assertTrue(sql.contains("join `b`"), sql);
        assertTrue(sql.contains("`a`.`amount` >= `b`.`threshold`"), "应渲染非等值连接条件: " + sql);
    }

    @Test
    void onConstant_rendersFieldOpValue() {
        // LEFT JOIN + 右表常量口径条件写进 on：不滤掉左表无匹配行
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Join j = new Mql.Join();
        j.table = "cash_transaction"; j.type = "left";
        Mql.On onKey = new Mql.On();
        onKey.left = "account.account_id"; onKey.right = "cash_transaction.account_id";
        Mql.On onConst = new Mql.On();
        onConst.left = "cash_transaction.direction"; onConst.op = "="; onConst.value = "OUT";
        j.on = List.of(onKey, onConst);
        mql.joins = List.of(j);
        mql.groupBy = List.of("account.account_name");
        Mql.Metric m = new Mql.Metric();
        m.op = "count"; m.field = "cash_transaction.txn_id"; m.alias = "cnt";
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("left outer join `cash_transaction`"), sql);
        assertTrue(sql.contains("`cash_transaction`.`direction` = 'out'"), "常量条件应渲染在 on 里: " + sql);
        int onPos = sql.indexOf(" on ");
        int wherePos = sql.indexOf(" where ");
        assertTrue(onPos > 0 && (wherePos < 0 || sql.indexOf("'out'") < wherePos || wherePos < onPos),
                "常量条件不应落到 WHERE: " + sql);
    }

    @Test
    void equiJoin_stillWorks() {
        Mql mql = new Mql();
        mql.table = "a";
        Mql.Join j = new Mql.Join();
        j.table = "b";
        Mql.On on = new Mql.On();
        on.left = "a.id";
        on.right = "b.aid"; // op 默认 =
        j.on = List.of(on);
        mql.joins = List.of(j);

        assertTrue(render(mql).contains("`a`.`id` = `b`.`aid`"));
    }

    @Test
    void selfJoin_rendersAliases() {
        // ①-3：自连接，主表与 join 同一张表、不同别名
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.alias = "cur";
        Mql.Join j = new Mql.Join();
        j.table = "cash_transaction";
        j.alias = "prev";
        Mql.On on = new Mql.On();
        on.left = "cur.account_id";
        on.right = "prev.account_id";
        j.on = List.of(on);
        mql.joins = List.of(j);
        mql.columns = List.of("cur.amount", "prev.amount");

        String sql = render(mql);
        assertTrue(sql.contains("`cash_transaction` as `cur`"), sql);
        assertTrue(sql.contains("`cash_transaction` as `prev`"), sql);
        assertTrue(sql.contains("`cur`.`account_id` = `prev`.`account_id`"), sql);
    }

    @Test
    void derivedMetric_netFlow_rendersCaseAndArithmetic() {
        // ①-4：净流入 = sum(CASE WHEN IN) - sum(CASE WHEN OUT)
        Mql mql = new Mql();
        mql.table = "cash_transaction";

        Mql.Metric in = new Mql.Metric();
        in.op = "sum"; in.field = "amount";
        Mql.Condition cin = new Mql.Condition();
        cin.field = "direction"; cin.op = "="; cin.value = "IN";
        in.filter = List.of(cin);

        Mql.Metric out = new Mql.Metric();
        out.op = "sum"; out.field = "amount";
        Mql.Condition cout = new Mql.Condition();
        cout.field = "direction"; cout.op = "="; cout.value = "OUT";
        out.filter = List.of(cout);

        Mql.Metric net = new Mql.Metric();
        net.alias = "net_flow";
        net.expr = new Mql.Expr();
        net.expr.left = in; net.expr.arith = "-"; net.expr.right = out;
        mql.metrics = List.of(net);

        String sql = render(mql);
        assertTrue(sql.contains("case when `direction` = 'in'"), "应有条件聚合 CASE WHEN: " + sql);
        assertTrue(sql.contains("-"), "应有减法: " + sql);
        assertTrue(sql.contains("as `net_flow`"), sql);
    }

    @Test
    void fieldExpr_rendersRowLevelArithmetic() {
        // 折人民币：SUM(balance * rate_to_cny)
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.alias = "total_cny";
        m.fieldExpr = new Mql.FieldExpr();
        m.fieldExpr.left = "account.balance"; m.fieldExpr.arith = "*"; m.fieldExpr.right = "currency_rate.rate_to_cny";
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("sum((`account`.`balance` * `currency_rate`.`rate_to_cny`))"), sql);
        assertTrue(sql.contains("as `total_cny`"), sql);
    }

    @Test
    void fieldExprWithMetricFilter_wrapsCase() {
        // 条件聚合包裹行级表达式：SUM(CASE WHEN direction='IN' THEN amount*rate END)
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.alias = "in_cny";
        m.fieldExpr = new Mql.FieldExpr();
        m.fieldExpr.left = "amount"; m.fieldExpr.arith = "*"; m.fieldExpr.right = "rate_to_cny";
        m.filter = List.of(leaf("direction", "=", "IN"));
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("case when `direction` = 'in'"), sql);
        assertTrue(sql.contains("`amount` * `rate_to_cny`"), sql);
    }

    @Test
    void exprConstantOperand_renders() {
        // 占比×100：(sum(amount) / sum(amount)) 简化为 sum(amount) * 100
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.Metric m = new Mql.Metric();
        m.alias = "pct";
        m.expr = new Mql.Expr();
        Mql.Metric left = new Mql.Metric();
        left.op = "sum"; left.field = "amount";
        Mql.Metric constant = new Mql.Metric();
        constant.value = 100;
        m.expr.left = left; m.expr.arith = "*"; m.expr.right = constant;
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("sum(`amount`)"), sql);
        assertTrue(sql.contains("*"), sql);
        assertTrue(sql.contains("100"), sql);
    }

    @Test
    void groupByWithHaving_renders() {
        // group by currency, sum(amount) as total, having total > 100
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.groupBy = List.of("currency");
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.field = "amount"; m.alias = "total";
        mql.metrics = List.of(m);
        Mql.Condition h = new Mql.Condition();
        h.field = "total"; h.op = ">"; h.value = 100;
        mql.having = List.of(h);

        String sql = render(mql);
        assertTrue(sql.contains("group by `currency`"), sql);
        assertTrue(sql.contains("having"), sql);
        assertTrue(sql.contains("as `total`"), sql);
    }

    @Test
    void orderByAndLimit_render() {
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Sort s = new Mql.Sort();
        s.field = "balance"; s.direction = "desc";
        mql.orderBy = List.of(s);
        mql.limit = 5;

        String sql = render(mql);
        assertTrue(sql.contains("order by `balance` desc"), sql);
        assertTrue(sql.contains("limit 5"), sql);
    }

    @Test
    void inAndLikeLeaves_render() {
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Condition cin = new Mql.Condition();
        cin.field = "currency"; cin.op = "in"; cin.value = List.of("USD", "EUR");
        Mql.Condition clike = new Mql.Condition();
        clike.field = "account_name"; clike.op = "like"; clike.value = "%总部%";
        mql.filter = List.of(cin, clike);

        String sql = render(mql);
        assertTrue(sql.contains("`currency` in ('usd', 'eur')"), sql);
        assertTrue(sql.contains("`account_name` like '%总部%'"), sql);
    }

    @Test
    void leftJoin_rendersLeftOuter() {
        Mql mql = new Mql();
        mql.table = "a";
        Mql.Join j = new Mql.Join();
        j.table = "b"; j.type = "left";
        Mql.On on = new Mql.On();
        on.left = "a.id"; on.right = "b.aid";
        j.on = List.of(on);
        mql.joins = List.of(j);

        assertTrue(render(mql).contains("left outer join `b`"), render(mql));
    }

    @Test
    void avgMinMax_render() {
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Metric a = new Mql.Metric(); a.op = "avg"; a.field = "balance"; a.alias = "a";
        Mql.Metric mn = new Mql.Metric(); mn.op = "min"; mn.field = "balance"; mn.alias = "mn";
        Mql.Metric mx = new Mql.Metric(); mx.op = "max"; mx.field = "balance"; mx.alias = "mx";
        mql.metrics = List.of(a, mn, mx);

        String sql = render(mql);
        assertTrue(sql.contains("avg("), sql);
        assertTrue(sql.contains("min("), sql);
        assertTrue(sql.contains("max("), sql);
    }

    @Test
    void singleTableNoProjection_rendersSelectStar() {
        Mql mql = new Mql();
        mql.table = "account";
        String sql = render(mql);
        assertTrue(sql.contains("select *"), sql);
        assertTrue(sql.contains("from `account`"), sql);
    }

    private static Mql.TimeColumn monthCol() {
        Mql.TimeColumn tc = new Mql.TimeColumn();
        tc.func = "month"; tc.field = "txn_date"; tc.alias = "ym";
        return tc;
    }

    @Test
    void timeColumn_groupByExpandsDateFormat() {
        // 按月分组:SELECT 与 GROUP BY 均展开为 date_format 表达式(非裸别名)
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.timeColumns = List.of(monthCol());
        mql.groupBy = List.of("ym");
        Mql.Metric m = new Mql.Metric();
        m.op = "sum"; m.field = "amount"; m.alias = "total";
        mql.metrics = List.of(m);
        Mql.Sort s = new Mql.Sort();
        s.field = "ym";
        mql.orderBy = List.of(s);

        String sql = render(mql);
        assertTrue(sql.contains("date_format(`txn_date`, '%y-%m')"), sql);
        assertTrue(sql.contains("as `ym`"), sql);
        int groupByPos = sql.indexOf("group by");
        assertTrue(groupByPos > 0 && sql.indexOf("date_format", groupByPos) > groupByPos,
                "group by 应展开为 date_format 表达式而非裸别名: " + sql);
        assertTrue(sql.contains("order by `ym`"), sql);
    }

    @Test
    void timeColumnQuarter_rendersConcat() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.TimeColumn tc = new Mql.TimeColumn();
        tc.func = "quarter"; tc.field = "txn_date"; tc.alias = "yq";
        mql.timeColumns = List.of(tc);
        mql.groupBy = List.of("yq");
        Mql.Metric m = new Mql.Metric();
        m.op = "count"; m.alias = "cnt";
        mql.metrics = List.of(m);

        String sql = render(mql);
        assertTrue(sql.contains("concat"), sql);
        assertTrue(sql.contains("quarter(`txn_date`)"), sql);
        assertTrue(sql.contains("'-q'"), sql);
    }

    @Test
    void timeColumn_plainProjection_renders() {
        // 无聚合时 timeColumn 与 columns 并入投影
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        mql.columns = List.of("amount");
        mql.timeColumns = List.of(monthCol());

        String sql = render(mql);
        assertTrue(sql.contains("`amount`"), sql);
        assertTrue(sql.contains("date_format(`txn_date`, '%y-%m')"), sql);
    }

    @Test
    void negationOps_render() {
        Mql mql = new Mql();
        mql.table = "cash_transaction";
        Mql.Condition notIn = leaf("currency", "not in", List.of("USD", "EUR"));
        Mql.Condition notLike = leaf("counterparty", "not like", "%经销商%");
        Mql.Condition notNull = leaf("counterparty", "IS NOT NULL", null);   // 大小写归一化
        mql.filter = List.of(notIn, notLike, notNull);

        String sql = render(mql);
        assertTrue(sql.contains("`currency` not in ('usd', 'eur')"), sql);
        assertTrue(sql.contains("`counterparty` not like '%经销商%'"), sql);
        assertTrue(sql.contains("`counterparty` is not null"), sql);
    }

    @Test
    void antiJoin_rendersLeftJoinIsNull() {
        // 反连接：没有任何流水的账户 = left join + 右表非空列 is null
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Join j = new Mql.Join();
        j.table = "cash_transaction"; j.type = "left";
        Mql.On on = new Mql.On();
        on.left = "account.account_id"; on.right = "cash_transaction.account_id";
        j.on = List.of(on);
        mql.joins = List.of(j);
        mql.columns = List.of("account.account_name");
        mql.filter = List.of(leaf("cash_transaction.txn_id", "is null", null));

        String sql = render(mql);
        assertTrue(sql.contains("left outer join `cash_transaction`"), sql);
        assertTrue(sql.contains("`cash_transaction`.`txn_id` is null"), sql);
    }

    @Test
    void crossColumnOr_rendersOr() {
        // ①-1 回归：跨列 OR
        Mql mql = new Mql();
        mql.table = "account";
        Mql.Condition leaf1 = new Mql.Condition();
        leaf1.field = "balance"; leaf1.op = ">"; leaf1.value = 5000000;
        Mql.Condition leaf2 = new Mql.Condition();
        leaf2.field = "status"; leaf2.op = "="; leaf2.value = "FROZEN";
        Mql.Condition group = new Mql.Condition();
        group.or = List.of(leaf1, leaf2);
        mql.filter = List.of(group);

        String sql = render(mql);
        assertTrue(sql.contains(" or "), "应渲染 OR: " + sql);
        assertTrue(sql.contains("`balance` > 5000000") && sql.contains("`status` = 'frozen'"), sql);
    }
}
