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
 *  - 声明比较的章节里 comparable 的指标按比较用途派生基期规约（派生指标 → 其操作数派生基期规约）；
 *    环比（wow/mom/qoq）共用 purpose=COMPARE，同比独立 purpose=COMPARE_YOY，窗口由调用方按用途传入；
 *  - 同指标多章共享同一规约（去重），chapterId 记首个引用章节；
 *  - 产出顺序固定 CURRENT 块 → COMPARE 块 → COMPARE_YOY 块——spec/fact 编号可复现的地基，不许变。
 */
@Component
public class SpecResolveStep {

    private static final Logger log = LoggerFactory.getLogger(SpecResolveStep.class);

    /** 旧签名（Phase03 前）：单对比窗口 = 同粒度环比基期。保留为委托重载，既有调用与单测语义不变。 */
    public List<MetricQuerySpec> run(Outline outline, PeriodResolver.Window current, PeriodResolver.Window compare,
                                     Map<String, MetricDefinition> defs) {
        return run(outline, current, Map.of(MetricQuerySpec.PURPOSE_COMPARE, compare), defs);
    }

    /**
     * 将 outline 映射为可执行规约列表（幂等、可复现）。
     * <p>处理原则：按本期→环比→同比输出顺序；章节引用去重；派生指标不直接取数、先补操作数。
     * @param compareWindows 比较用途 → 基期窗口（key 取 MetricQuerySpec.PURPOSE_COMPARE / PURPOSE_COMPARE_YOY）
     */
    public List<MetricQuerySpec> run(Outline outline, PeriodResolver.Window current,
                                     Map<String, PeriodResolver.Window> compareWindows,
                                     Map<String, MetricDefinition> defs) {
        // metricId → 首个引用章节；保持章节内顺序（事实编号可复现）
        Map<String, String> fetchMetrics = new LinkedHashMap<>();
        // purpose → 有序去重指标集；键序即产出块序（COMPARE 在前、COMPARE_YOY 在后）
        Map<String, LinkedHashSet<String>> compareMetrics = new LinkedHashMap<>();
        compareMetrics.put(MetricQuerySpec.PURPOSE_COMPARE, new LinkedHashSet<>());
        compareMetrics.put(MetricQuerySpec.PURPOSE_COMPARE_YOY, new LinkedHashSet<>());

        for (Outline.OutlineChapter ch : outline.chapters()) {
            List<String> purposes = ch.effectiveComparisons().stream()
                    .map(t -> ComparisonType.require(t).purpose())
                    .distinct().toList();
            for (String metricId : ch.metricIds()) {
                MetricDefinition def = defs.get(metricId);
                if (def == null) {
                    throw new PolicyException("章节「" + ch.title() + "」的指标「" + metricId
                            + "」映射不到语义定义（确认过的大纲不应包含未知指标）");
                }
                boolean wantCompare = !purposes.isEmpty() && def.comparable();
                if (def.isDerivedMetric()) {
                    // 派生指标自身不取数；操作数补进取数清单
                    for (String operand : List.of(def.derived().left(), def.derived().right())) {
                        MetricDefinition dep = defs.get(operand);
                        if (dep == null || dep.isDerivedMetric()) {
                            throw new PolicyException("派生指标「" + metricId + "」的操作数「" + operand
                                    + "」不存在或不是取数指标");
                        }
                        fetchMetrics.putIfAbsent(operand, ch.chapterId());
                        if (wantCompare && dep.timeBound()) {
                            purposes.forEach(p -> compareMetrics.get(p).add(operand));
                        }
                    }
                    continue;
                }
                fetchMetrics.putIfAbsent(metricId, ch.chapterId());
                if (wantCompare && def.timeBound()) {
                    purposes.forEach(p -> compareMetrics.get(p).add(metricId));
                }
            }
        }

        List<MetricQuerySpec> specs = new ArrayList<>();
        int seq = 0;
        // 本期规约在前、基期在后：事实编号先本期后基期，稳定可复现
        for (Map.Entry<String, String> e : fetchMetrics.entrySet()) {
            MetricDefinition def = defs.get(e.getKey());
            boolean timeBound = def.timeBound();
            // 时间型指标才绑定时间窗；非时间窗指标（如口径常量/静态配置）只做本期当前值，不生成 compare 重复窜扰。
            specs.add(new MetricQuerySpec(specId(++seq), e.getKey(), e.getValue(),
                    MetricQuerySpec.PURPOSE_CURRENT, current.label(),
                    timeBound ? current.start().toString() : null,
                    timeBound ? current.end().toString() : null));
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : compareMetrics.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            PeriodResolver.Window w = compareWindows.get(e.getKey());
            if (w == null) {
                throw new PolicyException("章节声明的比较需要基期窗口「" + e.getKey()
                        + "」但编排未提供（防御性失败关闭）");
            }
            for (String metricId : e.getValue()) {
                specs.add(new MetricQuerySpec(specId(++seq), metricId, fetchMetrics.get(metricId),
                        e.getKey(), w.label(), w.start().toString(), w.end().toString()));
            }
        }
        // CHART_SERIES 块（Phase04）：series 图表的历史期取数，块序恒最后（既有编号逐字节不变）。
        // 键 = metricId|periodLabel 去重（多图/多章共用同一历史点）；窗口由 previous() 自本期迭代推导；
        // 本期点复用 CURRENT fact，不重复取数。
        Set<String> seenSeries = new LinkedHashSet<>();
        for (Outline.OutlineChapter ch : outline.chapters()) {
            if (ch.charts() == null) continue;
            for (var chart : ch.charts()) {
                if (chart == null || chart.binding() == null
                        || !com.treasury.nl2sql.report.domain.ChartDef.KIND_SERIES.equals(chart.binding().kind())) {
                    continue;
                }
                String metricId = chart.binding().metricId();
                MetricDefinition def = defs.get(metricId);
                if (def == null) {
                    throw new PolicyException("图表「" + chart.chartId() + "」绑定的指标「" + metricId
                            + "」映射不到语义定义");
                }
                int periods = chart.binding().periods() == null ? 0 : chart.binding().periods();
                // 历史期窗口：prev1..prev(N-1)，emit 顺序旧→新（序列 fact _s1 为最早期）
                List<PeriodResolver.Window> history = new ArrayList<>();
                PeriodResolver.Window w = current;
                for (int i = 1; i < periods; i++) {
                    w = PeriodResolver.previous(w);
                    history.add(0, w);
                }
                for (PeriodResolver.Window hw : history) {
                    if (!seenSeries.add(metricId + "|" + hw.label())) continue;
                    specs.add(new MetricQuerySpec(specId(++seq), metricId,
                            fetchMetrics.getOrDefault(metricId, ch.chapterId()),
                            MetricQuerySpec.PURPOSE_CHART_SERIES, hw.label(),
                            hw.start().toString(), hw.end().toString()));
                }
            }
        }
        log.info("[SPEC] 生成取数规约 {} 条（本期 {} + 对比期 {} + 同比期 {}）", specs.size(),
                fetchMetrics.size(), compareMetrics.get(MetricQuerySpec.PURPOSE_COMPARE).size(),
                compareMetrics.get(MetricQuerySpec.PURPOSE_COMPARE_YOY).size());
        return specs;
    }

    /**
     * 基期窗口装配：环比无条件（保现行为）、同比仅大纲声明了才算。
     * ReportPipeline.runAsync 与模板预览共用——装配逻辑复制两份会在比较类型演进时漂移。
     */
    public static Map<String, PeriodResolver.Window> buildCompareWindows(Outline outline,
                                                                         PeriodResolver.Window current) {
        Map<String, PeriodResolver.Window> compareWindows = new LinkedHashMap<>();
        compareWindows.put(MetricQuerySpec.PURPOSE_COMPARE, PeriodResolver.previous(current));
        if (requiredComparePurposes(outline).contains(MetricQuerySpec.PURPOSE_COMPARE_YOY)) {
            compareWindows.put(MetricQuerySpec.PURPOSE_COMPARE_YOY, PeriodResolver.sameLastYear(current));
        }
        return compareWindows;
    }

    /**
     * 大纲声明的全部比较用途（purpose 集合）——调用方据此决定是否算同比窗口。
     * <p>
     * 只返回 token 已通过 `ComparisonType.require` 语义校验后的值，不在这里吞掉非法 token，
     * 避免“解析阶段已阻断、执行阶段再次掩盖根因”。
     */
    public static Set<String> requiredComparePurposes(Outline outline) {
        LinkedHashSet<String> purposes = new LinkedHashSet<>();
        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String token : ch.effectiveComparisons()) {
                purposes.add(ComparisonType.require(token).purpose());
            }
        }
        return purposes;
    }

    private static String specId(int seq) {
        return String.format("qs_%03d", seq);
    }
}
