package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.report.asset.TemplateAdminService.NotFoundException;
import com.treasury.nl2sql.report.asset.TemplateAdminService.ValidationFailedException;
import com.treasury.nl2sql.report.asset.TemplateValidator.ValidationError;
import com.treasury.nl2sql.report.pipeline.MqlTemplateFiller;
import com.treasury.nl2sql.report.pipeline.MqlTrialExecutor;
import com.treasury.nl2sql.report.pipeline.PolicyException;
import com.treasury.nl2sql.report.store.AssetRow;
import com.treasury.nl2sql.report.store.MetricAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标资产管理服务（P5 契约；镜像 TemplateAdminService：校验在服务端、行不可变、单一 PUBLISHED）。
 * 保存走五重校验（结构 → 哨兵填充无残留 → 白名单校验 → 真实库试执行 → 恰 1 行 1 值），
 * 全过才写 DRAFT；错误 details 的 location 承载 check 类别
 * （STRUCTURE / PLACEHOLDER / MQL_VALIDATION / TRIAL_EXECUTION / RESULT_SHAPE），前端按类归红字。
 */
@Service
public class MetricAdminService {

    private static final Logger log = LoggerFactory.getLogger(MetricAdminService.class);
    private static final Pattern METRIC_ID = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Set<String> NULL_POLICIES = Set.of(MetricDefinition.NULL_ZERO, MetricDefinition.NULL_BLOCK);
    private static final Set<String> QUALITY_CHECKS = Set.of(MetricDefinition.CHECK_NON_NEGATIVE);

    public record MetricSummary(String metricId, String name, int latestVersion,
                                Integer publishedVersion, String latestStatus, String source,
                                LocalDateTime updatedAt) {}
    public record VersionInfo(int version, String name, String status, String source,
                              String createdBy, LocalDateTime createdAt, String remark) {}
    public record MetricDetail(String metricId, List<VersionInfo> versions,
                               MetricDefinition published, MetricDefinition latest) {}
    public record SaveResult(String metricId, int version, String status) {}

    private final MetricAssetRepository repo;
    private final ReportAssetService assets;
    private final MqlTrialExecutor trial;
    private final ObjectMapper mapper;

    public MetricAdminService(MetricAssetRepository repo, ReportAssetService assets,
                              MqlTrialExecutor trial, ObjectMapper mapper) {
        this.repo = repo;
        this.assets = assets;
        this.trial = trial;
        this.mapper = mapper;
    }

    // ---------- 读 ----------

    public List<MetricSummary> list() {
        Map<String, List<AssetRow>> grouped = new LinkedHashMap<>();
        for (AssetRow row : repo.findAll()) {
            grouped.computeIfAbsent(row.assetId(), k -> new ArrayList<>()).add(row);
        }
        List<MetricSummary> out = new ArrayList<>();
        for (List<AssetRow> rows : grouped.values()) {
            AssetRow latest = rows.get(0);
            Integer publishedVersion = rows.stream()
                    .filter(r -> "PUBLISHED".equals(r.status()))
                    .map(AssetRow::version).findFirst().orElse(null);
            out.add(new MetricSummary(latest.assetId(), latest.name(), latest.version(),
                    publishedVersion, latest.status(), latest.source(), latest.createdAt()));
        }
        return out;
    }

    public MetricDetail detail(String metricId) {
        List<AssetRow> rows = repo.findByAssetId(metricId);
        if (rows.isEmpty()) {
            throw new NotFoundException("指标不存在: " + metricId);
        }
        List<VersionInfo> versions = rows.stream()
                .map(r -> new VersionInfo(r.version(), r.name(), r.status(), r.source(),
                        r.createdBy(), r.createdAt(), r.remark()))
                .toList();
        MetricDefinition published = rows.stream()
                .filter(r -> "PUBLISHED".equals(r.status())).findFirst()
                .map(this::parse).orElse(null);
        return new MetricDetail(metricId, versions, published, parse(rows.get(0)));
    }

    // ---------- 保存（五重校验全过才写 v1 DRAFT） ----------

    public SaveResult save(MetricDefinition m, String tryQuestion, String createdBy) {
        // ① STRUCTURE
        List<ValidationError> errors = new ArrayList<>();
        checkStructure(m, errors);
        failIfAny(errors);

        // ② PLACEHOLDER：哨兵填充无残留 + timeBound 双向一致性
        String tplStr = m.mqlTemplate().toString();
        if (m.timeBound() && !tplStr.contains("{{")) {
            errors.add(err("PLACEHOLDER", "期间指标（timeBound=true）的模板必须含 {{period_start}}/{{period_end}} 占位符——"
                    + "带死日期字面量入库会让指标永远取同一窗口"));
        }
        if (!m.timeBound() && tplStr.contains("{{")) {
            errors.add(err("PLACEHOLDER", "快照指标（timeBound=false）的模板不得含占位符"));
        }
        failIfAny(errors);
        Mql filled;
        try {
            filled = MqlTemplateFiller.fill(mapper, m.metricId(), m.mqlTemplate(), MqlTemplateFiller.SENTINEL_PARAMS);
        } catch (PolicyException e) {
            throw new ValidationFailedException(List.of(err("PLACEHOLDER", e.getMessage())));
        }

        // ③ MQL_VALIDATION：白名单校验
        List<String> mqlErrors = trial.validate(filled);
        if (!mqlErrors.isEmpty()) {
            throw new ValidationFailedException(mqlErrors.stream()
                    .map(msg -> err("MQL_VALIDATION", msg)).toList());
        }

        // ④ TRIAL_EXECUTION：真实库只读试执行
        List<Map<String, Object>> rows;
        try {
            rows = trial.execute(filled);
        } catch (PolicyException e) {
            throw new ValidationFailedException(List.of(err("TRIAL_EXECUTION", e.getMessage())));
        }

        // ⑤ RESULT_SHAPE：恰 1 行且 valueColumn 列存在
        if (rows.size() != 1) {
            throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                    "试执行返回 " + rows.size() + " 行——指标要求恰 1 行 1 值，请改为聚合口径或收窄条件")));
        }
        if (!rows.get(0).containsKey(m.valueColumn())) {
            throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                    "试执行结果中不存在 valueColumn「" + m.valueColumn() + "」，实际列: " + rows.get(0).keySet())));
        }

        String remark = tryQuestion == null || tryQuestion.isBlank() ? "指标向导制作"
                : "试查问法: " + tryQuestion;
        int v = repo.insertNewVersion(m.metricId(), m.name(), toJson(m),
                "DRAFT", "MANUAL", blankTo(createdBy), remark);
        log.info("[METRIC-ADMIN] 新建指标 {} v{} DRAFT by {}", m.metricId(), v, createdBy);
        return new SaveResult(m.metricId(), v, "DRAFT");
    }

    private void checkStructure(MetricDefinition m, List<ValidationError> errors) {
        if (m == null) {
            errors.add(err("STRUCTURE", "指标体为空"));
            return;
        }
        if (m.metricId() == null || !METRIC_ID.matcher(m.metricId()).matches()) {
            errors.add(err("STRUCTURE", "metricId 必须匹配 ^[a-z][a-z0-9_]{2,63}$（当前: " + m.metricId() + "）"));
        } else if (repo.existsById(m.metricId())) {
            errors.add(err("STRUCTURE", "指标已存在，不允许重名新建: " + m.metricId()));
        }
        if (m.name() == null || m.name().isBlank()) errors.add(err("STRUCTURE", "指标名称不能为空"));
        if (m.unit() == null || m.unit().isBlank()) errors.add(err("STRUCTURE", "单位不能为空"));
        if (m.valueColumn() == null || m.valueColumn().isBlank()) errors.add(err("STRUCTURE", "valueColumn 不能为空"));
        if (m.nullPolicy() == null || !NULL_POLICIES.contains(m.nullPolicy())) {
            errors.add(err("STRUCTURE", "nullPolicy 只允许 ZERO/BLOCK（当前: " + m.nullPolicy() + "）"));
        }
        if (m.qualityChecks() != null && !QUALITY_CHECKS.containsAll(m.qualityChecks())) {
            errors.add(err("STRUCTURE", "qualityChecks 只支持 NON_NEGATIVE（当前: " + m.qualityChecks() + "）"));
        }
        if (m.derived() != null) {
            errors.add(err("STRUCTURE", "向导只制作取数指标，不接受 derived 派生定义"));
        }
        if (m.mqlTemplate() == null || m.mqlTemplate().isNull()) {
            errors.add(err("STRUCTURE", "缺少 mqlTemplate"));
        }
        if (m.comparable() && !m.timeBound()) {
            errors.add(err("STRUCTURE", "comparable=true 要求 timeBound=true（快照指标无环比可言）"));
        }
    }

    // ---------- 状态流转（镜像 TemplateAdminService；守卫在服务端） ----------

    /** DRAFT→PUBLISHED；旧 PUBLISHED 自动 DEPRECATED；reload 失败即回滚，坏指标不上线。 */
    @Transactional
    public SaveResult publish(String metricId, int version) {
        AssetRow row = requireVersion(metricId, version);
        if (!"DRAFT".equals(row.status())) {
            throw new IllegalArgumentException("只允许 DRAFT→PUBLISHED（当前 " + row.status()
                    + "）: " + metricId + " v" + version);
        }
        repo.findByAssetId(metricId).stream()
                .filter(r -> "PUBLISHED".equals(r.status()))
                .forEach(r -> repo.updateStatus(metricId, r.version(), "DEPRECATED"));
        repo.updateStatus(metricId, version, "PUBLISHED");
        // 注册表重载即可；TemplateMatcher 无需 refresh——模板向量画像只含 name/keywords/章题，不含指标
        assets.reload();
        log.info("[METRIC-ADMIN] 发布指标 {} v{}", metricId, version);
        return new SaveResult(metricId, version, "PUBLISHED");
    }

    /** DRAFT/PUBLISHED→DEPRECATED；PUBLISHED 版本被任何 PUBLISHED 模板引用时拒绝下架。 */
    @Transactional
    public SaveResult deprecate(String metricId, int version) {
        AssetRow row = requireVersion(metricId, version);
        if ("DEPRECATED".equals(row.status())) {
            throw new IllegalArgumentException("已是 DEPRECATED，非法流转: " + metricId + " v" + version);
        }
        if ("PUBLISHED".equals(row.status())) {
            List<String> referencing = assets.templatesReferencing(metricId);
            if (!referencing.isEmpty()) {
                throw new IllegalArgumentException("指标「" + metricId + "」被 PUBLISHED 模板引用，不可下架: "
                        + String.join("、", referencing) + "（请先调整模板）");
            }
        }
        repo.updateStatus(metricId, version, "DEPRECATED");
        if ("PUBLISHED".equals(row.status())) {
            assets.reload();
        }
        log.info("[METRIC-ADMIN] 下架指标 {} v{}（原状态 {}）", metricId, version, row.status());
        return new SaveResult(metricId, version, "DEPRECATED");
    }

    // ---------- 工具 ----------

    private AssetRow requireVersion(String metricId, int version) {
        return repo.findByIdAndVersion(metricId, version)
                .orElseThrow(() -> new NotFoundException("指标版本不存在: " + metricId + " v" + version));
    }

    private static ValidationError err(String check, String message) {
        return new ValidationError(check, message);
    }

    private static void failIfAny(List<ValidationError> errors) {
        if (!errors.isEmpty()) throw new ValidationFailedException(List.copyOf(errors));
    }

    private MetricDefinition parse(AssetRow row) {
        try {
            return mapper.readValue(row.bodyJson(), MetricDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("库中指标 body_json 无法解析: " + row.assetId() + " v" + row.version(), e);
        }
    }

    private String toJson(MetricDefinition m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("指标序列化失败", e);
        }
    }

    private static String blankTo(String createdBy) {
        return createdBy == null || createdBy.isBlank() ? "demo" : createdBy;
    }
}
