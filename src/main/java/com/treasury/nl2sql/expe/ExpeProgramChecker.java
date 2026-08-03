package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROGRAM 型规则的确定性校验器（零 LLM）——校验器词表见 RULES_TEMPLATE.json 的 program_checkers。
 * 判定纪律：解析失败/目标缺失一律 FAIL 并写明原因，不做修复重试（方案 §9.3「首次输出解析失败直接计入失败」）。
 */
@Component
public class ExpeProgramChecker {

    /** 输出解析结果：root=JSON 树（不可解析为 null）；usedFence=剥离 ``` 围栏后才解析成功；wholeText=title+summary+全部章节正文 */
    public record ParsedOutput(JsonNode root, boolean usedFence, String wholeText, String raw) {
        public boolean parsed() { return root != null; }
    }

    private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.DOTALL);

    private final ObjectMapper mapper;

    public ExpeProgramChecker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 解析被评输出：先按纯 JSON 解析，失败再尝试剥离代码围栏（是否判罚由 json_schema 校验器的 allow_fence 决定） */
    public ParsedOutput parse(String content) {
        String trimmed = content == null ? "" : content.trim();
        JsonNode root = tryParse(trimmed);
        boolean fence = false;
        if (root == null) {
            Matcher m = FENCE.matcher(trimmed);
            if (m.matches()) {
                root = tryParse(m.group(1));
                fence = root != null;
            }
        }
        String wholeText;
        if (root != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(root.path("title").asText("")).append('\n');
            sb.append(root.path("summary").asText(""));
            for (JsonNode s : root.path("sections")) {
                sb.append('\n').append(s.path("content").asText(""));
            }
            wholeText = sb.toString();
        } else {
            wholeText = trimmed;
        }
        return new ParsedOutput(root, fence, wholeText, trimmed);
    }

    private JsonNode tryParse(String s) {
        try {
            JsonNode n = mapper.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行单条 PROGRAM 规则；validEvidenceIds 来自该生成任务冻结的 data.json */
    public RuleVerdict check(Rule rule, ParsedOutput out, Set<String> validEvidenceIds) {
        try {
            JsonNode spec = rule.checkSpec();
            String checker = spec.path("checker").asText();
            JsonNode p = spec.path("params");
            return switch (checker) {
                case "json_schema" -> jsonSchema(rule, out, p);
                case "char_length_range" -> charLengthRange(rule, out, p);
                case "section_names_equals" -> sectionNamesEquals(rule, out, p);
                case "evidence_ids_valid" -> evidenceIdsValid(rule, out, validEvidenceIds);
                case "forbidden_terms" -> forbiddenTerms(rule, out, p);
                case "required_regex" -> regexCheck(rule, out, p, true);
                case "forbidden_regex" -> regexCheck(rule, out, p, false);
                case "starts_with_any" -> startsWithAny(rule, out, p);
                case "ends_with" -> endsWith(rule, out, p);
                case "term_max_frequency" -> termMaxFrequency(rule, out, p);
                default -> new RuleVerdict(rule.ruleId(), "ERROR", null, "未知校验器: " + checker);
            };
        } catch (Exception e) {
            return new RuleVerdict(rule.ruleId(), "ERROR", null, "校验器异常: " + e.getMessage());
        }
    }

    // ---------- 各校验器 ----------

    private RuleVerdict jsonSchema(Rule rule, ParsedOutput out, JsonNode p) {
        if (!out.parsed()) return fail(rule, null, "输出无法解析为 JSON 对象");
        boolean allowFence = !p.hasNonNull("allow_fence") || p.get("allow_fence").asBoolean();
        if (out.usedFence() && !allowFence) return fail(rule, null, "输出被代码围栏包裹，非纯 JSON（allow_fence=false）");
        JsonNode r = out.root();
        if (!r.hasNonNull("title") || r.path("title").asText().isBlank()) return fail(rule, null, "缺少非空 title");
        if (!r.hasNonNull("summary") || r.path("summary").asText().isBlank()) return fail(rule, null, "缺少非空 summary");
        JsonNode secs = r.path("sections");
        if (!secs.isArray() || secs.isEmpty()) return fail(rule, null, "缺少非空 sections 数组");
        for (int i = 0; i < secs.size(); i++) {
            JsonNode s = secs.get(i);
            if (!s.hasNonNull("name") || !s.hasNonNull("content") || !s.path("evidence").isArray()) {
                return fail(rule, null, "sections[" + i + "] 缺少 name/content/evidence");
            }
        }
        return pass(rule, out.usedFence() ? "剥离代码围栏后解析成功" : null);
    }

    private RuleVerdict charLengthRange(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        String joined = String.join("", targets).replaceAll("\\s+", "");
        int len = joined.codePointCount(0, joined.length());
        int min = p.path("min").asInt(0), max = p.path("max").asInt(Integer.MAX_VALUE);
        return (len >= min && len <= max)
                ? pass(rule, "实际 " + len + " 字")
                : fail(rule, null, "实际 " + len + " 字，要求 [" + min + "," + max + "]");
    }

    private RuleVerdict sectionNamesEquals(Rule rule, ParsedOutput out, JsonNode p) {
        if (!out.parsed()) return fail(rule, null, "JSON 不可解析，无法定位章节");
        List<String> actual = new ArrayList<>();
        for (JsonNode s : out.root().path("sections")) actual.add(s.path("name").asText());
        List<String> expected = new ArrayList<>();
        for (JsonNode e : p.path("expected")) expected.add(e.asText());
        return actual.equals(expected)
                ? pass(rule, null)
                : fail(rule, String.join("、", actual), "章节应为 " + String.join("—", expected));
    }

    private RuleVerdict evidenceIdsValid(Rule rule, ParsedOutput out, Set<String> validIds) {
        if (!out.parsed()) return fail(rule, null, "JSON 不可解析，无法定位 evidence");
        List<String> problems = new ArrayList<>();
        int i = 0;
        for (JsonNode s : out.root().path("sections")) {
            JsonNode ev = s.path("evidence");
            if (!ev.isArray() || ev.isEmpty()) {
                problems.add("章节[" + i + "]『" + s.path("name").asText() + "』evidence 为空");
            } else {
                for (JsonNode id : ev) {
                    if (!validIds.contains(id.asText())) {
                        problems.add("章节『" + s.path("name").asText() + "』引用了不存在的 ID: " + id.asText());
                    }
                }
            }
            i++;
        }
        return problems.isEmpty() ? pass(rule, null)
                : fail(rule, String.join("；", problems), "evidence 校验未通过");
    }

    private RuleVerdict forbiddenTerms(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        String joined = String.join("\n", targets);
        List<String> hits = new ArrayList<>();
        for (JsonNode t : p.path("terms")) {
            if (joined.contains(t.asText())) hits.add(t.asText());
        }
        return hits.isEmpty() ? pass(rule, null)
                : fail(rule, String.join("、", hits), "出现禁用词");
    }

    private RuleVerdict regexCheck(Rule rule, ParsedOutput out, JsonNode p, boolean required) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        Pattern pattern = Pattern.compile(p.path("pattern").asText());
        for (String t : targets) {
            boolean found = pattern.matcher(t).find();
            if (required && !found) return fail(rule, abbreviate(t), "未匹配要求的模式 " + pattern.pattern());
            if (!required && found) {
                Matcher m = pattern.matcher(t);
                m.find();
                return fail(rule, abbreviate(m.group()), "命中禁止的模式 " + pattern.pattern());
            }
        }
        return pass(rule, null);
    }

    private RuleVerdict startsWithAny(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        List<String> options = new ArrayList<>();
        for (JsonNode o : p.path("options")) options.add(o.asText());
        for (String t : targets) {
            for (String seg : t.split("[。；;\n]")) {
                String s = seg.strip();
                if (s.isEmpty()) continue;
                if (options.stream().noneMatch(s::startsWith)) {
                    return fail(rule, abbreviate(s), "分条未以指定开头之一起始: " + String.join("/", options));
                }
            }
        }
        return pass(rule, null);
    }

    private RuleVerdict endsWith(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        String suffix = p.path("suffix").asText();
        String last = targets.get(targets.size() - 1).strip();
        return last.endsWith(suffix) ? pass(rule, null)
                : fail(rule, abbreviate(last.substring(Math.max(0, last.length() - 30))), "未以『" + suffix + "』结尾");
    }

    private RuleVerdict termMaxFrequency(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "JSON 不可解析，无法定位目标字段");
        String joined = String.join("\n", targets);
        int max = p.path("max").asInt(Integer.MAX_VALUE);
        for (JsonNode t : p.path("terms")) {
            String term = t.asText();
            int count = 0, idx = 0;
            while ((idx = joined.indexOf(term, idx)) >= 0) { count++; idx += term.length(); }
            if (count > max) return fail(rule, term, "『" + term + "』出现 " + count + " 次，上限 " + max);
        }
        return pass(rule, null);
    }

    // ---------- 目标定位 ----------

    private static final Pattern SECTION_BY_NAME = Pattern.compile("\\$\\.sections\\[\\?name='(.+?)'\\]\\.content");

    /** 返回 null=需要 JSON 结构但不可解析；返回空列表=结构可解析但目标缺失 */
    List<String> resolveTarget(String target, ParsedOutput out) {
        if ("whole_text".equals(target)) return List.of(out.wholeText());
        if (!out.parsed()) return null;
        JsonNode r = out.root();
        switch (target) {
            case "$.title": return r.hasNonNull("title") ? List.of(r.get("title").asText()) : List.of();
            case "$.summary": return r.hasNonNull("summary") ? List.of(r.get("summary").asText()) : List.of();
            case "$.sections[*].content": {
                List<String> list = new ArrayList<>();
                for (JsonNode s : r.path("sections")) list.add(s.path("content").asText(""));
                return list;
            }
            default: {
                Matcher m = SECTION_BY_NAME.matcher(target);
                if (m.matches()) {
                    List<String> list = new ArrayList<>();
                    for (JsonNode s : r.path("sections")) {
                        if (m.group(1).equals(s.path("name").asText())) list.add(s.path("content").asText(""));
                    }
                    return list;
                }
                throw new IllegalArgumentException("不支持的 target 语法: " + target);
            }
        }
    }

    private static RuleVerdict pass(Rule rule, String note) {
        return new RuleVerdict(rule.ruleId(), "PASS", null, note);
    }

    private static RuleVerdict fail(Rule rule, String evidence, String reason) {
        return new RuleVerdict(rule.ruleId(), "FAIL", evidence, reason);
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
