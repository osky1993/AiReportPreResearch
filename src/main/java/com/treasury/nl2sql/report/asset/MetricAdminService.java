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
 * 指标资产管理服务（P5 契约）。
 *
 * <p>规则：指标只能以“新版本”方式演进，且新建前必须通过五重校验后才落入 DRAFT。
 * 五重校验覆盖：结构边界、占位符策略、MQL 白名单与形状校验、真实库试执行、返回行列形状。
 * 任何失败都会以结构化错误返回，便于前端按检查类型逐项提示。
 * </p>
 */
@Service
public class MetricAdminService {

    private static final Logger log = LoggerFactory.getLogger(MetricAdminService.class);
    private static final Pattern METRIC_ID = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Set<String> NULL_POLICIES = Set.of(MetricDefinition.NULL_ZERO, MetricDefinition.NULL_BLOCK);
    private static final Set<String> QUALITY_CHECKS = Set.of(MetricDefinition.CHECK_NON_NEGATIVE);

    /** 列表页摘要：给前端提供“最新版本与已发布标识”的最小展示模型。 */
    public record MetricSummary(String metricId, String name, int latestVersion,
                               Integer publishedVersion, String latestStatus, String source,
                               LocalDateTime updatedAt) {}
    /** 版本明细模型：每行对应一个版本 row。 */
    public record VersionInfo(int version, String name, String status, String source,
                             String createdBy, LocalDateTime createdAt, String remark) {}
    /** 详情模型：历史版本 + 最新发布版本 + 当前最新版本，前端“回溯-编辑”共用。 */
    public record MetricDetail(String metricId, List<VersionInfo> versions,
                              MetricDefinition published, MetricDefinition latest) {}
    /** 写入或状态流转结果模型。 */
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

    /** 列表查询：按业务 id 分组，返回每组最新版本并保留“发布版本号”用于页面红绿标识。 */
    public List<MetricSummary> list() {
        Map<String, List<AssetRow>> grouped = new LinkedHashMap<>();
        // findAll 已按版本倒序返回，rows[0] 为该 metricId 的最新版本；这样列表显示的是“当前视图”而不是“发布视图”。
        for (AssetRow row : repo.findAll()) {
            grouped.computeIfAbsent(row.assetId(), k -> new ArrayList<>()).add(row);
        }
        List<MetricSummary> out = new ArrayList<>();
        for (List<AssetRow> rows : grouped.values()) {
            AssetRow latest = rows.get(0);
            // 发布版本可能不存在（可为 null）；列表只展示是否已发布，不把无发布状态误导为 0 号版本。
            Integer publishedVersion = rows.stream()
                    .filter(r -> "PUBLISHED".equals(r.status()))
                    .map(AssetRow::version).findFirst().orElse(null);
            out.add(new MetricSummary(latest.assetId(), latest.name(), latest.version(),
                    publishedVersion, latest.status(), latest.source(), latest.createdAt()));
        }
        return out;
    }

    /** 详情查询：返回该指标全部版本链与发布点快照；找不到则返回 404。 */
    public MetricDetail detail(String metricId) {
        List<AssetRow> rows = repo.findByAssetId(metricId);
        if (rows.isEmpty()) {
            throw new NotFoundException("指标不存在: " + metricId);
        }
        List<VersionInfo> versions = rows.stream()
                .map(r -> new VersionInfo(r.version(), r.name(), r.status(), r.source(),
                        r.createdBy(), r.createdAt(), r.remark()))
                .toList();
        // 返回已发布快照用于对比，仅在 detail 展示时使用，不影响编辑动作默认填充到 latest。
        MetricDefinition published = rows.stream()
                .filter(r -> "PUBLISHED".equals(r.status())).findFirst()
                .map(this::parse).orElse(null);
        return new MetricDetail(metricId, versions, published, parse(rows.get(0)));
    }

    // ---------- 保存（五重校验全过才写 v1 DRAFT） ----------

    /**
     * 五重校验后才落库（结构/占位符/白名单/试执行/结果形状）：
     * - 任何一层有错即 ValidationFailedException（结构化 details）；
     * - 通过才写 DRAFT，保证运行时可回溯“为什么没法发版”。
     */
    public SaveResult save(MetricDefinition m, String tryQuestion, String createdBy) {
        // ① STRUCTURE
        List<ValidationError> errors = new ArrayList<>();
        checkStructure(m, errors);
        failIfAny(errors);

        validateFetchChain(m);

        String remark = tryQuestion == null || tryQuestion.isBlank() ? "指标向导制作"
                : "试查问法: " + tryQuestion;
        // 新建永远写 DRAFT：必须经过独立发布动作才允许上线，避免生成即发布带来的误发布风险。
        int v = repo.insertNewVersion(m.metricId(), m.name(), toJson(m),
                "DRAFT", "MANUAL", blankTo(createdBy), remark);
        log.info("[METRIC-ADMIN] 新建指标 {} v{} DRAFT by {}", m.metricId(), v, createdBy);
        return new SaveResult(m.metricId(), v, "DRAFT");
    }

    /**
     * 存量指标发新版（Gate2：补 description/category 元数据、修口径等）。与 save() 的差异仅两点：
     * ① id 必须已存在（save 是拒绝重名新建）；② 允许派生指标（种子里有 derived 指标，向导不做但改版要能覆盖）——
     * 派生指标无 MQL，跳过取数校验链，只查派生引用存在性；深层引用规则由 publish 时 assets.reload() 自检兜底（失败回滚）。
     * 产物一律新版本 DRAFT——行不可变，绝无 UPDATE body_json。
     */
    public SaveResult saveNewVersion(String metricId, MetricDefinition m, String createdBy, String remark) {
        List<ValidationError> errors = new ArrayList<>();
        if (m == null) {
            throw new ValidationFailedException(List.of(err("STRUCTURE", "指标体为空")));
        }
        if (!metricId.equals(m.metricId())) {
            errors.add(err("STRUCTURE", "路径 id 与指标体 metricId 不一致: " + metricId + " ≠ " + m.metricId()));
        }
        if (!repo.existsById(metricId)) {
            errors.add(err("STRUCTURE", "指标不存在，存量改版要求 id 已入库: " + metricId));
        }
        checkCommonStructure(m, errors);
        if (m.isDerivedMetric()) {
            if (m.mqlTemplate() != null && !m.mqlTemplate().isNull()) {
                errors.add(err("STRUCTURE", "派生指标不得同时携带 mqlTemplate"));
            }
            Map<String, MetricDefinition> catalog = assets.allMetrics();
            for (String ref : List.of(String.valueOf(m.derived().left()), String.valueOf(m.derived().right()))) {
                if (!catalog.containsKey(ref)) {
                    errors.add(err("STRUCTURE", "派生引用的指标不存在: " + ref));
                }
            }
            failIfAny(errors);
        } else {
            if (m.valueColumn() == null || m.valueColumn().isBlank()) {
                errors.add(err("STRUCTURE", "valueColumn 不能为空"));
            }
            if (m.mqlTemplate() == null || m.mqlTemplate().isNull()) {
                errors.add(err("STRUCTURE", "缺少 mqlTemplate"));
            }
            failIfAny(errors);
            validateFetchChain(m);
        }
        int v = repo.insertNewVersion(metricId, m.name(), toJson(m),
                "DRAFT", "MANUAL", blankTo(createdBy),
                remark == null || remark.isBlank() ? "存量改版" : remark);
        log.info("[METRIC-ADMIN] 指标 {} 存量改版 v{} DRAFT by {}", metricId, v, createdBy);
        return new SaveResult(metricId, v, "DRAFT");
    }

    /** 取数指标的 ②~⑤ 重校验（占位符/白名单/试执行/结果形状），save 与 saveNewVersion 共用。 */
    private void validateFetchChain(MetricDefinition m) {
        List<ValidationError> errors = new ArrayList<>();
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
            // 用固定哨兵参数渲染，不是为了验证返回值，而是为了检测“未替换占位符”风险与参数非法字段。
            filled = MqlTemplateFiller.fill(mapper, m.metricId(), m.mqlTemplate(), MqlTemplateFiller.SENTINEL_PARAMS);
        } catch (PolicyException e) {
            throw new ValidationFailedException(List.of(err("PLACEHOLDER", e.getMessage())));
        }

        // ③ MQL_VALIDATION：白名单校验 + 维度声明↔groupBy 一致性 + 异常规则形状（与启动自检共用规则类）
        List<String> mqlErrors = new ArrayList<>(trial.validate(filled));
        mqlErrors.addAll(MetricDimensionRule.check(m, filled));
        mqlErrors.addAll(MetricAnomalyRule.check(m));
        if (!mqlErrors.isEmpty()) {
            throw new ValidationFailedException(mqlErrors.stream()
                    .map(msg -> err("MQL_VALIDATION", msg)).toList());
        }

        // ④ TRIAL_EXECUTION：真实库只读试执行
        List<Map<String, Object>> rows;
        try {
            // 真库一次只读执行用于提前暴露“能编译但不能运行”的 SQL；失败会变成结构化提示。
            rows = trial.execute(filled);
        } catch (PolicyException e) {
            throw new ValidationFailedException(List.of(err("TRIAL_EXECUTION", e.getMessage())));
        }

        // ⑤ RESULT_SHAPE：单值指标恰 1 行；维度指标 1~上限 行且每行含维度列（Phase03）
        if (!m.isDimensional() && rows.size() != 1) {
            throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                    "试执行返回 " + rows.size() + " 行——指标要求恰 1 行 1 值，请改为聚合口径或收窄条件；"
                            + "按维度拆解请声明 dimensions")));
        }
        if (m.isDimensional()) {
            if (rows.isEmpty() || rows.size() > MetricDimensionRule.MAX_DIMENSION_ROWS) {
                throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                        "维度指标试执行返回 " + rows.size() + " 行——要求 1~" + MetricDimensionRule.MAX_DIMENSION_ROWS
                                + " 行（0 行无法验证形状，超限见契约2 上限）")));
            }
            for (String d : m.dimensions()) {
                if (!rows.get(0).containsKey(d)) {
                    throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                            "试执行结果中不存在维度列「" + d + "」，实际列: " + rows.get(0).keySet())));
                }
            }
        }
        if (!rows.get(0).containsKey(m.valueColumn())) {
            throw new ValidationFailedException(List.of(err("RESULT_SHAPE",
                    "试执行结果中不存在 valueColumn「" + m.valueColumn() + "」，实际列: " + rows.get(0).keySet())));
        }
    }

    /**
     * 结构层校验：指标定义边界（id、name、metricTemplate 是否存在、可比性与 nullPolicy一致性）。
     * 这里故意不校验 mql 语义，留到后续哨兵填充和 validator 阶段。
     */
    private void checkStructure(MetricDefinition m, List<ValidationError> errors) {
        if (m == null) {
            errors.add(err("STRUCTURE", "指标体为空"));
            return;
        }
        checkCommonStructure(m, errors);
        if (m.metricId() != null && METRIC_ID.matcher(m.metricId()).matches()
                && repo.existsById(m.metricId())) {
            errors.add(err("STRUCTURE", "指标已存在，不允许重名新建: " + m.metricId()));
        }
        if (m.valueColumn() == null || m.valueColumn().isBlank()) errors.add(err("STRUCTURE", "valueColumn 不能为空"));
        if (m.derived() != null) {
            errors.add(err("STRUCTURE", "向导只制作取数指标，不接受 derived 派生定义"));
        }
        if (m.mqlTemplate() == null || m.mqlTemplate().isNull()) {
            errors.add(err("STRUCTURE", "缺少 mqlTemplate"));
        }
    }

    /** 新建与存量改版共用的字段级结构校验（id 形态/名称/单位/nullPolicy/质量断言/可比一致性）。 */
    private void checkCommonStructure(MetricDefinition m, List<ValidationError> errors) {
        if (m.metricId() == null || !METRIC_ID.matcher(m.metricId()).matches()) {
            errors.add(err("STRUCTURE", "metricId 必须匹配 ^[a-z][a-z0-9_]{2,63}$（当前: " + m.metricId() + "）"));
        }
        if (m.name() == null || m.name().isBlank()) errors.add(err("STRUCTURE", "指标名称不能为空"));
        if (m.unit() == null || m.unit().isBlank()) errors.add(err("STRUCTURE", "单位不能为空"));
        if (m.nullPolicy() == null || !NULL_POLICIES.contains(m.nullPolicy())) {
            errors.add(err("STRUCTURE", "nullPolicy 只允许 ZERO/BLOCK（当前: " + m.nullPolicy() + "）"));
        }
        if (m.qualityChecks() != null && !QUALITY_CHECKS.containsAll(m.qualityChecks())) {
            errors.add(err("STRUCTURE", "qualityChecks 只支持 NON_NEGATIVE（当前: " + m.qualityChecks() + "）"));
        }
        if (m.comparable() && !m.timeBound()) {
            errors.add(err("STRUCTURE", "comparable=true 要求 timeBound=true（快照指标无环比可言）"));
        }
    }

    // ---------- 状态流转（镜像 TemplateAdminService；守卫在服务端） ----------

    /** 发布：DRAFT→PUBLISHED；旧 PUBLISHED 自动 DEPRECATED；失败即回滚，坏指标不会上线。 */
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

    /** 下架：PUBLISHED 下架前会检测引用约束（被 PUBLISHED 模板引用则拒绝）；
     * 作用是保护已在运行中的报表资产不会出现断链。
     */
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
