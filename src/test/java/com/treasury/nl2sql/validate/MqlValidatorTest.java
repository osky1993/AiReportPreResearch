package com.treasury.nl2sql.validate;

import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.schema.SchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MqlValidator 全量规则回归单测（纯逻辑，mock SchemaService）。
 * 覆盖聚合约束、IN/NOT IN 空数组、columns+groupBy 冲突、distinct 限制、caseColumn、fieldExpr、timeColumn、JOIN 条件、
 * 子查询与窗口函数等关键校验路径；用于保证 ①-5 安全边界不会被上游 query 重构绕过。
 */
class MqlValidatorTest {

    private final SchemaService schema = mock(SchemaService.class);
    private MqlValidator validator;

    /**
     * 使用单表 cash_transaction 约定常量列特性搭建可重复验证语境。
     * 覆盖数值/非数值、是否存在/时间列等最小 schema 事实集合。
     */
    @BeforeEach
    void setup() {
        validator = new MqlValidator(schema);
        // 单表 cash_transaction：amount=数值, currency/status=非数值
        when(schema.hasTable("cash_transaction")).thenReturn(true);
        lenient().when(schema.hasColumn("cash_transaction", "amount")).thenReturn(true);
        lenient().when(schema.hasColumn("cash_transaction", "currency")).thenReturn(true);
        lenient().when(schema.hasColumn("cash_transaction", "status")).thenReturn(true);
        lenient().when(schema.isNumericColumn("cash_transaction", "amount")).thenReturn(true);
        lenient().when(schema.isNumericColumn("cash_transaction", "currency")).thenReturn(false);
        lenient().when(schema.isNumericColumn("cash_transaction", "status")).thenReturn(false);
        lenient().when(schema.columnType("cash_transaction", "currency")).thenReturn("varchar");
        // txn_date=日期列（timeColumns 用）
        lenient().when(schema.hasColumn("cash_transaction", "txn_date")).thenReturn(true);
        lenient().when(schema.isTemporalColumn("cash_transaction", "txn_date")).thenReturn(true);
    }

    /**
     * 构造基础 MQL（cash_transaction）。测试函数复用，避免重复设置产生噪声。
     */
    private Mql base() {
        Mql m = new Mql();
        m.table = "cash_transaction";
        return m;
    }

    private Mql.Metric metric(String op, String field, String alias) {
        Mql.Metric mt = new Mql.Metric();
        mt.op = op; mt.field = field; mt.alias = alias;
        return mt;
    }

    // ---- (a) 聚合数值类型 ----
    @Test
    void sumOnNonNumeric_isRejected() {
        Mql m = base();
        m.metrics = List.of(metric("sum", "currency", "x"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("sum 只能用于数值列")), errs.toString());
    }

    @Test
    void sumOnNumeric_passes() {
        Mql m = base();
        m.metrics = List.of(metric("sum", "amount", "x"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void maxOnNonNumeric_andCountStar_pass() {
        Mql m = base();
        m.metrics = List.of(metric("max", "status", "a"), metric("count", null, "b"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (b) in 空数组 ----
    @Test
    void emptyInArray_isRejected() {
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "in"; c.value = List.of();
        m.filter = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("空数组")), errs.toString());
    }

    @Test
    void nonEmptyInArray_passes() {
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "in"; c.value = List.of("USD", "EUR");
        m.filter = List.of(c);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (b2) 否定谓词 / 判空 ----
    @Test
    void isNullWithValue_isRejected() {
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "is null"; c.value = "CNY";
        m.filter = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("不带 value")), errs.toString());
    }

    @Test
    void isNullWithSubquery_isRejected() {
        Mql sub = new Mql();
        sub.table = "cash_transaction";
        sub.metrics = List.of(metric("avg", "amount", "a"));
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "is not null"; c.subquery = sub;
        m.filter = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("不能与 subquery 组合")), errs.toString());
    }

    @Test
    void notInEmptyArray_isRejected() {
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "currency"; c.op = "not in"; c.value = List.of();
        m.filter = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("空数组")), errs.toString());
    }

    @Test
    void negationOps_pass() {
        Mql m = base();
        Mql.Condition isNull = new Mql.Condition();
        isNull.field = "currency"; isNull.op = "is null";
        Mql.Condition notIn = new Mql.Condition();
        notIn.field = "status"; notIn.op = "NOT IN"; notIn.value = List.of("SETTLED");   // 大小写归一化
        Mql.Condition notLike = new Mql.Condition();
        notLike.field = "status"; notLike.op = "not like"; notLike.value = "%FAI%";
        m.filter = List.of(isNull, notIn, notLike);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void qualifyWithIsNull_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "row_number"; w.alias = "rn";
        Mql.Sort s = new Mql.Sort();
        s.field = "amount";
        w.orderBy = List.of(s);
        m.windows = List.of(w);
        Mql.Condition c = new Mql.Condition();
        c.field = "rn"; c.op = "is null";
        m.qualify = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("qualify 仅支持比较运算符")), errs.toString());
    }

    // ---- (c) groupBy + columns 并存 ----
    @Test
    void columnsWithGroupBy_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        m.groupBy = List.of("currency");
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("columns 会被忽略")), errs.toString());
    }

    @Test
    void groupByOnly_passes() {
        Mql m = base();
        m.groupBy = List.of("currency");
        m.metrics = List.of(metric("sum", "amount", "total"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (d) DISTINCT ----
    @Test
    void distinctWithMetrics_isRejected() {
        Mql m = base();
        m.distinct = true;
        m.columns = List.of("currency");
        m.metrics = List.of(metric("count", null, "cnt"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("顶层 distinct 仅用于")), errs.toString());
    }

    @Test
    void distinctWithoutColumns_isRejected() {
        Mql m = base();
        m.distinct = true;
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("需要显式 columns")), errs.toString());
    }

    @Test
    void distinctColumns_passes() {
        Mql m = base();
        m.distinct = true;
        m.columns = List.of("currency");
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void metricDistinctOnNonCount_isRejected() {
        Mql m = base();
        Mql.Metric mt = metric("sum", "amount", "x");
        mt.distinct = true;
        m.metrics = List.of(mt);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("distinct 聚合仅支持 count")), errs.toString());
    }

    @Test
    void countDistinctWithField_passes() {
        Mql m = base();
        Mql.Metric mt = metric("count", "currency", "cnt");
        mt.distinct = true;
        m.metrics = List.of(mt);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (e) caseColumns ----
    private Mql.CaseColumn caseCol(String alias, Object then) {
        Mql.CaseColumn cc = new Mql.CaseColumn();
        cc.alias = alias;
        Mql.CaseWhen cw = new Mql.CaseWhen();
        Mql.Condition c = new Mql.Condition();
        c.field = "amount"; c.op = ">="; c.value = 100;
        cw.when = List.of(c);
        cw.then = then;
        cc.cases = List.of(cw);
        return cc;
    }

    @Test
    void caseColumnMissingAlias_isRejected() {
        Mql m = base();
        Mql.CaseColumn cc = caseCol(null, "大额");
        m.caseColumns = List.of(cc);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("caseColumn 缺少 alias")), errs.toString());
    }

    @Test
    void caseWhenBadColumn_isRejected() {
        Mql m = base();
        Mql.CaseColumn cc = caseCol("tier", "大额");
        cc.cases.get(0).when.get(0).field = "ghost_col";
        m.caseColumns = List.of(cc);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("不存在")), errs.toString());
    }

    @Test
    void caseThenNonScalar_isRejected() {
        Mql m = base();
        Mql.CaseColumn cc = caseCol("tier", List.of("列表非法"));
        m.caseColumns = List.of(cc);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("then 必须是标量常量")), errs.toString());
    }

    @Test
    void caseAliasNotInGroupBy_whenAggregating_isRejected() {
        Mql m = base();
        m.caseColumns = List.of(caseCol("tier", "大额"));
        m.metrics = List.of(metric("count", null, "cnt"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("必须放进 groupBy")), errs.toString());
    }

    @Test
    void groupByCaseAlias_passes() {
        Mql m = base();
        m.caseColumns = List.of(caseCol("tier", "大额"));
        m.groupBy = List.of("tier");
        m.metrics = List.of(metric("count", null, "cnt"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (d2) fieldExpr 行级表达式聚合 + Expr 常量操作数 ----
    private Mql.Metric fieldExprMetric(String left, Object right, String alias) {
        Mql.Metric mt = new Mql.Metric();
        mt.op = "sum"; mt.alias = alias;
        mt.fieldExpr = new Mql.FieldExpr();
        mt.fieldExpr.left = left; mt.fieldExpr.arith = "*"; mt.fieldExpr.right = right;
        return mt;
    }

    @Test
    void fieldExpr_passes() {
        Mql m = base();
        m.metrics = List.of(fieldExprMetric("amount", "amount", "sq"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void fieldExprWithConstantRight_passes() {
        Mql m = base();
        m.metrics = List.of(fieldExprMetric("amount", 100, "x100"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void fieldExprWithField_isRejected() {
        Mql m = base();
        Mql.Metric mt = fieldExprMetric("amount", "amount", "x");
        mt.field = "amount";
        m.metrics = List.of(mt);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("fieldExpr 与 field 只能二选一")), errs.toString());
    }

    @Test
    void countWithFieldExpr_isRejected() {
        Mql m = base();
        Mql.Metric mt = fieldExprMetric("amount", "amount", "x");
        mt.op = "count";
        m.metrics = List.of(mt);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("count 不支持行级表达式")), errs.toString());
    }

    @Test
    void fieldExprLeftNonNumeric_isRejected() {
        Mql m = base();
        m.metrics = List.of(fieldExprMetric("currency", "amount", "x"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("fieldExpr.left 只能是数值列")), errs.toString());
    }

    @Test
    void fieldExprRightGhostColumn_isRejected() {
        Mql m = base();
        m.metrics = List.of(fieldExprMetric("amount", "ghost_col", "x"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("fieldExpr.right") && e.contains("不存在")), errs.toString());
    }

    @Test
    void fieldExprRightBoolean_isRejected() {
        Mql m = base();
        m.metrics = List.of(fieldExprMetric("amount", Boolean.TRUE, "x"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("数值列引用或数字常量")), errs.toString());
    }

    @Test
    void exprConstantOperand_passes() {
        // 占比×100：expr 的 right 为常量 {"value":100}
        Mql m = base();
        Mql.Metric mt = new Mql.Metric();
        mt.alias = "pct";
        mt.expr = new Mql.Expr();
        mt.expr.left = metric("sum", "amount", null);
        Mql.Metric constant = new Mql.Metric();
        constant.value = 100;
        mt.expr.right = constant;
        mt.expr.arith = "*";
        m.metrics = List.of(mt);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void exprBothConstants_isRejected() {
        Mql m = base();
        Mql.Metric mt = new Mql.Metric();
        mt.alias = "bad";
        mt.expr = new Mql.Expr();
        Mql.Metric c1 = new Mql.Metric(); c1.value = 1;
        Mql.Metric c2 = new Mql.Metric(); c2.value = 2;
        mt.expr.left = c1; mt.expr.right = c2; mt.expr.arith = "+";
        m.metrics = List.of(mt);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("不能都是常量")), errs.toString());
    }

    @Test
    void topLevelConstantMetric_isRejected() {
        Mql m = base();
        Mql.Metric mt = new Mql.Metric();
        mt.alias = "c"; mt.value = 100;
        m.metrics = List.of(mt);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("仅可用于 expr 的 left/right")), errs.toString());
    }

    // ---- (e2) timeColumns ----
    private Mql.TimeColumn timeCol(String func, String field, String alias) {
        Mql.TimeColumn tc = new Mql.TimeColumn();
        tc.func = func; tc.field = field; tc.alias = alias;
        return tc;
    }

    @Test
    void timeColumnBadFunc_isRejected() {
        Mql m = base();
        m.timeColumns = List.of(timeCol("week", "txn_date", "yw"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("非法时间函数")), errs.toString());
    }

    @Test
    void timeColumnOnNonTemporal_isRejected() {
        Mql m = base();
        m.timeColumns = List.of(timeCol("month", "currency", "ym"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("只能作用于日期/时间列")), errs.toString());
    }

    @Test
    void timeAliasNotInGroupBy_whenAggregating_isRejected() {
        Mql m = base();
        m.timeColumns = List.of(timeCol("month", "txn_date", "ym"));
        m.metrics = List.of(metric("sum", "amount", "total"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("timeColumn 必须放进 groupBy")), errs.toString());
    }

    @Test
    void timeAliasCollidesRealColumn_isRejected() {
        Mql m = base();
        m.timeColumns = List.of(timeCol("month", "txn_date", "currency"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("与真实列名冲突")), errs.toString());
    }

    @Test
    void groupByTimeAlias_andOrderByAlias_pass() {
        Mql m = base();
        m.timeColumns = List.of(timeCol("month", "txn_date", "ym"));
        m.groupBy = List.of("ym");
        m.metrics = List.of(metric("sum", "amount", "total"));
        Mql.Sort s = new Mql.Sort();
        s.field = "ym";
        m.orderBy = List.of(s);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    // ---- (e3) JOIN on 常量条件 ----
    private Mql joinWithOn(Mql.On... ons) {
        Mql m = base();
        Mql.Join j = new Mql.Join();
        j.table = "cash_transaction";
        j.alias = "t";
        m.alias = "c";
        j.on = List.of(ons);
        m.joins = List.of(j);
        m.columns = List.of("c.amount");
        return m;
    }

    private Mql.On on(String left, String op, String right, Object value) {
        Mql.On o = new Mql.On();
        o.left = left; o.op = op; o.right = right; o.value = value;
        return o;
    }

    @Test
    void onConstant_passes() {
        Mql m = joinWithOn(
                on("c.currency", "=", "t.currency", null),
                on("t.status", "=", null, "SETTLED"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void onRightAndValueBoth_isRejected() {
        Mql m = joinWithOn(on("c.currency", "=", "t.currency", "CNY"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("right 与 value 只能二选一")), errs.toString());
    }

    @Test
    void onValueNonScalar_isRejected() {
        Mql m = joinWithOn(
                on("c.currency", "=", "t.currency", null),
                on("t.status", "=", null, List.of("SETTLED", "FAILED")));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("value 必须是标量常量")), errs.toString());
    }

    @Test
    void onNeitherRightNorValue_isRejected() {
        Mql m = joinWithOn(on("c.currency", "=", null, null));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("连接条件右侧为空")), errs.toString());
    }

    // ---- (f) 子查询 ----
    private Mql.Condition subCond(String field, String op, Mql sub) {
        Mql.Condition c = new Mql.Condition();
        c.field = field; c.op = op; c.subquery = sub;
        return c;
    }

    private Mql scalarSub() {
        Mql sub = new Mql();
        sub.table = "cash_transaction";
        sub.metrics = List.of(metric("avg", "amount", "avg_amt"));
        return sub;
    }

    @Test
    void scalarSubquery_passes() {
        Mql m = base();
        m.filter = List.of(subCond("amount", ">", scalarSub()));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void subqueryWithValueBoth_isRejected() {
        Mql m = base();
        Mql.Condition c = subCond("amount", ">", scalarSub());
        c.value = 100;
        m.filter = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("只能二选一")), errs.toString());
    }

    @Test
    void scalarSubqueryMultiMetric_isRejected() {
        Mql sub = scalarSub();
        sub.metrics = List.of(metric("avg", "amount", "a"), metric("max", "amount", "b"));
        Mql m = base();
        m.filter = List.of(subCond("amount", ">", sub));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("标量子查询必须恰有 1 个聚合指标")), errs.toString());
    }

    @Test
    void inSubqueryTwoColumns_isRejected() {
        Mql sub = new Mql();
        sub.table = "cash_transaction";
        sub.columns = List.of("currency", "status");
        Mql m = base();
        m.filter = List.of(subCond("currency", "in", sub));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("in 子查询必须只输出 1 列")), errs.toString());
    }

    @Test
    void nestedSubquery_isRejected_withPrefix() {
        Mql inner = scalarSub();
        Mql sub = scalarSub();
        sub.filter = List.of(subCond("amount", ">", inner));   // 子查询里再嵌子查询
        Mql m = base();
        m.filter = List.of(subCond("amount", ">", sub));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("子查询: ") && e.contains("嵌套过深")), errs.toString());
    }

    @Test
    void subqueryBadColumn_isRejected_withPrefix() {
        Mql sub = scalarSub();
        sub.metrics = List.of(metric("avg", "ghost_col", "x"));
        Mql m = base();
        m.filter = List.of(subCond("amount", ">", sub));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("子查询: ") && e.contains("不存在")), errs.toString());
    }

    // ---- (g) 窗口函数 + qualify ----
    private Mql.WindowSpec win(String op, String field, String alias) {
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = op; w.field = field; w.alias = alias;
        w.partitionBy = List.of("currency");
        Mql.Sort s = new Mql.Sort();
        s.field = "amount"; s.direction = "desc";
        w.orderBy = List.of(s);
        return w;
    }

    @Test
    void windowRowNumber_passes() {
        Mql m = base();
        m.columns = List.of("currency", "amount");
        m.windows = List.of(win("row_number", null, "rn"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void aggWindow_refOutsideGroupByOrMetric_isRejected() {
        // 聚合后开窗：窗口 orderBy 引用了未进 groupBy/metric 的裸列 amount → 拒
        Mql m = base();
        m.groupBy = List.of("currency");
        m.metrics = List.of(metric("sum", "amount", "total"));
        m.windows = List.of(win("row_number", null, "rn"));   // orderBy=amount，不在 allowedRefs
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("聚合后开窗时") && e.contains("只能引用")), errs.toString());
    }

    @Test
    void aggWindowLag_onMetricAlias_passes() {
        // 环比：按月聚合后 lag 取上月值
        Mql m = base();
        m.timeColumns = List.of(timeCol("month", "txn_date", "ym"));
        m.groupBy = List.of("ym");
        m.metrics = List.of(metric("sum", "amount", "total"));
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "total"; w.alias = "prev_total";
        Mql.Sort s = new Mql.Sort();
        s.field = "ym";
        w.orderBy = List.of(s);
        m.windows = List.of(w);
        Mql.Sort top = new Mql.Sort();
        top.field = "ym";
        m.orderBy = List.of(top);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void windowAliasInColumns_isTolerated() {
        // LLM 常把窗口别名列进 columns（当作输出列清单）：应放行而非报"列不存在/须加前缀"
        Mql m = base();
        m.columns = List.of("txn_date", "amount", "prev");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "amount"; w.alias = "prev";
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        m.windows = List.of(w);
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void distinctWithWindows_stillRejected() {
        Mql m = base();
        m.distinct = true;
        m.columns = List.of("currency");
        m.windows = List.of(win("row_number", null, "rn"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("windows 与 distinct 互斥")), errs.toString());
    }

    @Test
    void lagMissingOrderBy_isRejected() {
        Mql m = base();
        m.columns = List.of("txn_date", "amount");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "amount"; w.alias = "prev";
        w.partitionBy = List.of("currency");   // 有 partition 但无 orderBy
        m.windows = List.of(w);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("lag/lead 必须指定 orderBy")), errs.toString());
    }

    @Test
    void lagMissingField_isRejected() {
        Mql m = base();
        m.columns = List.of("txn_date");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.alias = "prev";
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        m.windows = List.of(w);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("lag/lead 必须指定 field")), errs.toString());
    }

    @Test
    void lagOffsetZero_isRejected() {
        Mql m = base();
        m.columns = List.of("txn_date", "amount");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "lag"; w.field = "amount"; w.alias = "prev"; w.offset = 0;
        Mql.Sort s = new Mql.Sort();
        s.field = "txn_date";
        w.orderBy = List.of(s);
        m.windows = List.of(w);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("offset 须 ≥1")), errs.toString());
    }

    @Test
    void offsetOnRankOp_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        Mql.WindowSpec w = win("row_number", null, "rn");
        w.offset = 2;
        m.windows = List.of(w);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("offset 仅 lag/lead 可用")), errs.toString());
    }

    @Test
    void qualifyWithoutWindows_isRejected() {
        Mql m = base();
        Mql.Condition c = new Mql.Condition();
        c.field = "rn"; c.op = "<="; c.value = 2;
        m.qualify = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("qualify 仅在有 windows 时可用")), errs.toString());
    }

    @Test
    void qualifyOnNonWindowAlias_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        m.windows = List.of(win("row_number", null, "rn"));
        Mql.Condition c = new Mql.Condition();
        c.field = "amount"; c.op = "<="; c.value = 2;
        m.qualify = List.of(c);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("qualify 的 field 必须是窗口别名")), errs.toString());
    }

    @Test
    void rankWithoutOrderBy_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        Mql.WindowSpec w = new Mql.WindowSpec();
        w.op = "rank"; w.alias = "rk";
        w.partitionBy = List.of("currency");
        m.windows = List.of(w);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("排名窗口函数必须指定 orderBy")), errs.toString());
    }

    @Test
    void windowSumOnNonNumeric_isRejected() {
        Mql m = base();
        m.columns = List.of("currency");
        m.windows = List.of(win("sum", "status", "x"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("窗口 sum 只能用于数值列")), errs.toString());
    }

    @Test
    void qualifyWithQualifiedOrderBy_isRejected() {
        Mql m = base();
        m.columns = List.of("currency", "amount");
        m.windows = List.of(win("row_number", null, "rn"));
        Mql.Condition c = new Mql.Condition();
        c.field = "rn"; c.op = "<="; c.value = 2;
        m.qualify = List.of(c);
        Mql.Sort s = new Mql.Sort();
        s.field = "cash_transaction.amount";
        m.orderBy = List.of(s);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("须用别名或裸列名")), errs.toString());
    }

    @Test
    void subqueryInHaving_isRejected() {
        Mql m = base();
        m.groupBy = List.of("currency");
        m.metrics = List.of(metric("sum", "amount", "total"));
        m.having = List.of(subCond("total", ">", scalarSub()));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("having 中不支持子查询")), errs.toString());
    }

    // ---- (h) union ----
    private Mql seg(String... columns) {
        Mql s = new Mql();
        s.table = "cash_transaction";
        s.columns = List.of(columns);
        return s;
    }

    @Test
    void union_sameColumnCount_passes() {
        Mql m = seg("currency");
        m.union = List.of(seg("status"));
        assertTrue(validator.validate(m).isEmpty(), validator.validate(m).toString());
    }

    @Test
    void unionColumnCountMismatch_isRejected() {
        Mql m = seg("currency");
        m.union = List.of(seg("currency", "status"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("union 各段列数不一致")), errs.toString());
    }

    @Test
    void unionSegmentWithOrderBy_isRejected() {
        Mql m = seg("currency");
        Mql s2 = seg("status");
        Mql.Sort sort = new Mql.Sort();
        sort.field = "status";
        s2.orderBy = List.of(sort);
        m.union = List.of(s2);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("union第1段: ") && e.contains("orderBy/limit")), errs.toString());
    }

    @Test
    void nestedUnion_isRejected() {
        Mql inner = seg("status");
        Mql s2 = seg("currency");
        s2.union = List.of(inner);
        Mql m = seg("currency");
        m.union = List.of(s2);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.contains("不得再嵌 union")), errs.toString());
    }

    @Test
    void unionSegmentSelectStar_isRejected() {
        Mql m = seg("currency");
        Mql s2 = new Mql();
        s2.table = "cash_transaction";   // 无 columns/metrics = SELECT *
        m.union = List.of(s2);
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("union第1段: ") && e.contains("不能 SELECT *")), errs.toString());
    }

    @Test
    void unionSegmentBadColumn_isRejected_withPrefix() {
        Mql m = seg("currency");
        m.union = List.of(seg("ghost_col"));
        List<String> errs = validator.validate(m);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("union第1段: ") && e.contains("不存在")), errs.toString());
    }
}
