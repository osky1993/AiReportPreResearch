package com.treasury.nl2sql.compile;

import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * MQL(IR) 到 jOOQ 的确定性编译器。
 * <p>职责是将验证通过的 AST 映射为可执行 SelectQuery：保留语义、固定参数化风格、统一方言适配。
 * 与 validator 的关系是“后置映射、前置校验”——复杂语义边界先在校验器约束，编译器按固定规则渲染。</p>
 */
@Component
public class MqlSqlCompiler {

    private final DSLContext dsl;

    public MqlSqlCompiler(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 编译入口。主查询若存在 union，先逐段编译为子查询，再统一 wrap 一次进行外层 order/limit 注入，
     * 这样既保证段内语义不被干扰，也保证最终分页/排序可被用户理解为“全局语义”。
     */
    public SelectQuery<Record> compile(Mql mql) {
        if (safe(mql.union).isEmpty()) {
            return compileSegment(mql, true);
        }
        // UNION：各段编译（不含 orderBy/limit，校验器已禁段内出现），折叠后包派生表加整体排序/限行
        Select<Record> whole = compileSegment(mql, false);
        for (Mql seg : mql.union) {
            Select<Record> s = compileSegment(seg, false);
            whole = Boolean.TRUE.equals(mql.unionAll) ? whole.unionAll(s) : whole.union(s);
        }
        SelectQuery<Record> outer = dsl.selectQuery();
        outer.addSelect(DSL.asterisk());
        outer.addFrom(whole.asTable("u"));
        addOrderAndLimit(outer, mql.orderBy, mql.limit);
        return outer;
    }

    /**
     * 编译单段查询（union 各段 / 无 union 的整条）。
     * includeOrderLimit=false 时会把 orderBy/limit 留到上层统一注入，避免段内 orderBy/limit 影响 union 语义。
     */
    private SelectQuery<Record> compileSegment(Mql mql, boolean includeOrderLimit) {
        SelectQuery<Record> q = dsl.selectQuery();
        q.addFrom(aliased(mql.table, mql.alias));

        // ---- JOIN ----
        for (Mql.Join j : safe(mql.joins)) {
            List<Condition> on = new ArrayList<>();
            for (Mql.On o : safe(j.on)) {
                on.add(joinCondition(o));
            }
            Condition onCond = on.stream().reduce(DSL.noCondition(), Condition::and);
            JoinType type = "left".equalsIgnoreCase(j.type) ? JoinType.LEFT_OUTER_JOIN : JoinType.JOIN;
            q.addJoin(aliased(j.table, j.alias), type, onCond);
        }

        // ---- SELECT ----
        // case/time 派生别名会被缓存到内存 Map，用于 groupBy 时“别名 -> 表达式”反查，避免重复构造导致表达式不一致。
        // case/time 派生维度按别名索引:groupBy 引用别名时,SELECT/GROUP BY 展开为同一表达式
        // (GROUP BY 不下放别名,确定性满足 ONLY_FULL_GROUP_BY)
        Map<String, Mql.CaseColumn> caseByAlias = new LinkedHashMap<>();
        for (Mql.CaseColumn cc : safe(mql.caseColumns)) caseByAlias.put(cc.alias, cc);
        Map<String, Mql.TimeColumn> timeByAlias = new LinkedHashMap<>();
        for (Mql.TimeColumn tc : safe(mql.timeColumns)) timeByAlias.put(tc.alias, tc);

        if (Boolean.TRUE.equals(mql.distinct)) q.setDistinct(true);

        List<SelectFieldOrAsterisk> selects = new ArrayList<>();
        for (String g : safe(mql.groupBy)) {
            selects.add(derivedOrPlain(g, caseByAlias, timeByAlias));
        }
        for (Mql.Metric m : safe(mql.metrics)) {
            selects.add(aggregate(m).as(m.alias));
        }
        if (selects.isEmpty()) {
            if ((mql.columns == null || mql.columns.isEmpty())
                    && safe(mql.caseColumns).isEmpty() && safe(mql.timeColumns).isEmpty()) {
                selects.add(DSL.asterisk());
            } else {
                // columns 里出现的窗口别名跳过（校验器已放行；窗口列在下方统一追加，避免重复列）
                java.util.Set<String> winAliases = new java.util.HashSet<>();
                for (Mql.WindowSpec w : safe(mql.windows)) winAliases.add(w.alias);
                for (String c : safe(mql.columns)) {
                    if (!winAliases.contains(c)) selects.add(fld(c));
                }
                for (Mql.CaseColumn cc : safe(mql.caseColumns)) selects.add(caseField(cc).as(cc.alias));
                for (Mql.TimeColumn tc : safe(mql.timeColumns)) selects.add(timeField(tc).as(tc.alias));
            }
        }
        // 聚合后开窗：窗口须作用于聚合结果，窗口列不进本层 SELECT（在外层派生表加）
        // 这样做是为了保证窗口字段能看到“聚合产物”而不是明细行。
        boolean aggregated = !safe(mql.groupBy).isEmpty() || !safe(mql.metrics).isEmpty();
        boolean aggWindow = aggregated && !safe(mql.windows).isEmpty();
        if (!aggWindow) {
            for (Mql.WindowSpec w : safe(mql.windows)) {
                selects.add(windowField(w).as(w.alias));
            }
        }
        q.addSelect(selects);

        // ---- WHERE ----
        List<Condition> where = new ArrayList<>();
        for (Mql.Condition c : safe(mql.filter)) where.add(toCondition(c));
        if (!where.isEmpty()) q.addConditions(where);

        // ---- GROUP BY ----
        List<Field<?>> groups = new ArrayList<>();
        for (String g : safe(mql.groupBy)) {
            Mql.CaseColumn cc = caseByAlias.get(g);
            Mql.TimeColumn tc = timeByAlias.get(g);
            groups.add(cc != null ? caseField(cc) : tc != null ? timeField(tc) : fld(g));
        }
        if (!groups.isEmpty()) q.addGroupBy(groups);

        // ---- HAVING ----
        List<Condition> having = new ArrayList<>();
        for (Mql.Condition c : safe(mql.having)) having.add(toCondition(c));
        if (!having.isEmpty()) q.addHaving(having);

        // ---- 聚合后开窗：聚合内层 + 窗口外层（SELECT g.*, 窗口列 FROM (聚合) g） ----
        // 窗口引用的都是内层输出的裸名（groupBy 列/派生别名/metric 别名，校验器已保证）
        // 该路径保证“先聚合再开窗”与“开窗再 qualify/分页”顺序正确。
        if (aggWindow) {
            SelectQuery<Record> win = dsl.selectQuery();
            win.addSelect(DSL.asterisk());
            for (Mql.WindowSpec w : safe(mql.windows)) {
                win.addSelect(windowField(w).as(w.alias));
            }
            win.addFrom(q.asTable("g"));
            q = win;   // 后续 qualify 在其外再包一层、orderBy/limit 落最外层，复用既有逻辑
        }

        // ---- QUALIFY：窗口结果过滤必须发生在窗口计算之后 → 包一层派生表 ----
        // SELECT * FROM ( ...含窗口列... ) t WHERE rn <= 2；顶层 orderBy/limit 移到外层
        if (!safe(mql.qualify).isEmpty()) {
            SelectQuery<Record> outer = dsl.selectQuery();
            outer.addSelect(DSL.asterisk());
            outer.addFrom(q.asTable("t"));
            for (Mql.Condition c : mql.qualify) outer.addConditions(toCondition(c));
            if (includeOrderLimit) addOrderAndLimit(outer, mql.orderBy, mql.limit);
            return outer;
        }

        if (includeOrderLimit) addOrderAndLimit(q, mql.orderBy, mql.limit);
        return q;
    }

    /**
     * 统一处理排序分页的公共方法。
     * 统一入口可以避免 UNION/QUALIFY 下出现重复语义注入，且保证方向字符串容错为 asc/desc（默认 asc）。
     */
    private void addOrderAndLimit(SelectQuery<Record> q, List<Mql.Sort> orderBy, Integer limit) {
        List<OrderField<?>> orders = new ArrayList<>();
        for (Mql.Sort s : safe(orderBy)) {
            Field<Object> f = fld(s.field);
            orders.add("desc".equalsIgnoreCase(s.direction) ? f.desc() : f.asc());
        }
        if (!orders.isEmpty()) q.addOrderBy(orders);
        if (limit != null) q.addLimit(limit);
    }

    /**
     * 渲染成可读 SQL（值内联），用于日志、问题排查与结果回溯；
     * 实际执行依旧使用 SelectQuery 参数绑定，避免拼接风险。
     */
    public String renderSql(SelectQuery<Record> query) {
        return dsl.renderInlined(query);
    }

    /**
     * 顶层聚合列构建：
     * - expr 形态表示“聚合结果再算术组合”，在 SQL 层先聚合再做 + - * /；
     * - 普通形态则构造单一 count/sum/avg/min/max。
     */
    private Field<? extends Number> aggregate(Mql.Metric m) {
        if (m.expr != null) {
            Field<BigDecimal> l = aggregateLeaf(m.expr.left).cast(BigDecimal.class);
            Field<BigDecimal> r = aggregateLeaf(m.expr.right).cast(BigDecimal.class);
            return switch (m.expr.arith) {
                case "+" -> l.add(r);
                case "-" -> l.sub(r);
                case "*" -> l.mul(r);
                case "/" -> l.div(r);
                default -> throw new IllegalArgumentException("不支持的算术运算符: " + m.expr.arith);
            };
        }
        return aggregateLeaf(m);
    }

    /**
     * 单个聚合构建。
     * - count 支持 field 可空（count *）；
     * - count distinct 走专用算子；
     * - 其他聚合结合 filter 使用 CASE WHEN 封装为条件聚合。
     */
    @SuppressWarnings("unchecked")
    private Field<? extends Number> aggregateLeaf(Mql.Metric m) {
        // Expr 操作数位的数字常量（如占比×100）：直接内联
        if (m.value instanceof Number n) {
            return DSL.inline(new BigDecimal(n.toString()));
        }
        String op = m.op.toLowerCase();
        Condition cond = (m.filter != null && !m.filter.isEmpty())
                ? DSL.and(m.filter.stream().map(this::toCondition).toList()) : null;
        if ("count".equals(op)) {
            if (m.field == null || m.field.isBlank()) {
                return cond == null ? DSL.count() : DSL.count(DSL.when(cond, DSL.val(1)));
            }
            Field<Object> f = fld(m.field);
            if (Boolean.TRUE.equals(m.distinct)) {
                return cond == null ? DSL.countDistinct(f) : DSL.countDistinct(DSL.when(cond, f));
            }
            return cond == null ? DSL.count(f) : DSL.count(DSL.when(cond, f));
        }
        // 行级表达式聚合（SUM(a*b)）或普通列聚合；条件聚合的 CASE 包裹对两者一致生效
        Field<BigDecimal> base = (m.fieldExpr != null) ? fieldExprField(m.fieldExpr)
                : field(nm(m.field), BigDecimal.class);
        Field<BigDecimal> arg = cond == null ? base : DSL.when(cond, base);
        return switch (op) {
            case "sum" -> DSL.sum(arg);
            case "avg" -> DSL.avg(arg);
            case "min" -> DSL.min(arg);
            case "max" -> DSL.max(arg);
            default -> throw new IllegalArgumentException("不支持的聚合: " + m.op);
        };
    }

    /** 行级二元表达式（a op b）到字段映射，b 支持列名或数字常量。 */
    private Field<BigDecimal> fieldExprField(Mql.FieldExpr fe) {
        Field<BigDecimal> l = field(nm(fe.left), BigDecimal.class);
        Field<BigDecimal> r = (fe.right instanceof Number n)
                ? DSL.inline(new BigDecimal(n.toString()))
                : field(nm((String) fe.right), BigDecimal.class);
        return switch (fe.arith) {
            case "+" -> l.add(r);
            case "-" -> l.sub(r);
            case "*" -> l.mul(r);
            case "/" -> l.div(r);
            default -> throw new IllegalArgumentException("不支持的算术运算符: " + fe.arith);
        };
    }

    /** 窗口函数映射：排名、聚合 over、lag/lead。 */
    private Field<?> windowField(Mql.WindowSpec w) {
        WindowSpecification spec = windowSpec(w);
        int offset = w.offset == null ? 1 : w.offset;
        return switch (w.op.toLowerCase()) {
            case "row_number" -> DSL.rowNumber().over(spec);
            case "rank"       -> DSL.rank().over(spec);
            case "dense_rank" -> DSL.denseRank().over(spec);
            case "count"      -> ((w.field == null || w.field.isBlank())
                                    ? DSL.count() : DSL.count(fld(w.field))).over(spec);
            case "sum"        -> DSL.sum(field(nm(w.field), BigDecimal.class)).over(spec);
            case "avg"        -> DSL.avg(field(nm(w.field), BigDecimal.class)).over(spec);
            case "lag"        -> DSL.lag(fld(w.field), offset).over(spec);
            case "lead"       -> DSL.lead(fld(w.field), offset).over(spec);
            default -> throw new IllegalArgumentException("不支持的窗口函数: " + w.op);
        };
    }

    /**
     * OVER 规格映射：若提供 partition 且无 order，则走 PARTITION BY；
     * 若仅有 order，则保留纯 orderBy。
     */
    private WindowSpecification windowSpec(Mql.WindowSpec w) {
        List<Field<?>> parts = new ArrayList<>();
        for (String p : safe(w.partitionBy)) parts.add(fld(p));
        List<OrderField<?>> ords = new ArrayList<>();
        for (Mql.Sort s : safe(w.orderBy)) {
            Field<Object> f = fld(s.field);
            ords.add("desc".equalsIgnoreCase(s.direction) ? f.desc() : f.asc());
        }
        if (!parts.isEmpty() && !ords.isEmpty()) return DSL.partitionBy(parts).orderBy(ords);
        if (!parts.isEmpty()) return DSL.partitionBy(parts);
        return DSL.orderBy(ords);
    }

    /**
     * groupBy 映射：别名命中时展开派生表达式，否则按普通列。
     * 该行为使 SELECT/GROUP BY 在 case/time 场景下一致引用，避免 SQL 层语义漂移。
     */
    private SelectFieldOrAsterisk derivedOrPlain(String ref, Map<String, Mql.CaseColumn> caseByAlias,
                                                 Map<String, Mql.TimeColumn> timeByAlias) {
        Mql.CaseColumn cc = caseByAlias.get(ref);
        if (cc != null) return caseField(cc).as(cc.alias);
        Mql.TimeColumn tc = timeByAlias.get(ref);
        if (tc != null) return timeField(tc).as(tc.alias);
        return fld(ref);
    }

    /**
     * 时间函数映射为字符串维度，便于月份/季度等跨年可排序比较。
     */
    private Field<?> timeField(Mql.TimeColumn tc) {
        Field<Object> col = fld(tc.field);
        return switch (tc.func.toLowerCase()) {
            case "year"    -> DSL.function("date_format", String.class, col, DSL.inline("%Y"));
            case "month"   -> DSL.function("date_format", String.class, col, DSL.inline("%Y-%m"));
            case "day"     -> DSL.function("date_format", String.class, col, DSL.inline("%Y-%m-%d"));
            case "quarter" -> DSL.concat(
                    DSL.function("date_format", String.class, col, DSL.inline("%Y")),
                    DSL.inline("-Q"),
                    DSL.function("quarter", Integer.class, col).cast(String.class));
            default -> throw new IllegalArgumentException("不支持的时间函数: " + tc.func);
        };
    }

    /**
     * CASE WHEN 的 DSL 构造。按 when 顺序拼接，缺省 else 不额外追加（jOOQ 表达式默认 NULL）。
     */
    private Field<?> caseField(Mql.CaseColumn cc) {
        CaseConditionStep<Object> step = null;
        for (Mql.CaseWhen cw : cc.cases) {
            Condition cond = DSL.and(cw.when.stream().map(this::toCondition).toList());
            Field<Object> then = DSL.val(cw.then);
            step = (step == null) ? DSL.when(cond, then) : step.when(cond, then);
        }
        return cc.elseValue != null ? step.otherwise(DSL.val(cc.elseValue)) : step;
    }

    /**
     * 构建表引用：有别名直接作为 SQL alias 使用，避免同名表在自连接下混淆。
     */
    private static Table<?> aliased(String tableName, String alias) {
        Table<?> t = table(name(tableName));
        return (alias == null || alias.isBlank()) ? t : t.as(alias);
    }

    /**
     * 连接条件映射。字段-字段与字段-常量均走参数绑定，保证注入面可控；
     * 同时支持非等值连接，便于表达业务口径差异条件。
     */
    @SuppressWarnings("unchecked")
    private Condition joinCondition(Mql.On o) {
        Field<Object> l = fld(o.left);
        String op = (o.op == null || o.op.isBlank()) ? "=" : o.op;
        if (o.value != null) {
            return switch (op) {
                case "="  -> l.eq(o.value);
                case "!=" -> l.ne(o.value);
                case ">"  -> l.gt(o.value);
                case ">=" -> l.ge(o.value);
                case "<"  -> l.lt(o.value);
                case "<=" -> l.le(o.value);
                default   -> throw new IllegalArgumentException("不支持的连接运算符: " + o.op);
            };
        }
        Field<Object> r = fld(o.right);
        return switch (op) {
            case "="  -> l.eq(r);
            case "!=" -> l.ne(r);
            case ">"  -> l.gt(r);
            case ">=" -> l.ge(r);
            case "<"  -> l.lt(r);
            case "<=" -> l.le(r);
            default   -> throw new IllegalArgumentException("不支持的连接运算符: " + o.op);
        };
    }

    /**
     * 字段名语法归一化：表名.列名 与 单列名两种写法。
     * 真正的表列存在性已在 validator 完成，这里只负责语法级映射。
     */
    private static Name nm(String ref) {
        if (ref != null && ref.contains(".")) {
            String[] p = ref.split("\\.", 2);
            return name(p[0], p[1]);
        }
        return name(ref);
    }

    /** 通用字段包装：统一走 nm(ref) 做转义。 */
    private static Field<Object> fld(String ref) {
        return field(nm(ref));
    }

    /**
     * 条件树递归映射到 jOOQ Condition：
     * 支持逻辑组（AND/OR）与叶子比较（字段/操作符/常量或 subquery）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition toCondition(Mql.Condition c) {
        // 组节点：递归
        if (c.or != null && !c.or.isEmpty()) {
            return DSL.or(c.or.stream().map(this::toCondition).toList());
        }
        if (c.and != null && !c.and.isEmpty()) {
            return DSL.and(c.and.stream().map(this::toCondition).toList());
        }
        // 叶子节点（op 归一化规则与校验器一致）
        Field<Object> f = fld(c.field);
        String op = MqlValidator.normOp(c.op);
        // 子查询叶子：标量比较 / IN（形状——恰 1 列——已由校验器保证，raw 强转安全）
        if (c.subquery != null) {
            Select sub = compile(c.subquery);
            return switch (op) {
                case "="    -> f.eq(sub);
                case "!="   -> f.ne(sub);
                case ">"    -> f.gt(sub);
                case ">="   -> f.ge(sub);
                case "<"    -> f.lt(sub);
                case "<="   -> f.le(sub);
                case "in"   -> f.in(sub);
                default     -> throw new IllegalArgumentException("子查询不支持的运算符: " + c.op);
            };
        }
        Object v = c.value;
        return switch (op) {
            case "="           -> f.eq(v);
            case "!="          -> f.ne(v);
            case ">"           -> f.gt(v);
            case ">="          -> f.ge(v);
            case "<"           -> f.lt(v);
            case "<="          -> f.le(v);
            case "like"        -> f.like(String.valueOf(v));
            case "not like"    -> f.notLike(String.valueOf(v));
            case "in"          -> f.in((Collection<?>) v);
            case "not in"      -> f.notIn((Collection<?>) v);
            case "is null"     -> f.isNull();
            case "is not null" -> f.isNotNull();
            default     -> throw new IllegalArgumentException("不支持的运算符: " + c.op);
        };
    }

    private static <T> List<T> safe(List<T> l) {
        return l == null ? List.of() : l;
    }
}
