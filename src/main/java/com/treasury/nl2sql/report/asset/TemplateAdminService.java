package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.pipeline.TemplateMatcher;
import com.treasury.nl2sql.report.store.AssetRow;
import com.treasury.nl2sql.report.store.TemplateAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板资产管理服务（P2 契约的服务端实现，一切校验在此执行，前端只做展示与预检）。
 * 保存 = 校验通过才写新版本 DRAFT；行不可变，绝无 UPDATE body_json。
 */
@Service
public class TemplateAdminService {

    private static final Logger log = LoggerFactory.getLogger(TemplateAdminService.class);

    /** 校验失败：details 逐条含 location（controller 转 400 统一错误结构）。 */
    public static class ValidationFailedException extends RuntimeException {
        private final transient List<ValidationError> errors;
        public ValidationFailedException(List<ValidationError> errors) {
            super("模板校验未通过（" + errors.size() + " 处）");
            this.errors = errors;
        }
        public List<ValidationError> errors() { return errors; }
    }

    /** 404 语义（controller 单独映射，与 400 区分）。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public record TemplateSummary(String templateId, String name, int latestVersion,
                                  Integer publishedVersion, String latestStatus, String source,
                                  LocalDateTime updatedAt) {}
    public record VersionInfo(int version, String name, String status, String source,
                              String createdBy, LocalDateTime createdAt, String remark) {}
    public record TemplateDetail(String templateId, List<VersionInfo> versions,
                                 ReportTemplateDef published, ReportTemplateDef latest) {}
    public record VersionBody(int version, String status, ReportTemplateDef template) {}
    public record SaveResult(String templateId, int version, String status) {}

    private final TemplateAssetRepository repo;
    private final ReportAssetService assets;
    private final TemplateMatcher matcher;
    private final ObjectMapper mapper;

    public TemplateAdminService(TemplateAssetRepository repo, ReportAssetService assets,
                                TemplateMatcher matcher, ObjectMapper mapper) {
        this.repo = repo;
        this.assets = assets;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    // ---------- 读 ----------

    public List<TemplateSummary> list() {
        Map<String, List<AssetRow>> grouped = new LinkedHashMap<>();
        for (AssetRow row : repo.findAll()) {
            grouped.computeIfAbsent(row.assetId(), k -> new ArrayList<>()).add(row);
        }
        List<TemplateSummary> out = new ArrayList<>();
        for (List<AssetRow> rows : grouped.values()) {
            AssetRow latest = rows.get(0);   // findAll 已按 version DESC
            Integer publishedVersion = rows.stream()
                    .filter(r -> "PUBLISHED".equals(r.status()))
                    .map(AssetRow::version).findFirst().orElse(null);
            out.add(new TemplateSummary(latest.assetId(), latest.name(), latest.version(),
                    publishedVersion, latest.status(), latest.source(), latest.createdAt()));
        }
        return out;
    }

    public TemplateDetail detail(String templateId) {
        List<AssetRow> rows = repo.findByAssetId(templateId);
        if (rows.isEmpty()) {
            throw new NotFoundException("模板不存在: " + templateId);
        }
        List<VersionInfo> versions = rows.stream()
                .map(r -> new VersionInfo(r.version(), r.name(), r.status(), r.source(),
                        r.createdBy(), r.createdAt(), r.remark()))
                .toList();
        ReportTemplateDef published = rows.stream()
                .filter(r -> "PUBLISHED".equals(r.status())).findFirst()
                .map(this::parse).orElse(null);
        ReportTemplateDef latest = parse(rows.get(0));
        return new TemplateDetail(templateId, versions, published, latest);
    }

    public VersionBody version(String templateId, int version) {
        AssetRow row = repo.findByIdAndVersion(templateId, version)
                .orElseThrow(() -> new NotFoundException("模板版本不存在: " + templateId + " v" + version));
        return new VersionBody(row.version(), row.status(), parse(row));
    }

    // ---------- 写（校验通过才落库，全部 DRAFT） ----------

    /** 新建：templateId 不得已存在，写 v1 DRAFT。 */
    public SaveResult create(ReportTemplateDef tpl, String createdBy, String remark) {
        validateOrThrow(tpl);
        if (repo.existsById(tpl.templateId())) {
            throw new IllegalArgumentException("模板已存在，不允许重复新建（请用保存产生新版本）: " + tpl.templateId());
        }
        int v = repo.insertNewVersion(tpl.templateId(), tpl.name(), toJson(tpl),
                "DRAFT", "MANUAL", blankTo(createdBy), remark);
        log.info("[TPL-ADMIN] 新建模板 {} v{} DRAFT by {}", tpl.templateId(), v, createdBy);
        return new SaveResult(tpl.templateId(), v, "DRAFT");
    }

    /** 保存：已有模板写新版本 DRAFT（body 内 templateId 必须与路径一致）。 */
    public SaveResult saveNewVersion(String pathTemplateId, ReportTemplateDef tpl, String createdBy, String remark) {
        if (tpl == null || !pathTemplateId.equals(tpl.templateId())) {
            throw new IllegalArgumentException("body 内 templateId 与路径不一致: "
                    + (tpl == null ? null : tpl.templateId()) + " ≠ " + pathTemplateId);
        }
        if (!repo.existsById(pathTemplateId)) {
            throw new NotFoundException("模板不存在（新建请走 POST）: " + pathTemplateId);
        }
        validateOrThrow(tpl);
        int v = repo.insertNewVersion(tpl.templateId(), tpl.name(), toJson(tpl),
                "DRAFT", "MANUAL", blankTo(createdBy), remark);
        log.info("[TPL-ADMIN] 保存模板 {} 新版本 v{} DRAFT by {}", tpl.templateId(), v, createdBy);
        return new SaveResult(tpl.templateId(), v, "DRAFT");
    }

    // ---------- 状态流转（守卫在服务端；publish 的旧版下线+新版上线+缓存刷新一个事务内完成） ----------

    /**
     * DRAFT→PUBLISHED；同一模板旧 PUBLISHED 自动置 DEPRECATED（保持单一 PUBLISHED 不变量）。
     * reload/自检失败即异常 → 事务回滚，坏资产不会上线。
     */
    @Transactional
    public SaveResult publish(String templateId, int version) {
        AssetRow row = requireVersion(templateId, version);
        if (!"DRAFT".equals(row.status())) {
            throw new IllegalArgumentException("只允许 DRAFT→PUBLISHED（当前 " + row.status()
                    + "）: " + templateId + " v" + version);
        }
        repo.findByAssetId(templateId).stream()
                .filter(r -> "PUBLISHED".equals(r.status()))
                .forEach(r -> repo.updateStatus(templateId, r.version(), "DEPRECATED"));
        repo.updateStatus(templateId, version, "PUBLISHED");
        assets.reload();
        matcher.refresh();
        log.info("[TPL-ADMIN] 发布模板 {} v{}（旧 PUBLISHED 已置 DEPRECATED）", templateId, version);
        return new SaveResult(templateId, version, "PUBLISHED");
    }

    /**
     * DRAFT/PUBLISHED→DEPRECATED（终态，不物理删除）。
     * 下线 PUBLISHED 版本即从运行匹配摘除（在跑 run 用 outline 快照不受影响）；
     * 若这是库中最后一个 PUBLISHED 模板，reload 自检会拒绝（注册表不允许为空）→ 事务回滚。
     */
    @Transactional
    public SaveResult deprecate(String templateId, int version) {
        AssetRow row = requireVersion(templateId, version);
        if ("DEPRECATED".equals(row.status())) {
            throw new IllegalArgumentException("已是 DEPRECATED，非法流转: " + templateId + " v" + version);
        }
        repo.updateStatus(templateId, version, "DEPRECATED");
        if ("PUBLISHED".equals(row.status())) {
            assets.reload();
            matcher.refresh();
        }
        log.info("[TPL-ADMIN] 下架模板 {} v{}（原状态 {}）", templateId, version, row.status());
        return new SaveResult(templateId, version, "DEPRECATED");
    }

    private AssetRow requireVersion(String templateId, int version) {
        return repo.findByIdAndVersion(templateId, version)
                .orElseThrow(() -> new NotFoundException("模板版本不存在: " + templateId + " v" + version));
    }

    /** 干跑校验（validate 端点：不写库，返回全部错误）。 */
    public List<ValidationError> validateOnly(ReportTemplateDef tpl) {
        return TemplateValidator.validate(tpl, assets.allMetrics().keySet());
    }

    private void validateOrThrow(ReportTemplateDef tpl) {
        List<ValidationError> errors = validateOnly(tpl);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }

    // ---------- 工具 ----------

    private ReportTemplateDef parse(AssetRow row) {
        try {
            return mapper.readValue(row.bodyJson(), ReportTemplateDef.class);
        } catch (Exception e) {
            throw new IllegalStateException("库中模板 body_json 无法解析: " + row.assetId() + " v" + row.version(), e);
        }
    }

    private String toJson(ReportTemplateDef tpl) {
        try {
            return mapper.writeValueAsString(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("模板序列化失败", e);
        }
    }

    private static String blankTo(String createdBy) {
        return createdBy == null || createdBy.isBlank() ? "demo" : createdBy;
    }
}
