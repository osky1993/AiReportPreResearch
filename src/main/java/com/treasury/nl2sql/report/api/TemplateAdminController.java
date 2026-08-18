package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.asset.TemplateAdminService;
import com.treasury.nl2sql.report.asset.TemplateDraftService;
import com.treasury.nl2sql.report.asset.TemplateAdminService.NotFoundException;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplatePreviewService;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.pipeline.ComparisonType;
import com.treasury.nl2sql.report.pipeline.PeriodResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模板资产管理接口（模板库 CRUD + 发布流转 + 草案生成 + 全链路校验）。
 *
 * <p>职责：对齐报告流水线编排器的模板契约，不直接触发 run，仅提供可被审核/发布的版本化资产。
 * 与流水线入口不同，本控制器默认不做耗时重跑，所有动作都保留为资产操作，便于回溯和人工复核。</p>
 *
 * <p>错误结构统一为：
 * <code>{"error": "...", "details":[{location, message}]}</code>，
 * 其中 validation 失败会返回字段级 {@code details}，notfound 返回空数组，非法状态变更返回 400。</p>
 *
 * <p>统一前置语义：
 * - 资产为版本化行，发布/下线基于 {@code version} 执行；
 * - 不允许直接覆盖历史版本 body，所有变更都走新版本；
 * - 不落库草案，仅用于前端编辑预览。</p>
 */
@RestController
@RequestMapping("/api/report/templates")
public class TemplateAdminController {

    /** 写请求体：模板定义 + 操作人 + 版本备注；remark 可用于发布日志和 diff 复核。 */
    public record SaveRequest(ReportTemplateDef template, String createdBy, String remark) {}

    /** AI 起草请求体：面向页面场景描述文本输入。 */
    public record DraftRequest(String description, String createdBy) {}

    /** 预览请求体：模板草稿（未落库编辑态）+ 可选报告期标签 + 可选单章 id。 */
    public record PreviewRequest(ReportTemplateDef template, String periodLabel, String chapterId) {}

    private final TemplateAdminService service;
    private final TemplateDraftService draftService;
    private final TemplatePreviewService previewService;

    /**
     * 依赖注入。所有异常响应由本类统一封装，避免上抛到全局默认处理影响前端结构一致性。
     */
    public TemplateAdminController(TemplateAdminService service, TemplateDraftService draftService,
                                   TemplatePreviewService previewService) {
        this.service = service;
        this.draftService = draftService;
        this.previewService = previewService;
    }

    /**
     * 模板试生成预览（Gate3）：对草稿干跑 ②③④，返回带真实数字的事实/图表章节样例。
     * <p>零落库；流水线语义的失败关闭以 200 + blocked=true 返回（预览的用途就是把拦截原因给编辑者看）；
     * 结构性校验错误按保存同款 400 契约返回字段级 details。</p>
     */
    @PostMapping("/preview")
    public TemplatePreviewService.PreviewResult preview(@RequestBody PreviewRequest req) {
        return previewService.preview(req.template(), req.periodLabel(), req.chapterId());
    }

    /** 预览推荐报告期：按业务数据最大日期锚定的最近完整周/月/季（避免推到空数据周期）。 */
    @GetMapping("/preview/periods")
    public Map<String, String> previewPeriods() {
        return previewService.recommendedPeriods();
    }

    /**
     * 编辑器选项元数据：对比方式 / 周期粒度 / 图表种类的中文标签与约束矩阵。
     * <p>数据源是 {@link ComparisonType} 枚举（比较规则的单一权威定义）——前端不硬编码任何
     * token 或矩阵，规则演进只改枚举一处，本端点与校验器自动同步，避免前后端漂移。</p>
     */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, String> unitWord = Map.of(
                PeriodResolver.TYPE_WEEK, "周", PeriodResolver.TYPE_MONTH, "月", PeriodResolver.TYPE_QUARTER, "季");
        List<String> allPeriodTypes = List.of(
                PeriodResolver.TYPE_WEEK, PeriodResolver.TYPE_MONTH, PeriodResolver.TYPE_QUARTER);
        List<Map<String, Object>> comparisons = new java.util.ArrayList<>();
        for (ComparisonType ct : ComparisonType.values()) {
            List<String> allowed = allPeriodTypes.stream().filter(ct::allows).toList();
            // UI 措辞由枚举数据推导：同比固定「较去年同期」；环比按其唯一适用粒度拼「较上周/月/季」
            String label = "同比".equals(ct.label())
                    ? "较去年同期（同比）"
                    : "较上" + unitWord.get(allowed.get(0)) + "（环比）";
            comparisons.add(Map.of("token", ct.token(), "label", label, "kind", ct.label(), "periodTypes", allowed));
        }
        return Map.of(
                "comparisons", comparisons,
                "periodTypes", List.of(
                        Map.of("code", PeriodResolver.TYPE_WEEK, "label", "周报"),
                        Map.of("code", PeriodResolver.TYPE_MONTH, "label", "月报"),
                        Map.of("code", PeriodResolver.TYPE_QUARTER, "label", "季报")),
                "chartKinds", List.of(
                        Map.of("kind", "series", "label", "趋势（近 N 期）", "types", List.of("line", "bar")),
                        Map.of("kind", "dimension", "label", "构成（按维度）", "types", List.of("pie", "bar"))),
                "chartTypes", Map.of("line", "折线图", "bar", "柱状图", "pie", "饼图"));
    }

    /**
     * AI 起草模板。
     * <p>输入场景描述后，由起草服务返回草案文本与候选字段；不会持久化到库，避免未确认口径污染生产资产。</p>
     */
    @PostMapping("/draft")
    public TemplateDraftService.DraftResult draft(@RequestBody DraftRequest req) {
        return draftService.draft(req.description());
    }

    /**
     * 列表模板摘要。
     * <p>用于管理页左侧列表与下拉筛选，返回最小字段集和最近版本状态，便于按版本树做可视化展示。</p>
     */
    @GetMapping
    public List<TemplateAdminService.TemplateSummary> list() {
        return service.list();
    }

    /**
     * 新建模板主版本（首版）。
     * <p>幂等边界：同一 {@code template.id} 重复提交会由服务层按版本规则产生后续版本，而不是直接替换。</p>
     * <p>成功返回 {@code 201}，失败原因可能是 JSON 结构、字段约束、或白名单验证不通过。</p>
     */
    @PostMapping
    public ResponseEntity<TemplateAdminService.SaveResult> create(@RequestBody SaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.template(), req.createdBy(), req.remark()));
    }

    /**
     * 获取某模板详情（含版本树头部摘要）。
     * <p>返回值用于模板编辑页主表单回填和子版本定位；若模板不存在，统一映射为 {@code 404}。</p>
     */
    @GetMapping("/{id}")
    public TemplateAdminService.TemplateDetail detail(@PathVariable String id) {
        return service.detail(id);
    }

    /**
     * 获取模板指定版本全量数据。
     * <p>常用于回滚/对比，调用方可据此确认历史版本 body 与当前草案差异；返回 200 表示版本存在且可读。</p>
     */
    @GetMapping("/{id}/versions/{v}")
    public TemplateAdminService.VersionBody version(@PathVariable String id, @PathVariable int v) {
        return service.version(id, v);
    }

    /**
     * 保存新版本。
     * <p>语义是“以当前版本为基线产生新版本”，不允许更新老版本行；失败通常是并发/状态机冲突或校验失败。</p>
     */
    @PutMapping("/{id}")
    public TemplateAdminService.SaveResult save(@PathVariable String id, @RequestBody SaveRequest req) {
        return service.saveNewVersion(id, req.template(), req.createdBy(), req.remark());
    }

    /** 状态流转请求体。 */
    public record StatusRequest(int version) {}

    /**
     * 发布目标版本为可用状态。
     * <p>发布会触发匹配缓存刷新与旧生效版本下线；若 target version 已是当前生效版本，服务返回幂等成功。</p>
     */
    @PostMapping("/{id}/publish")
    public TemplateAdminService.SaveResult publish(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.publish(id, req.version());
    }

    /**
     * 下线指定版本。
     * <p>仅变更状态为 DEPRECATED，不删除任何历史体，保留稽核和可回放性；如引用约束存在，服务层可拒绝发布。</p>
     */
    @PostMapping("/{id}/deprecate")
    public TemplateAdminService.SaveResult deprecate(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.deprecate(id, req.version());
    }

    /**
     * 全链路验证（dry-run）入口。
     * <p>不落库、不写 run，仅执行结构、占位符、MQL、回放等关键校验；用于保存前和发布前预检，减少无效版本推入。</p>
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody SaveRequest req) {
        List<ValidationError> errors = service.validateOnly(req.template());
        return errors.isEmpty() ? Map.of("valid", true) : Map.of("valid", false, "errors", errors);
    }

    // ---------- 统一错误结构 ----------

    /**
     * 校验失败映射统一错误格式。
     * <p>保留字段级错误列表，前端用于高亮对应表单控件；HTTP 状态固定 400。</p>
     */
    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(ValidationFailedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", e.errors()));
    }

    /**
     * 404 语义统一包装。
     * <p>模板不存在属于前端流程可预期状态，返回空 details 便于统一分支处理。</p>
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage(), "details", List.of()));
    }

    /**
     * 非法状态/参数统一映射为 400。
     * <p>用于捕获服务层抛出的状态机冲突、参数缺失或版本流转不合法等场景。</p>
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", List.of()));
    }
}
