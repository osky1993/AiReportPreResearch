package com.treasury.nl2sql.report.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.report.pipeline.MqlTemplateFiller;
import com.treasury.nl2sql.validate.MqlValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报告支撑资产加载器：1 个模板 + 指标语义定义（resources/report/*.json）。
 * 启动即自检（资产坏了尽早炸，不留到运行期）：
 *  - 模板引用的 metricId 必须全部存在；派生指标的操作数必须存在且为取数指标；
 *  - 每个取数指标的 mqlTemplate 用哨兵日期填充后过一遍 MqlValidator（对真实 schema 白名单校验）。
 */
@Service
public class ReportAssetService {

    private static final Logger log = LoggerFactory.getLogger(ReportAssetService.class);

    private final ObjectMapper mapper;
    private final MqlValidator validator;

    private ReportTemplateDef template;
    private final Map<String, MetricDefinition> metrics = new LinkedHashMap<>();

    public ReportAssetService(ObjectMapper mapper, MqlValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    @PostConstruct
    public void load() {
        try (var in = new ClassPathResource("report/metrics.json").getInputStream()) {
            MetricDefinition[] arr = mapper.readValue(in, MetricDefinition[].class);
            for (MetricDefinition m : arr) {
                if (metrics.put(m.metricId(), m) != null) {
                    throw new IllegalStateException("指标定义重复: " + m.metricId());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("加载指标语义定义失败", e);
        }
        try (var in = new ClassPathResource("report/template-treasury-weekly.json").getInputStream()) {
            template = mapper.readValue(in, ReportTemplateDef.class);
        } catch (Exception e) {
            throw new RuntimeException("加载报告模板失败", e);
        }
        selfCheck();
        log.info("报告资产已加载并自检通过: 模板「{}」({} 章), 指标语义定义 {} 个", template.name(),
                template.chapters().size(), metrics.size());
    }

    private void selfCheck() {
        for (ReportTemplateDef.ChapterDef ch : template.chapters()) {
            for (String id : ch.metrics()) {
                if (!metrics.containsKey(id)) {
                    throw new IllegalStateException("模板章节「" + ch.chapterId() + "」引用了不存在的指标: " + id);
                }
            }
        }
        Map<String, String> sentinel = Map.of("period_start", "2026-06-22", "period_end", "2026-06-28");
        for (MetricDefinition m : metrics.values()) {
            if (m.isDerived()) {
                if (m.mqlTemplate() != null) {
                    throw new IllegalStateException("指标「" + m.metricId() + "」不能同时携带 derived 与 mqlTemplate");
                }
                for (String ref : List.of(m.derived().left(), m.derived().right())) {
                    MetricDefinition dep = metrics.get(ref);
                    if (dep == null || dep.isDerived()) {
                        throw new IllegalStateException("派生指标「" + m.metricId() + "」的操作数「" + ref
                                + "」不存在或不是取数指标");
                    }
                }
                continue;
            }
            if (m.valueColumn() == null || m.valueColumn().isBlank()) {
                throw new IllegalStateException("指标「" + m.metricId() + "」缺少 valueColumn");
            }
            Mql mql = MqlTemplateFiller.fill(mapper, m.metricId(), m.mqlTemplate(), sentinel);
            List<String> errors = validator.validate(mql);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("指标「" + m.metricId() + "」的 MQL 模板未通过校验: " + errors);
            }
        }
    }

    public ReportTemplateDef template() {
        return template;
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
}
