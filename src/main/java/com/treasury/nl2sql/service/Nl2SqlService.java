package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.fewshot.FewShotSelector;
import com.treasury.nl2sql.glossary.GlossaryService;
import com.treasury.nl2sql.guard.CurrencyGuard;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.llm.LlmProperties;
import com.treasury.nl2sql.schema.SchemaLinker;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编排器：NL → (LLM) MQL → 校验/自我修正 → (jOOQ) SQL → 执行。
 * 每一跳的中间产物都保留在 {@link NlQueryResult} 里，方便观察这条链路。
 * 该类只承接“自然语言到可执行结果”的公共查询，不处理报告口径复用与资产化策略。
 */
@Service
public class Nl2SqlService {

    private static final Logger log = LoggerFactory.getLogger(Nl2SqlService.class);

    private final SchemaLinker schemaLinker;
    private final FewShotSelector fewShot;
    private final GlossaryService glossary;
    private final EmbeddingClient embedding;
    private final CurrencyGuard currencyGuard;
    private final LlmClient llm;
    private final LlmProperties llmProps;
    private final MqlValidator validator;
    private final MqlSqlCompiler compiler;
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final com.treasury.nl2sql.schema.SchemaService schemaService;

    public Nl2SqlService(SchemaLinker schemaLinker, FewShotSelector fewShot, GlossaryService glossary,
                         EmbeddingClient embedding,
                         CurrencyGuard currencyGuard, LlmClient llm, LlmProperties llmProps,
                         MqlValidator validator, MqlSqlCompiler compiler,
                         DSLContext dsl, ObjectMapper mapper,
                         com.treasury.nl2sql.schema.SchemaService schemaService) {
        this.schemaLinker = schemaLinker;
        this.fewShot = fewShot;
        this.glossary = glossary;
        this.embedding = embedding;
        this.currencyGuard = currencyGuard;
        this.llm = llm;
        this.llmProps = llmProps;
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
        this.mapper = mapper;
        this.schemaService = schemaService;
    }

    /**
     * 对外主入口。调用方通常不传 qv，默认按问题动态 embed 一次。
     */
    public NlQueryResult query(String question) {
        return query(question, Double.POSITIVE_INFINITY);
    }

    /**
     * @param fewshotMaxSim few-shot 近重复排除阈值（≥该相似度的示例不注入）。
     *        默认 +∞（生产：不排除）；评估时传阈值排除与评估问题近重复的示例，防答案泄漏。
     */
    public NlQueryResult query(String question, double fewshotMaxSim) {
        return query(question, fewshotMaxSim, null);
    }

    /**
     * @param question 用户自然语言问题。空值或空白问题由调用方参数校验确保进入前置拒绝。
     * @param fewshotMaxSim few-shot 去重阈值（值越大，去重越严格；值越小接近会允许更多近邻示例）。
     *                     业务上常用 {@code Double.POSITIVE_INFINITY} 表示不去重，评测场景可显式指定阈值避免泄漏。
     * @param qv 问题向量，若外部已预计算可复用；null 时本方法将做一次 embedding 并在失败时降级继续执行。
     * @return 查询链路产物：包括输入、MQL、SQL、结果集、失败信息、重试轮次、成功与告警；
     *         成功路径 rows 非空且 success=true，失败路径 success=false 且留 warnings/errors。
     */
    public NlQueryResult query(String question, double fewshotMaxSim, float[] qv) {
        String degradeWarn = null;
        if (qv == null) {
            try {
                qv = embedding.embed(question);
            } catch (Exception e) {
                degradeWarn = "embedding 服务不可用，已降级（全量 schema/全量术语/无 few-shot）";
                log.warn("问题向量化失败，本次查询降级: {}", e.getMessage());
            }
        }

        // Schema linking：只把相关表的 schema 注入 prompt（避免无关表噪音，保留检索预算）
        SchemaLinker.LinkingResult linking = schemaLinker.select(question, qv);
        // 术语口径声明的表强制并入：口径涉及的表（如折算的 currency_rate 无外键、向量召回可能裁掉）
        // 不能缺席，否则模型会因"看不到表"而拒答
        java.util.Set<String> termTables = glossary.tablesOfSelected(question, qv);
        if (!termTables.isEmpty() && !linking.tables().containsAll(termTables)) {
            List<String> merged = new ArrayList<>(linking.tables());
            for (String t : termTables) {
                if (!merged.contains(t)) merged.add(t);
            }
            linking = new SchemaLinker.LinkingResult(merged, schemaService.assemble(merged), linking.scores());
            log.info("术语口径声明的表已并入 linking: {}", termTables);
        }
        log.info("Schema linking 命中表: {}", linking.tables());

        // 动态 few-shot：按相似度选 Top-N 示例（评估时可排除近重复，防泄漏）
        String fewShotBlock = fewShot.formatBlock(question, fewshotMaxSim, qv);
        // 业务术语口径：按问题相关性选 Top-N（术语库小时全量短路）
        String glossaryBlock = glossary.formatBlock(question, qv);

        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt(linking.schemaText(), glossaryBlock, fewShotBlock)));
        conversation.add(LlmClient.Message.user("用户问题：" + question + "\n请输出 MQL JSON。"));

        Mql mql = null;
        String sql = null;
        List<Map<String, Object>> rows = null;
        List<String> errors = new ArrayList<>();
        int round = 0;

        // 重试循环：解析失败/校验失败/执行失败都会回灌 prompt，直到 maxFixRounds 或首次成功。
        for (; round <= llmProps.getMaxFixRounds(); round++) {
            String raw = llm.completeJson(conversation);
            log.info("LLM 第{}轮输出: {}", round, raw);

            // 1) 解析：合法 JSON 且无 unanswerable 才进入下一层校验
            try {
                String json = stripFence(raw);
                // 拒答：模型判定问题无法用本库只读查询回答 → 直接返回 success=false，不再重试
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
                if (node.path("unanswerable").asBoolean(false)) {
                    String reason = node.path("reason").asText("无法用本库的只读查询回答该问题");
                    log.info("模型拒答: {}", reason);
                    return new NlQueryResult(question, null, null, null,
                            List.of("拒答: " + reason), round, false,
                            degradeWarn == null ? List.of() : List.of(degradeWarn), reason);
                }
                mql = mapper.readValue(json, Mql.class);
            } catch (Exception e) {
                errors = List.of("MQL JSON 解析失败: " + e.getMessage());
                feedback(conversation, raw, "上面的输出不是合法 JSON：" + e.getMessage()
                        + "。请只输出合法的 MQL JSON。");
                continue;
            }

            // 2) 确定性校验：统一规则通过后再进编译，避免抛异常污染执行层
            errors = validator.validate(mql);
            if (!errors.isEmpty()) {
                log.info("第{}轮校验失败: {}", round, errors);
                feedback(conversation, raw, "MQL 校验未通过，错误如下：\n- "
                        + String.join("\n- ", errors) + "\n请修正后只输出合法的 MQL JSON。");
                continue;
            }

            // 3) 编译 + 执行（异常同样回灌），失败只做模型可修正提示，不直接对外抛 500
            try {
                SelectQuery<Record> query = compiler.compile(mql);
                sql = compiler.renderSql(query);
                log.info("编译得到 SQL: {}", sql);
                rows = dsl.fetch(query).intoMaps();
                errors = List.of();
                break; // 成功
            } catch (Exception e) {
                String msg = rootMessage(e);
                errors = List.of("SQL 编译/执行失败: " + msg);
                log.info("第{}轮执行失败: {}", round, msg);
                feedback(conversation, raw, "上面 MQL 生成的 SQL 编译/执行失败：" + msg
                        + (sql != null ? "\n（SQL：" + sql + "）" : "")
                        + "\n请修正后只输出合法的 MQL JSON。");
            }
        }

        boolean success = errors.isEmpty() && rows != null;
        List<String> warnings = new ArrayList<>();
        if (degradeWarn != null) warnings.add(degradeWarn);   // 成功与失败路径都留痕
        if (success) warnings.addAll(currencyGuard.check(mql));
        if (!warnings.isEmpty()) log.info("护栏警告: {}", warnings);
        return new NlQueryResult(question, mql, sql, rows, errors, round, success, warnings, null);
    }

    /**
     * 将上一步 assistant 输出及其问题化 feedback 拼回对话历史。
     * 让同一轮 LLM 具备“错误上下文”，提高下一轮修复成功率。
     */
    private static void feedback(List<LlmClient.Message> conv, String assistantRaw, String userMsg) {
        conv.add(LlmClient.Message.assistant(assistantRaw));
        conv.add(LlmClient.Message.user(userMsg));
    }

    /**
     * 取异常链最深处的根因消息，压成单行，便于回灌提示词；
     * 避免抛出过长堆栈，提升反馈文本可读性与可修复性。
     */
    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m == null ? c.getClass().getSimpleName() : m).replaceAll("\\s+", " ").trim();
    }

    /**
     * 组装系统提示词：强约束 + 示例 + 规则。
     * 核心策略是“减少模型自由度”——约束清晰时能显著降低后续自我修正轮次。
     */
    private String systemPrompt(String schemaText, String glossaryBlock, String fewShotBlock) {
        return """
            你是一个把中文自然语言问题翻译成「MQL（结构化中间查询语言）」的引擎，用于查询企业资金（treasury）数据。
            只输出一个 JSON 对象，不要输出任何解释、不要使用 markdown 代码块。

            ## MQL JSON 结构
            {
              "table": "主表名(必填)",
              "alias": "主表别名(可选；自连接时用)",
              "joins": [ {"table":"被连接表","alias":"别名(自连接必填)","type":"inner|left",
                          "on":[{"left":"前缀.列名","op":"= | != | > | >= | < | <=（默认=）","right":"前缀.列名"},
                                {"left":"前缀.列名","op":"=","value":"常量"}]} ],
                 // on 右侧可为字段(right)或常量(value)二选一；对右表的常量口径条件写进 on 的 value 形态
              "columns": ["无聚合时要选的列；有聚合时留空"],
              "distinct": true,   // 可选：列投影去重(SELECT DISTINCT)；仅限无聚合/无分组且已写 columns
              "caseColumns": [ {"alias":"tier","cases":[{"when":[条件节点...],"then":"高"},...],"else":"低"} ],
                 // 可选：CASE WHEN 派生维度(如按余额分档打标签)，按序匹配；then/else 只能是常量；
                 // 分组统计时必须把 alias 放进 groupBy（如 "groupBy":["tier"]）
              "timeColumns": [ {"func":"year|quarter|month|day","field":"日期列","alias":"ym"} ],
                 // 可选：时间截断派生维度（"按月/按季/按年统计"用它）。输出为字符串：月='2026-06'、季='2026-Q2'、年='2026'，
                 // 跨年安全、可直接排序；分组统计时必须把 alias 放进 groupBy，按时间排序直接 orderBy 该 alias
              "filter": [ 条件节点, ... ],   // 顶层列表元素之间为 AND
              "windows": [ {"op":"row_number|rank|dense_rank|sum|avg|count|lag|lead","field":"sum/avg/count/lag/lead需要","offset":1,"partitionBy":["分区列"],"orderBy":[{"field":"列","direction":"desc"}],"alias":"别名"} ],
                 // 可选：窗口函数（组内排名/累计/取前后行）。排名不填 field 且必须给 orderBy；累计如 {"op":"sum","field":"amount","orderBy":[{"field":"txn_date"}],"alias":"running_total"}
                 // lag/lead=按 orderBy 排序后取前/后一行的 field 值（offset 缺省1，通常省略），用于"环比上月/与前一笔对比"
                 // 通常与 groupBy/metrics 互斥；唯一例外是"先聚合再开窗"（如逐月合计后取上月值做环比）：
                 //   此时窗口的 field/partitionBy/orderBy 只能引用 groupBy 列（裸列名或 timeColumn 别名）或 metric 别名，
                 //   例 环比：timeColumns 按月 + groupBy ["ym"] + sum 得 total + {"op":"lag","field":"total","orderBy":[{"field":"ym"}],"alias":"prev_total"}
                 // 与 distinct 互斥
              "qualify": [ {"field":"窗口别名","op":"<=","value":2} ],   // 可选：对窗口结果过滤（如"每组前2名"）；只能引用窗口别名，普通条件仍写 filter
              "groupBy": ["分组列或 caseColumn 的 alias"],
              "metrics": [ 指标, ... ],   // 指标为以下三种之一：
                 // 单聚合(可条件聚合)： {"op":"sum|count|avg|min|max","field":"列(count可省略)","filter":[条件...可选,仅对满足行聚合],"alias":"别名"}
                 //   去重计数加 "distinct":true（仅 count 且须指定 field）→ COUNT(DISTINCT 列)，如"有交易的账户数"
                 // 行级表达式聚合(先逐行算再聚合)： {"op":"sum|avg|min|max","fieldExpr":{"left":"数值列","arith":"+|-|*|/","right":"数值列或数字"},"alias":"别名"}
                 //   例 折人民币总额=SUM(金额×汇率)：{"op":"sum","fieldExpr":{"left":"cash_transaction.amount","arith":"*","right":"currency_rate.rate_to_cny"},"alias":"total_cny"}
                 // 派生指标(两个聚合结果之间的算术，先聚合再算)： {"expr":{"left":单聚合,"arith":"+|-|*|/","right":单聚合},"alias":"别名"}
                 //   例 净流入=收入−支出：{"expr":{"left":{"op":"sum","field":"amount","filter":[{"field":"direction","op":"=","value":"IN"}]},"arith":"-","right":{"op":"sum","field":"amount","filter":[{"field":"direction","op":"=","value":"OUT"}]}},"alias":"net_flow"}
                 //   expr 的 left/right 也可为数字常量 {"value":100}（如占比再乘100），但不能两侧都是常量
                 // 注意区分：fieldExpr 是"行内两列"先算后聚合；expr 是"两个聚合结果"先聚合后算
              "having": [ {"field":"列名或聚合别名","op":"...","value": 值} ],
              "orderBy": [ {"field":"列名或聚合别名","direction":"asc|desc"} ],
              "limit": 整数或省略,
              "union": [ {另一段完整MQL}, ... ],   // 可选：多段结果合并；各段列数必须一致且显式给出 columns/metrics
              "unionAll": false                     // true=UNION ALL 保留重复行；缺省=UNION 去重
            }

            ## 条件节点（filter / having 通用）
            每个条件节点是以下三选一：
              - 叶子： {"field":"列名","op":"= | != | > | >= | < | <= | like | not like | in | not in | is null | is not null","value": 值或数组}
                （is null / is not null 判空，不带 value；in / not in 的 value 必须是数组；like / not like 的 value 自带 %）
              - 或组： {"or":[ 条件节点, 条件节点, ... ]}    // 组内任一满足
              - 与组： {"and":[ 条件节点, 条件节点, ... ]}   // 组内全部满足；组可嵌套
            叶子的 value 可以换成 "subquery": {完整MQL对象}（与 value 二选一，仅 filter 可用）：
              - op 为比较符时=标量子查询，子查询须恰有 1 个聚合指标、无 groupBy/columns，如"余额高于平均值"：
                {"field":"balance","op":">","subquery":{"table":"account","metrics":[{"op":"avg","field":"balance","alias":"avg_bal"}]}}
              - op=in 时子查询只输出 1 列（单个 columns 或单个聚合指标），如"有销售流水的账户"
              - 子查询不能引用外层表的字段（不支持相关子查询），内部不得再嵌子查询/orderBy/limit
            布尔逻辑约定：
              - 同一列的"或"（如币种是 USD 或 EUR）用 in：{"field":"currency","op":"in","value":["USD","EUR"]}
              - 跨不同列的"或"用 or 组：如「余额>500万 或 状态=冻结」→
                {"or":[{"field":"balance","op":">","value":5000000},{"field":"status","op":"=","value":"FROZEN"}]}
              - 复杂逻辑 (A 且 B) 或 (C 且 D) 用 or 套 and 嵌套表达
              - "没有/缺失/不属于/不含"类问法用否定 op（is null / not in / not like）直接表达，不要用子查询绕行；
                "没有任何 XX 记录的 YY"（如从未交易的账户）用 left join + 右表非空列 is null（反连接）表达

            ## 规则
            - 只能使用下面 schema 中真实存在的表名和列名，禁止臆造。
            - 涉及多张表（有 joins）时，所有字段（columns/filter/groupBy/metrics/orderBy/on）一律用「前缀.列名」限定（前缀=表名或别名）；单表时可省略前缀。
            - 自连接（同一张表连接两次，如本期 vs 上期对比）：主表设 alias，join 同名表也设不同 alias，字段用各自别名前缀引用。
            - 连接键请参考下方 schema 的「可连接关系(外键)」，不要臆造连接条件。
            - 需要展示名称类字段（如账户名）而表里只有 id 时，应 join 出对应的名称表。
              问"哪些账户/每个账户/各账户"时，结果一律展示账户名称（join account 取 account_name）而不是只给 account_id；
              输出列保持克制——只给问题问到的列，不要额外附带 id 等冗余列。
            - 金额字段为原币；当问题明确针对某一币种时用 currency 过滤，不要把不同币种的金额无意义地相加。
            - 日期用 'YYYY-MM-DD' 字符串；"本月"等相对时间请按今天=2026-06-30 推算具体区间，用两个比较条件表达。
            - "按月/按季度/按年统计"用 timeColumns 派生时间维度再 groupBy 其 alias，不要直接 groupBy 日期列当月份用，也不要臆造月份列；"按天/按日"分组直接 groupBy 日期列即可，不需要 timeColumns。
            - like / not like 的 value 需自带通配符 %。in / not in 的 value 必须是数组。
            - **left join 陷阱**：left join 时不要在 filter 里对被连接表（右表）的列写常量条件——WHERE 会把左表中无匹配的行一并滤掉，使 left join 退化成 inner join。右表的常量口径条件应放进该 join 的 on（value 形态，如 {"left":"t.direction","op":"=","value":"OUT"}）；聚合口径也可用 metrics 的条件聚合 filter 表达；找"没有匹配"的行则用右表列 is null。
            - 只读查询，不得产生写操作。
            - **优先用最简单的结构**：能用 filter/groupBy/limit 表达的，不要用子查询/窗口等复杂结构。
            - 仅当需要"与整表统计值比较"（如高于平均）或"存在于另一查询结果中"时才用 subquery。
            - 仅当需要"每组内排名/累计/环比取前后行"（如每个币种余额前2、按日累计、环比上月）时才用 windows；对排名结果的过滤只能写在 qualify，不能写在 filter。全表 Top-N 用 orderBy+limit 即可，不要用窗口。
            - 仅当需要"把两类查询结果合并成一张表"（如账户名与对手方合并名单）时才用 union；段内不得写 orderBy/limit/union，排序限行写在最外层。
            - 拒答：若问题要求的字段/表在下方 schema 中**不存在**，或要求**新增/修改/删除**数据，或与本资金库业务**无关**而无法用只读查询回答，则不要勉强编造查询（如 select * 或恒假条件兜底），只输出：{"unanswerable": true, "reason": "简述无法回答的原因"}。

            ## 当前数据库 schema
            {{SCHEMA}}

            {{GLOSSARY}}

            {{FEWSHOT}}
            """.replace("{{SCHEMA}}", schemaText)
               .replace("{{GLOSSARY}}", glossaryBlock)
               .replace("{{FEWSHOT}}", fewShotBlock);
    }

    /**
     * 兼容模型返回带代码围栏的常见格式，保留无围栏和有围栏两种输入路径。
     * 仅用于“输入清洗”，不执行 JSON 校验与语义判断。
     */
    private static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t.trim();
    }
}
