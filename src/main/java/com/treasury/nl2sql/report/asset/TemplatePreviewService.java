package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.domain.ChartRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import com.treasury.nl2sql.report.pipeline.ChartBuildStep;
import com.treasury.nl2sql.report.pipeline.FactBuildStep;
import com.treasury.nl2sql.report.pipeline.FetchStep;
import com.treasury.nl2sql.report.pipeline.PeriodResolver;
import com.treasury.nl2sql.report.pipeline.PolicyException;
import com.treasury.nl2sql.report.pipeline.SpecResolveStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板试生成预览（Gate3）：对编辑器里的模板草稿（未落库）干跑 ②语义解析→③确定性取数→④事实/图表构建，
 * 返回带真实数字的章节样例——业务人员改完模板即刻看到「生成出来长什么样」，不必保存→发布→跑流水线。
 *
 * 边界（与四条硬约束的关系）：
 *  - 复用流水线同款步骤类原样执行（③ 依旧零 LLM、白名单校验、双哈希），预览不引入任何自愈/降级；
 *  - 全程零落库：不建 run、不写 step/fact/chart（落库调用都在 ReportPipeline.runAsync，这里不触碰）；
 *  - 失败关闭可视化：PolicyException（质量断言/MQL 校验/周期不符等）不吞——以 blocked=true 返回给编辑者，
 *    这正是把「失败关闭」暴露到制作期的价值；结构性校验错误则按保存同款 400 契约抛出；
 *  - 跳过异常检测与维度贡献拆解（ContributionStep 会追加维度查询，预览目标只是章节样例，避免查询翻倍）。
 */
@Service
public class TemplatePreviewService {

    private static final Logger log = LoggerFactory.getLogger(TemplatePreviewService.class);

    /** 预览结果：blocked=true 表示流水线语义的失败关闭（HTTP 仍 200，前端展示原因引导改模板）。 */
    public record PreviewResult(String periodLabel, List<FactRecord> facts, List<ChartRecord> charts,
                                List<String> notes, List<String> warnings,
                                boolean blocked, String blockedReason) {}

    private final ReportAssetService assets;
    private final SpecResolveStep specStep;
    private final FetchStep fetchStep;
    private final FactBuildStep factStep;
    private final ChartBuildStep chartStep;
    private final JdbcTemplate jdbc;

    /** 推荐周期的锚定表/列（业务数据最大日期）；查询失败回退系统日期并给 warning。 */
    @Value("${report.preview.anchor-table:cash_transaction}")
    private String anchorTable;
    @Value("${report.preview.anchor-column:txn_date}")
    private String anchorColumn;

    public TemplatePreviewService(ReportAssetService assets, SpecResolveStep specStep,
                                  FetchStep fetchStep, FactBuildStep factStep,
                                  ChartBuildStep chartStep, JdbcTemplate jdbc) {
        this.assets = assets;
        this.specStep = specStep;
        this.fetchStep = fetchStep;
        this.factStep = factStep;
        this.chartStep = chartStep;
        this.jdbc = jdbc;
    }

    /**
     * 推荐报告期：按业务数据最大日期锚定的最近完整周/月/季标签。
     * 返回 {WEEK: 2026-W33, MONTH: ..., QUARTER: ..., anchorDate: ...}。
     */
    public Map<String, String> recommendedPeriods() {
        LocalDate anchor = anchorDate();
        Map<String, String> out = new LinkedHashMap<>();
        out.put(PeriodResolver.TYPE_WEEK, PeriodResolver.latestCompleteLabel(anchor, PeriodResolver.TYPE_WEEK));
        out.put(PeriodResolver.TYPE_MONTH, PeriodResolver.latestCompleteLabel(anchor, PeriodResolver.TYPE_MONTH));
        out.put(PeriodResolver.TYPE_QUARTER, PeriodResolver.latestCompleteLabel(anchor, PeriodResolver.TYPE_QUARTER));
        out.put("anchorDate", anchor.toString());
        return out;
    }

    /**
     * 对模板草稿干跑 ②③④。
     * @param def         模板草稿（未落库的编辑态 JSON，保存同款结构）
     * @param periodLabel 报告期标签；空 = 按模板首个适用粒度取推荐周期
     * @param chapterId   非空 = 只预览该章节（裁剪后走同一条链，FactBuildStep 按 chapterId 分组无副作用）
     */
    public PreviewResult preview(ReportTemplateDef def, String periodLabel, String chapterId) {
        // 结构校验：保存同款规则、保存同款 400 契约（结构坏了属于「先把表单改对」，不是流水线失败关闭）
        List<ValidationError> errors = TemplateValidator.validate(def, assets.allMetrics());
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        List<String> warnings = new ArrayList<>();
        if (chapterId != null && !chapterId.isBlank()) {
            List<ReportTemplateDef.ChapterDef> kept = def.chapters().stream()
                    .filter(c -> chapterId.equals(c.chapterId())).toList();
            if (kept.isEmpty()) {
                throw new IllegalArgumentException("模板中不存在章节: " + chapterId);
            }
            def = new ReportTemplateDef(def.templateId(), def.name(), def.keywords(), def.periodTypes(), kept);
        }
        String label = (periodLabel == null || periodLabel.isBlank())
                ? PeriodResolver.latestCompleteLabel(anchorDate(), def.effectivePeriodTypes().get(0))
                : periodLabel.trim();
        try {
            PeriodResolver.Window current = PeriodResolver.resolve(label);
            // 周期-模板粒度把关：与 ① OutlineStep 同规则（预览用不匹配的周期出的数没有参考意义）
            String granularity = PeriodResolver.granularity(current.label());
            if (!def.effectivePeriodTypes().contains(granularity)) {
                throw new PolicyException("模板不支持 " + granularity + " 粒度的报告期（支持: "
                        + String.join("/", def.effectivePeriodTypes()) + "，当前 " + current.label() + "）");
            }
            Outline outline = Outline.fromTemplate(def, current.label(), List.of());
            Map<String, PeriodResolver.Window> compareWindows =
                    SpecResolveStep.buildCompareWindows(outline, current);
            Map<String, MetricDefinition> defs = assets.allMetrics();
            List<MetricQuerySpec> specs = specStep.run(outline, current, compareWindows, defs);
            List<FetchStep.FetchResult> fetched = fetchStep.run(specs, defs);
            FactBuildStep.FactBuildResult built = factStep.run(outline, fetched, defs, null);
            List<String> notes = new ArrayList<>(built.notes());
            List<ChartRecord> charts = chartStep.build(outline, built.facts(), notes);
            notes.add("预览为干跑：不落库、不含异常检测与维度贡献拆解；正式报告以流水线运行为准");
            log.info("[PREVIEW] 模板={} 期={} 事实 {} 条、图表 {} 张（零落库）",
                    def.templateId(), current.label(), built.facts().size(), charts.size());
            return new PreviewResult(current.label(), built.facts(), charts, notes, warnings, false, null);
        } catch (PolicyException e) {
            // 失败关闭的可视化：预览的用途之一就是让编辑者在制作期看到会被哪条硬约束拦下
            log.info("[PREVIEW] 模板={} 期={} 失败关闭: {}", def.templateId(), label, e.getMessage());
            return new PreviewResult(label, List.of(), List.of(), List.of(), warnings, true, e.getMessage());
        }
    }

    /** 业务数据锚定日期（MAX(日期列)）；查询失败回退系统日期（预览仍可用，只是推荐期可能落空数据）。 */
    private LocalDate anchorDate() {
        try {
            LocalDate d = jdbc.queryForObject(
                    "SELECT MAX(" + anchorColumn + ") FROM " + anchorTable, LocalDate.class);
            if (d != null) return d;
        } catch (Exception e) {
            log.warn("[PREVIEW] 锚定日期查询失败（{}.{}），回退系统日期: {}", anchorTable, anchorColumn, e.getMessage());
        }
        return LocalDate.now();
    }
}
