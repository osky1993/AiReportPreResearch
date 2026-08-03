package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 程序校验器纯逻辑单测（无需 DB/LLM）。 */
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

    private static final Set<String> VALID_IDS = Set.of(
            "IND_GDP", "INDUSTRY_ADVANCED_MANUFACTURING", "RISK_REAL_ESTATE", "IND_FIXED_ASSET_INVESTMENT");

    private Rule rule(String checkerName, String paramsJson) throws Exception {
        return new Rule("R-T-001", "测试规则", "P2", true, "A", 2, "PROGRAM", "测试",
                List.of(), mapper.readTree("{\"checker\":\"" + checkerName + "\",\"params\":" + paramsJson + "}"));
    }

    @Test
    void jsonSchema_validPasses_fenceTolerated_garbageFails() throws Exception {
        Rule r = rule("json_schema", "{}");
        assertEquals("PASS", checker.check(r, checker.parse(GOOD_JSON), VALID_IDS).verdict());
        assertEquals("PASS", checker.check(r, checker.parse("```json\n" + GOOD_JSON + "\n```"), VALID_IDS).verdict());
        assertEquals("FAIL", checker.check(r, checker.parse("这是一段纯文本，不是 JSON"), VALID_IDS).verdict());
        // allow_fence=false 时围栏包裹判罚
        Rule strict = rule("json_schema", "{\"allow_fence\":false}");
        assertEquals("FAIL", checker.check(strict, checker.parse("```json\n" + GOOD_JSON + "\n```"), VALID_IDS).verdict());
    }

    @Test
    void charLengthRange_onSummaryAndWholeText() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", checker.check(
                rule("char_length_range", "{\"target\":\"$.summary\",\"min\":50,\"max\":120}"), parsed, VALID_IDS).verdict());
        RuleVerdict tooLong = checker.check(
                rule("char_length_range", "{\"target\":\"$.summary\",\"min\":1,\"max\":10}"), parsed, VALID_IDS);
        assertEquals("FAIL", tooLong.verdict());
        assertTrue(tooLong.reason().contains("实际"));
        // JSON 不可解析时 whole_text 仍可计数
        assertEquals("PASS", checker.check(
                rule("char_length_range", "{\"target\":\"whole_text\",\"min\":1,\"max\":100}"),
                checker.parse("十个字的纯文本内容啊"), VALID_IDS).verdict());
    }

    @Test
    void sectionNames_andEvidenceIds() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", checker.check(rule("section_names_equals",
                "{\"expected\":[\"总体情况\",\"结构变化\",\"重点问题\",\"工作建议\"]}"), parsed, VALID_IDS).verdict());
        assertEquals("FAIL", checker.check(rule("section_names_equals",
                "{\"expected\":[\"总体情况\",\"工作建议\"]}"), parsed, VALID_IDS).verdict());
        assertEquals("PASS", checker.check(rule("evidence_ids_valid", "{}"), parsed, VALID_IDS).verdict());
        // 引用不存在的 ID
        RuleVerdict bad = checker.check(rule("evidence_ids_valid", "{}"), parsed, Set.of("IND_GDP"));
        assertEquals("FAIL", bad.verdict());
        assertTrue(bad.evidence().contains("不存在的 ID"));
    }

    @Test
    void forbiddenTerms_requiredRegex_frequency() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", checker.check(rule("forbidden_terms",
                "{\"target\":\"whole_text\",\"terms\":[\"众所周知\"]}"), parsed, VALID_IDS).verdict());
        RuleVerdict hit = checker.check(rule("forbidden_terms",
                "{\"target\":\"whole_text\",\"terms\":[\"平稳\"]}"), parsed, VALID_IDS);
        assertEquals("FAIL", hit.verdict());
        assertEquals("平稳", hit.evidence());
        assertEquals("PASS", checker.check(rule("required_regex",
                "{\"target\":\"$.title\",\"pattern\":\"海州市.*2026年.*(二季度|第二季度).*经济运行分析\"}"), parsed, VALID_IDS).verdict());
        assertEquals("PASS", checker.check(rule("term_max_frequency",
                "{\"target\":\"whole_text\",\"terms\":[\"增长\"],\"max\":6}"), parsed, VALID_IDS).verdict());
        assertEquals("FAIL", checker.check(rule("term_max_frequency",
                "{\"target\":\"whole_text\",\"terms\":[\"，\"],\"max\":1}"), parsed, VALID_IDS).verdict());
    }

    @Test
    void startsWithAny_endsWith_onNamedSection() throws Exception {
        var parsed = checker.parse(GOOD_JSON);
        assertEquals("PASS", checker.check(rule("starts_with_any",
                "{\"target\":\"$.sections[?name='工作建议'].content\",\"options\":[\"建议\",\"加快\",\"强化\",\"推动\"]}"),
                parsed, VALID_IDS).verdict());
        RuleVerdict badStart = checker.check(rule("starts_with_any",
                "{\"target\":\"$.sections[?name='工作建议'].content\",\"options\":[\"强化\"]}"), parsed, VALID_IDS);
        assertEquals("FAIL", badStart.verdict());
        assertEquals("PASS", checker.check(rule("ends_with",
                "{\"target\":\"$.sections[?name='重点问题'].content\",\"suffix\":\"需持续关注。\"}"), parsed, VALID_IDS).verdict());
        // 目标章节不存在 → FAIL 而非异常
        assertEquals("FAIL", checker.check(rule("ends_with",
                "{\"target\":\"$.sections[?name='不存在'].content\",\"suffix\":\"x\"}"), parsed, VALID_IDS).verdict());
    }

    @Test
    void unparsedJson_structTargetsFail_wholeTextStillWorks() throws Exception {
        var garbage = checker.parse("非 JSON 输出");
        RuleVerdict v = checker.check(rule("section_names_equals", "{\"expected\":[\"总体情况\"]}"), garbage, VALID_IDS);
        assertEquals("FAIL", v.verdict());
        assertFalse(garbage.parsed());
        assertEquals("FAIL", checker.check(rule("evidence_ids_valid", "{}"), garbage, VALID_IDS).verdict());
    }

    @Test
    void ruleSetParse_templateFileShape() throws Exception {
        // 模板自身必须能被解析器接受（结构守护，防止模板与解析器漂移）
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("expe/RULES_TEMPLATE.json"));
        ExpeRuleSet rs = ExpeRuleSet.parse(bytes, mapper);
        assertEquals(24, rs.rules().size());
        assertEquals(8, rs.applicableTo("A").size());
        assertEquals(13, rs.applicableTo("B").size());
        assertEquals(13, rs.applicableTo("B-Pad").size());
        assertEquals(19, rs.applicableTo("C").size());
        assertEquals(22, rs.applicableTo("D").size());
        assertEquals(22, rs.applicableTo("D-Clean").size());
        assertEquals(24, rs.applicableTo("D-Conflict").size());
        assertEquals(0, rs.introducedAtTop("A").size());
        assertEquals(5, rs.introducedAtTop("B").size());
        assertEquals(3, rs.introducedAtTop("D").size());
        assertEquals(2, rs.introducedAtTop("D-Conflict").size());
    }
}
