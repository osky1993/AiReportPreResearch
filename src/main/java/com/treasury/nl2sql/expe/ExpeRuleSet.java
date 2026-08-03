package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原子规则清单（expe/RULES_TEMPLATE.json 格式）的解析与组别适用性判定。
 * 嵌套设计（方案 §5）：规则对 introduced_in 层及其之后的组生效；
 * D-Conflict 专属规则只对 D-Conflict 组生效（层秩 4 只有该组可达）。
 */
public class ExpeRuleSet {

    /** 单条原子规则；checkSpec 仅 PROGRAM 规则有 */
    public record Rule(String ruleId, String ruleText, String level, boolean shared, String introducedIn,
                       double weight, String checkType, String passRule, List<String> conflictWith,
                       JsonNode checkSpec) {}

    /** 单条规则对单份输出的判定：verdict ∈ PASS/PARTIAL/FAIL/ERROR（ERROR=判定基础设施故障，非样本结论） */
    public record RuleVerdict(String ruleId, String verdict, String evidence, String reason) {}

    /** 组别 → 可达层秩（B-Pad 与 B 同层、D-Clean 与 D 同层） */
    private static final Map<String, Integer> GROUP_RANK = Map.of(
            "A", 0, "B", 1, "B-Pad", 1, "C", 2, "D", 3, "D-Clean", 3, "D-Conflict", 4);

    private static final Map<String, Integer> LAYER_RANK = Map.of(
            "A", 0, "B", 1, "C", 2, "D", 3, "D-Conflict", 4);

    private final List<Rule> rules;
    private final String sha256;
    private final String version;

    private ExpeRuleSet(List<Rule> rules, String sha256, String version) {
        this.rules = rules;
        this.sha256 = sha256;
        this.version = version;
    }

    public List<Rule> rules() { return rules; }
    public String sha256() { return sha256; }
    public String version() { return version; }

    public static Set<String> validGroups() { return GROUP_RANK.keySet(); }

    /** 该组适用的规则（保持清单原顺序） */
    public List<Rule> applicableTo(String group) {
        Integer rank = GROUP_RANK.get(group);
        if (rank == null) throw new IllegalArgumentException("未知组别: " + group + "，合法值 " + GROUP_RANK.keySet());
        return rules.stream()
                .filter(r -> LAYER_RANK.getOrDefault(r.introducedIn(), Integer.MAX_VALUE) <= rank)
                .toList();
    }

    /** 该组自身新增层的规则（ExtraAdherence 统计范围）；A 组无新增层返回空 */
    public List<Rule> introducedAtTop(String group) {
        int rank = GROUP_RANK.get(group);
        return rules.stream()
                .filter(r -> LAYER_RANK.getOrDefault(r.introducedIn(), -1) == rank && rank > 0)
                .toList();
    }

    /** 解析并校验规则文件；结构非法即抛 IllegalArgumentException（失败关闭，不猜测补全） */
    public static ExpeRuleSet parse(byte[] json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则文件不是合法 JSON: " + e.getMessage());
        }
        JsonNode arr = root.path("rules");
        if (!arr.isArray() || arr.isEmpty()) throw new IllegalArgumentException("规则文件缺少非空 rules 数组");

        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : arr) {
            String id = req(n, "rule_id");
            if (!ids.add(id)) throw new IllegalArgumentException("规则 id 重复: " + id);
            String checkType = req(n, "check_type");
            if (!Set.of("PROGRAM", "AI", "HUMAN").contains(checkType)) {
                throw new IllegalArgumentException(id + " 的 check_type 非法: " + checkType);
            }
            String introducedIn = req(n, "introduced_in");
            if (!LAYER_RANK.containsKey(introducedIn)) {
                throw new IllegalArgumentException(id + " 的 introduced_in 非法: " + introducedIn);
            }
            JsonNode spec = n.get("check_spec");
            if ("PROGRAM".equals(checkType) && (spec == null || !spec.hasNonNull("checker"))) {
                throw new IllegalArgumentException(id + " 是 PROGRAM 规则但缺 check_spec.checker");
            }
            List<String> conflicts = new ArrayList<>();
            for (JsonNode c : n.path("conflict_with")) conflicts.add(c.asText());
            rules.add(new Rule(id, req(n, "rule_text"), req(n, "level"),
                    n.path("shared").asBoolean(false), introducedIn,
                    n.path("weight").asDouble(1), checkType, req(n, "pass_rule"),
                    List.copyOf(conflicts), spec));
        }
        for (Rule r : rules) {
            for (String c : r.conflictWith()) {
                if (!ids.contains(c)) throw new IllegalArgumentException(r.ruleId() + " 的 conflict_with 指向不存在的 " + c);
            }
        }
        return new ExpeRuleSet(List.copyOf(rules), sha256(json), root.path("template_version").asText(""));
    }

    private static String req(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.asText().isBlank()) {
            throw new IllegalArgumentException("规则缺少必填字段 " + field + ": " + n.path("rule_id").asText("(无id)"));
        }
        return v.asText();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
