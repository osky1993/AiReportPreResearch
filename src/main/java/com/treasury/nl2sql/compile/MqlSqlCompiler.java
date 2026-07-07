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
 * 把 MQL(IR) 确定性地编译成 jOOQ 查询。
 * jOOQ 负责：方言适配（MySQL/PG/...）、标识符转义、参数绑定（防注入）。
 */
@Component
public class MqlSqlCompiler {

    private final DSLContext dsl;

    public MqlSqlCompiler(DSLContext dsl) {
        this.dsl = dsl;
    }

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

    /** 编译单段查询（union 各段 / 无 union 的整条）；includeOrderLimit=false 时顶层排序/限行交由外层处理 */
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

    private void addOrderAndLimit(SelectQuery<Record> q, List<Mql.Sort> orderBy, Integer limit) {
        List<OrderField<?>> orders = new ArrayList<>();
        for (Mql.Sort s : safe(orderBy)) {
            Field<Object> f = fld(s.field);
            orders.add("desc".equalsIgnoreCase(s.direction) ? f.desc() : f.asc());
        }
        if (!orders.isEmpty()) q.addOrderBy(orders);
        if (limit != null) q.addLimit(limit);
    }

    /** 渲染成可读 SQL（值内联，便于展示/排查；执行仍走参数化的 query 对象） */
    public String renderSql(SelectQuery<Record> query) {
        return dsl.renderInlined(query);
    }

    /** 顶层聚合：派生表达式(expr) 或 单聚合(可含条件聚合 filter) */
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

    /** 单个聚合；若带 filter 则编译为条件聚合 SUM(CASE WHEN cond THEN field END) */
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

    /** 行级二元表达式：left arith right（right=列或数字常量；校验器已保证类型） */
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

    /** 窗口函数列：排名（row_number/rank/dense_rank）、聚合开窗（sum/avg/count over）或偏移取值（lag/lead） */
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

    /** OVER(...) 规格：partitionBy / orderBy 至少一项（校验器已保证） */
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

    /** groupBy 项：case/time 派生别名 → 展开为表达式（带 as）；否则普通列 */
    private SelectFieldOrAsterisk derivedOrPlain(String ref, Map<String, Mql.CaseColumn> caseByAlias,
                                                 Map<String, Mql.TimeColumn> timeByAlias) {
        Mql.CaseColumn cc = caseByAlias.get(ref);
        if (cc != null) return caseField(cc).as(cc.alias);
        Mql.TimeColumn tc = timeByAlias.get(ref);
        if (tc != null) return timeField(tc).as(tc.alias);
        return fld(ref);
    }

    /**
     * 时间截断：统一渲染为字符串（跨年安全、可字典序排序）。
     * date_format 为 MySQL 专用（多方言已在 LongTermTODO 正式放弃）；
     * 函数名/格式串均出自本方法白名单常量，列名走 nm() 转义，无注入面。
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

    /** CASE WHEN 派生维度：按序 WHEN，缺省 else 即 NULL；then/else 为常量（校验器已保证标量） */
    private Field<?> caseField(Mql.CaseColumn cc) {
        CaseConditionStep<Object> step = null;
        for (Mql.CaseWhen cw : cc.cases) {
            Condition cond = DSL.and(cw.when.stream().map(this::toCondition).toList());
            Field<Object> then = DSL.val(cw.then);
            step = (step == null) ? DSL.when(cond, then) : step.when(cond, then);
        }
        return cc.elseValue != null ? step.otherwise(DSL.val(cc.elseValue)) : step;
    }

    /** 表源：有别名则取别名（自连接靠别名区分同一张表） */
    private static Table<?> aliased(String tableName, String alias) {
        Table<?> t = table(name(tableName));
        return (alias == null || alias.isBlank()) ? t : t.as(alias);
    }

    /** 连接条件：字段 op 字段（支持非等值），或字段 op 常量（LEFT JOIN 右表口径条件，参数绑定） */
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

    /** 解析字段引用： "表名.列名" -> Name(表,列)； "列名" -> Name(列) */
    private static Name nm(String ref) {
        if (ref != null && ref.contains(".")) {
            String[] p = ref.split("\\.", 2);
            return name(p[0], p[1]);
        }
        return name(ref);
    }

    private static Field<Object> fld(String ref) {
        return field(nm(ref));
    }

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
