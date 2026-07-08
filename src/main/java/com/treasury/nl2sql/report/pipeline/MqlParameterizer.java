package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.treasury.nl2sql.schema.SchemaService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 参数化辅助识别（指标向导第 3 步，纯程序确定性扫描、零 LLM）。
 * 在 MQL 的 JsonNode 表示上做泛化 DFS：叶子条件（field/op/value 三件套）散布在
 * filter/having/qualify/嵌套 and-or/metrics[].filter/caseColumns/subquery/union 等位置，
 * 泛化遍历一个递归全覆盖，JSON Pointer 路径自然生成。
 * 命中规则：时间型列 + YYYY-MM-DD 字面量 → 按 op 确定占位符方向（用户只勾选，配不错方向）：
 *   op ∈ {>=, >} → {{period_start}}；op ∈ {<=, <} → {{period_end}}；
 *   op = "="（或 in 含日期）→ 不可勾选的提示项（等值日期映射不了期间占位符）。
 * 字段表归属照 MqlValidator.resolveTable 语义：含 "." 查 ref2real（alias→真实表，含 joins），
 * 裸字段归当前作用域主表（多表裸字段已被校验器拒，故此归属严格正确）。
 */
@Component
public class MqlParameterizer {

    /** @param placeholder null=不可勾选的提示项（reason 说明原因） */
    public record Suggestion(String path, String field, String op, String value, String placeholder, String reason) {}
    public record ScanResult(List<Suggestion> suggestions, List<String> notes) {}
    public record ApplyResult(JsonNode mqlTemplate, List<String> applied, List<Suggestion> remaining) {}

    public static final String PH_START = "{{period_start}}";
    public static final String PH_END = "{{period_end}}";

    private static final Pattern DATE_LITERAL = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final SchemaService schema;

    public MqlParameterizer(SchemaService schema) {
        this.schema = schema;
    }

    /** 扫描建议清单（不改动输入）。 */
    public ScanResult scan(JsonNode mql) {
        List<Suggestion> suggestions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (mql == null || !mql.isObject()) {
            return new ScanResult(suggestions, List.of("输入不是合法的 MQL JSON 对象"));
        }
        walk(mql, "", null, null, suggestions, notes);
        return new ScanResult(List.copyOf(suggestions), List.copyOf(notes));
    }

    /**
     * 按勾选的 path 替换为占位符，返回 mqlTemplate（输入深拷贝，不改原件）。
     * 安全闸：每个 path 必须命中本次重算出的「可勾选」建议，否则拒绝——
     * 防止借 apply 篡改任意节点（如改表名绕过白名单心智）。
     */
    public ApplyResult apply(JsonNode mql, List<String> paths) {
        ScanResult scanned = scan(mql);
        Map<String, Suggestion> applicable = new LinkedHashMap<>();
        for (Suggestion s : scanned.suggestions()) {
            if (s.placeholder() != null) applicable.put(s.path(), s);
        }
        JsonNode work = mql.deepCopy();
        List<String> applied = new ArrayList<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            Suggestion s = applicable.get(path);
            if (s == null) {
                throw new IllegalArgumentException("apply 的 path 不在可勾选建议中（已重算校验）: " + path);
            }
            JsonPointer p = JsonPointer.compile(path);
            JsonNode leaf = work.at(p.head());
            if (!(leaf instanceof ObjectNode obj) || !leaf.has("value")) {
                throw new IllegalArgumentException("path 定位不到叶子条件节点: " + path);
            }
            obj.put("value", s.placeholder());
            applied.add(path);
        }
        List<Suggestion> remaining = scanned.suggestions().stream()
                .filter(s -> !applied.contains(s.path())).toList();
        return new ApplyResult(work, applied, remaining);
    }

    // ---------- 泛化 DFS ----------

    /** 作用域：含 table 属性的 ObjectNode（顶层 / subquery / union 段）各自成域。 */
    private record Scope(String mainRealTable, Map<String, String> ref2real) {}

    private void walk(JsonNode node, String path, String parentField, Scope scope,
                      List<Suggestion> suggestions, List<String> notes) {
        if (node == null) return;
        if (node.isObject()) {
            // join 节点也含 table（父字段 joins），不开新作用域
            if (node.has("table") && node.get("table").isTextual() && !"joins".equals(parentField)) {
                scope = buildScope(node);
            }
            if (isLeafCondition(node)) {
                inspectLeaf((ObjectNode) node, path, scope, suggestions);
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                walk(e.getValue(), path + "/" + e.getKey(), e.getKey(), scope, suggestions, notes);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "/" + i, parentField, scope, suggestions, notes);
            }
        }
    }

    private static Scope buildScope(JsonNode mqlNode) {
        String table = mqlNode.path("table").asText();
        Map<String, String> ref2real = new LinkedHashMap<>();
        String alias = mqlNode.path("alias").isTextual() ? mqlNode.path("alias").asText() : null;
        ref2real.put(alias != null && !alias.isBlank() ? alias : table, table);
        for (JsonNode j : mqlNode.path("joins")) {
            String jt = j.path("table").asText(null);
            if (jt == null) continue;
            String ja = j.path("alias").isTextual() && !j.path("alias").asText().isBlank()
                    ? j.path("alias").asText() : jt;
            ref2real.putIfAbsent(ja, jt);
        }
        return new Scope(table, ref2real);
    }

    /** 叶子条件三件套：field(textual) + op + value（且非 and/or 组节点）。 */
    private static boolean isLeafCondition(JsonNode node) {
        return node.path("field").isTextual() && node.has("op") && node.has("value");
    }

    private void inspectLeaf(ObjectNode leaf, String path, Scope scope, List<Suggestion> suggestions) {
        if (scope == null) return;
        String field = leaf.path("field").asText();
        String op = leaf.path("op").asText("").trim().toLowerCase();
        JsonNode value = leaf.get("value");

        String realTable;
        String column;
        if (field.contains(".")) {
            String[] p = field.split("\\.", 2);
            realTable = scope.ref2real().get(p[0]);
            column = p[1];
            if (realTable == null) return;   // 校验器已拒的形态，防御性跳过
        } else {
            realTable = scope.mainRealTable();
            column = field;
        }
        if (!schema.isTemporalColumn(realTable, column)) return;

        if (value.isTextual()) {
            String v = value.asText();
            if (v.contains("{{")) return;   // 已参数化，幂等不重复建议
            if (!DATE_LITERAL.matcher(v).matches()) return;
            switch (op) {
                case ">=", ">" -> suggestions.add(new Suggestion(path + "/value", field, op, v, PH_START,
                        "时间列下界条件，建议替换为报告期起始占位符"));
                case "<=", "<" -> suggestions.add(new Suggestion(path + "/value", field, op, v, PH_END,
                        "时间列上界条件，建议替换为报告期截止占位符"));
                case "=" -> suggestions.add(new Suggestion(path + "/value", field, op, v, null,
                        "等值日期无法映射期间占位符——请改问法为日期范围，或确认这是快照口径"));
                default -> { /* like/!= 等不建议 */ }
            }
        } else if (value.isArray() && ("in".equals(op) || "not in".equals(op))) {
            for (JsonNode item : value) {
                if (item.isTextual() && DATE_LITERAL.matcher(item.asText()).matches()) {
                    suggestions.add(new Suggestion(path + "/value", field, op, value.toString(), null,
                            "IN 列表含日期字面量，无法映射期间占位符——请改问法为日期范围"));
                    return;
                }
            }
        }
    }
}
