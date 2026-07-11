package com.treasury.nl2sql.report.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import com.treasury.nl2sql.report.pipeline.FactBuildStep;
import com.treasury.nl2sql.report.pipeline.FetchStep;
import com.treasury.nl2sql.report.pipeline.OutlineStep;
import com.treasury.nl2sql.report.pipeline.PeriodResolver;
import com.treasury.nl2sql.report.pipeline.PolicyException;
import com.treasury.nl2sql.report.pipeline.SpecResolveStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告层回归评测（P2-T7，范式照底座 EvalService）。金标准 = report/eval/golden-set.json，
 * 其中 FACTS 期望值由手写 SQL 直查得出（纪律 7：期望值不得由被评测系统自产自证）。
 * 分层运行、只读（不写任何状态表，不创建 run）：
 *  - 确定性层 DETERMINISTIC：对每个 FACTS case 跑 ②③④（模板→spec→SQL→facts），
 *    BASE 事实逐值与期望比对——值不一致即失败；产物 sql_hash 随报告输出作复现记录
 *    （契约3：值定成败，hash 供追踪、变化仅告警不打红）。需 DB、零 LLM。
 *  - LLM 层 LLM：对 MATCH/BLOCKED case 跑 ①，产出模板匹配正确率与失败关闭正确率。
 *    BLOCKED 判据 = ① 抛 PolicyException（失败关闭而非硬套模板）；烧 token，手动触发。
 */
@Service
public class ReportEvalService {

    private static final Logger log = LoggerFactory.getLogger(ReportEvalService.class);
    /** 值比对容差：期望值来自手写 SQL 的精确小数，仅容浮点噪声。 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final ObjectMapper mapper;
    private final ReportAssetService assets;
    private final OutlineStep outlineStep;
    private final SpecResolveStep specStep;
    private final FetchStep fetchStep;
    private final FactBuildStep factStep;

    public ReportEvalService(ObjectMapper mapper, ReportAssetService assets, OutlineStep outlineStep,
                             SpecResolveStep specStep, FetchStep fetchStep, FactBuildStep factStep) {
        this.mapper = mapper;
        this.assets = assets;
        this.outlineStep = outlineStep;
        this.specStep = specStep;
        this.fetchStep = fetchStep;
        this.factStep = factStep;
    }

    public record ItemResult(String what, boolean pass, String detail) {}
    public record CaseResult(String id, String type, boolean pass, List<ItemResult> items) {}
    public record EvalReport(String layer, int caseTotal, int casePassed,
                             Map<String, String> rates, List<CaseResult> cases) {}

    // ---------- 确定性层：②③④ 取数等价率 ----------

    public EvalReport runDeterministic() {
        List<CaseResult> results = new ArrayList<>();
        int expectationTotal = 0;
        int expectationPassed = 0;
        for (JsonNode c : goldenCases("FACTS")) {
            CaseResult r = runFactsCase(c);
            results.add(r);
            expectationTotal += r.items().size();
            expectationPassed += (int) r.items().stream().filter(ItemResult::pass).count();
        }
        int casePassed = (int) results.stream().filter(CaseResult::pass).count();
        Map<String, String> rates = new LinkedHashMap<>();
        rates.put("取数等价率(期望值粒度)", rate(expectationPassed, expectationTotal));
        rates.put("取数等价率(case 粒度)", rate(casePassed, results.size()));
        log.info("[EVAL] 确定性层完成: case {}/{}，期望值 {}/{}", casePassed, results.size(),
                expectationPassed, expectationTotal);
        return new EvalReport("DETERMINISTIC", results.size(), casePassed, rates, results);
    }

    private CaseResult runFactsCase(JsonNode c) {
        String id = c.path("id").asText();
        String templateId = c.path("templateId").asText();
        String periodLabel = c.path("periodLabel").asText();
        List<ItemResult> items = new ArrayList<>();
        try {
            ReportTemplateDef tpl = assets.template(templateId)
                    .orElseThrow(() -> new IllegalStateException("模板不在注册表: " + templateId));
            Outline outline = outlineOf(tpl, periodLabel);
            PeriodResolver.Window current = PeriodResolver.resolve(periodLabel);
            // 基期窗口组装与 ReportPipeline.runAsync 同款：环比无条件、同比按模板声明
            Map<String, PeriodResolver.Window> compareWindows = new LinkedHashMap<>();
            compareWindows.put(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(current));
            if (SpecResolveStep.requiredComparePurposes(outline)
                    .contains(MetricQuerySpec.PURPOSE_COMPARE_YOY)) {
                compareWindows.put(MetricQuerySpec.PURPOSE_COMPARE_YOY, PeriodResolver.sameLastYear(current));
            }
            Map<String, MetricDefinition> defs = assets.allMetrics();   // 评测对象 = 现役 PUBLISHED 资产
            List<MetricQuerySpec> specs = specStep.run(outline, current, compareWindows, defs);
            List<FetchStep.FetchResult> fetched = fetchStep.run(specs, defs);
            FactBuildStep.FactBuildResult built = factStep.run(outline, fetched, defs);

            // BASE 事实 → (metricId|purpose[|维度])，维度指标一行一键（Phase03：键维度化，行不互相覆盖）
            Map<String, BigDecimal> produced = new LinkedHashMap<>();
            Map<String, String> producedHash = new LinkedHashMap<>();
            for (var f : built.facts()) {
                if (!"BASE".equals(f.factType())) continue;
                MetricQuerySpec spec = mapper.readValue(f.specJson(), MetricQuerySpec.class);
                String key = expectationKey(f.metricId(), spec.purpose(), f.dimensions());
                produced.put(key, f.value());
                producedHash.put(key, f.sqlHash());
            }
            for (JsonNode e : c.path("expected")) {
                Map<String, String> dims = null;
                if (e.has("dimensions")) {
                    dims = new LinkedHashMap<>();
                    var it = e.path("dimensions").fields();
                    while (it.hasNext()) {
                        var kv = it.next();
                        dims.put(kv.getKey(), kv.getValue().asText());
                    }
                }
                String key = expectationKey(e.path("metricId").asText(), e.path("purpose").asText(), dims);
                BigDecimal expected = new BigDecimal(e.path("value").asText());
                BigDecimal actual = produced.remove(key);
                if (actual == null) {
                    items.add(new ItemResult(key, false, "期望的取数事实未产生"));
                } else if (expected.subtract(actual).abs().compareTo(TOLERANCE) <= 0) {
                    items.add(new ItemResult(key, true, "值 " + actual.stripTrailingZeros().toPlainString()
                            + " = 期望；sql_hash=" + shortHash(producedHash.get(key))));
                } else {
                    items.add(new ItemResult(key, false, "值 " + actual + " ≠ 手写 SQL 期望 " + expected));
                }
            }
            // 系统多产出的取数事实（期望清单没有）也算失败——覆盖面必须精确对齐
            for (var extra : produced.entrySet()) {
                items.add(new ItemResult(extra.getKey(), false, "产生了期望清单之外的取数事实，值 " + extra.getValue()));
            }
        } catch (Exception ex) {
            items.add(new ItemResult("case", false, "执行异常: " + ex.getMessage()));
        }
        boolean pass = !items.isEmpty() && items.stream().allMatch(ItemResult::pass);
        return new CaseResult(id, "FACTS", pass, items);
    }

    /** 比对键：metricId|purpose[|k=v,...]——维度取值排序后拼接，维度行一行一键（无维度与 Phase02 键逐字节相同）。 */
    static String expectationKey(String metricId, String purpose, Map<String, String> dimensions) {
        StringBuilder sb = new StringBuilder(metricId).append('|').append(purpose);
        if (dimensions != null && !dimensions.isEmpty()) {
            sb.append('|').append(dimensions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        return sb.toString();
    }

    /** 确定性大纲 = 模板全章节全指标（等价于卡点1 不做删减直接确认）。 */
    private static Outline outlineOf(ReportTemplateDef tpl, String periodLabel) {
        List<Outline.OutlineChapter> chapters = tpl.chapters().stream()
                .map(ch -> new Outline.OutlineChapter(ch.chapterId(), ch.title(), ch.metrics(),
                        ch.comparison(), ch.comparisons(), ch.guidance(), ch.stylePrompt(), ch.charts()))
                .toList();
        return new Outline(tpl.templateId(), periodLabel, chapters, List.of());
    }

    // ---------- LLM 层：① 匹配正确率 / 失败关闭正确率 ----------

    public EvalReport runLlm() {
        List<CaseResult> results = new ArrayList<>();
        int matchTotal = 0, matchPassed = 0, blockedTotal = 0, blockedPassed = 0;
        for (JsonNode c : goldenCases("MATCH")) {
            matchTotal++;
            CaseResult r = runMatchCase(c);
            if (r.pass()) matchPassed++;
            results.add(r);
        }
        for (JsonNode c : goldenCases("BLOCKED")) {
            blockedTotal++;
            CaseResult r = runBlockedCase(c);
            if (r.pass()) blockedPassed++;
            results.add(r);
        }
        Map<String, String> rates = new LinkedHashMap<>();
        rates.put("模板匹配正确率", rate(matchPassed, matchTotal));
        rates.put("失败关闭正确率", rate(blockedPassed, blockedTotal));
        log.info("[EVAL] LLM 层完成: 匹配 {}/{}，失败关闭 {}/{}", matchPassed, matchTotal, blockedPassed, blockedTotal);
        return new EvalReport("LLM", results.size(), matchPassed + blockedPassed, rates, results);
    }

    private CaseResult runMatchCase(JsonNode c) {
        String id = c.path("id").asText();
        String request = c.path("requestText").asText();
        List<ItemResult> items = new ArrayList<>();
        try {
            Outline outline = outlineStep.run(request, null);
            boolean tplOk = outline.templateId().equals(c.path("expectedTemplateId").asText());
            boolean periodOk = outline.periodLabel().equals(c.path("expectedPeriodLabel").asText());
            items.add(new ItemResult("template", tplOk, "命中 " + outline.templateId()
                    + "（期望 " + c.path("expectedTemplateId").asText() + "）"));
            items.add(new ItemResult("period", periodOk, "识别 " + outline.periodLabel()
                    + "（期望 " + c.path("expectedPeriodLabel").asText() + "）"));
        } catch (PolicyException e) {
            items.add(new ItemResult("match", false, "被失败关闭（期望命中模板）: " + e.getMessage()));
        } catch (Exception e) {
            items.add(new ItemResult("match", false, "执行异常: " + e.getMessage()));
        }
        return new CaseResult(id, "MATCH", items.stream().allMatch(ItemResult::pass), items);
    }

    private CaseResult runBlockedCase(JsonNode c) {
        String id = c.path("id").asText();
        String request = c.path("requestText").asText();
        String reasonContains = c.path("reasonContains").asText(null);
        List<ItemResult> items = new ArrayList<>();
        try {
            Outline outline = outlineStep.run(request, null);
            items.add(new ItemResult("blocked", false, "未失败关闭，反而命中了模板 " + outline.templateId()
                    + "（期望 BLOCKED 转人工）"));
        } catch (PolicyException e) {
            // 判据 = 业务性失败关闭本身；"POLICY" 为类别断言（PolicyException 即 [POLICY]），其余关键词查原因文本
            boolean reasonOk = reasonContains == null || "POLICY".equals(reasonContains)
                    || String.valueOf(e.getMessage()).contains(reasonContains);
            items.add(new ItemResult("blocked", reasonOk, "已失败关闭: " + e.getMessage()));
        } catch (Exception e) {
            items.add(new ItemResult("blocked", false, "意外异常（期望 [POLICY] 业务性失败关闭）: " + e.getMessage()));
        }
        return new CaseResult(id, "BLOCKED", items.stream().allMatch(ItemResult::pass), items);
    }

    // ---------- 工具 ----------

    private List<JsonNode> goldenCases(String type) {
        try (var in = new ClassPathResource("report/eval/golden-set.json").getInputStream()) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode c : mapper.readTree(in).path("cases")) {
                if (type.equals(c.path("type").asText())) out.add(c);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("加载黄金需求集失败(report/eval/golden-set.json)", e);
        }
    }

    private static String rate(int passed, int total) {
        return total == 0 ? "n/a" : passed + "/" + total + " = "
                + BigDecimal.valueOf(passed * 100.0 / total).setScale(1, java.math.RoundingMode.HALF_UP) + "%";
    }

    private static String shortHash(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(12, hash.length()));
    }
}
