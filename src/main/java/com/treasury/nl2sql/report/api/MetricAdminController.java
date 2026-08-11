package com.treasury.nl2sql.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.treasury.nl2sql.report.asset.MetricAdminService;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.asset.MetricWizardService;
import com.treasury.nl2sql.report.asset.TemplateAdminService.NotFoundException;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.pipeline.MqlParameterizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 指标资产管理与指标制作向导端点（P5 契约，见 README；演示页 metric-wizard.html 的后端）。
 *
 * <p>职责链路：试查(P0) + 反翻译(P1) + 指标保存/发布(P2) + 参数化(P3)。
 * 本控制器不直接执行报告主链路，只负责把“可执行口径”沉淀为版本化资产，供流水线和匹配模型消费。</p>
 *
 * <p>统一错误结构：{"error": 总述, "details": [{location, message}]}。</p>
 * <p>其中 validation 的 location 承载类别（STRUCTURE/PLACEHOLDER/MQL_VALIDATION/TRIAL_EXECUTION/RESULT_SHAPE）。</p>
 * <p>与 ReportController 的 GET /api/report/metrics/{id}/references 同前缀共存；路径匹配采用最长前缀策略。</p>
 */
@RestController
@RequestMapping("/api/report/metrics")
public class MetricAdminController {

    /** 保存指标定义的请求体：metric 定义 + 可选试查语境 + 创建人，用于审计痕迹。 */
    public record SaveRequest(MetricDefinition metric, String tryQuestion, String createdBy) {}
    /** 状态流转请求体：目标版本号。 */
    public record StatusRequest(int version) {}
    /** 参数化请求体：mql+apply 字段；apply 为空表示扫描可参数化字段，不为空表示提交替换结果。 */
    public record ParameterizeRequest(JsonNode mql, List<String> apply) {}

    /** 试查请求体：仅含自然语言问题。 */
    public record TryRequest(String question) {}
    /** 反翻译请求体：输入 MQL 结构，返回可读口径。 */
    public record ExplainRequest(JsonNode mql) {}

    private final MetricAdminService service;
    private final MetricWizardService wizard;
    private final MqlParameterizer parameterizer;

    /**
     * 依赖注入，确保参数化/向导/持久化路径可配套调用，减少运行时 NPE。
     */
    public MetricAdminController(MetricAdminService service, MetricWizardService wizard,
                                 MqlParameterizer parameterizer) {
        this.service = service;
        this.wizard = wizard;
        this.parameterizer = parameterizer;
    }

    // ---------- 向导第 1/2 步：试查与口径反翻译 ----------

    /** 试查：走引擎自由生成路径（资产制作期工具）；引擎失败沿 /api/query 惯例 200 + success=false。 */
    @PostMapping("/try")
    public MetricWizardService.TryResult tryQuery(@RequestBody TryRequest req) {
        return wizard.tryQuery(req.question());
    }

    /**
     * 入参已是 MQL 的反翻译入口：返回中文口径说明，帮助人工评估“试查结果是否合规”。
     */
    @PostMapping("/explain")
    public MetricWizardService.ExplainResult explain(@RequestBody ExplainRequest req) {
        return wizard.explain(req.mql());
    }

    // ---------- 资产 CRUD 与状态流转 ----------

    /**
     * 指标列表。
     * <p>返回每个指标 id 的摘要信息与版本头信息，供管理页列表渲染；当前不分页，不接收查询参数。</p>
     */
    @GetMapping
    public List<MetricAdminService.MetricSummary> list() {
        return service.list();
    }

    /** 查询单个指标定义与版本树。不存在则走统一 404。 */
    @GetMapping("/{id}")
    public MetricAdminService.MetricDetail detail(@PathVariable String id) {
        return service.detail(id);
    }

    /**
     * 新建指标定义。
     * <p>结果默认入库为 DRAFT；可附带 tryQuestion 用于保留试查上下文，便于版本复核时理解创建动机。</p>
     * <p>创建成功返回 201，失败（校验不通过）返回 400。</p>
     */
    @PostMapping
    public ResponseEntity<MetricAdminService.SaveResult> save(@RequestBody SaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.save(req.metric(), req.tryQuestion(), req.createdBy()));
    }

    /**
     * 发布某指标版本。
     * <p>幂等语义：同版本重复 publish 可视为同义更新，服务层会保证旧版本下线/新版本生效在一次事务内完成。</p>
     */
    @PostMapping("/{id}/publish")
    public MetricAdminService.SaveResult publish(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.publish(id, req.version());
    }

    /**
     * 下线某指标版本。
     * <p>仅变更状态，不清理历史和引用链，支持审计复盘与重审；下线后默认不再被主流程选中。</p>
     */
    @PostMapping("/{id}/deprecate")
    public MetricAdminService.SaveResult deprecate(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.deprecate(id, req.version());
    }

    // ---------- 向导第 3 步：参数化 ----------

    /**
     * 参数化入口。
     * <p>若 apply 为空，返回可参数化字段扫描结果；若含 apply 列表，返回 apply 后的模板 JSON。</p>
     * <p>作用是把硬编码时间范围转换为可复现参数，减少指标重复定义并提高模板重用。</p>
     */
    @PostMapping("/parameterize")
    public Object parameterize(@RequestBody ParameterizeRequest req) {
        if (req.apply() == null || req.apply().isEmpty()) {
            return parameterizer.scan(req.mql());
        }
        return parameterizer.apply(req.mql(), req.apply());
    }

    // ---------- 统一错误结构（照 TemplateAdminController） ----------

    /**
     * 校验失败响应。
     * <p>返回 400，包含结构化 details，便于前端将错误映射到字段或步骤级提示。</p>
     */
    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(ValidationFailedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", e.errors()));
    }

    /**
     * 资源不存在响应。
     * <p>统一返回 404 与空 details，避免将非法 id 与参数错误混淆。</p>
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage(), "details", List.of()));
    }

    /**
     * 参数/状态非法响应（400）。
     * <p>覆盖 IllegalStateException 与 IllegalArgumentException，使前端有统一的错误处理分支。</p>
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", List.of()));
    }
}
