package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.report.pipeline.MqlTemplateFiller;
import com.treasury.nl2sql.report.store.AssetRow;
import com.treasury.nl2sql.report.store.MetricAssetRepository;
import com.treasury.nl2sql.report.store.TemplateAssetRepository;
import com.treasury.nl2sql.validate.MqlValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 报告资产注册表：模板与指标以库（report_template / report_metric）为唯一事实源，
 * classpath JSON（report/templates/*.json + report/metrics.json）仅作空库种子。
 * 启动流程：种子幂等导入（按资产 id 粒度，DEPRECATED 也算存在——人为下架不被种子复活）
 * → 加载全部 PUBLISHED 入内存缓存（同一 id 双 PUBLISHED 即启动失败）→ 全量自检。
 * 启动即自检（资产坏了尽早炸，不留到运行期）：
 *  - 模板 keywords 非空（运行期匹配召回依赖）；引用的 metricId 必须全部存在；
 *  - 派生指标的操作数必须存在且为取数指标；
 *  - 每个取数指标的 mqlTemplate 用哨兵日期填充后过一遍 MqlValidator（对真实 schema 白名单校验）。
 */
@Service
public class ReportAssetService {

    private static final Logger log = LoggerFactory.getLogger(ReportAssetService.class);
    private static final String SEED_USER = "seed";

    /** 缓存值：模板定义 + 其 PUBLISHED 版本号（创建 run 时固化进 report_run.template_version）。 */
    public record VersionedTemplate(ReportTemplateDef def, int version) {}

    private final ObjectMapper mapper;
    private final MqlValidator validator;
    private final TemplateAssetRepository templateRepo;
    private final MetricAssetRepository metricRepo;

    private final Map<String, VersionedTemplate> templates = new LinkedHashMap<>();
    private final Map<String, MetricDefinition> metrics = new LinkedHashMap<>();

    public ReportAssetService(ObjectMapper mapper, MqlValidator validator,
                              TemplateAssetRepository templateRepo, MetricAssetRepository metricRepo) {
        this.mapper = mapper;
        this.validator = validator;
        this.templateRepo = templateRepo;
        this.metricRepo = metricRepo;
    }

    @PostConstruct
    public void load() {
        int seededMetrics = seedMetrics();
        loadMetricsFromDb();
        int seededTemplates = seedTemplates();
        loadTemplatesFromDb();
        selfCheck();
        log.info("报告资产注册表就绪: 本次种入模板 {} 个、指标 {} 个；已加载 PUBLISHED 模板 {} 个、指标 {} 个并自检通过",
                seededTemplates, seededMetrics, templates.size(), metrics.size());
    }

    // ---------- 种子导入（幂等：按资产 id 粒度 existsById） ----------

    private int seedMetrics() {
        MetricDefinition[] seeds;
        try (var in = new ClassPathResource("report/metrics.json").getInputStream()) {
            seeds = mapper.readValue(in, MetricDefinition[].class);
        } catch (Exception e) {
            throw new RuntimeException("加载指标种子失败(report/metrics.json)", e);
        }
        // 种入前先对种子集合整体自检（含派生指标操作数），坏种子不落库、启动即炸
        Map<String, MetricDefinition> seedMap = new LinkedHashMap<>();
        for (MetricDefinition m : seeds) {
            if (seedMap.put(m.metricId(), m) != null) {
                throw new IllegalStateException("指标种子重复: " + m.metricId());
            }
        }
        for (MetricDefinition m : seedMap.values()) {
            checkMetric(m, seedMap);
        }
        int seeded = 0;
        for (MetricDefinition m : seedMap.values()) {
            if (metricRepo.existsById(m.metricId())) continue;
            metricRepo.insertNewVersion(m.metricId(), m.name(), toJson(m),
                    "PUBLISHED", "SEED", SEED_USER, "classpath 种子");
            seeded++;
        }
        return seeded;
    }

    private int seedTemplates() {
        List<Resource> files;
        try {
            files = List.of(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:report/templates/*.json"));
        } catch (Exception e) {
            throw new RuntimeException("扫描模板种子目录失败(report/templates/)", e);
        }
        int seeded = 0;
        for (Resource r : files) {
            ReportTemplateDef tpl;
            try (var in = r.getInputStream()) {
                tpl = mapper.readValue(in, ReportTemplateDef.class);
            } catch (Exception e) {
                throw new RuntimeException("加载模板种子失败: " + r.getFilename(), e);
            }
            // 种入前自检：keywords 非空 + 引用指标必须已在注册表（指标先种先载）
            checkTemplate(tpl, metrics);
            if (templateRepo.existsById(tpl.templateId())) continue;
            templateRepo.insertNewVersion(tpl.templateId(), tpl.name(), toJson(tpl),
                    "PUBLISHED", "SEED", SEED_USER, "classpath 种子");
            seeded++;
        }
        return seeded;
    }

    // ---------- 从库装缓存（库是唯一事实源） ----------

    private void loadMetricsFromDb() {
        metrics.clear();
        for (AssetRow row : metricRepo.findPublished()) {
            MetricDefinition m = fromJson(row.bodyJson(), MetricDefinition.class,
                    "指标 " + row.assetId() + " v" + row.version());
            if (metrics.put(m.metricId(), m) != null) {
                throw new IllegalStateException("指标「" + m.metricId()
                        + "」存在多个 PUBLISHED 版本，违反资产不变量，拒绝启动");
            }
        }
    }

    private void loadTemplatesFromDb() {
        templates.clear();
        for (AssetRow row : templateRepo.findPublished()) {
            ReportTemplateDef tpl = fromJson(row.bodyJson(), ReportTemplateDef.class,
                    "模板 " + row.assetId() + " v" + row.version());
            if (templates.put(tpl.templateId(), new VersionedTemplate(tpl, row.version())) != null) {
                throw new IllegalStateException("模板「" + tpl.templateId()
                        + "」存在多个 PUBLISHED 版本，违反资产不变量，拒绝启动");
            }
        }
    }

    // ---------- 自检（对缓存全量执行，覆盖库中手工写入的资产） ----------

    private void selfCheck() {
        if (templates.isEmpty()) {
            throw new IllegalStateException("注册表中没有任何 PUBLISHED 模板（种子导入失败或被全部下架）");
        }
        for (VersionedTemplate vt : templates.values()) {
            checkTemplate(vt.def(), metrics);
        }
        for (MetricDefinition m : metrics.values()) {
            checkMetric(m, metrics);
        }
    }

    /** 与保存/validate 端点共用 TemplateValidator 规则，任何错误即启动失败。 */
    private void checkTemplate(ReportTemplateDef tpl, Map<String, MetricDefinition> catalog) {
        List<TemplateValidator.ValidationError> errors = TemplateValidator.validate(tpl, catalog.keySet());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("模板「" + (tpl == null ? "?" : tpl.templateId()) + "」未通过校验: "
                    + errors.stream().map(e -> e.location() + " " + e.message()).collect(Collectors.joining("；")));
        }
    }

    private void checkMetric(MetricDefinition m, Map<String, MetricDefinition> catalog) {
        if (m.isDerivedMetric()) {
            if (m.mqlTemplate() != null && !m.mqlTemplate().isNull()) {
                throw new IllegalStateException("指标「" + m.metricId() + "」不能同时携带 derived 与 mqlTemplate");
            }
            for (String ref : List.of(m.derived().left(), m.derived().right())) {
                MetricDefinition dep = catalog.get(ref);
                if (dep == null || dep.isDerivedMetric()) {
                    throw new IllegalStateException("派生指标「" + m.metricId() + "」的操作数「" + ref
                            + "」不存在或不是取数指标");
                }
            }
            return;
        }
        if (m.valueColumn() == null || m.valueColumn().isBlank()) {
            throw new IllegalStateException("指标「" + m.metricId() + "」缺少 valueColumn");
        }
        Map<String, String> sentinel = Map.of("period_start", "2026-06-22", "period_end", "2026-06-28");
        Mql mql = MqlTemplateFiller.fill(mapper, m.metricId(), m.mqlTemplate(), sentinel);
        List<String> errors = validator.validate(mql);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("指标「" + m.metricId() + "」的 MQL 模板未通过校验: " + errors);
        }
    }

    // ---------- 查询 API ----------

    public Optional<ReportTemplateDef> template(String templateId) {
        VersionedTemplate vt = templates.get(templateId);
        return vt == null ? Optional.empty() : Optional.of(vt.def());
    }

    public List<ReportTemplateDef> allTemplates() {
        return templates.values().stream().map(VersionedTemplate::def).toList();
    }

    /** 模板的 PUBLISHED 版本号（创建 run 时固化；模板必须存在于注册表）。 */
    public int publishedVersion(String templateId) {
        VersionedTemplate vt = templates.get(templateId);
        if (vt == null) {
            throw new IllegalStateException("注册表中不存在 PUBLISHED 模板: " + templateId);
        }
        return vt.version();
    }

    public Optional<MetricDefinition> metric(String metricId) {
        return Optional.ofNullable(metrics.get(metricId));
    }

    public Map<String, MetricDefinition> allMetrics() {
        return metrics;
    }

    /** 指标目录（给 ① 的 LLM prompt：只暴露 id/名称/单位/是否期间指标，不暴露 MQL）。 */
    public String metricCatalogText() {
        StringBuilder sb = new StringBuilder();
        for (MetricDefinition m : metrics.values()) {
            sb.append("- ").append(m.metricId()).append("：").append(m.name())
              .append("（单位 ").append(m.unit()).append(m.timeBound() ? "，期间指标" : "，快照指标").append("）\n");
        }
        return sb.toString();
    }

    // ---------- JSON 工具 ----------

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("资产序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type, String what) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("库中资产 body_json 无法解析（" + what + "），拒绝启动", e);
        }
    }
}
