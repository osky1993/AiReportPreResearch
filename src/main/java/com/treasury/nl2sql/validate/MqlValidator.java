package com.treasury.nl2sql.validate;

import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.schema.SchemaService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 确定性校验闸门：约束 MQL 的可执行形态，避免“幻觉字段/非法算子/危险结构”进入编译-执行链路。
 * <p>该类不执行 SQL，仅进行白名单与结构约束校验。校验通过后输出稳定为“可编译输入”，
 * 失败后把错误列表回灌给 LLM，自助修正后重试。
 * <p>关键失败边界：
 * <ul>
 *   <li>表/列不存在、别名冲突、连接条件/过滤器/窗口定义形状错误；</li>
 *   <li>UNION/子查询不允许无限扩展，强制限制深度与每段形状；</li>
 *   <li>聚合、窗口、qualify 的上下文约束不满足（避免可执行歧义）。</li>
 * </ul>
 */
@Component
public class MqlValidator {

    private static final Set<String> OPS = Set.of("=", "!=", ">", ">=", "<", "<=",
            "like", "not like", "in", "not in", "is null", "is not null");
    /** 判空运算符：不带 value / subquery */
    private static final Set<String> NO_VALUE_OPS = Set.of("is null", "is not null");
    /** 数组值运算符：value 必须是非空数组 */
    private static final Set<String> ARRAY_OPS = Set.of("in", "not in");
    private static final Set<String> JOIN_OPS = Set.of("=", "!=", ">", ">=", "<", "<=");
    private static final Set<String> AGG = Set.of("sum", "count", "avg", "min", "max");
    private static final Set<String> ARITH = Set.of("+", "-", "*", "/");
    private static final Set<String> JOIN_TYPES = Set.of("inner", "left");
    private static final Set<String> WIN_OPS = Set.of("row_number", "rank", "dense_rank", "sum", "avg", "count", "lag", "lead");
    private static final Set<String> RANK_OPS = Set.of("row_number", "rank", "dense_rank");
    private static final Set<String> OFFSET_OPS = Set.of("lag", "lead");
    private static final Set<String> TIME_FUNCS = Set.of("year", "quarter", "month", "day");

    private final SchemaService schema;

    public MqlValidator(SchemaService schema) {
        this.schema = schema;
    }

    /**
     * 校验入口（顶层查询）。对外返回错误列表而非抛异常，便于上层实现“生成-自修正”循环。
     */
    public List<String> validate(Mql mql) {
        return validate(mql, 0);
    }

    /**
     * @param depth 嵌套深度：0=顶层；1=子查询/union 段内部（其中不得再嵌子查询）。
     *              嵌套查询自成作用域（全新 ref2real），所有表/列仍全量白名单校验，零绕过。
     *              depth>1 会直接失败返回，避免无限递归语义扩散。
     */
    private List<String> validate(Mql mql, int depth) {
        List<String> errors = new ArrayList<>();
        if (mql == null) {
            errors.add("MQL 为空");
            return errors;
        }
        String table = mql.table;
        if (table == null || table.isBlank()) {
            errors.add("缺少 table 字段");
            return errors;
        }
        if (!schema.hasTable(table)) {
            errors.add("表不存在: " + table + "，可用表: " + schema.tableNames());
            return errors;
        }

        // 引用前缀(别名优先，否则表名) -> 真实表名；后续字段校验全部复用该映射做来源归一化。
        Map<String, String> ref2real = new LinkedHashMap<>();
        String mainRef = (mql.alias != null && !mql.alias.isBlank()) ? mql.alias : table;
        ref2real.put(mainRef, table);
        for (Mql.Join j : nz(mql.joins)) {
            if (j.table == null || !schema.hasTable(j.table)) {
                errors.add("连接表不存在: " + j.table);
                continue;
            }
            String jref = (j.alias != null && !j.alias.isBlank()) ? j.alias : j.table;
            if (ref2real.containsKey(jref)) {
                errors.add("表名/别名冲突（自连接需用不同别名区分）: " + jref);
            } else {
                ref2real.put(jref, j.table);
            }
            if (j.type != null && !JOIN_TYPES.contains(j.type.toLowerCase())) {
                errors.add("非法连接类型: " + j.type + "，仅支持 " + JOIN_TYPES);
            }
            if (nz(j.on).isEmpty()) {
                errors.add("连接 " + j.table + " 缺少 on 条件");
            }
        }
        boolean multiTable = ref2real.size() > 1;
        // on 条件字段必须是限定字段且存在
        for (Mql.Join j : nz(mql.joins)) {
            for (Mql.On on : nz(j.on)) {
                if (on.op != null && !on.op.isBlank() && !JOIN_OPS.contains(on.op)) {
                    errors.add("非法连接运算符: " + on.op + "，仅支持 " + JOIN_OPS);
                }
                checkField(on.left, ref2real, table, multiTable, "连接条件左侧", errors);
                // 右侧：字段（right）或常量（value）二选一；常量形态供 LEFT JOIN 右表口径条件用
                if (on.right != null && !on.right.isBlank() && on.value != null) {
                    errors.add("连接条件的 right 与 value 只能二选一: " + on.left);
                } else if (on.value != null) {
                    if (!isScalar(on.value)) {
                        errors.add("连接条件的 value 必须是标量常量（字符串/数字）: " + on.left);
                    }
                } else {
                    checkField(on.right, ref2real, table, multiTable, "连接条件右侧", errors);
                }
            }
        }

        // ---- 顶层 distinct：仅用于无聚合/无分组的列投影去重 ----
        // 目的：防止 distinct 被混用到聚合分组语义中产生不可预期执行行为。
        if (Boolean.TRUE.equals(mql.distinct)) {
            if (!nz(mql.metrics).isEmpty() || !nz(mql.groupBy).isEmpty()) {
                errors.add("顶层 distinct 仅用于无聚合/无分组的列去重；聚合去重请用 metrics 的 count+distinct");
            }
            if (nz(mql.columns).isEmpty()) {
                errors.add("顶层 distinct 需要显式 columns（select distinct * 无意义）");
            }
        }

        // metric 别名集合（可被 having / orderBy 引用）
        // 这里提前收集是为了后续在 HAVING/ORDER BY 时统一判断“字段或别名”两套命名空间。
        Set<String> aliases = new HashSet<>();
        for (Mql.Metric m : nz(mql.metrics)) {
            if (m.alias == null || m.alias.isBlank()) {
                errors.add("指标缺少 alias");
            } else {
                aliases.add(m.alias);
            }
            if (m.expr != null) {
                // 派生指标：left arith right
                if (m.expr.arith == null || !ARITH.contains(m.expr.arith)) {
                    errors.add("非法算术运算符: " + m.expr.arith + "，仅支持 " + ARITH);
                }
                if (m.expr.left == null || m.expr.right == null) {
                    errors.add("派生指标 expr 需要 left 与 right");
                } else {
                    checkAggSpec(m.expr.left, ref2real, table, multiTable, depth, errors);
                    checkAggSpec(m.expr.right, ref2real, table, multiTable, depth, errors);
                    if (m.expr.left.value != null && m.expr.right.value != null) {
                        errors.add("expr 的 left/right 不能都是常量（至少一侧是聚合）: " + m.alias);
                    }
                }
            } else {
                if (m.value != null) {
                    errors.add("metric 的常量操作数 value 仅可用于 expr 的 left/right 位置: " + m.alias);
                }
                checkAggSpec(m, ref2real, table, multiTable, depth, errors);
            }
        }

        // ---- caseColumns：CASE WHEN 派生维度 ----
        // 用途是可读性与复用性提升，但在有聚合/分组场景下，必须强制出现在 groupBy 中才生效。
        Set<String> caseAliases = new LinkedHashSet<>();
        for (Mql.CaseColumn cc : nz(mql.caseColumns)) {
            if (cc.alias == null || cc.alias.isBlank()) {
                errors.add("caseColumn 缺少 alias");
                continue;
            }
            if (!caseAliases.add(cc.alias) || aliases.contains(cc.alias)) {
                errors.add("caseColumn 别名与其他别名冲突: " + cc.alias);
            }
            if (fieldExists(cc.alias, ref2real, table, multiTable)) {
                errors.add("caseColumn 别名与真实列名冲突: " + cc.alias);
            }
            if (nz(cc.cases).isEmpty()) {
                errors.add("caseColumn " + cc.alias + " 需要至少一个 when 分支");
            }
            for (Mql.CaseWhen cw : nz(cc.cases)) {
                if (nz(cw.when).isEmpty()) {
                    errors.add("caseColumn " + cc.alias + " 的分支缺少 when 条件");
                }
                for (Mql.Condition cond : nz(cw.when)) {
                    checkCondition(cond, ref2real, table, multiTable, Set.of(), false, depth, errors);
                }
                if (!isScalar(cw.then)) {
                    errors.add("caseColumn " + cc.alias + " 的 then 必须是标量常量（字符串/数字）");
                }
            }
            if (cc.elseValue != null && !isScalar(cc.elseValue)) {
                errors.add("caseColumn " + cc.alias + " 的 else 必须是标量常量（字符串/数字）");
            }
        }
        // 分组/聚合查询里 caseColumn 只有被 groupBy 引用才生效
        if (!caseAliases.isEmpty() && (!nz(mql.groupBy).isEmpty() || !nz(mql.metrics).isEmpty())) {
            for (String ca : caseAliases) {
                if (!nz(mql.groupBy).contains(ca)) {
                    errors.add("分组/聚合查询中 caseColumn 必须放进 groupBy 才生效: " + ca);
                }
            }
        }

        // ---- timeColumns：时间截断派生维度（校验模式与 caseColumns 平行） ----
        // 时间维度通常用于按月/季度统计，禁止非时间类型列进入，防止产生错误聚合维度。
        Set<String> timeAliases = new LinkedHashSet<>();
        for (Mql.TimeColumn tc : nz(mql.timeColumns)) {
            if (tc.alias == null || tc.alias.isBlank()) {
                errors.add("timeColumn 缺少 alias");
                continue;
            }
            if (!timeAliases.add(tc.alias) || aliases.contains(tc.alias) || caseAliases.contains(tc.alias)) {
                errors.add("timeColumn 别名与其他别名冲突: " + tc.alias);
            }
            if (fieldExists(tc.alias, ref2real, table, multiTable)) {
                errors.add("timeColumn 别名与真实列名冲突: " + tc.alias);
            }
            String func = tc.func == null ? "" : tc.func.toLowerCase();
            if (!TIME_FUNCS.contains(func)) {
                errors.add("非法时间函数: " + tc.func + "，仅支持 " + TIME_FUNCS);
            }
            checkField(tc.field, ref2real, table, multiTable, "timeColumn 字段", errors);
            String real = resolveTable(tc.field, ref2real, table);
            String col = (tc.field != null && tc.field.contains(".")) ? tc.field.split("\\.", 2)[1] : tc.field;
            if (real != null && col != null && schema.hasColumn(real, col) && !schema.isTemporalColumn(real, col)) {
                errors.add("timeColumn 只能作用于日期/时间列，但 " + tc.field + " 是 "
                        + schema.columnType(real, col) + " 类型");
            }
        }
        // 分组/聚合查询里 timeColumn 只有被 groupBy 引用才生效
        if (!timeAliases.isEmpty() && (!nz(mql.groupBy).isEmpty() || !nz(mql.metrics).isEmpty())) {
            for (String ta : timeAliases) {
                if (!nz(mql.groupBy).contains(ta)) {
                    errors.add("分组/聚合查询中 timeColumn 必须放进 groupBy 才生效: " + ta);
                }
            }
        }

        // ---- windows / qualify：窗口函数（普通模式 / 聚合后开窗模式） ----
        // 对于“聚合后开窗”，必须将窗口别名仅引用外层派生表可见字段，避免内层窗口引用不一致。
        Set<String> windowAliases = new LinkedHashSet<>();
        boolean aggWindow = !nz(mql.windows).isEmpty()
                && (!nz(mql.groupBy).isEmpty() || !nz(mql.metrics).isEmpty());
        if (!nz(mql.windows).isEmpty() && Boolean.TRUE.equals(mql.distinct)) {
            errors.add("windows 与 distinct 互斥");
        }
        // 聚合后开窗：编译为「聚合内层 + 窗口外层」派生表，窗口引用只能是外层可见的名字——
        // groupBy 列的裸名（含 time/case 派生别名）或 metric 别名
        Set<String> allowedRefs = new LinkedHashSet<>();
        if (aggWindow) {
            for (String g : nz(mql.groupBy)) {
                String bare = g.contains(".") ? g.split("\\.", 2)[1] : g;
                if (!allowedRefs.add(bare)) {
                    errors.add("聚合后开窗时 groupBy 列去前缀后重名（外层派生表列名撞车，请改用别名列）: " + bare);
                }
            }
            allowedRefs.addAll(aliases);
        }
        for (Mql.WindowSpec w : nz(mql.windows)) {
            if (w.alias == null || w.alias.isBlank()) {
                errors.add("窗口函数缺少 alias");
            } else if (!windowAliases.add(w.alias) || aliases.contains(w.alias) || caseAliases.contains(w.alias)
                    || timeAliases.contains(w.alias) || fieldExists(w.alias, ref2real, table, multiTable)) {
                errors.add("窗口别名与其他别名/真实列冲突: " + w.alias);
            }
            String wop = w.op == null ? "" : w.op.toLowerCase();
            if (w.offset != null && !OFFSET_OPS.contains(wop)) {
                errors.add("offset 仅 lag/lead 可用: " + w.op);
            }
            if (!WIN_OPS.contains(wop)) {
                errors.add("非法窗口函数: " + w.op + "，仅支持 " + WIN_OPS);
            } else if (RANK_OPS.contains(wop)) {
                if (w.field != null && !w.field.isBlank()) {
                    errors.add("排名窗口函数不需要 field: " + w.op);
                }
                if (nz(w.orderBy).isEmpty()) {
                    errors.add("排名窗口函数必须指定 orderBy（排名依据）: " + w.op);
                }
            } else if (OFFSET_OPS.contains(wop)) {
                if (w.field == null || w.field.isBlank()) {
                    errors.add("lag/lead 必须指定 field（取前/后行的哪一列）: " + w.op);
                } else {
                    checkWindowRef(w.field, aggWindow, allowedRefs, ref2real, table, multiTable, "窗口取值字段", errors);
                }
                if (nz(w.orderBy).isEmpty()) {
                    errors.add("lag/lead 必须指定 orderBy（偏移沿排序方向）: " + w.op);
                }
                if (w.offset != null && w.offset < 1) {
                    errors.add("lag/lead 的 offset 须 ≥1: " + w.offset);
                }
            } else {
                boolean isCountStar = "count".equals(wop) && (w.field == null || w.field.isBlank());
                if (!isCountStar) {
                    checkWindowRef(w.field, aggWindow, allowedRefs, ref2real, table, multiTable, "窗口聚合字段", errors);
                    // 数值类型检查仅普通模式（聚合模式引用 metric 别名，不在 schema 里）
                    if (!aggWindow && ("sum".equals(wop) || "avg".equals(wop)) && w.field != null && !w.field.isBlank()) {
                        String real = resolveTable(w.field, ref2real, table);
                        String col = w.field.contains(".") ? w.field.split("\\.", 2)[1] : w.field;
                        if (real != null && schema.hasColumn(real, col) && !schema.isNumericColumn(real, col)) {
                            errors.add("窗口 " + wop + " 只能用于数值列，但 " + w.field + " 是 "
                                    + schema.columnType(real, col) + " 类型");
                        }
                    }
                }
            }
            if (nz(w.partitionBy).isEmpty() && nz(w.orderBy).isEmpty()) {
                errors.add("窗口函数需要 partitionBy 或 orderBy 至少一项: " + w.alias);
            }
            for (String p : nz(w.partitionBy)) {
                checkWindowRef(p, aggWindow, allowedRefs, ref2real, table, multiTable, "窗口分区列", errors);
            }
            for (Mql.Sort s : nz(w.orderBy)) {
                checkWindowRef(s.field, aggWindow, allowedRefs, ref2real, table, multiTable, "窗口排序列", errors);
                if (s.direction != null && !s.direction.equalsIgnoreCase("asc") && !s.direction.equalsIgnoreCase("desc")) {
                    errors.add("非法排序方向: " + s.direction);
                }
            }
        }
        if (!nz(mql.qualify).isEmpty() && nz(mql.windows).isEmpty()) {
            errors.add("qualify 仅在有 windows 时可用（它是对窗口结果的过滤）");
        }
        for (Mql.Condition c : nz(mql.qualify)) {
            if (c == null || c.field == null || c.op == null
                    || (c.and != null && !c.and.isEmpty()) || (c.or != null && !c.or.isEmpty())
                    || c.subquery != null) {
                errors.add("qualify 只支持对窗口别名的简单比较叶子（不支持组/子查询）");
                continue;
            }
            if (!windowAliases.contains(c.field)) {
                errors.add("qualify 的 field 必须是窗口别名: " + c.field + "，可用: " + windowAliases);
            }
            if (!JOIN_OPS.contains(c.op.toLowerCase())) {
                errors.add("qualify 仅支持比较运算符: " + c.op);
            }
            if (!(c.value instanceof Number)) {
                errors.add("qualify 的 value 必须是数值: " + c.field);
            }
        }

        for (String c : nz(mql.columns)) {
            if (windowAliases.contains(c)) continue;   // LLM 常把窗口别名列进 columns：宽容跳过（窗口列自动输出）
            checkField(c, ref2real, table, multiTable, "列", errors);
        }
        // 分组/聚合查询里顶层 columns 会被编译器静默忽略 → 提示改正
        if (!nz(mql.columns).isEmpty() && (!nz(mql.groupBy).isEmpty() || !nz(mql.metrics).isEmpty())) {
            errors.add("分组/聚合查询中顶层 columns 会被忽略，展示列请放入 groupBy、度量请放入 metrics");
        }
        for (String g : nz(mql.groupBy)) {
            if (caseAliases.contains(g)) continue;   // case 派生维度别名，编译期展开为表达式
            if (timeAliases.contains(g)) continue;   // 时间截断派生维度别名，同上
            checkField(g, ref2real, table, multiTable, "分组列", errors);
        }
        for (Mql.Condition cond : nz(mql.filter)) {
            checkCondition(cond, ref2real, table, multiTable, aliases, false, depth, errors);
        }
        for (Mql.Condition cond : nz(mql.having)) {
            checkCondition(cond, ref2real, table, multiTable, aliases, true, depth, errors);
        }
        for (Mql.Sort s : nz(mql.orderBy)) {
            if (s.field == null || (!aliases.contains(s.field) && !caseAliases.contains(s.field)
                    && !timeAliases.contains(s.field) && !windowAliases.contains(s.field)
                    && !fieldExists(s.field, ref2real, table, multiTable))) {
                errors.add("排序字段既非列也非聚合/case/time/窗口别名: " + s.field);
            }
            // 有 qualify/union/聚合后开窗时整体被包成派生表，外层无表前缀 → 排序须用别名或裸列名
            if ((!nz(mql.qualify).isEmpty() || !nz(mql.union).isEmpty() || aggWindow)
                    && s.field != null && s.field.contains(".")) {
                errors.add("有 qualify/union/聚合后开窗时排序字段须用别名或裸列名（外层派生表无表前缀）: " + s.field);
            }
            if (s.direction != null && !s.direction.equalsIgnoreCase("asc") && !s.direction.equalsIgnoreCase("desc")) {
                errors.add("非法排序方向: " + s.direction);
            }
        }
        if (mql.limit != null && mql.limit < 0) {
            errors.add("limit 不能为负: " + mql.limit);
        }

        // ---- union：多段合并 ----
        // union 只允许“列结构一致且显式投影”，并要求分页/排序在最外层统一处理，保证可重现与并行解释性。
        if (!nz(mql.union).isEmpty()) {
            if (depth >= 1) {
                errors.add("子查询/union 段内不得再嵌 union");
            }
            if (!nz(mql.windows).isEmpty() || !nz(mql.qualify).isEmpty()) {
                errors.add("union 查询中不支持窗口函数/qualify");
            }
            int mainCols = projectionCount(mql);
            if (mainCols <= 0) {
                errors.add("union 各段必须显式给出 columns 或 metrics（不能 SELECT *）");
            }
            int i = 1;
            for (Mql seg : nz(mql.union)) {
                String prefix = "union第" + i + "段: ";
                if (seg == null) {
                    errors.add(prefix + "为空");
                    i++;
                    continue;
                }
                if (!nz(seg.union).isEmpty()) {
                    errors.add(prefix + "不得再嵌 union");
                }
                if (!nz(seg.windows).isEmpty() || !nz(seg.qualify).isEmpty()) {
                    errors.add(prefix + "不支持窗口函数/qualify");
                }
                if (!nz(seg.orderBy).isEmpty() || seg.limit != null) {
                    errors.add(prefix + "不支持 orderBy/limit（排序/限行请写在最外层，作用于整体）");
                }
                int segCols = projectionCount(seg);
                if (segCols <= 0) {
                    errors.add(prefix + "必须显式给出 columns 或 metrics（不能 SELECT *）");
                } else if (mainCols > 0 && segCols != mainCols) {
                    errors.add("union 各段列数不一致: 主段 " + mainCols + " 列 vs 第" + i + "段 " + segCols + " 列");
                }
                // 段自成作用域,递归全量白名单校验(同深度:段内允许子查询,子查询内不得再嵌 union)
                for (String e : validate(seg, depth)) {
                    errors.add(prefix + e);
                }
                i++;
            }
        }
        return errors;
    }

    /**
     * 计算单段 SELECT 的输出列数，用于 UNION 列一致性检查。
     * 约定：聚合段以 groupBy+metrics 定义列数；非聚合段以 columns+caseColumns+timeColumns 计数。
     * 若返回 0 表示 SELECT * 风格，在 UNION 上下文会被直接判定为非法。
     */
    private static int projectionCount(Mql m) {
        if (!nz(m.metrics).isEmpty() || !nz(m.groupBy).isEmpty()) {
            return nz(m.groupBy).size() + nz(m.metrics).size();
        }
        return nz(m.columns).size() + nz(m.caseColumns).size() + nz(m.timeColumns).size();
    }

    /**
     * 校验单个指标表达式是否可编译：
     * 1) 聚合函数白名单；
     * 2) expr 与 fieldExpr 的形状互斥；
     * 3) numeric/聚合类型约束（如 sum/avg 要求字段可做数值）；
     * 4) 递归校验 filter 内条件树。
     */
    private void checkAggSpec(Mql.Metric m, Map<String, String> ref2real, String mainTable,
                             boolean multiTable, int depth, List<String> errors) {
        // Expr 操作数位的常量形态：{"value": 数字}，其余属性必须全空
        if (m.value != null) {
            if (!(m.value instanceof Number)) {
                errors.add("表达式常量操作数必须是数字: " + m.value);
            }
            if (m.op != null || (m.field != null && !m.field.isBlank()) || m.fieldExpr != null
                    || (m.filter != null && !m.filter.isEmpty()) || m.expr != null) {
                errors.add("常量操作数不得再带 op/field/fieldExpr/filter/expr: " + m.value);
            }
            return;
        }
        if (m.op == null || !AGG.contains(m.op.toLowerCase())) {
            errors.add("非法聚合函数: " + m.op + "，仅支持 " + AGG);
        }
        // distinct 聚合白名单从紧：只放开 COUNT(DISTINCT 列)
        if (Boolean.TRUE.equals(m.distinct)
                && (!"count".equalsIgnoreCase(m.op) || m.field == null || m.field.isBlank())) {
            errors.add("distinct 聚合仅支持 count 且必须指定 field（COUNT(DISTINCT 列)）");
        }
        if (m.fieldExpr != null) {
            // 行级表达式聚合：SUM(a*b) 等，先逐行算再聚合
            if (m.field != null && !m.field.isBlank()) {
                errors.add("fieldExpr 与 field 只能二选一");
            }
            if ("count".equalsIgnoreCase(m.op)) {
                errors.add("count 不支持行级表达式（数行请直接 count）");
            }
            if (Boolean.TRUE.equals(m.distinct)) {
                errors.add("fieldExpr 不支持 distinct");
            }
            if (m.fieldExpr.arith == null || !ARITH.contains(m.fieldExpr.arith)) {
                errors.add("fieldExpr 非法算术运算符: " + m.fieldExpr.arith + "，仅支持 " + ARITH);
            }
            checkNumericOperand(m.fieldExpr.left, "fieldExpr.left", ref2real, mainTable, multiTable, errors);
            if (m.fieldExpr.right instanceof String s) {
                checkNumericOperand(s, "fieldExpr.right", ref2real, mainTable, multiTable, errors);
            } else if (!(m.fieldExpr.right instanceof Number)) {
                errors.add("fieldExpr.right 必须是数值列引用或数字常量: " + m.fieldExpr.right);
            }
        } else {
            boolean isCountStar = "count".equalsIgnoreCase(m.op) && (m.field == null || m.field.isBlank());
            if (!isCountStar) {
                checkField(m.field, ref2real, mainTable, multiTable, "聚合字段", errors);
                // sum/avg 只能作用于数值列（min/max/count 任意类型）
                String op = m.op == null ? "" : m.op.toLowerCase();
                if (("sum".equals(op) || "avg".equals(op)) && m.field != null && !m.field.isBlank()) {
                    String real = resolveTable(m.field, ref2real, mainTable);
                    String col = m.field.contains(".") ? m.field.split("\\.", 2)[1] : m.field;
                    if (real != null && schema.hasColumn(real, col) && !schema.isNumericColumn(real, col)) {
                        errors.add(op + " 只能用于数值列，但 " + m.field + " 是 "
                                + schema.columnType(real, col) + " 类型");
                    }
                }
            }
        }
        for (Mql.Condition cond : nz(m.filter)) {
            checkCondition(cond, ref2real, mainTable, multiTable, Set.of(), false, depth, errors);
        }
    }

    /**
     * 校验表达式中的字段操作数（用于 fieldExpr 的左右值）。先验：引用必须存在；
     * 再验：若可定位到已知列，要求数值类型，避免后续编译阶段整形/浮点算术报错。
     */
    private void checkNumericOperand(String ref, String role, Map<String, String> ref2real,
                                     String mainTable, boolean multiTable, List<String> errors) {
        checkField(ref, ref2real, mainTable, multiTable, role, errors);
        String real = resolveTable(ref, ref2real, mainTable);
        String col = (ref != null && ref.contains(".")) ? ref.split("\\.", 2)[1] : ref;
        if (real != null && col != null && schema.hasColumn(real, col) && !schema.isNumericColumn(real, col)) {
            errors.add(role + " 只能是数值列，但 " + ref + " 是 " + schema.columnType(real, col) + " 类型");
        }
    }

    /**
     * 递归校验条件树：
     * 根必须是“纯树结构”，且叶子不允许同时携带 and/or 与 field/op/value。
     * 这样能在树形遍历前把结构歧义一次性拉平，减少后续 toCondition 的容错风险。
     */
    private void checkCondition(Mql.Condition c, Map<String, String> ref2real, String mainRealTable,
                               boolean multiTable, Set<String> aliases, boolean havingCtx, int depth,
                               List<String> errors) {
        if (c == null) {
            errors.add("条件节点为空");
            return;
        }
        boolean hasOr = c.or != null && !c.or.isEmpty();
        boolean hasAnd = c.and != null && !c.and.isEmpty();
        boolean hasLeaf = c.field != null || c.op != null || c.value != null || c.subquery != null;
        if (hasOr && hasAnd) {
            errors.add("条件节点不能同时含 and 与 or");
            return;
        }
        if ((hasOr || hasAnd) && hasLeaf) {
            errors.add("条件节点不能既是组(and/or)又是叶子(field/op/value): " + c.field);
            return;
        }
        if (hasOr) {
            for (Mql.Condition ch : c.or) checkCondition(ch, ref2real, mainRealTable, multiTable, aliases, havingCtx, depth, errors);
            return;
        }
        if (hasAnd) {
            for (Mql.Condition ch : c.and) checkCondition(ch, ref2real, mainRealTable, multiTable, aliases, havingCtx, depth, errors);
            return;
        }
        // 叶子
        checkOp(c, errors);
        if (c.subquery != null) {
            checkSubquery(c, havingCtx, depth, errors);
        }
        if (havingCtx) {
            if (c.field != null && !aliases.contains(c.field)
                    && !fieldExists(c.field, ref2real, mainRealTable, multiTable)) {
                errors.add("having 字段既非列也非聚合别名: " + c.field);
            }
        } else {
            checkField(c.field, ref2real, mainRealTable, multiTable, "过滤列", errors);
        }
    }

    /**
     * 子查询叶子约束：禁止 value 与 subquery 共存，限定比较/IN 语义形状，禁止 orderBy/limit，限制递归深度。
     * 这样可避免相关子查询和无限嵌套导致的复杂化，保证可解释可复现的统计语义。
     */
    private void checkSubquery(Mql.Condition c, boolean havingCtx, int depth, List<String> errors) {
        if (c.value != null) {
            errors.add("条件叶子的 value 与 subquery 只能二选一: " + c.field);
        }
        if (havingCtx) {
            errors.add("having 中不支持子查询: " + c.field);
            return;
        }
        if (depth >= 1) {
            errors.add("子查询嵌套过深（最多一层）: " + c.field);
            return;
        }
        Mql sub = c.subquery;
        String op = c.op == null ? "" : normOp(c.op);
        boolean scalar = JOIN_OPS.contains(op);
        boolean inOp = "in".equals(op);
        if (!scalar && !inOp) {
            // not in 子查询有意不放开：子查询结果含 NULL 时 NOT IN 恒空，是经典陷阱；反连接请用 left join + is null
            errors.add("子查询仅支持比较运算符（标量）或 in: " + c.op);
        }
        if (!nz(sub.orderBy).isEmpty() || sub.limit != null) {
            errors.add("子查询内不支持 orderBy/limit: " + c.field);
        }
        if (!nz(sub.windows).isEmpty() || !nz(sub.qualify).isEmpty()) {
            errors.add("子查询内不支持窗口函数/qualify: " + c.field);
        }
        boolean singleMetric = nz(sub.metrics).size() == 1 && nz(sub.groupBy).isEmpty()
                && nz(sub.columns).isEmpty() && nz(sub.caseColumns).isEmpty();
        boolean singleColumn = nz(sub.columns).size() == 1 && nz(sub.metrics).isEmpty()
                && nz(sub.groupBy).isEmpty() && nz(sub.caseColumns).isEmpty();
        if (scalar && !singleMetric) {
            errors.add("标量子查询必须恰有 1 个聚合指标且无 groupBy/columns（保证 1 行 1 列）: " + c.field);
        }
        if (inOp && !singleMetric && !singleColumn) {
            errors.add("in 子查询必须只输出 1 列（单个 columns 或单个聚合指标）: " + c.field);
        }
        // 递归全量白名单校验（自成作用域），错误带「子查询: 」前缀回灌
        for (String e : validate(sub, depth + 1)) {
            errors.add("子查询: " + e);
        }
    }

    /**
     * 窗口字段引用分派：
     * - aggWindow=true 时只允许 groupBy/metric 外层可见名；
     * - false 时走 schema 白名单（含前缀列校验）。
     */
    private void checkWindowRef(String ref, boolean aggWindow, Set<String> allowedRefs,
                                Map<String, String> ref2real, String mainTable, boolean multiTable,
                                String role, List<String> errors) {
        if (aggWindow) {
            if (ref == null || !allowedRefs.contains(ref)) {
                errors.add("聚合后开窗时" + role + "只能引用 groupBy 列（裸名/派生别名）或聚合别名: "
                        + ref + "，可用: " + allowedRefs);
            }
        } else {
            checkField(ref, ref2real, mainTable, multiTable, role, errors);
        }
    }

    /**
     * 校验字段引用最核心的方法之一。
     * 多表时禁止裸列（歧义风险），单表时允许裸列但必须确认为主表列。
     */
    private void checkField(String ref, Map<String, String> ref2real, String mainRealTable,
                            boolean multiTable, String role, List<String> errors) {
        if (ref == null || ref.isBlank()) {
            errors.add(role + "为空");
            return;
        }
        if (ref.contains(".")) {
            String[] p = ref.split("\\.", 2);
            String real = ref2real.get(p[0]);
            if (real == null) {
                errors.add(role + "引用了未参与查询的表/别名: " + ref);
            } else if (!schema.hasColumn(real, p[1])) {
                errors.add(role + "不存在: " + ref);
            }
        } else {
            if (multiTable) {
                errors.add(role + "在多表查询中必须用 前缀.列名 限定: " + ref);
            } else if (!schema.hasColumn(mainRealTable, ref)) {
                errors.add(role + "不存在: " + mainRealTable + "." + ref);
            }
        }
    }

    /**
     * 解析字段引用所属的真实表：含前缀走 ref2real；否则默认主表。
     * 该逻辑同样用于错误定位与跨字段引用合法性判断。
     */
    private String resolveTable(String ref, Map<String, String> ref2real, String mainRealTable) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.contains(".")) return ref2real.get(ref.split("\\.", 2)[0]);
        return mainRealTable;
    }

    /**
     * 辅助判断：在当前上下文中是否能作为“列名”直接存在。
     * 注意：当前方法是“宽松预检”，不替代 {@link #checkField(String, Map, String, boolean, String, List)} 的报错逻辑。
     */
    private boolean fieldExists(String ref, Map<String, String> ref2real, String mainRealTable, boolean multiTable) {
        if (ref == null || ref.isBlank()) return false;
        if (ref.contains(".")) {
            String[] p = ref.split("\\.", 2);
            String real = ref2real.get(p[0]);
            return real != null && schema.hasColumn(real, p[1]);
        }
        return !multiTable && schema.hasColumn(mainRealTable, ref);
    }

    /**
     * 统一检查操作符并做形状边界约束：
     * - NULL/NOT NULL 不允许 value/subquery；
     * - in / not in 的 value 仅允许非空数组；
     * - 非法操作符会直接中断该条件分支。
     */
    private void checkOp(Mql.Condition cond, List<String> errors) {
        String op = normOp(cond.op);
        if (op == null || !OPS.contains(op)) {
            errors.add("非法运算符: " + cond.op + "，仅支持 " + OPS);
            return;
        }
        if (NO_VALUE_OPS.contains(op)) {
            if (cond.value != null) {
                errors.add(op + " 不带 value（判空本身就是完整条件）: " + cond.field);
            }
            if (cond.subquery != null) {
                errors.add(op + " 不能与 subquery 组合: " + cond.field);
            }
        }
        if (ARRAY_OPS.contains(op) && cond.subquery == null) {   // in 子查询无 value，另行校验形状
            if (!(cond.value instanceof Collection)) {
                errors.add(op + " 运算符的 value 必须是数组: " + cond.field);
            } else if (((Collection<?>) cond.value).isEmpty()) {
                errors.add(op + " 的值不能为空数组（会得到恒假空结果）: " + cond.field);
            }
        }
    }

    /** 运算符归一化：小写、压缩空白（"IS  NULL" → "is null"），validator 与 compiler 共用同一规则 */
    public static String normOp(String op) {
        return op == null ? null : op.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** 标量常量判定：字符串/数字/布尔；数组与对象不算（防列引用/结构注入） */
    private static boolean isScalar(Object v) {
        return v != null && !(v instanceof Collection) && !(v instanceof Map);
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? Collections.emptyList() : l;
    }
}
