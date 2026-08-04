package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 程序校验器纯逻辑单测（无需 DB/LLM）：覆盖 JSON 与 Markdown 双载体。 */
class ExpeProgramCheckerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpeProgramChecker checker = new ExpeProgramChecker(mapper);

    private static final String GOOD_JSON = """
            {"title":"海州市2026年二季度经济运行分析",
             "summary":"二季度全市经济平稳增长，先进制造业与数字经济带动明显，房地产投资与出口下行压力需关注，总体运行处于合理区间，结构持续优化改善向好。",
             "sections":[
               {"name":"总体情况","content":"全市地区生产总值保持平稳。","evidence":["IND_GDP"]},
               {"name":"结构变化","content":"先进制造业带动明显。","evidence":["INDUSTRY_ADVANCED_MANUFACTURING"]},
               {"name":"重点问题","content":"房地产投资下行。需持续关注。","evidence":["RISK_REAL_ESTATE"]},
               {"name":"工作建议","content":"建议稳定预期；加快项目建设","evidence":["IND_FIXED_ASSET_INVESTMENT"]}
             ]}""";

    /** 对齐 v2.0 交付载体的 Markdown 简报样例（含序号章节名、表格、证据标识、JSONPath） */
    private static final String GOOD_MD = """
            # 关于海州市 2026 年第二季度经济运行情况的分析报告

            ## 一、核心结论

            全市经济基本盘稳固。地区生产总值3286.50亿元，同比增长5.00%[IND_GDP]。

            ## 二、总体运行态势

            经济运行总体平稳。一般公共预算收入286.40亿元[IND_FISCAL_REVENUE]，收支差额-55.80亿元[fiscal_structure.revenue_expenditure_gap]。

            ## 三、增长动能与区域结构

            先进制造业带动明显[INDUSTRY_ADVANCED_MANUFACTURING]。

            | 行业名称 | 增加值 |
            |---|---|
            | 先进制造业 | 692.40 |

            ## 四、突出问题与风险

            - 房地产开发投资下降8.61%[IND_REAL_ESTATE_INVESTMENT][RISK_REAL_ESTATE]。

            上述问题需持续关注。

            ## 五、数据质量与口径说明

            民间投资本期值缺失。本报告结论以上述数据边界为准。
            """;

    private static final Set<String> VALID_IDS = Set.of(
            "IND_GDP", "IND_FISCAL_REVENUE", "INDUSTRY_ADVANCED_MANUFACTURING", "RISK_REAL_ESTATE",
            "IND_REAL_ESTATE_INVESTMENT", "IND_FIXED_ASSET_INVESTMENT");

    private final JsonNode dataRoot;

    ExpeProgramCheckerTest() throws Exception {
        dataRoot = mapper.readTree("""
                {"fiscal_structure":{"revenue_expenditure_gap":-55.80},
                 "data_quality":[{"field_name":"民间投资总额","status":"本期数据暂缺"}]}""");
    }

    private Rule rule(String checkerName, String paramsJson) throws Exception {
        return new Rule("R-T-001", "测试规则", "P2", true, "A", 2, "PROGRAM", "测试",
                List.of(), mapper.readTree("{\"checker\":\"" + checkerName + "\",\"params\":" + paramsJson + "}"));
    }

    private RuleVerdict check(Rule r, ExpeProgramChecker.ParsedOutput out) {
        return checker.check(r, out, VALID_IDS, dataRoot);
    }

    // ---------- JSON 载体（兼容保留） ----------

    @Test
    void jsonSchema_validPasses_fenceTolerated_markdownAndGarbageFail() throws Exception {
        Rule r = rule("json_schema", "{}");
        assertEquals("PASS", check(r, checker.parse(GOOD_JSON)).verdict());
        assertEquals("PASS", check(r, checker.parse("```json\n" + GOOD_JSON + "\n```")).verdict());
        assertEquals("FAIL", check(r, checker.parse("这是一段纯文本，不是 JSON")).verdict());
        // Markdown 结构树不得骗过 JSON 外壳校验
        assertEquals("FAIL", check(r, checker.parse(GOOD_MD)).verdict());
        Rule strict = rule("json_schema", "{\"allow_fence\":false}");
        assertEquals("FAIL", check(strict, checker.parse("```json\n" + GOOD_JSON + "\n```")).verdict());
    }

    @Test
    void charLengthRange_onSummaryAndWholeText() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", check(
                rule("char_length_range", "{\"target\":\"$.summary\",\"min\":50,\"max\":120}"), parsed).verdict());
        RuleVerdict tooLong = check(
                rule("char_length_range", "{\"target\":\"$.summary\",\"min\":1,\"max\":10}"), parsed);
        assertEquals("FAIL", tooLong.verdict());
        assertTrue(tooLong.reason().contains("实际"));
        assertEquals("PASS", check(
                rule("char_length_range", "{\"target\":\"whole_text\",\"min\":1,\"max\":100}"),
                checker.parse("十个字的纯文本内容啊")).verdict());
        // Markdown 无 summary 字段 → 目标缺失 FAIL
        assertEquals("FAIL", check(
                rule("char_length_range", "{\"target\":\"$.summary\",\"min\":1,\"max\":100}"),
                checker.parse(GOOD_MD)).verdict());
    }

    @Test
    void forbiddenTerms_requiredRegex_frequency() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", check(rule("forbidden_terms",
                "{\"target\":\"whole_text\",\"terms\":[\"众所周知\"]}"), parsed).verdict());
        RuleVerdict hit = check(rule("forbidden_terms",
                "{\"target\":\"whole_text\",\"terms\":[\"平稳\"]}"), parsed);
        assertEquals("FAIL", hit.verdict());
        assertEquals("平稳", hit.evidence());
        assertEquals("PASS", check(rule("term_max_frequency",
                "{\"target\":\"whole_text\",\"terms\":[\"增长\"],\"max\":6}"), parsed).verdict());
        assertEquals("FAIL", check(rule("term_max_frequency",
                "{\"target\":\"whole_text\",\"terms\":[\"，\"],\"max\":1}"), parsed).verdict());
    }

    @Test
    void startsWithAny_endsWith_onNamedSection() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", check(rule("starts_with_any",
                "{\"target\":\"$.sections[?name='工作建议'].content\",\"options\":[\"建议\",\"加快\",\"强化\",\"推动\"]}"),
                parsed).verdict());
        RuleVerdict badStart = check(rule("starts_with_any",
                "{\"target\":\"$.sections[?name='工作建议'].content\",\"options\":[\"强化\"]}"), parsed);
        assertEquals("FAIL", badStart.verdict());
        assertEquals("PASS", check(rule("ends_with",
                "{\"target\":\"$.sections[?name='重点问题'].content\",\"suffix\":\"需持续关注。\"}"), parsed).verdict());
        assertEquals("FAIL", check(rule("ends_with",
                "{\"target\":\"$.sections[?name='不存在'].content\",\"suffix\":\"x\"}"), parsed).verdict());
    }

    @Test
    void unparsedText_withoutHeadings_structTargetsFail_wholeTextStillWorks() throws Exception {
        var garbage = checker.parse("非 JSON 输出，也没有任何标题");
        assertFalse(garbage.parsed());
        assertEquals("FAIL", check(rule("section_names_equals", "{\"expected\":[\"总体情况\"]}"), garbage).verdict());
        assertEquals("FAIL", check(rule("evidence_ids_valid", "{}"), garbage).verdict());
        assertEquals("PASS", check(
                rule("forbidden_terms", "{\"target\":\"whole_text\",\"terms\":[\"众所周知\"]}"), garbage).verdict());
    }

    // ---------- Markdown 载体（v2.0 主路径） ----------

    @Test
    void markdown_parsedIntoStructure_titleAndNormalizedSections() throws Exception {
        var parsed = checker.parse(GOOD_MD);
        assertTrue(parsed.parsed());
        assertTrue(parsed.fromMarkdown());
        // 标题定位 + 章节名去序号归一化
        assertEquals("PASS", check(rule("required_regex",
                "{\"target\":\"$.title\",\"pattern\":\"关于海州市\\\\s*2026\\\\s*年第二季度经济运行情况的分析报告\"}"),
                parsed).verdict());
        assertEquals("PASS", check(rule("section_names_equals",
                "{\"expected\":[\"核心结论\",\"总体运行态势\",\"增长动能与区域结构\",\"突出问题与风险\",\"数据质量与口径说明\"]}"),
                parsed).verdict());
        // 命名章节定位（归一化后）
        assertEquals("PASS", check(rule("ends_with",
                "{\"target\":\"$.sections[?name='数据质量与口径说明'].content\",\"suffix\":\"本报告结论以上述数据边界为准。\"}"),
                parsed).verdict());
        assertEquals("PASS", check(rule("ends_with",
                "{\"target\":\"$.sections[?name='突出问题与风险'].content\",\"suffix\":\"需持续关注。\"}"),
                parsed).verdict());
    }

    @Test
    void sectionNamesContains_subsequenceSemantics() throws Exception {
        var parsed = checker.parse(GOOD_MD);
        // 三个业务章节作为五章序列的子序列 → PASS
        assertEquals("PASS", check(rule("section_names_contains",
                "{\"expected\":[\"总体运行态势\",\"增长动能与区域结构\",\"突出问题与风险\"]}"), parsed).verdict());
        // 乱序 → FAIL
        assertEquals("FAIL", check(rule("section_names_contains",
                "{\"expected\":[\"突出问题与风险\",\"总体运行态势\"]}"), parsed).verdict());
        assertEquals("FAIL", check(rule("section_names_contains",
                "{\"expected\":[\"工作建议\"]}"), parsed).verdict());
    }

    @Test
    void requiredTerms_onWholeTextAndNamedSection() throws Exception {
        var parsed = checker.parse(GOOD_MD);
        assertEquals("PASS", check(rule("required_terms",
                "{\"target\":\"whole_text\",\"terms\":[\"先进制造业\",\"[fiscal_structure.\"]}"), parsed).verdict());
        RuleVerdict missing = check(rule("required_terms",
                "{\"target\":\"$.sections[?name='总体运行态势'].content\",\"terms\":[\"一般公共预算收入\",\"社会消费品零售总额\"]}"),
                parsed);
        assertEquals("FAIL", missing.verdict());
        assertEquals("社会消费品零售总额", missing.evidence());
    }

    @Test
    void markdownEvidenceIds_validAndFabricatedAndPath() throws Exception {
        // 全部标识真实（含 JSONPath 导航）→ PASS
        assertEquals("PASS", check(rule("markdown_evidence_ids_valid", "{\"mode\":\"ids_valid\"}"),
                checker.parse(GOOD_MD)).verdict());
        // 编造 ID 与编造路径 → FAIL 并点名
        RuleVerdict bad = check(rule("markdown_evidence_ids_valid", "{\"mode\":\"ids_valid\"}"),
                checker.parse("# 报告\n\n## 总体运行态势\n\n增长[IND_FAKE]，差额[fiscal_structure.not_exist]。"));
        assertEquals("FAIL", bad.verdict());
        assertTrue(bad.evidence().contains("IND_FAKE"));
        assertTrue(bad.evidence().contains("fiscal_structure.not_exist"));
        // 数组下标路径可导航
        assertEquals("PASS", check(rule("markdown_evidence_ids_valid", "{\"mode\":\"ids_valid\"}"),
                checker.parse("# 报告\n\n## 总体运行态势\n\n缺失[data_quality.0.status]。")).verdict());
    }

    @Test
    void markdownEvidenceIds_sectionCoverage() throws Exception {
        var parsed = checker.parse(GOOD_MD);
        assertEquals("PASS", check(rule("markdown_evidence_ids_valid",
                "{\"mode\":\"sections_have_evidence\",\"sections\":[\"总体运行态势\",\"增长动能与区域结构\",\"突出问题与风险\"]}"),
                parsed).verdict());
        // 数据质量章无标识 → 该章覆盖 FAIL
        RuleVerdict v = check(rule("markdown_evidence_ids_valid",
                "{\"mode\":\"sections_have_evidence\",\"sections\":[\"数据质量与口径说明\"]}"), parsed);
        assertEquals("FAIL", v.verdict());
        assertTrue(v.evidence().contains("数据质量与口径说明"));
    }

    @Test
    void narrativeCharLength_stripsHeadingsTablesIdsAndListMarkers() throws Exception {
        var parsed = checker.parse(GOOD_MD);
        // GOOD_MD 叙述正文远低于 1000 → 下限 FAIL；理由含实际字数
        RuleVerdict small = check(rule("narrative_char_length", "{\"min\":1000,\"max\":1500}"), parsed);
        assertEquals("FAIL", small.verdict());
        assertTrue(small.reason().contains("叙述正文"));
        // 宽区间 PASS，且剥离生效：全文含表格/标题/ID 时计数应显著小于原文长度
        assertEquals("PASS", check(rule("narrative_char_length", "{\"min\":10,\"max\":1000}"), parsed).verdict());
    }

    @Test
    void ruleSetParse_templateFileShape() throws Exception {
        // 模板自身必须能被解析器接受（结构守护，防止模板与解析器漂移）
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("expe/RULES_TEMPLATE.json"));
        ExpeRuleSet rs = ExpeRuleSet.parse(bytes, mapper);
        assertEquals(52, rs.rules().size());
        assertEquals(6, rs.applicableTo("A").size());                // K
        assertEquals(15, rs.applicableTo("B").size());               // K+H
        assertEquals(15, rs.applicableTo("B-Pad").size());
        assertEquals(33, rs.applicableTo("C").size());               // K+H+M
        assertEquals(45, rs.applicableTo("D").size());               // K+H+M+L
        assertEquals(45, rs.applicableTo("D-Clean").size());
        // D-Conflict 提示词不含 L 层文本：K+H+M+DC，不含 D 层
        assertEquals(40, rs.applicableTo("D-Conflict").size());
        assertTrue(rs.applicableTo("D-Conflict").stream().noneMatch(r -> "D".equals(r.introducedIn())));
        assertEquals(0, rs.introducedAtTop("A").size());
        assertEquals(9, rs.introducedAtTop("B").size());
        assertEquals(18, rs.introducedAtTop("C").size());
        assertEquals(12, rs.introducedAtTop("D").size());
        assertEquals(7, rs.introducedAtTop("D-Conflict").size());
        // 共享核心 = 且仅 = K 层（跨组统一标尺的地基）
        assertTrue(rs.rules().stream().allMatch(r -> r.shared() == "A".equals(r.introducedIn())));
    }
}
