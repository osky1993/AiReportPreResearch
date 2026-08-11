package com.treasury.nl2sql.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.domain.ClaimRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.domain.ReportStep;
import com.treasury.nl2sql.report.export.ReportExportService;
import com.treasury.nl2sql.report.lineage.LineageAssembler;
import com.treasury.nl2sql.report.lineage.LineageService;
import com.treasury.nl2sql.report.observe.RunStatsService;
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

/**
 * 报告流水线 REST 端点。
 *
 * <p>该类是控制面：把用户动作（创建、审批、续跑、导出）转为流水线状态机操作，
 * 并把运行快照聚合为前端可直接渲染的视图模型。</p>
 *
 * <p>失败语义与状态语义分离：请求参数与非法状态迁移由本类统一映射到 400，
 * 真正的流程可否推进、哪些字段必须存在、导出是否允许等规则仍由
 * {@link ReportPipeline}/{@link com.treasury.nl2sql.report.store} 统一约束。</p>
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    /** 报告流水线编排器：承载状态机、分步运行、卡点、审计、续跑与状态校验。 */
    private final ReportPipeline pipeline;
    /** 步骤仓储：运行详情轮询所需的步骤日志读源。 */
    private final ReportStepRepository stepRepo;
    /** 事实仓储：用于正文占位符回放与审计可复核数据。 */
    private final ReportFactRepository factRepo;
    /** 归因仓储：用于前端 claim 树与证据签核。 */
    private final ClaimRepository claimRepo;
    /** 资产服务：模板 / 指标版本管理与引用关系查询。 */
    private final ReportAssetService assets;
    /** 导出服务：文档生成与 MIME 组装。 */
    private final ReportExportService exportService;
    /** 血缘服务：构造运行级可回溯链路。 */
    private final LineageService lineageService;
    /** 指标观测服务：运行分布、耗时分布、失败原因 TopN。 */
    private final RunStatsService statsService;

    /**
     * 构造函数通过依赖注入确保控制器完整依赖；核心状态一致性在服务层内聚，不在控制器拼装。
     */
    public ReportController(ReportPipeline pipeline, ReportStepRepository stepRepo,
                            ReportFactRepository factRepo, ClaimRepository claimRepo,
                            ReportAssetService assets, ReportExportService exportService,
                            LineageService lineageService, RunStatsService statsService) {
        this.pipeline = pipeline;
        this.stepRepo = stepRepo;
        this.factRepo = factRepo;
        this.claimRepo = claimRepo;
        this.assets = assets;
        this.exportService = exportService;
        this.lineageService = lineageService;
        this.statsService = statsService;
    }

    /** 创建运行的入参：自然语言需求文本。 */
    public record CreateRequest(String requestText) {}
    /** 卡点一审批入参：操作者与已确认大纲。 */
    public record ApproveOutlineRequest(String approver, JsonNode outline) {}
    /** 卡点一重算入参：人工修订后的需求描述。 */
    public record RegenerateRequest(String revisedRequest) {}
    /** 卡点二审批入参：操作者。 */
    public record PublishRequest(String approver) {}
    /** 卡点二驳回入参：操作者与驳回原因。 */
    public record RejectRequest(String approver, String reason) {}
    /** 导出入参：chartImages 可空；无图时自动降级为数据表（路线 C 演进位）。 */
    public record ExportRequest(List<ReportExportService.ChartImage> chartImages) {}

    /** 详情视图：run 全字段 + 步骤留痕 + 事实 + 归因。 */
    public record RunDetail(ReportRun run, List<ReportStep> steps, List<FactRecord> facts,
                            List<ClaimRecord> claims) {}

    /** 创建运行并返回详情（进入 AWAITING_OUTLINE_APPROVAL）。 */
    @PostMapping("/runs")
    public RunDetail create(@RequestBody CreateRequest req) {
        // 控制层不做裁剪；任何空文本、资产不可用、流水线初始化失败都由 pipeline.createRun 统一封装成明确错误。
        return detail(pipeline.createRun(req.requestText()).runId());
    }

    /** 列出全部运行记录。 */
    @GetMapping("/runs")
    public List<ReportRun> list() {
        return pipeline.list();
    }

    /** 查询单个运行详情；不存在则抛出找不到异常由上层映射。 */
    @GetMapping("/runs/{id}")
    public RunDetail get(@PathVariable long id) {
        return detail(pipeline.require(id).runId());
    }

    /** HITL 卡点1：确认大纲（可回传人工调整后的大纲，以人确认版为准）→ kick ②~⑥。 */
    @PostMapping("/runs/{id}/outline/approve")
    public RunDetail approveOutline(@PathVariable long id, @RequestBody ApproveOutlineRequest req) {
        // HITL 卡点 1：把人工确认后的大纲与批准人落地，随后触发后续步骤；
        // 若传入大纲为空或状态不在 AWAITING_OUTLINE_APPROVAL，会在服务层直接 fail-closed。
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
        // HITL 卡点 2：仅允许 audit 一致率为 100% 的运行进入 PUBLISHED。
        return detail(pipeline.approvePublish(id, req.approver()).runId());
    }

    /**
     * HITL 卡点2：驳回签发（卡控主线继续留在 REJECTED），并记录理由与签发人；
     * 被驳回 run 不会进入自动重算，通常配合新一轮重新建 run 使用。
     */
    @PostMapping("/runs/{id}/publish/reject")
    public RunDetail rejectPublish(@PathVariable long id, @RequestBody RejectRequest req) {
        return detail(pipeline.rejectPublish(id, req.approver(), req.reason()).runId());
    }

    /** 卡点2 归因确认（Phase05 T0 拍板：勾选 + confirmed_by/at 留痕）：仅 hypothesis 可升 confirmed。 */
    @PostMapping("/runs/{id}/claims/{claimId}/confirm")
    public RunDetail confirmClaim(@PathVariable long id, @PathVariable String claimId,
                                  @RequestBody PublishRequest req) {
        // Claim 只记录人工确认行为，不直接改变 run 主流程状态，确保可重复点击不会触发误迁移。
        pipeline.confirmClaim(id, claimId, req.approver());
        return detail(id);
    }

    /** 断点续跑：仅 BLOCKED（或已停摆的 RUNNING）。 */
    @PostMapping("/runs/{id}/resume")
    public RunDetail resume(@PathVariable long id) {
        // resume 不是幂等重跑全部；内部按失败点位决定从 SPEC 还是 WRITE/AUDIT 续跑，避免重复取数抖动。
        return detail(pipeline.resume(id).runId());
    }

    /**
     * 已签发报告导出（仅 PUBLISHED）：正文/事实/图表数据一律取库中终稿，客户端只上送图表 PNG；
     * 每图必附数据表（display_value 同源），无图降级纯数据表。format = pdf | docx。
     */
    @PostMapping("/runs/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id, @RequestParam String format,
                                         @RequestBody(required = false) ExportRequest req) {
        // 导出输入不含业务正文，客户端仅传可选图表二进制，避免前端篡改事实值导致“最终稿脱钩”。
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
        // 刻意不做分页：facts 是为审计复核设计的完整账本，必须支持一次性核对 run 全链路计算结果。
        pipeline.require(id);
        return factRepo.findByRun(id);
    }

    /**
     * 血缘导出（P6 契约1，只读）：run 级血缘单文档（需求→模板/指标版本→spec→SQL→fact→
     * 图表/归因→审批），字段全部同源于库中留痕；装配断链 → 400 带断点明细（纪律 14）。
     */
    @GetMapping("/runs/{id}/lineage")
    public LineageAssembler.LineageDoc lineage(@PathVariable long id) {
        // 只读装配，装配异常意味着存在不完整留痕；返回 400 便于快速定位“哪些节点没有落库”。
        return lineageService.export(id);
    }

    /**
     * 运行看板统计（P6 契约3，只读）：run 状态分布 / BLOCKED 原因 TopN / 各步成功率·重试率·
     * 阻断率与 P50/P95 耗时 / LLM 用量与可选成本。缺省近 30 天；from=all 查全量。
     */
    @GetMapping("/stats")
    public RunStatsService.StatsResponse stats(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        return statsService.stats(from, to);
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

    /**
     * 运行详情聚合视图。
     * 读顺序固定为 run -> step -> fact -> claim，保证前端轮询下“run 基础信息与明细账本”一致性。
     */
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
