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

    /**
     * 依赖注入。
     * <p>注意：该服务是离线评测服务，不持久化 run_step/run 表，也不调用 ReportPipeline，避免影响线上流水线状态。</p>
     */
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

    /**
     * 执行确定性评测（无 LLM 依赖）。
     * <p>按 GOLDEN CASE 中 {@code FACTS} 类型执行「模板→Spec→Fetch→FactBuild」链路，逐项与手写 SQL 期望值比对。</p>
     * <p>失败语义：任意用例出现执行异常、缺失事实、值不一致、额外事实时置为失败；最终报告返回通过/未通过统计。</p>
     */
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

    /**
     * FACTS 用例执行：
     * 1) 依据模板与 period 运行 ②③④，沿用主链的窗口组装；
     * 2) 抽取 BASE fact 与黄金期望逐条比对；
     * 3) 允许的对齐键必须完全一致，额外产物视为回归失败，防止隐式输出。
     */
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

            // BASE 事实 → (metricId|purpose[|维度][|序列期])：维度行一行一键（P3）、序列点一期一键（P4）
            Map<String, BigDecimal> produced = new LinkedHashMap<>();
            Map<String, String> producedHash = new LinkedHashMap<>();
            for (var f : built.facts()) {
                if (!"BASE".equals(f.factType())) continue;
                MetricQuerySpec spec = mapper.readValue(f.specJson(), MetricQuerySpec.class);
                String key = expectationKey(f.metricId(), spec.purpose(), f.dimensions(), spec.periodLabel());
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
                String key = expectationKey(e.path("metricId").asText(), e.path("purpose").asText(), dims,
                        e.path("periodLabel").asText(null));
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

    /**
     * 期望比对主键：metricId|purpose|（排序后的维度）|（序列期，若有）；
     * 与 ③/④ 输出约束一致，避免评测阶段和运行阶段采用不同序列化口径。
     */
    static String expectationKey(String metricId, String purpose, Map<String, String> dimensions,
                                String periodLabel) {
        StringBuilder sb = new StringBuilder(metricId).append('|').append(purpose);
        if (dimensions != null && !dimensions.isEmpty()) {
            sb.append('|').append(dimensions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        if (MetricQuerySpec.PURPOSE_CHART_SERIES.equals(purpose)) {
            sb.append('|').append(periodLabel);
        }
        return sb.toString();
    }

    /**
     * 确定性评测大纲：不做模板裁剪，直接跑模板中全部章节（卡点1 人工全量确认语义）。
     * 同时也可视为“模板约定大纲”，用于评测中保证固定口径复现。
     */
    private static Outline outlineOf(ReportTemplateDef tpl, String periodLabel) {
        List<Outline.OutlineChapter> chapters = tpl.chapters().stream()
                .map(ch -> new Outline.OutlineChapter(ch.chapterId(), ch.title(), ch.metrics(),
                        ch.comparison(), ch.comparisons(), ch.guidance(), ch.stylePrompt(), ch.charts()))
                .toList();
        return new Outline(tpl.templateId(), periodLabel, chapters, List.of());
    }

    // ---------- LLM 层：① 匹配正确率 / 失败关闭正确率 ----------

    /**
     * 运行 LLM 层评测。
     *
     * <p>仅评估「模板匹配」与「失败关闭」语义，不触达数据库取数链路。
     * 适合在无数据波动但模型版本/提示词变更时做回归。</p>
     *
     * <ul>
     *   <li>MATCH：强校验 templateId/periodLabel 与黄金值一致；不检查文本风格以避免语义无关漂移。</li>
     *   <li>BLOCKED：要求抛出 {@link PolicyException}，并用 reasonContains 检查关闭原因是否符合预期。</li>
     * </ul>
     * <p>该层失败会记入 blocked 率，不阻断系统可用性，但用于发布门禁时可直接判定回退到人工。</p>
     */
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

    /**
     * 匹配类用例：仅校验 LLM 输出的 templateId/periodLabel 与黄金值一致；
     * 这里不检查文本质量，避免把语义正确但写法差异误判为错分。
     */
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

    /**
     * 失败关闭类用例：要求抛出 PolicyException（业务性失败）；
     * 通过 reasonContains 进行“错误原因”断言，避免把所有 BLOCKED 都算同质。
     */
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

    /**
     * 读取黄金用例文件并按 type 过滤。
     * <p>失败（文件缺失/格式错误）将抛出 IllegalStateException，避免把评测失败静默处理为通过。</p>
     */
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

    /**
     * 生成通过率文本，统一保留一位小数。
     * <p>当分母为 0 时返回 n/a，防止除零异常污染评测结果。</p>
     */
    private static String rate(int passed, int total) {
        return total == 0 ? "n/a" : passed + "/" + total + " = "
                + BigDecimal.valueOf(passed * 100.0 / total).setScale(1, java.math.RoundingMode.HALF_UP) + "%";
    }

    /**
     * sql_hash 摘要短显示（前端/日志可读）。
     * <p>用于评测报告追溯，不用于逻辑决策；返回 12 字符以内片段，超长时截断避免日志污染。</p>
     */
    private static String shortHash(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(12, hash.length()));
    }
}
