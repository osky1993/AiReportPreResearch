package com.treasury.nl2sql.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.schema.SchemaService;
import com.treasury.nl2sql.validate.MqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 口径反翻译：把结构化查询（MQL JSON）翻译成业务人员能逐条核对的中文口径描述。
 *
 * <p>纯展示层辅助，不进任何取数/事实链路：解释与 SQL 同源（都由同一份 MQL 派生），LLM 只做翻译不做判断；
 * 失败关闭无副作用。确定性前置闸（能反序列化 + {@link MqlValidator} 白名单通过才调 LLM）兼作
 * 「不信任前端回传 MQL」的服务端重校验。两种语境：
 * <ul>
 *   <li>{@link Mode#TEMPLATE} —— 指标模板（含 {@code {{period_*}}} 占位符），指标向导第 2 步用；</li>
 *   <li>{@link Mode#AD_HOC} —— 问数即席查询（无占位符，条件值是口径的一部分），问数页「口径说明」用。</li>
 * </ul>
 * 同 MQL 必同解释，按 (mode, MQL 序列化文本) 做 LRU 缓存——HIT 复用同一口径资产时不重复烧 token。
 */
@Service
public class MqlExplainService {

    private static final Logger log = LoggerFactory.getLogger(MqlExplainService.class);
    private static final int CACHE_MAX = 256;

    /** 解释语境：TEMPLATE=指标模板（占位符按报告期描述）；AD_HOC=问数即席查询（条件值如实描述）。 */
    public enum Mode { TEMPLATE, AD_HOC }

    public record Explanation(String explanation, List<String> caveats) {}

    private final MqlValidator validator;
    private final SchemaService schema;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    /** (mode|mql 文本) → 解释 的 LRU；访问序淘汰，仅缓存成功结果。 */
    private final Map<String, Explanation> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Explanation> eldest) {
            return size() > CACHE_MAX;
        }
    };

    public MqlExplainService(MqlValidator validator, SchemaService schema, LlmClient llm, ObjectMapper mapper) {
        this.validator = validator;
        this.schema = schema;
        this.llm = llm;
        this.mapper = mapper;
    }

    /**
     * 将 MQL 反向翻译为可核对口径。
     * 执行顺序：
     * <ol>
     *   <li>先做反序列化 + 白名单校验；失败则 400/业务拒绝，绝不调用 LLM。</li>
     *   <li>命中 LRU 缓存直接返回，减少重复解释开销。</li>
     *   <li>构造系统提示 + 原始 MQL，单次重试一次并清洗 markdown fence。</li>
     *   <li>解析 description/caveats，失败则抛出说明性异常。</li>
     * </ol>
     * 失败关闭策略：只对成功解析并通过校验的输入生成解释；解释失败不会写数据库，不产生副作用。
     */
    public Explanation explain(JsonNode mqlNode, Mode mode) {
        // 确定性前置闸：垃圾输入不烧 token；也是对前端回传 MQL 的服务端重校验
        Mql mql;
        try {
            mql = mapper.treeToValue(mqlNode, Mql.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("mql 无法解析为合法查询结构: " + e.getMessage());
        }
        List<String> errors = validator.validate(mql);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("mql 未通过白名单校验，不做反翻译: " + String.join("；", errors));
        }

        String cacheKey = mode + "|" + mqlNode.toString();
        synchronized (cache) {
            Explanation hit = cache.get(cacheKey);
            if (hit != null) return hit;
        }

        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(systemPrompt(mqlNode, mode)));
        conversation.add(LlmClient.Message.user("MQL：\n" + mqlNode.toPrettyString() + "\n请输出口径描述 JSON。"));
        JsonNode node = completeWithOneRetry(conversation);

        if (node.path("unanswerable").asBoolean(false)) {
            throw new IllegalArgumentException("无法反翻译该查询: " + node.path("reason").asText("模型未给出原因"));
        }
        String description = node.path("description").asText(null);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("口径反翻译输出缺少 description");
        }
        List<String> caveats = new ArrayList<>();
        for (JsonNode c : node.path("caveats")) {
            if (c.isTextual() && !c.asText().isBlank()) caveats.add(c.asText());
        }
        log.info("[EXPLAIN:{}] 口径反翻译完成: {}", mode, description);
        Explanation result = new Explanation(description, caveats);
        synchronized (cache) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    /** 根据口径语境组装系统提示词，限定只描述 MQL 可见语义，禁止臆测结果。 */
    private String systemPrompt(JsonNode mqlNode, Mode mode) {
        Set<String> tables = new LinkedHashSet<>();
        collectTables(mqlNode, tables);
        String intro = mode == Mode.TEMPLATE
                ? "你是企业资金数据的指标口径解读器"
                : "你是企业资金数据的查询口径解读器";
        String modeRules = mode == Mode.TEMPLATE
                ? """
                  - 禁止输出任何具体数值或业务结论。
                  - 占位符 {{period_start}}/{{period_end}} 表示报告期起止日期，按"报告期内"描述。"""
                : """
                  - 查询条件中的日期、期间、阈值、取前 N 名等限定是口径的一部分，须如实描述。
                  - 除条件值外，禁止编造或预测任何查询结果数值，禁止输出业务结论。
                  - 面向不懂 SQL 的业务人员书写：优先用表/列注释里的业务名称表述，不要出现表别名（如 b.xxx），
                    仅在列含义不确定时括注技术列名。""";
        return """
            %s：把结构化查询（MQL JSON）翻译成业务人员能逐条核对的中文口径描述。
            只输出一个 JSON 对象，不要解释、不要 markdown 代码块。

            ## 涉及表结构（列名/类型/注释）
            %s
            ## 输出 JSON 结构
            {
              "description": "不超过 300 字的中文口径描述",
              "caveats": ["不确定之处逐条列出（没有则空数组）"],
              "unanswerable": false,
              "reason": "unanswerable 为 true 时说明原因"
            }

            ## 规则
            - 只允许描述 MQL 中实际存在的内容，须逐项覆盖：来源表、每一个过滤条件（含嵌套与子查询的语义）、
              连接与折算口径、聚合方式与结果含义。
            - 禁止编造 MQL 里没有的口径（如自行补充"不含内部划转"之类的限定）。
            - 某列含义不确定时引用列名原文，并把不确定点放进 caveats。
            %s
            """.formatted(intro, schema.assemble(tables), modeRules);
    }

    /** 递归收集 MQL 里出现的全部表名（顶层/joins/subquery/union）；用于拼装 schema 辅助上下文。 */
    private static void collectTables(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode t = node.get("table");
            if (t != null && t.isTextual()) out.add(t.asText());
            node.forEach(child -> collectTables(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectTables(child, out));
        }
    }

    /**
     * LLM 调用带一次修复重试：首轮若非合法 JSON，追加“只输出 JSON”的纠错提示再尝试一次。
     * 二次仍失败则抛出业务拒绝（避免吞掉模型漂移噪音）。
     */
    private JsonNode completeWithOneRetry(List<LlmClient.Message> conversation) {
        String raw = llm.completeJson(conversation);
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception first) {
            conversation.add(LlmClient.Message.assistant(raw));
            conversation.add(LlmClient.Message.user("上面的输出不是合法 JSON：" + first.getMessage() + "。请只输出合法 JSON。"));
            String retry = llm.completeJson(conversation);
            try {
                return mapper.readTree(stripFence(retry));
            } catch (Exception second) {
                throw new IllegalArgumentException("口径反翻译输出无法解析为 JSON（重试 1 次仍失败）");
            }
        }
    }

    /** 去除可能出现的 markdown fence（```json）后 trim，避免反序列化污染。 */
    private static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t.trim();
    }
}
