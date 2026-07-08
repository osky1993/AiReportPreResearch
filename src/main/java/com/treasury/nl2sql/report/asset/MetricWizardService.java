package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.schema.SchemaService;
import com.treasury.nl2sql.service.Nl2SqlService;
import com.treasury.nl2sql.service.NlQueryResult;
import com.treasury.nl2sql.validate.MqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 指标制作向导第 1/2 步的服务端（P5-T1）。
 * try = 薄包装引擎 query()（LLM 自由生成路径只用于资产制作期，不进报告主流水线）；
 * explain = MQL 反翻译为中文口径描述——先过确定性前置闸（能反序列化 + 白名单校验通过才调 LLM），
 * 它只是人工核验辅助（第 2 步人确认才往下走），不进事实链，失败关闭无副作用。
 */
@Service
public class MetricWizardService {

    private static final Logger log = LoggerFactory.getLogger(MetricWizardService.class);

    public record TryResult(JsonNode mql, String sql, List<java.util.Map<String, Object>> rows,
                            List<String> columns, boolean success, List<String> errors,
                            List<String> warnings, String clarifyReason) {}
    public record ExplainResult(String explanation, List<String> caveats) {}

    private final Nl2SqlService nl2sql;
    private final MqlValidator validator;
    private final SchemaService schema;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final ObjectMapper cleanMapper;   // NON_EMPTY：Mql→JsonNode 时去掉 null 与空数组，模板干净入库

    public MetricWizardService(Nl2SqlService nl2sql, MqlValidator validator, SchemaService schema,
                               LlmClient llm, ObjectMapper mapper) {
        this.nl2sql = nl2sql;
        this.validator = validator;
        this.schema = schema;
        this.llm = llm;
        this.mapper = mapper;
        this.cleanMapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    // ---------- 第 1 步：试查 ----------

    public TryResult tryQuery(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问法不能为空");
        }
        NlQueryResult r = nl2sql.query(question);
        List<String> columns = (r.rows() != null && !r.rows().isEmpty())
                ? new ArrayList<>(r.rows().get(0).keySet()) : List.of();
        List<String> warnings = new ArrayList<>(r.warnings() == null ? List.of() : r.warnings());
        if (r.success()) {
            if (r.rows().size() != 1) {
                warnings.add("结果 " + r.rows().size() + " 行——指标要求恰 1 行 1 值，请改为聚合问法或收窄条件（保存时会硬性拦截）");
            } else if (columns.size() != 1) {
                warnings.add("结果 " + columns.size() + " 列——指标只取其中一列为值，其余列将被忽略");
            }
        }
        JsonNode mqlNode = r.mql() == null ? null : cleanMapper.valueToTree(r.mql());
        return new TryResult(mqlNode, r.sql(), r.rows(), columns, r.success(),
                r.errors(), warnings, r.clarifyReason());
    }

    // ---------- 第 2 步：口径反翻译 ----------

    public ExplainResult explain(JsonNode mqlNode) {
        // 确定性前置闸：垃圾输入不烧 token
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

        List<LlmClient.Message> conversation = new ArrayList<>();
        conversation.add(LlmClient.Message.system(explainSystemPrompt(mqlNode)));
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
        log.info("[WIZARD] 口径反翻译完成: {}", description);
        return new ExplainResult(description, caveats);
    }

    private String explainSystemPrompt(JsonNode mqlNode) {
        Set<String> tables = new LinkedHashSet<>();
        collectTables(mqlNode, tables);
        return """
            你是企业资金数据的指标口径解读器：把结构化查询（MQL JSON）翻译成业务人员能逐条核对的中文口径描述。
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
            - 禁止输出任何具体数值或业务结论。
            - 某列含义不确定时引用列名原文，并把不确定点放进 caveats。
            - 占位符 {{period_start}}/{{period_end}} 表示报告期起止日期，按"报告期内"描述。
            """.formatted(schema.assemble(tables));
    }

    /** 递归收集 MQL 里出现的全部表名（顶层/joins/subquery/union）。 */
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

    private static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t.trim();
    }
}
