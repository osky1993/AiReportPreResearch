package com.treasury.nl2sql.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * MQL —— 介于自然语言与 SQL 之间的结构化中间查询语言（IR）。
 * LLM 只需产出这个受约束的 JSON 对象，再由 {@code MqlSqlCompiler} 确定性地编译成方言 SQL。
 * MVP 版本支持：单表 + 过滤 + 分组 + 聚合指标 + having + 排序 + 限制。
 *
 * <p>关键约束：
 * <ul>
 *   <li>该模型可序列化到 JSON，作为 few-shot/审计/重放的统一载体。</li>
 *   <li>字段语义固定：compiler/validator 按语义严格解释每个字段名与枚举值。</li>
 *   <li>字段组合有互斥规则（如 windows 与 groupBy 的部分互斥），超约束会在校验阶段拒绝。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mql {

    /** 主表名 */
    public String table;

    /** 主表别名（可选；自连接/需要别名时使用，字段引用须改用别名前缀） */
    public String alias;

    /** 连接的其他表（多表查询时字段需用 表名/别名.列名 限定） */
    public List<Join> joins = new ArrayList<>();

    /** 无聚合时直接选取的列；为空且无 metrics 时视为 SELECT * */
    public List<String> columns = new ArrayList<>();

    /** 列投影去重（SELECT DISTINCT）；仅用于无聚合/无分组查询且需显式 columns */
    public Boolean distinct;

    /** CASE WHEN 派生维度（如按余额分档打标签）；分组统计时把其 alias 放进 groupBy */
    public List<CaseColumn> caseColumns = new ArrayList<>();

    /** 时间截断派生维度（按年/季/月/日分组，如「按月统计」）；分组统计时把其 alias 放进 groupBy，orderBy 可引用 alias */
    public List<TimeColumn> timeColumns = new ArrayList<>();

    /** 过滤条件，列表内为 AND 关系 */
    public List<Condition> filter = new ArrayList<>();

    /** 窗口函数列（组内排名/累计等）；与 groupBy/metrics/distinct 互斥 */
    public List<WindowSpec> windows = new ArrayList<>();

    /** 窗口结果过滤（QUALIFY，如 排名<=2）：仅当有 windows 时可用，叶子 field 只能引用窗口别名 */
    public List<Condition> qualify = new ArrayList<>();

    /** 分组字段 */
    public List<String> groupBy = new ArrayList<>();

    /** 聚合指标 */
    public List<Metric> metrics = new ArrayList<>();

    /** 分组后过滤（可引用 metric 的 alias） */
    public List<Condition> having = new ArrayList<>();

    /** 排序 */
    public List<Sort> orderBy = new ArrayList<>();

    /** 行数限制 */
    public Integer limit;

    /**
     * UNION 后续段（本对象为第一段）：各段列数必须一致且显式投影（不能 SELECT *）；
     * 段内不得再嵌 union / windows / orderBy / limit——排序与限行只写在本对象顶层，作用于合并后的整体。
     */
    public List<Mql> union = new ArrayList<>();

    /** true=UNION ALL（保留重复行）；缺省/false=UNION（去重） */
    public Boolean unionAll;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Join {
        /** 被连接表名 */
        public String table;
        /** 被连接表别名（可选；自连接时必填以区分同一张表） */
        public String alias;
        /** 连接类型： inner / left */
        public String type = "inner";
        /** 连接条件（多个为 AND），字段须用 表名.列名 限定 */
        public List<On> on = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class On {
        /** 左侧字段： 表名.列名 */
        public String left;
        /** 连接运算符： = != > >= < <=（默认 =） */
        public String op = "=";
        /** 右侧字段： 表名.列名（与 value 二选一） */
        public String right;
        /**
         * 右侧常量（与 right 二选一）：LEFT JOIN 需要对右表加常量口径条件时用它
         * （写进 WHERE 会把左表无匹配行滤掉、退化成 INNER JOIN）。标量（字符串/数字）。
         */
        public Object value;
    }

    /**
     * 条件节点：要么是「叶子」(field/op/value)，要么是「组」(and/or 二选一，可递归嵌套)。
     * filter / having 的顶层列表元素之间为 AND；组内按 and/or 决定。
     * 同一列的"或"（currency 是 USD 或 EUR）请用 in 叶子；跨列的"或"用 or 组。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {
        /** 叶子-字段名（having 中可为 metric 的 alias） */
        public String field;
        /** 叶子-运算符： = != > >= < <= like | not like | in | not in | is null | is not null */
        public String op;
        /** 叶子-比较值：字符串/数字/数组(in/not in)；is null/is not null 不带 value */
        public Object value;
        /**
         * 叶子-子查询（与 value 二选一）：op 为比较符时=标量子查询（须恰 1 个聚合指标，保证 1 行 1 列）；
         * op=in 时子查询只输出 1 列。子查询自成作用域（不支持引用外层表），内部不得再嵌子查询/orderBy/limit。
         */
        public Mql subquery;
        /** 组-AND：子条件全部满足 */
        public List<Condition> and;
        /** 组-OR：子条件任一满足 */
        public List<Condition> or;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metric {
        /** 聚合函数： sum count avg min max */
        public String op;
        /** 被聚合字段（count 可省略，表示 count(*)） */
        public String field;
        /** 行级表达式聚合（与 field 二选一）：先逐行算 left⊕right 再聚合，如 SUM(amount*rate_to_cny) */
        public FieldExpr fieldExpr;
        /** 条件聚合：仅对满足条件的行聚合，编译为 SUM(CASE WHEN ... THEN field END) */
        public List<Condition> filter;
        /** 派生指标：两个聚合的算术组合（如净流入=收入−支出）；设了 expr 则忽略 op/field */
        public Expr expr;
        /** 常量操作数：仅在 Expr 的 left/right 位置合法（如占比×100 的 {"value":100}），须为数字 */
        public Object value;
        /** 去重聚合：仅 op=count 且指定 field 时可用 → COUNT(DISTINCT 列) */
        public Boolean distinct;
        /** 结果别名 */
        public String alias;
    }

    /**
     * 行级二元表达式（聚合函数的参数）：left arith right。
     * left=数值列引用；right=数值列引用（字符串）或数字常量。刻意不支持嵌套。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldExpr {
        /** 左操作数：数值列引用 */
        public String left;
        /** 算术运算符： + - * / */
        public String arith;
        /** 右操作数：字符串=数值列引用；数字=常量 */
        public Object right;
    }

    /**
     * CASE WHEN 派生维度：按序匹配 cases，命中即取该分支 then；都不中取 else（缺省为 NULL）。
     * then/else 只允许标量常量（字符串/数字），不允许列引用。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseColumn {
        /** 派生列别名（必填；groupBy/orderBy 可引用） */
        public String alias;
        /** WHEN 分支，按序匹配 */
        public List<CaseWhen> cases = new ArrayList<>();
        /** 所有分支都不命中时的取值（可选，缺省 NULL）。else 是 Java 关键字，故映射到 elseValue */
        @JsonProperty("else")
        public Object elseValue;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseWhen {
        /** 分支条件（多个为 AND），与 filter 同一套条件节点 */
        public List<Condition> when = new ArrayList<>();
        /** 命中取值（标量常量） */
        public Object then;
    }

    /**
     * 时间截断派生维度：把日期/时间列按粒度截断成可分组、可排序的字符串
     * （year→'2026'，quarter→'2026-Q2'，month→'2026-06'，day→'2026-06-30'，均跨年安全）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeColumn {
        /** 截断粒度： year | quarter | month | day */
        public String func;
        /** 被截断的日期/时间列 */
        public String field;
        /** 派生列别名（必填；groupBy/orderBy 可引用） */
        public String alias;
    }

    /** 派生指标表达式：left arith right，left/right 为聚合规格（含可选条件聚合 filter） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expr {
        public Metric left;
        /** 算术运算符： + - * / */
        public String arith;
        public Metric right;
    }

    /**
     * 窗口函数规格：排名（row_number/rank/dense_rank，须给 orderBy、不给 field）、
     * 聚合开窗（sum/avg/count over，须给 field——count 可省略；orderBy 给出即累计语义）、
     * 偏移取值（lag/lead，须给 field 与 orderBy——如环比取上月值）。
     * partitionBy 与 orderBy 至少给一项。
     * 与 groupBy/metrics 共存时=「聚合后开窗」：窗口的 field/partitionBy/orderBy
     * 只能引用 groupBy 列（裸列名或 time/case 别名）或 metric 别名。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WindowSpec {
        /** 窗口函数： row_number rank dense_rank sum avg count lag lead */
        public String op;
        /** 被聚合/取值字段（sum/avg/count/lag/lead；排名函数不填） */
        public String field;
        /** lag/lead 的偏移行数（可选，默认 1，须 ≥1；"上上月"=2） */
        public Integer offset;
        /** 分区列（"每个币种内"→ partitionBy 币种） */
        public List<String> partitionBy = new ArrayList<>();
        /** 窗口内排序（排名依据 / 累计顺序 / 偏移方向） */
        public List<Sort> orderBy = new ArrayList<>();
        /** 结果别名（必填；qualify/orderBy 可引用） */
        public String alias;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sort {
        /** 排序字段（可为列名或 metric 的 alias） */
        public String field;
        /** asc / desc */
        public String direction = "asc";
    }
}
