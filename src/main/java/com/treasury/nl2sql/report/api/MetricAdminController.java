package com.treasury.nl2sql.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.treasury.nl2sql.report.asset.MetricAdminService;
import com.treasury.nl2sql.report.asset.MetricDefinition;
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
 * 指标管理与制作向导 REST 端点（P5 契约，见 README；演示页 metric-wizard.html 的后端）。
 * 统一错误结构：{"error": 总述, "details": [{location, message}]}——
 * 保存链的 location 承载校验类别 STRUCTURE/PLACEHOLDER/MQL_VALIDATION/TRIAL_EXECUTION/RESULT_SHAPE。
 * 与 ReportController 的 GET /api/report/metrics/{id}/references 同前缀共存（更长字面量路径优先）。
 */
@RestController
@RequestMapping("/api/report/metrics")
public class MetricAdminController {

    public record SaveRequest(MetricDefinition metric, String tryQuestion, String createdBy) {}
    public record StatusRequest(int version) {}
    public record ParameterizeRequest(JsonNode mql, List<String> apply) {}

    private final MetricAdminService service;
    private final MqlParameterizer parameterizer;

    public MetricAdminController(MetricAdminService service, MqlParameterizer parameterizer) {
        this.service = service;
        this.parameterizer = parameterizer;
    }

    // ---------- 资产 CRUD 与状态流转 ----------

    @GetMapping
    public List<MetricAdminService.MetricSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public MetricAdminService.MetricDetail detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<MetricAdminService.SaveResult> save(@RequestBody SaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.save(req.metric(), req.tryQuestion(), req.createdBy()));
    }

    @PostMapping("/{id}/publish")
    public MetricAdminService.SaveResult publish(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.publish(id, req.version());
    }

    @PostMapping("/{id}/deprecate")
    public MetricAdminService.SaveResult deprecate(@PathVariable String id, @RequestBody StatusRequest req) {
        return service.deprecate(id, req.version());
    }

    // ---------- 向导第 3 步：参数化 ----------

    /** 不带 apply → 建议清单；带 apply → 服务端替换后的 mqlTemplate（前端不碰 JSON 结构）。 */
    @PostMapping("/parameterize")
    public Object parameterize(@RequestBody ParameterizeRequest req) {
        if (req.apply() == null || req.apply().isEmpty()) {
            return parameterizer.scan(req.mql());
        }
        return parameterizer.apply(req.mql(), req.apply());
    }

    // ---------- 统一错误结构（照 TemplateAdminController） ----------

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(ValidationFailedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", e.errors()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage(), "details", List.of()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "details", List.of()));
    }
}
