package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ② 语义解析（纯程序）：确认后的大纲 → List&lt;MetricQuerySpec&gt;。
 * 规则：
 *  - 章节指标映射不到语义定义 → 失败关闭（人确认过的大纲里不该有未知指标）；
 *  - 派生指标不取数，但其操作数自动补进取数清单（人在卡点1 勾掉了操作数也不影响派生指标成立）；
 *  - comparison 章节里 comparable 的指标派生对比期规约（派生指标 → 其操作数派生对比期规约）；
 *  - 同指标多章共享同一规约（去重），chapterId 记首个引用章节。
 */
@Component
public class SpecResolveStep {

    private static final Logger log = LoggerFactory.getLogger(SpecResolveStep.class);

    public List<MetricQuerySpec> run(Outline outline, PeriodResolver.Window current, PeriodResolver.Window compare,
                                     Map<String, MetricDefinition> defs) {
        // metricId → 首个引用章节；保持章节内顺序（事实编号可复现）
        Map<String, String> fetchMetrics = new LinkedHashMap<>();
        Set<String> compareMetrics = new LinkedHashSet<>();

        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String metricId : ch.metricIds()) {
                MetricDefinition def = defs.get(metricId);
                if (def == null) {
                    throw new PolicyException("章节「" + ch.title() + "」的指标「" + metricId
                            + "」映射不到语义定义（确认过的大纲不应包含未知指标）");
                }
                boolean wantCompare = ch.comparison() != null && def.comparable();
                if (def.isDerivedMetric()) {
                    // 派生指标自身不取数；操作数补进取数清单
                    for (String operand : List.of(def.derived().left(), def.derived().right())) {
                        MetricDefinition dep = defs.get(operand);
                        if (dep == null || dep.isDerivedMetric()) {
                            throw new PolicyException("派生指标「" + metricId + "」的操作数「" + operand
                                    + "」不存在或不是取数指标");
                        }
                        fetchMetrics.putIfAbsent(operand, ch.chapterId());
                        if (wantCompare && dep.timeBound()) compareMetrics.add(operand);
                    }
                    continue;
                }
                fetchMetrics.putIfAbsent(metricId, ch.chapterId());
                if (wantCompare && def.timeBound()) compareMetrics.add(metricId);
            }
        }

        List<MetricQuerySpec> specs = new ArrayList<>();
        int seq = 0;
        // 本期规约在前、对比期在后：事实编号先本期后对比期，稳定可复现
        for (Map.Entry<String, String> e : fetchMetrics.entrySet()) {
            MetricDefinition def = defs.get(e.getKey());
            boolean timeBound = def.timeBound();
            specs.add(new MetricQuerySpec(specId(++seq), e.getKey(), e.getValue(),
                    MetricQuerySpec.PURPOSE_CURRENT, current.label(),
                    timeBound ? current.start().toString() : null,
                    timeBound ? current.end().toString() : null));
        }
        for (String metricId : compareMetrics) {
            specs.add(new MetricQuerySpec(specId(++seq), metricId, fetchMetrics.get(metricId),
                    MetricQuerySpec.PURPOSE_COMPARE, compare.label(),
                    compare.start().toString(), compare.end().toString()));
        }
        log.info("[SPEC] 生成取数规约 {} 条（本期 {} + 对比期 {}）", specs.size(),
                fetchMetrics.size(), compareMetrics.size());
        return specs;
    }

    private static String specId(int seq) {
        return String.format("qs_%03d", seq);
    }
}
