package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROGRAM 型规则的确定性校验器（零 LLM）——校验器词表见 RULES_TEMPLATE.json 的 program_checkers。
 * 判定纪律：解析失败/目标缺失一律 FAIL 并写明原因，不做修复重试（方案 §9.3「首次输出解析失败直接计入失败」）。
 *
 * <p>交付载体（v2.0）：四组提示词要求 Markdown 简报（K05）。解析约定——第一个一级标题为 title，
 * 每个二级标题切出一个 section（章节名去除「一、」「（一）」等序号前缀后匹配），
 * 正文中方括号包裹的 [ID]/[JSONPath] 为证据标识。JSON 输出（D-Conflict 组可能被 I01 逼出）仍按原逻辑解析，
 * 同一套 target 语法对两种载体统一生效。
 */
@Component
public class ExpeProgramChecker {

    /**
     * 输出解析结果：root=结构树（JSON 原样 / Markdown 解析构造；两者皆不可解析时为 null）；
     * fromMarkdown=root 来自 Markdown 解析；usedFence=剥离 ``` 围栏后才解析成功；
     * wholeText=JSON 时 title+summary+全部章节正文，Markdown 时为原文全文。
     */
    public record ParsedOutput(JsonNode root, boolean usedFence, boolean fromMarkdown, String wholeText, String raw) {
        public boolean parsed() { return root != null; }
    }

    private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    /** 证据标识：方括号包裹、字母开头的 ID 或点分 JSONPath（中文方括号、Markdown 链接不在此形态内） */
    private static final Pattern EVIDENCE_TOKEN = Pattern.compile("\\[([A-Za-z][A-Za-z0-9_.]*)\\]");
    /** 章节名序号前缀：「一、」「（一）」「(1)」「1.」等 */
    private static final Pattern SECTION_PREFIX = Pattern.compile("^[（(]?[一二三四五六七八九十0-9]+[）)]?[、.．，,:：]?\\s*");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.、])\\s+");

    private final ObjectMapper mapper;

    public ExpeProgramChecker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 解析被评输出：先按 JSON 解析（含剥围栏），失败再按 Markdown 结构解析；均失败则仅保留纯文本 */
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
        if (root != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(root.path("title").asText("")).append('\n');
            sb.append(root.path("summary").asText(""));
            for (JsonNode s : root.path("sections")) {
                sb.append('\n').append(s.path("content").asText(""));
            }
            return new ParsedOutput(root, fence, false, sb.toString(), trimmed);
        }
        JsonNode mdRoot = parseMarkdown(trimmed);
        return new ParsedOutput(mdRoot, false, mdRoot != null, trimmed, trimmed);
    }

    private JsonNode tryParse(String s) {
        try {
            JsonNode n = mapper.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Markdown 结构化：# → title，## → sections（name 去序号前缀，content 至下一个 ≤2 级标题，evidence=正文方括号标识）。无任何标题时返回 null */
    private JsonNode parseMarkdown(String text) {
        String title = null;
        List<String[]> sections = new ArrayList<>();   // [name, content]
        StringBuilder current = null;
        String currentName = null;
        boolean sawHeading = false;
        for (String line : text.split("\n", -1)) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                String headText = h.group(2).strip();
                sawHeading = true;
                if (level == 1) {
                    if (title == null) title = headText;
                    continue;
                }
                if (level == 2) {
                    if (currentName != null) sections.add(new String[]{currentName, current.toString().strip()});
                    currentName = normalizeSectionName(headText);
                    current = new StringBuilder();
                    continue;
                }
                // 三级及以下标题算当前章节正文的一部分（L08 由 forbidden_regex 判罚）
            }
            if (current != null) current.append(line).append('\n');
        }
        if (currentName != null) sections.add(new String[]{currentName, current.toString().strip()});
        if (!sawHeading) return null;

        ObjectNode root = mapper.createObjectNode();
        root.put("title", title == null ? "" : title);
        ArrayNode arr = root.putArray("sections");
        for (String[] s : sections) {
            ObjectNode sec = arr.addObject();
            sec.put("name", s[0]);
            sec.put("content", s[1]);
            ArrayNode ev = sec.putArray("evidence");
            for (String token : extractTokens(s[1])) ev.add(token);
        }
        return root;
    }

    /** 章节名归一化：去除序号前缀与首尾空白（「一、核心结论」→「核心结论」） */
    static String normalizeSectionName(String name) {
        return SECTION_PREFIX.matcher(name.strip()).replaceFirst("").strip();
    }

    private static List<String> extractTokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = EVIDENCE_TOKEN.matcher(text);
        while (m.find()) tokens.add(m.group(1));
        return tokens;
    }

    /** 执行单条 PROGRAM 规则；validEvidenceIds/dataRoot 来自该生成任务冻结的 data.json */
    public RuleVerdict check(Rule rule, ParsedOutput out, Set<String> validEvidenceIds, JsonNode dataRoot) {
        try {
            JsonNode spec = rule.checkSpec();
            String checker = spec.path("checker").asText();
            JsonNode p = spec.path("params");
            return switch (checker) {
                case "json_schema" -> jsonSchema(rule, out, p);
                case "char_length_range" -> charLengthRange(rule, out, p);
                case "narrative_char_length" -> narrativeCharLength(rule, out, p);
                case "section_names_equals" -> sectionNamesEquals(rule, out, p);
                case "section_names_contains" -> sectionNamesContains(rule, out, p);
                case "evidence_ids_valid" -> evidenceIdsValid(rule, out, validEvidenceIds);
                case "markdown_evidence_ids_valid" -> markdownEvidenceIdsValid(rule, out, validEvidenceIds, dataRoot, p);
                case "required_terms" -> requiredTerms(rule, out, p);
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
        if (!out.parsed() || out.fromMarkdown()) return fail(rule, null, "输出无法解析为 JSON 对象");
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
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        String joined = String.join("", targets).replaceAll("\\s+", "");
        int len = joined.codePointCount(0, joined.length());
        int min = p.path("min").asInt(0), max = p.path("max").asInt(Integer.MAX_VALUE);
        return (len >= min && len <= max)
                ? pass(rule, "实际 " + len + " 字")
                : fail(rule, null, "实际 " + len + " 字，要求 [" + min + "," + max + "]");
    }

    /** 叙述性正文字数（K06 口径）：剥离标题行、表格行、证据标识、列表符号与空白后计数 */
    private RuleVerdict narrativeCharLength(Rule rule, ParsedOutput out, JsonNode p) {
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        for (String line : out.wholeText().split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("```")) { inFence = !inFence; continue; }
            if (inFence) continue;
            if (HEADING.matcher(line).matches()) continue;
            if (t.startsWith("|")) continue;
            sb.append(LIST_MARKER.matcher(line).replaceFirst("")).append('\n');
        }
        String cleaned = EVIDENCE_TOKEN.matcher(sb).replaceAll("").replaceAll("\\s+", "");
        int len = cleaned.codePointCount(0, cleaned.length());
        int min = p.path("min").asInt(0), max = p.path("max").asInt(Integer.MAX_VALUE);
        return (len >= min && len <= max)
                ? pass(rule, "叙述正文 " + len + " 字")
                : fail(rule, null, "叙述正文 " + len + " 字，要求 [" + min + "," + max + "]");
    }

    private List<String> sectionNames(ParsedOutput out) {
        List<String> names = new ArrayList<>();
        for (JsonNode s : out.root().path("sections")) names.add(normalizeSectionName(s.path("name").asText()));
        return names;
    }

    private RuleVerdict sectionNamesEquals(Rule rule, ParsedOutput out, JsonNode p) {
        if (!out.parsed()) return fail(rule, null, "输出无结构，无法定位章节");
        List<String> actual = sectionNames(out);
        List<String> expected = new ArrayList<>();
        for (JsonNode e : p.path("expected")) expected.add(e.asText());
        return actual.equals(expected)
                ? pass(rule, null)
                : fail(rule, String.join("、", actual), "章节应为 " + String.join("—", expected));
    }

    /** expected 按序作为章节名序列的子序列出现（允许穿插其他章节） */
    private RuleVerdict sectionNamesContains(Rule rule, ParsedOutput out, JsonNode p) {
        if (!out.parsed()) return fail(rule, null, "输出无结构，无法定位章节");
        List<String> actual = sectionNames(out);
        List<String> expected = new ArrayList<>();
        for (JsonNode e : p.path("expected")) expected.add(e.asText());
        int i = 0;
        for (String name : actual) {
            if (i < expected.size() && expected.get(i).equals(name)) i++;
        }
        return i == expected.size()
                ? pass(rule, null)
                : fail(rule, String.join("、", actual), "缺少或乱序的章节: " + expected.get(i));
    }

    private RuleVerdict evidenceIdsValid(Rule rule, ParsedOutput out, Set<String> validIds) {
        if (!out.parsed() || out.fromMarkdown()) return fail(rule, null, "输出不是 JSON 外壳，无法定位 evidence 数组");
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

    /**
     * Markdown 证据标识校验：
     * mode=ids_valid——全文所有 [标识] 必须真实（无点号 ∈ 合法 ID 全集；带点号能在 data.json 中逐段导航到真实字段）；
     * mode=sections_have_evidence——params.sections 列出的每个章节正文至少含一个合法标识。
     */
    private RuleVerdict markdownEvidenceIdsValid(Rule rule, ParsedOutput out, Set<String> validIds,
                                                 JsonNode dataRoot, JsonNode p) {
        String mode = p.path("mode").asText("ids_valid");
        if ("sections_have_evidence".equals(mode)) {
            if (!out.parsed()) return fail(rule, null, "输出无结构，无法定位章节");
            List<String> problems = new ArrayList<>();
            for (JsonNode sn : p.path("sections")) {
                String wanted = sn.asText();
                String content = null;
                for (JsonNode s : out.root().path("sections")) {
                    if (wanted.equals(normalizeSectionName(s.path("name").asText()))) {
                        content = s.path("content").asText("");
                        break;
                    }
                }
                if (content == null) { problems.add("章节『" + wanted + "』不存在"); continue; }
                boolean hasValid = extractTokens(content).stream().anyMatch(t -> tokenValid(t, validIds, dataRoot));
                if (!hasValid) problems.add("章节『" + wanted + "』未标注任何合法数据标识");
            }
            return problems.isEmpty() ? pass(rule, null)
                    : fail(rule, String.join("；", problems), "章节标注覆盖未通过");
        }
        // ids_valid：全文提取（含表格与标题），去重后逐个验证
        Set<String> tokens = new LinkedHashSet<>(extractTokens(out.wholeText()));
        List<String> bad = tokens.stream().filter(t -> !tokenValid(t, validIds, dataRoot)).toList();
        return bad.isEmpty() ? pass(rule, tokens.isEmpty() ? "未使用数据标识" : "标识 " + tokens.size() + " 个均真实")
                : fail(rule, String.join("、", bad), "出现编造的数据标识或路径");
    }

    /** 标识验证：无点号 → 合法 ID 全集；带点号 → data.json 逐段导航（数字段按数组下标） */
    private static boolean tokenValid(String token, Set<String> validIds, JsonNode dataRoot) {
        if (!token.contains(".")) return validIds.contains(token);
        if (dataRoot == null) return false;
        JsonNode node = dataRoot;
        for (String seg : token.split("\\.")) {
            if (seg.isEmpty()) return false;
            if (node.isArray() && seg.matches("\\d+")) {
                node = node.path(Integer.parseInt(seg));
            } else {
                node = node.path(seg);
            }
            if (node.isMissingNode()) return false;
        }
        return true;
    }

    private RuleVerdict requiredTerms(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        String joined = String.join("\n", targets);
        List<String> missing = new ArrayList<>();
        for (JsonNode t : p.path("terms")) {
            if (!joined.contains(t.asText())) missing.add(t.asText());
        }
        return missing.isEmpty() ? pass(rule, null)
                : fail(rule, String.join("、", missing), "缺少必须出现的内容");
    }

    private RuleVerdict forbiddenTerms(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
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
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
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
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
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
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
        if (targets.isEmpty()) return fail(rule, null, "目标字段不存在: " + p.path("target").asText());
        String suffix = p.path("suffix").asText();
        String last = targets.get(targets.size() - 1).strip();
        return last.endsWith(suffix) ? pass(rule, null)
                : fail(rule, abbreviate(last.substring(Math.max(0, last.length() - 30))), "未以『" + suffix + "』结尾");
    }

    private RuleVerdict termMaxFrequency(Rule rule, ParsedOutput out, JsonNode p) {
        List<String> targets = resolveTarget(p.path("target").asText(), out);
        if (targets == null) return fail(rule, null, "输出无结构，无法定位目标字段");
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

    /** 返回 null=需要结构但不可解析；返回空列表=结构可解析但目标缺失。章节名按归一化后比对 */
    List<String> resolveTarget(String target, ParsedOutput out) {
        if ("whole_text".equals(target)) return List.of(out.wholeText());
        if (!out.parsed()) return null;
        JsonNode r = out.root();
        switch (target) {
            case "$.title": {
                String title = r.path("title").asText("");
                return title.isBlank() ? List.of() : List.of(title);
            }
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
                    String wanted = normalizeSectionName(m.group(1));
                    for (JsonNode s : r.path("sections")) {
                        if (wanted.equals(normalizeSectionName(s.path("name").asText()))) {
                            list.add(s.path("content").asText(""));
                        }
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
