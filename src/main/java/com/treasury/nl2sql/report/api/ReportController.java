package com.treasury.nl2sql.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.domain.ReportStep;
import com.treasury.nl2sql.report.export.ReportExportService;
import com.treasury.nl2sql.report.pipeline.ReportPipeline;
import com.treasury.nl2sql.report.store.ClaimRepository;
import com.treasury.nl2sql.report.store.ReportFactRepository;
import com.treasury.nl2sql.report.store.ReportStepRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 报告流水线 REST 端点（演示页 report.html 的后端；风格照 QueryController：record DTO + /api 前缀）。 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportPipeline pipeline;
    private final ReportStepRepository stepRepo;
    private final ReportFactRepository factRepo;
    private final ClaimRepository claimRepo;
    private final ReportAssetService assets;
    private final ReportExportService exportService;

    public ReportController(ReportPipeline pipeline, ReportStepRepository stepRepo,
                            ReportFactRepository factRepo, ClaimRepository claimRepo,
                            ReportAssetService assets, ReportExportService exportService) {
        this.pipeline = pipeline;
        this.stepRepo = stepRepo;
        this.factRepo = factRepo;
        this.claimRepo = claimRepo;
        this.assets = assets;
        this.exportService = exportService;
    }

    public record CreateRequest(String requestText) {}
    public record ApproveOutlineRequest(String approver, JsonNode outline) {}
    public record RegenerateRequest(String revisedRequest) {}
    public record PublishRequest(String approver) {}
    public record RejectRequest(String approver, String reason) {}
    /** 导出请求体：chartImages 可选——缺图的图表在文档中降级为纯数据表（路线 C 演进位）。 */
    public record ExportRequest(List<ReportExportService.ChartImage> chartImages) {}

    /** 详情 = run 全字段 + 步骤留痕 + 事实 + 归因（演示页 1.5s 轮询此结构）。 */
    public record RunDetail(ReportRun run, List<ReportStep> steps, List<FactRecord> facts,
                            List<ClaimRecord> claims) {}

    @PostMapping("/runs")
    public RunDetail create(@RequestBody CreateRequest req) {
        return detail(pipeline.createRun(req.requestText()).runId());
    }

    @GetMapping("/runs")
    public List<ReportRun> list() {
        return pipeline.list();
    }

    @GetMapping("/runs/{id}")
    public RunDetail get(@PathVariable long id) {
        return detail(pipeline.require(id).runId());
    }

    /** HITL 卡点1：确认大纲（可回传人工调整后的大纲，以人确认版为准）→ kick ②~⑥。 */
    @PostMapping("/runs/{id}/outline/approve")
    public RunDetail approveOutline(@PathVariable long id, @RequestBody ApproveOutlineRequest req) {
        return detail(pipeline.approveOutline(id, req.approver(), req.outline()).runId());
    }

    /** HITL 卡点1：打回，带修改意见重新生成大纲。 */
    @PostMapping("/runs/{id}/outline/regenerate")
    public RunDetail regenerateOutline(@PathVariable long id, @RequestBody RegenerateRequest req) {
        return detail(pipeline.regenerateOutline(id, req.revisedRequest()).runId());
    }

    /** HITL 卡点2：审批发布（服务端复核审计包，一致率非 100% 不放行）。 */
    @PostMapping("/runs/{id}/publish/approve")
    public RunDetail approvePublish(@PathVariable long id, @RequestBody PublishRequest req) {
        return detail(pipeline.approvePublish(id, req.approver()).runId());
    }

    @PostMapping("/runs/{id}/publish/reject")
    public RunDetail rejectPublish(@PathVariable long id, @RequestBody RejectRequest req) {
        return detail(pipeline.rejectPublish(id, req.approver(), req.reason()).runId());
    }

    /** 卡点2 归因确认（Phase05 T0 拍板：勾选 + confirmed_by/at 留痕）：仅 hypothesis 可升 confirmed。 */
    @PostMapping("/runs/{id}/claims/{claimId}/confirm")
    public RunDetail confirmClaim(@PathVariable long id, @PathVariable String claimId,
                                  @RequestBody PublishRequest req) {
        pipeline.confirmClaim(id, claimId, req.approver());
        return detail(id);
    }

    /** 断点续跑：仅 BLOCKED（或已停摆的 RUNNING）。 */
    @PostMapping("/runs/{id}/resume")
    public RunDetail resume(@PathVariable long id) {
        return detail(pipeline.resume(id).runId());
    }

    /**
     * 已签发报告导出（仅 PUBLISHED）：正文/事实/图表数据一律取库中终稿，客户端只上送图表 PNG；
     * 每图必附数据表（display_value 同源），无图降级纯数据表。format = pdf | docx。
     */
    @PostMapping("/runs/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id, @RequestParam String format,
                                         @RequestBody(required = false) ExportRequest req) {
        ReportExportService.ExportFile file =
                exportService.export(id, format, req == null ? null : req.chartImages());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    /** 证据视图：该运行的全部事实（含 SQL、双哈希、规约快照）。 */
    @GetMapping("/runs/{id}/facts")
    public List<FactRecord> facts(@PathVariable long id) {
        pipeline.require(id);
        return factRepo.findByRun(id);
    }

    /** 支撑资产视图：模板 + 指标语义定义（演示页「资产」页签 / 调试）。 */
    @GetMapping("/assets")
    public Map<String, Object> assets() {
        return Map.of("templates", assets.allTemplates(), "metrics", assets.allMetrics().values());
    }

    /** 指标被哪些 PUBLISHED 模板引用（P5 指标下架保护的反向检查，P2 契约预留）。 */
    @GetMapping("/metrics/{id}/references")
    public Map<String, Object> metricReferences(@PathVariable String id) {
        return Map.of("metricId", id, "referencedBy", assets.templatesReferencing(id));
    }

    private RunDetail detail(long runId) {
        return new RunDetail(pipeline.require(runId), stepRepo.findByRun(runId), factRepo.findByRun(runId),
                claimRepo.findByRun(runId));
    }

    /** 非法状态迁移 / 参数问题 → 400 + 说明（演示页直接展示 error 字段）。 */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
