package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import com.treasury.nl2sql.report.asset.TemplateAdminService;
import com.treasury.nl2sql.report.asset.TemplateAdminService.NotFoundException;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
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
 * 模板管理 REST 端点（P2 契约，见 README「模板管理 API」；演示页 template-admin.html 的后端）。
 * 统一错误结构：400/404 → {"error": 总述, "details": [{location, message}]}。
 */
@RestController
@RequestMapping("/api/report/templates")
public class TemplateAdminController {

    /** 写请求体：模板定义 + 操作人 + 版本备注。 */
    public record SaveRequest(ReportTemplateDef template, String createdBy, String remark) {}

    private final TemplateAdminService service;

    public TemplateAdminController(TemplateAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateAdminService.TemplateSummary> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<TemplateAdminService.SaveResult> create(@RequestBody SaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.template(), req.createdBy(), req.remark()));
    }

    @GetMapping("/{id}")
    public TemplateAdminService.TemplateDetail detail(@PathVariable String id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/versions/{v}")
    public TemplateAdminService.VersionBody version(@PathVariable String id, @PathVariable int v) {
        return service.version(id, v);
    }

    @PutMapping("/{id}")
    public TemplateAdminService.SaveResult save(@PathVariable String id, @RequestBody SaveRequest req) {
        return service.saveNewVersion(id, req.template(), req.createdBy(), req.remark());
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody SaveRequest req) {
        List<ValidationError> errors = service.validateOnly(req.template());
        return errors.isEmpty() ? Map.of("valid", true) : Map.of("valid", false, "errors", errors);
    }

    // ---------- 统一错误结构 ----------

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
