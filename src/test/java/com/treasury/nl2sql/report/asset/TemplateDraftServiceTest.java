package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.llm.LlmClient;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateDraftService.DraftResult;
import com.treasury.nl2sql.report.pipeline.TemplateMatcher;
import com.treasury.nl2sql.report.store.TemplateAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 起草服务九步后处理链单测（P3-T1；FakeLlm 回放 + mock 资产服务，零 DB/LLM/Spring）。
 * 关注点：LLM 不是可信输入，重点验证
 * 1) 非 JSON 重试修复、拒答回流控制
 * 2) 幻觉指标剔除与章节清洗
 * 3) 全幻觉拒绝及 slug/id 冲突兜底
 * 4) 最终校验（keywords/chapterId/长度）与草案结构稳定性
 */
class TemplateDraftServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportAssetService assets = mock(ReportAssetService.class);
    private final TemplateMatcher matcher = mock(TemplateMatcher.class);
    private final TemplateAssetRepository repo = mock(TemplateAssetRepository.class);

    /** 依次回放预设输出的 FakeLlm。 */
    private TemplateDraftService serviceWithLlm(String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        LlmClient fake = messages -> {
            if (queue.isEmpty()) throw new IllegalStateException("FakeLlm 被多余调用");
            return queue.pop();
        };
        return new TemplateDraftService(fake, assets, matcher, repo, mapper);
    }

    TemplateDraftServiceTest() {
        Map<String, MetricDefinition> catalog = new LinkedHashMap<>();
        catalog.put("cny_total_balance", null);
        catalog.put("large_txn_count", null);
        lenient().when(assets.allMetrics()).thenReturn(catalog);
        lenient().when(assets.metricCatalogText()).thenReturn("- cny_total_balance：余额\n- large_txn_count：大额\n");
        ReportTemplateDef weekly = new ReportTemplateDef("treasury-weekly", "司库资金周报",
                List.of("周报"), null, List.of(new ReportTemplateDef.ChapterDef("c1", "一、", List.of("cny_total_balance"), null, null, "g", null, null)));
        lenient().when(assets.template("treasury-weekly")).thenReturn(Optional.of(weekly));
        lenient().when(assets.allTemplates()).thenReturn(List.of(weekly));
        lenient().when(matcher.recall(anyString())).thenReturn(List.of());
        lenient().when(repo.existsById(anyString())).thenReturn(false);
    }

    private static final String GOOD_REPLY = """
        {"template":{"templateId":"fx-risk-weekly","name":"外汇风险周报","keywords":["外汇风险","汇率"],
          "chapters":[{"chapterId":"x9","title":"一、头寸","metrics":["cny_total_balance"],"comparison":null,"guidance":"指引"}]},
         "unresolved":[],"unanswerable":false}""";

    /**
     * 验证合法模型输出可直接过初审并生成可保存草稿（chapterId 已重排）。
     */
    @Test
    void happyPathDraftPassesThrough() {
        DraftResult r = serviceWithLlm(GOOD_REPLY).draft("每周给资金部领导看的外汇风险报告");
        assertEquals("fx-risk-weekly", r.draft().templateId());
        assertEquals("ch1", r.draft().chapters().get(0).chapterId(), "chapterId 服务端重排");
        assertTrue(r.unresolved().isEmpty());
    }

    /**
     * 验证第一轮非 JSON 回复会进入重试链并最终恢复，避免偶发格式抖动误杀 LLM。
     */
    @Test
    void retryChainRecoversFromNonJsonFirstReply() {
        DraftResult r = serviceWithLlm("我觉得可以这样……不是 JSON", GOOD_REPLY)
                .draft("每周给资金部领导看的外汇风险报告");
        assertEquals("fx-risk-weekly", r.draft().templateId());
    }

    /**
     * 验证 unanswerable=true 直接拒答，保留原因透传用于运营提示。
     */
    @Test
    void unanswerableIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceWithLlm("""
                    {"unanswerable":true,"reason":"与资金领域无关"}""")
                        .draft("帮我做一个人力资源月度盘点报告"));
        assertTrue(e.getMessage().contains("与资金领域无关"));
    }

    /**
     * 验证幻觉指标会被剔除到 unresolved，不影响有效章节继续生成。
     */
    @Test
    void hallucinatedMetricGoesToUnresolvedAndChapterSurvives() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fx-w","name":"外汇周报","keywords":["外汇"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance","fx_exposure_ghost"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("外汇周报，要头寸和衍生品敞口");
        assertEquals(List.of("cny_total_balance"), r.draft().chapters().get(0).metrics());
        assertTrue(r.unresolved().stream().anyMatch(u -> u.contains("fx_exposure_ghost")));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("剔除")));
    }

    /**
     * 验证整章仅幻觉指标时，该章节会移除并写入 notes 便于人工核对。
     */
    @Test
    void chapterWithOnlyHallucinationsIsRemoved() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fx-w","name":"外汇周报","keywords":["外汇"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],"guidance":"g"},
                          {"chapterId":"b","title":"二、衍生品","metrics":["ghost1","ghost2"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("外汇周报，要头寸和衍生品敞口分析");
        assertEquals(1, r.draft().chapters().size());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("二、衍生品") && n.contains("移除")));
    }

    /**
     * 验证全部指标幻觉的模板会整体拒绝，避免保存空洞草稿。
     */
    @Test
    void allHallucinationsMeansRefusal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceWithLlm("""
                    {"template":{"templateId":"d","name":"衍生品日报","keywords":["衍生品"],
                      "chapters":[{"chapterId":"a","title":"一、敞口","metrics":["ghost1"],"guidance":"g"}]},
                     "unresolved":[],"unanswerable":false}""")
                        .draft("衍生品敞口日报，只要敞口相关内容"));
        assertTrue(e.getMessage().contains("无法起草"));
        assertTrue(e.getMessage().contains("ghost1"));
    }

    /**
     * 验证中文 templateId 通过 slugify 兜底，确保可持久化 id 符合规范。
     */
    @Test
    void chineseTemplateIdGetsSlugFallbackAndPassesFinalCheck() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"外汇风险周报","name":"外汇风险周报","keywords":["外汇"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每周给资金部领导看的外汇风险报告");
        assertTrue(r.draft().templateId().matches("^[a-z][a-z0-9-]{2,63}$"),
                "slugify 兜底 id 须过保存同款正则: " + r.draft().templateId());
    }

    /**
     * 验证模板 ID 冲突时自动加后缀，不覆盖现有资产。
     */
    @Test
    void idCollisionGetsSuffix() {
        when(repo.existsById("fx-risk-weekly")).thenReturn(true);
        when(repo.existsById("fx-risk-weekly-2")).thenReturn(false);
        DraftResult r = serviceWithLlm(GOOD_REPLY).draft("每周给资金部领导看的外汇风险报告");
        assertEquals("fx-risk-weekly-2", r.draft().templateId());
    }

    /**
     * 验证输入描述过短直接 fail-closed，不应触发不必要的 LLM 调用。
     */
    @Test
    void tooShortDescriptionRejectedWithoutLlmCall() {
        TemplateDraftService svc = serviceWithLlm();   // 队列为空——若调 LLM 会炸，证明未调用
        assertThrows(IllegalArgumentException.class, () -> svc.draft("周报"));
    }

    /**
     * 验证扩展 schema：LLM 输出的 periodTypes 与 comparisons 数组原样进入草案，
     * 且草案只写新字段 comparisons（旧单值 comparison 恒为 null，校验器禁双字段同填）。
     */
    @Test
    void periodTypesAndComparisonsParsedThrough() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fund-monthly","name":"资金月报","keywords":["月报"],
              "periodTypes":["MONTH"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],
                "comparisons":["month_over_month","year_over_year"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每月给资金部领导看的资金月报，要环比和同比");
        assertEquals(List.of("MONTH"), r.draft().periodTypes());
        ReportTemplateDef.ChapterDef ch = r.draft().chapters().get(0);
        assertNull(ch.comparison(), "草案不写旧单值字段");
        assertEquals(List.of("month_over_month", "year_over_year"), ch.comparisons());
    }

    /**
     * 验证未知比较 token 剔除转 notes（降噪不整案拒绝），合法 token 保留。
     */
    @Test
    void unknownComparisonTokenRemovedWithNote() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fx-w","name":"外汇周报","keywords":["外汇"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],
                "comparisons":["day_over_day","week_over_week"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每周给资金部领导看的外汇风险报告");
        assertEquals(List.of("week_over_week"), r.draft().chapters().get(0).comparisons());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("day_over_day") && n.contains("未知比较类型")));
    }

    /**
     * 验证与周期粒度矩阵不符的比较剔除（月报模板里 wow 不合法，yoy 保留）——同校验器规则，
     * 单个幻觉 token 不再导致整个草案被终检毙掉。
     */
    @Test
    void granularityMismatchedComparisonRemoved() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fund-m","name":"资金月报","keywords":["月报"],
              "periodTypes":["MONTH"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],
                "comparisons":["week_over_week","year_over_year"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每月给资金部领导看的资金月报");
        assertEquals(List.of("year_over_year"), r.draft().chapters().get(0).comparisons());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("week_over_week") && n.contains("不匹配")));
    }

    /**
     * 验证非法 periodTypes 值剔除转 notes；剔完为空 → null（保持缺省 WEEK 语义不显式化）。
     */
    @Test
    void illegalPeriodTypeRemovedAndEmptyBecomesNull() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fx-w","name":"外汇周报","keywords":["外汇"],
              "periodTypes":["DAILY"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],"guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每周给资金部领导看的外汇风险报告");
        assertNull(r.draft().periodTypes(), "非法值剔完为空须回落 null 缺省");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("DAILY")));
    }

    /**
     * 验证 LLM 仍按旧 schema 输出单值 comparison 时自动迁移进 comparisons 新字段。
     */
    @Test
    void legacySingleComparisonFieldMigrated() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"fx-w","name":"外汇周报","keywords":["外汇"],
              "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],
                "comparison":"week_over_week","guidance":"g"}]},
             "unresolved":[],"unanswerable":false}""")
                .draft("每周给资金部领导看的外汇风险报告");
        ReportTemplateDef.ChapterDef ch = r.draft().chapters().get(0);
        assertNull(ch.comparison());
        assertEquals(List.of("week_over_week"), ch.comparisons());
    }

    // ---------- Gate4：历史报告导入模式 ----------

    /** 捕获送入 LLM 的对话（校验 prompt 组装），并回放预设输出。 */
    private TemplateDraftService serviceCapturing(List<List<LlmClient.Message>> captured, String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        LlmClient fake = messages -> {
            captured.add(List.copyOf(messages));
            if (queue.isEmpty()) throw new IllegalStateException("FakeLlm 被多余调用");
            return queue.pop();
        };
        return new TemplateDraftService(fake, assets, matcher, repo, mapper);
    }

    private static String longReport(int minLen) {
        StringBuilder sb = new StringBuilder("2026年第26周司库资金周报\n一、核心结论\n本周人民币账户余额合计保持平稳，交易总额较上周小幅上升。\n");
        while (sb.length() < minLen) sb.append("二、交易与收支\n本周交易活跃，净流入为正，工资发放与税款缴纳正常执行。\n");
        return sb.toString();
    }

    /**
     * 验证 report 模式 prompt 组装：含指标目录/禁止编造/比较措辞映射规则，不含场景模式的 few-shot 段；
     * user 段携带报告全文。
     */
    @Test
    void reportModePromptSkipsFewShotAndCarriesRules() {
        List<List<LlmClient.Message>> captured = new ArrayList<>();
        serviceCapturing(captured, GOOD_REPLY).draftFromReport(longReport(200));
        String system = captured.get(0).get(0).content();
        assertTrue(system.contains("可用指标目录"));
        assertTrue(system.contains("禁止发明目录外的指标 id"));
        assertTrue(system.contains("较去年同期"), "含比较措辞映射规则");
        assertFalse(system.contains("既有模板示例"), "report 模式不含 few-shot 段");
        assertTrue(captured.get(0).get(1).content().contains("历史报告全文"));
    }

    /**
     * 验证报告全文过短（<100 字）失败关闭且不烧 LLM。
     */
    @Test
    void tooShortReportRejectedWithoutLlmCall() {
        TemplateDraftService svc = serviceWithLlm();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.draftFromReport("资金周报：本周一切正常。"));
        assertTrue(e.getMessage().contains("过短"));
    }

    /**
     * 验证超长报告截断 + note 提示（不失败关闭），送入 LLM 的文本不超过上限。
     */
    @Test
    void overlongReportTruncatedWithNote() {
        List<List<LlmClient.Message>> captured = new ArrayList<>();
        DraftResult r = serviceCapturing(captured, GOOD_REPLY)
                .draftFromReport(longReport(TemplateDraftService.REPORT_TEXT_MAX + 3000));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("截断")));
        String user = captured.get(0).get(1).content();
        assertTrue(user.length() < TemplateDraftService.REPORT_TEXT_MAX + 200, "送 LLM 的正文须已截断");
    }

    /**
     * 验证 report 模式走同一条后处理链：幻觉指标剔除转 unresolved、unanswerable 失败关闭。
     */
    @Test
    void reportModeSharesPostProcessChain() {
        DraftResult r = serviceWithLlm("""
            {"template":{"templateId":"weekly-x","name":"资金周报","keywords":["周报"],
              "chapters":[{"chapterId":"a","title":"一、核心结论","metrics":["cny_total_balance","ghost_metric"],"guidance":"g"}]},
             "unresolved":["外币衍生品敞口 1.2 亿元"],"unanswerable":false}""")
                .draftFromReport(longReport(200));
        assertTrue(r.unresolved().stream().anyMatch(u -> u.contains("ghost_metric")));
        assertTrue(r.unresolved().stream().anyMatch(u -> u.contains("外币衍生品敞口")));

        assertThrows(IllegalArgumentException.class, () -> serviceWithLlm("""
            {"unanswerable":true,"reason":"粘贴内容是聊天记录，不是报告"}""")
                .draftFromReport(longReport(200)));
    }

    /**
     * 验证终检规则（keywords/chapterId/长度）不满足时抛出可解释的字段定位错误。
     */
    @Test
    void invalidDraftFailsFinalValidation() {
        // keywords 为空 → 终检 ValidationFailedException（details 带 location）
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> serviceWithLlm("""
                    {"template":{"templateId":"fx-w","name":"外汇周报","keywords":[],
                      "chapters":[{"chapterId":"a","title":"一、头寸","metrics":["cny_total_balance"],"guidance":"g"}]},
                     "unresolved":[],"unanswerable":false}""")
                        .draft("每周给资金部领导看的外汇风险报告"));
        assertTrue(e.errors().stream().anyMatch(x -> x.location().equals("keywords")));
    }
}
