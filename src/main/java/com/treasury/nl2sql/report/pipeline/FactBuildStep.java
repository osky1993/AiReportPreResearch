package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.MetricDefinition;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ④ 事实构建（程序，不是 LLM）：查询结果 → FactRecord。
 * 三类产物：BASE（取数值，含各基期）、DERIVED 派生指标（如净流入=流入−流出）、
 * DERIVED 比较（环比 _wow/_mom/_qoq 与同比 _yoy，(本期−基期)/基期，unit=percent，
 * 后缀与配对 purpose 由 {@link ComparisonType} 统一定义）。
 * display_value 在这里一次性渲染定死——⑤ 的占位符替换与 ⑥ 的回读核对都以它为准。
 * 质量断言失败 → 失败关闭；基期基数为 0 → 仅跳过该比较并留 note（报告里就不提它）。
 */
@Component
public class FactBuildStep {

    private static final Logger log = LoggerFactory.getLogger(FactBuildStep.class);

    private final ObjectMapper mapper;

    public FactBuildStep(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record FactBuildResult(List<FactRecord> facts, List<String> notes) {}

    /**
     * 纯逻辑单测入口（无版本快照：metricVersion 记 null）。
     * 用于验证派生与限制策略，不受“已发布版本快照”影响。
     */
    public FactBuildResult run(Outline outline, List<FetchStep.FetchResult> fetched,
                               Map<String, MetricDefinition> metricDefs) {
        return run(outline, fetched, metricDefs, null);
    }

    /**
     * 阶段核心入口：从 Fetch 结果构建可写入 fact 阶段的事实集。
     * @param metricVersions run 固化的指标版本快照（null=Phase02 前存量 run 未固化，fact 版本记 null）
     * 失败模式：缺列、错数、比较缺失、质量规则不满足、fact 上限超限都会抛 PolicyException。
     */
    public FactBuildResult run(Outline outline, List<FetchStep.FetchResult> fetched,
                               Map<String, MetricDefinition> metricDefs, Map<String, Integer> metricVersions) {
        List<FactRecord> facts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // metricId → purpose → fact（派生与环比计算的查找索引）
        Map<String, Map<String, FactRecord>> byMetric = new LinkedHashMap<>();
        int[] seq = {0};

        // 1) BASE：每条取数结果一条事实（含各基期）；维度指标走多行分支（Phase03）；
        //    图表序列（CHART_SERIES）攒到本轮后统一分组造 fact（Phase04）
        List<FetchStep.FetchResult> chartSeries = new ArrayList<>();
        for (FetchStep.FetchResult fr : fetched) {
            MetricQuerySpec spec = fr.spec();
            MetricDefinition def = require(metricDefs, spec.metricId());
            if (MetricQuerySpec.PURPOSE_CHART_SERIES.equals(spec.purpose())) {
                chartSeries.add(fr);
                continue;
            }
            if (def.isDimensional()) {
                // 维度指标不复用 single-value 分支，先统一构造维度行/合计/占比并写入同一索引，保证派生与比较可复用。
                buildDimensionalGroup(fr, def, versionOf(metricVersions, spec.metricId()),
                        facts, byMetric, notes, seq);
                continue;
            }
            BigDecimal value = extractValue(def, fr);
            assertQuality(def, value);
            FactRecord fact = new FactRecord(
                    nextKey(seq), spec.metricId(), versionOf(metricVersions, spec.metricId()),
                    def.name(), spec.chapterId(),
                    FactRecord.TYPE_BASE, value, def.unit(), renderDisplay(value, def.unit()),
                    spec.periodLabel(), null, toJson(spec),
                    fr.sql(), fr.sqlHash(), fr.resultHash(), null,
                    FactRecord.QUALITY_PASSED, null);
            facts.add(fact);
            byMetric.computeIfAbsent(spec.metricId(), k -> new HashMap<>()).put(spec.purpose(), fact);
        }
        buildChartSeriesFacts(chartSeries, metricDefs, metricVersions, facts);

        // 2) DERIVED 派生指标（净流入等）：对两个 BASE 指标做算术，本期/对比期各算一条
        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String metricId : ch.metricIds()) {
                MetricDefinition def = metricDefs.get(metricId);
                if (def == null || !def.isDerivedMetric() || byMetric.containsKey(metricId)) continue;
                Map<String, FactRecord> left = byMetric.get(def.derived().left());
                Map<String, FactRecord> right = byMetric.get(def.derived().right());
                if (left == null || right == null) {
                    throw new PolicyException("派生指标「" + metricId + "」的操作数事实缺失（"
                            + def.derived().left() + " / " + def.derived().right() + "）");
                }
                for (String purpose : List.of(MetricQuerySpec.PURPOSE_CURRENT, MetricQuerySpec.PURPOSE_COMPARE,
                        MetricQuerySpec.PURPOSE_COMPARE_YOY)) {
                    FactRecord l = left.get(purpose);
                    FactRecord r = right.get(purpose);
                    if (l == null || r == null) continue;   // 对比期未取数（非 comparison 章节）则只算本期
                    BigDecimal value = switch (def.derived().op()) {
                        case "subtract" -> l.value().subtract(r.value());
                        default -> throw new PolicyException("派生指标「" + metricId + "」的运算「"
                                + def.derived().op() + "」不支持");
                    };
                    FactRecord fact = new FactRecord(
                            nextKey(seq), metricId, versionOf(metricVersions, metricId),
                            def.name(), ch.chapterId(),
                            FactRecord.TYPE_DERIVED, value, def.unit(), renderDisplay(value, def.unit()),
                            l.periodLabel(), null, null, null, null, null,
                            l.factKey() + "," + r.factKey(),
                            FactRecord.QUALITY_PASSED, null);
                    facts.add(fact);
                    byMetric.computeIfAbsent(metricId, k -> new HashMap<>()).put(purpose, fact);
                }
            }
        }

        // 3) DERIVED 比较：声明比较的章节里 comparable 指标，(本期−基期)/基期 → percent；
        //    环比（wow/mom/qoq）与同比（yoy）同一公式，配对键与后缀由 ComparisonType 决定
        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String token : ch.effectiveComparisons()) {
                ComparisonType ct = ComparisonType.require(token);
                for (String metricId : ch.metricIds()) {
                    MetricDefinition def = metricDefs.get(metricId);
                    if (def == null || !def.comparable()) continue;
                    Map<String, FactRecord> pair = byMetric.get(metricId);
                    if (pair == null) continue;
                    FactRecord cur = pair.get(MetricQuerySpec.PURPOSE_CURRENT);
                    FactRecord cmp = pair.get(ct.purpose());
                    if (cur == null || cmp == null) continue;
                    String key = cur.factKey() + ct.factSuffix();
                    if (facts.stream().anyMatch(f -> f.factKey().equals(key))) continue;   // 多章共享同一比较事实
                    if (cmp.value().signum() == 0) {
                        notes.add("指标「" + def.name() + "」" + baseWording(ct) + "（" + cmp.periodLabel()
                                + "）基数为 0，跳过" + ct.label());
                        continue;
                    }
                    BigDecimal change = cur.value().subtract(cmp.value())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(cmp.value().abs(), 1, RoundingMode.HALF_UP);
                    facts.add(new FactRecord(
                            key, metricId, versionOf(metricVersions, metricId),
                            def.name() + "（" + ct.label() + "）", ch.chapterId(),
                            FactRecord.TYPE_DERIVED, change, "percent", renderDisplay(change, "percent"),
                            cur.periodLabel(), null, null, null, null, null,
                            cur.factKey() + "," + cmp.factKey(),
                            FactRecord.QUALITY_PASSED, null));
                }
            }
        }

        assertChapterFactLimit(facts);
        log.info("[FACT] 构建事实 {} 条（notes {} 条）", facts.size(), notes.size());
        return new FactBuildResult(facts, notes);
    }

    /**
     * 章节 fact 数上限（含 BASE+DERIVED+维度行+占比）：触顶失败关闭，守 ⑤ 的占位符纪律。
     * T0 拍板 20；gk 首期放大至 30——苏州分县维度全列 10 县域需 2N+1=21（业务确认必须全列，不截断）。
     */
    static final int CHAPTER_FACT_LIMIT = 30;
    /**
     * 图表序列独立配额。
     * P4 契约2：序列 fact 不进 ⑤ prompt，故单独配额，不与章节正文 fact 共享阈值。
     */
    static final int CHAPTER_CHART_SERIES_LIMIT = 24;

    /** 图表序列 fact 判定：按 spec 快照的 purpose 识别（不靠 key 模式——维度 slug 可能撞形）。 */
    static boolean isChartSeriesFact(FactRecord f) {
        return f.specJson() != null && f.specJson().contains("\"" + MetricQuerySpec.PURPOSE_CHART_SERIES + "\"");
    }

    /**
     * 校验章节维度上的事实总量边界。
     * 超上限直接 fail-closed，防止 prompt 生成面超过 LLM 处理上限。
     */
    private static void assertChapterFactLimit(List<FactRecord> facts) {
        Map<String, Long> byChapter = new LinkedHashMap<>();
        Map<String, Long> chartByChapter = new LinkedHashMap<>();
        for (FactRecord f : facts) {
            if (isChartSeriesFact(f)) {
                chartByChapter.merge(f.chapterId(), 1L, Long::sum);
            } else {
                byChapter.merge(f.chapterId(), 1L, Long::sum);
            }
        }
        for (Map.Entry<String, Long> e : byChapter.entrySet()) {
            if (e.getValue() > CHAPTER_FACT_LIMIT) {
                throw new PolicyException("章节「" + e.getKey() + "」事实数 " + e.getValue() + " 超上限 "
                        + CHAPTER_FACT_LIMIT + "（失败关闭，请拆分章节或减少指标/维度）");
            }
        }
        for (Map.Entry<String, Long> e : chartByChapter.entrySet()) {
            if (e.getValue() > CHAPTER_CHART_SERIES_LIMIT) {
                throw new PolicyException("章节「" + e.getKey() + "」图表序列事实数 " + e.getValue() + " 超配额 "
                        + CHAPTER_CHART_SERIES_LIMIT + "（失败关闭，请减少图表或缩短 periods）");
            }
        }
    }

    /**
     * 图表序列 fact（Phase04 契约1「序列点即 fact」）。
     * 按 metricId 分组、按 periodLabel 升序后打上 fact_cxx_sy 的主键，只用于 chart 渲染绑定。
     * 序列 fact 不进 byMetric 索引、不进 ⑤ prompt，不作为评测主链路可比对对象。
     */
    private void buildChartSeriesFacts(List<FetchStep.FetchResult> chartSeries,
                                       Map<String, MetricDefinition> metricDefs,
                                       Map<String, Integer> metricVersions, List<FactRecord> facts) {
        if (chartSeries.isEmpty()) return;
        Map<String, List<FetchStep.FetchResult>> byMetricId = new LinkedHashMap<>();
        for (FetchStep.FetchResult fr : chartSeries) {
            byMetricId.computeIfAbsent(fr.spec().metricId(), k -> new ArrayList<>()).add(fr);
        }
        for (Map.Entry<String, List<FetchStep.FetchResult>> e : byMetricId.entrySet()) {
            MetricDefinition def = require(metricDefs, e.getKey());
            List<FetchStep.FetchResult> points = new ArrayList<>(e.getValue());
            // 同一度量的历史点按 periodLabel 从旧到新排序，便于图表序列在 UI 里自然可读；
            // 同时保持 fact_cNN_sK 的 K 与时间顺序一一对应，避免时序漂移。
            points.sort(java.util.Comparator.comparing(p -> p.spec().periodLabel()));
            // 序列组用「fact_c<NN>」前缀独立于主序列命名域——不动主序列 NNN 编号（既有基线稳定）
            String groupKey = String.format("fact_c%02d", chartGroupIndex(facts) + 1);
            int k = 0;
            for (FetchStep.FetchResult fr : points) {
                MetricQuerySpec spec = fr.spec();
                BigDecimal value = extractValue(def, fr);
                assertQuality(def, value);
                facts.add(new FactRecord(
                        groupKey + "_s" + (++k), spec.metricId(), versionOf(metricVersions, spec.metricId()),
                        def.name() + "（序列·" + spec.periodLabel() + "）", spec.chapterId(),
                        FactRecord.TYPE_BASE, value, def.unit(), renderDisplay(value, def.unit()),
                        spec.periodLabel(), null, toJson(spec),
                        fr.sql(), fr.sqlHash(), fr.resultHash(), null,
                        FactRecord.QUALITY_PASSED, null));
            }
        }
    }

    private static int chartGroupIndex(List<FactRecord> facts) {
        return (int) facts.stream().map(FactRecord::factKey)
                .filter(key -> key.startsWith("fact_c"))
                .map(key -> key.replaceAll("_s\\d+$", ""))
                .distinct().count();
    }

    /**
     * 维度指标多行造 fact（Phase03 契约2）：
     * 行 fact_NNN_&lt;slug&gt;（BASE，dimensions 非空）→ 合计 fact_NNN（DERIVED 求和，占比分母）
     * → 占比 fact_NNN_&lt;slug&gt;_share（DERIVED percent）。0 行按 nullPolicy：ZERO=合计 0 + note、BLOCK=失败关闭。
     */
    private void buildDimensionalGroup(FetchStep.FetchResult fr, MetricDefinition def, Integer version,
                                       List<FactRecord> facts, Map<String, Map<String, FactRecord>> byMetric,
                                       List<String> notes, int[] seq) {
        MetricQuerySpec spec = fr.spec();
        List<Map<String, Object>> rows = fr.rows();
        if (rows.size() > com.treasury.nl2sql.report.asset.MetricDimensionRule.MAX_DIMENSION_ROWS) {
            // ③ 已拦截；此处死代码兜底（纯逻辑单测走这里）
            throw new PolicyException("指标「" + def.metricId() + "」维度行数 " + rows.size() + " 超上限 "
                    + com.treasury.nl2sql.report.asset.MetricDimensionRule.MAX_DIMENSION_ROWS + "（失败关闭，不静默截断）");
        }
        String dimBare = com.treasury.nl2sql.report.asset.MetricDimensionRule.bareName(def.dimensions().get(0));
        String groupKey = nextKey(seq);   // NNN 预留给合计，行后缀其上
        if (rows.isEmpty()) {
            if (MetricDefinition.NULL_BLOCK.equals(def.nullPolicy())) {
                throw new PolicyException("指标「" + def.metricId() + "」维度取数 0 行且 nullPolicy=BLOCK（失败关闭）");
            }
            notes.add("指标「" + def.name() + "」本期（" + spec.periodLabel() + "）无数据，维度拆解为空");
            FactRecord empty = new FactRecord(groupKey, spec.metricId(), version,
                    def.name() + "（合计）", spec.chapterId(),
                    FactRecord.TYPE_DERIVED, BigDecimal.ZERO, def.unit(), renderDisplay(BigDecimal.ZERO, def.unit()),
                    spec.periodLabel(), null, toJson(spec), null, fr.sqlHash(), fr.resultHash(), null,
                    FactRecord.QUALITY_PASSED, null);
            facts.add(empty);
            byMetric.computeIfAbsent(spec.metricId(), k -> new HashMap<>()).put(spec.purpose(), empty);
            return;
        }
        java.util.LinkedHashSet<String> usedSlugs = new java.util.LinkedHashSet<>();
        List<FactRecord> rowFacts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int idx = 0;
        for (Map<String, Object> row : rows) {
            idx++;
            if (!row.containsKey(dimBare)) {
                throw new PolicyException("指标「" + def.metricId() + "」取数结果缺少维度列「" + dimBare
                        + "」，实际列: " + row.keySet());
            }
            String dimValue = String.valueOf(row.get(dimBare));
            BigDecimal value = rowValue(def, row);
            assertQuality(def, value);
            String key = groupKey + "_" + slugOf(dimValue, idx, usedSlugs);
            FactRecord fact = new FactRecord(key, spec.metricId(), version,
                    def.name() + "（" + dimValue + "）", spec.chapterId(),
                    FactRecord.TYPE_BASE, value, def.unit(), renderDisplay(value, def.unit()),
                    spec.periodLabel(), Map.of(dimBare, dimValue), toJson(spec),
                    fr.sql(), fr.sqlHash(), fr.resultHash(), null,
                    FactRecord.QUALITY_PASSED, null);
            facts.add(fact);
            rowFacts.add(fact);
            total = total.add(value);
        }
        String rowKeys = rowFacts.stream().map(FactRecord::factKey)
                .collect(java.util.stream.Collectors.joining(","));
        FactRecord totalFact = new FactRecord(groupKey, spec.metricId(), version,
                def.name() + "（合计）", spec.chapterId(),
                FactRecord.TYPE_DERIVED, total, def.unit(), renderDisplay(total, def.unit()),
                spec.periodLabel(), null, toJson(spec), null, fr.sqlHash(), fr.resultHash(), rowKeys,
                FactRecord.QUALITY_PASSED, null);
        facts.add(totalFact);
        byMetric.computeIfAbsent(spec.metricId(), k -> new HashMap<>()).put(spec.purpose(), totalFact);
        if (total.signum() == 0) {
            notes.add("指标「" + def.name() + "」维度合计为 0，跳过占比");
            return;
        }
        for (FactRecord rf : rowFacts) {
            BigDecimal share = rf.value().multiply(BigDecimal.valueOf(100))
                    .divide(total, 1, RoundingMode.HALF_UP);
            facts.add(new FactRecord(rf.factKey() + "_share", spec.metricId(), version,
                    rf.metricName() + "占比", spec.chapterId(),
                    FactRecord.TYPE_DERIVED, share, "percent", renderDisplay(share, "percent"),
                    spec.periodLabel(), rf.dimensions(), null, null, null, null,
                    rf.factKey() + "," + groupKey,
                    FactRecord.QUALITY_PASSED, null));
        }
    }

    /**
     * 维度值 → fact_key 后缀 slug（契约2）：ASCII 小写、非 [a-z0-9] 转 '_'；
     * 非 ASCII / 转换后无实义 / 冲突 → 回退行序 rNN。
     */
    private static String slugOf(String dimValue, int rowIdx, java.util.Set<String> used) {
        String fallback = String.format("r%02d", rowIdx);
        if (dimValue == null || !dimValue.chars().allMatch(c -> c < 128)) {
            used.add(fallback);
            return fallback;
        }
        String s = dimValue.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (s.isBlank() || s.chars().allMatch(c -> c == '_') || !used.add(s)) {
            used.add(fallback);
            return fallback;
        }
        return s;
    }

    /**
     * 取数结果必须恰 1 行、含 valueColumn；NULL 按 nullPolicy 处置（ZERO=0 / BLOCK=失败关闭）。
     * 该约束保证派生/比较阶段可直接按 key=purpose 取数，不再出现“隐式多行折叠”。
     */
    private BigDecimal extractValue(MetricDefinition def, FetchStep.FetchResult fr) {
        if (fr.rows().size() != 1) {
            throw new PolicyException("指标「" + def.metricId() + "」取数结果应恰 1 行，实际 " + fr.rows().size() + " 行");
        }
        return rowValue(def, fr.rows().get(0));
    }

    /** 单行取值（单值指标与维度行共用）：valueColumn 存在性 + NULL 处置 + 数值类型。 */
    private static BigDecimal rowValue(MetricDefinition def, Map<String, Object> row) {
        if (!row.containsKey(def.valueColumn())) {
            throw new PolicyException("指标「" + def.metricId() + "」取数结果缺少列「" + def.valueColumn()
                    + "」，实际列: " + row.keySet());
        }
        Object v = row.get(def.valueColumn());
        if (v == null) {
            if (MetricDefinition.NULL_ZERO.equals(def.nullPolicy())) {
                return BigDecimal.ZERO;
            }
            throw new PolicyException("指标「" + def.metricId() + "」取数值为 NULL 且 nullPolicy=BLOCK（数据质量失败关闭）");
        }
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        throw new PolicyException("指标「" + def.metricId() + "」取数值类型非数值: " + v.getClass().getSimpleName());
    }

    /**
     * 质量检查只做最小必要断言（目前支持 NON_NEGATIVE），失败即 policy-block；
     * 目的在于把数据脏值挡在 ④，避免污染 ⑤ 的 prompt 输入与 ⑥ 的数字一致率校验。
     */
    private void assertQuality(MetricDefinition def, BigDecimal value) {
        if (def.qualityChecks() == null) return;
        if (def.qualityChecks().contains(MetricDefinition.CHECK_NON_NEGATIVE) && value.signum() < 0) {
            throw new PolicyException("指标「" + def.metricId() + "」质量断言 NON_NEGATIVE 失败: 值为 " + value);
        }
    }

    /**
     * 展示串一次性渲染（⑤ 替换与 ⑥ 回读都以此为准，格式变更必须与 NumberAuditor.RENDERED/parseBack 同步）。
     * CNY 且 |值|≥1万 -> "6,570.00 万元"；CNY -> "1,234.56 元"；percent -> "+12.5%"；
     * 外币码（USD/EUR 等 3 位大写）-> "1,234.56 USD"（金额精度，不抹小数）；计数 -> "4 笔"。
     */
    static String renderDisplay(BigDecimal value, String unit) {
        DecimalFormat money = new DecimalFormat("#,##0.00");
        if ("CNY".equals(unit)) {
            if (value.abs().compareTo(BigDecimal.valueOf(10000)) >= 0) {
                return money.format(value.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP)) + " 万元";
            }
            return money.format(value.setScale(2, RoundingMode.HALF_UP)) + " 元";
        }
        if ("亿元".equals(unit)) {
            // gk 国库域：金额以亿元存储（业务确认），两位小数展示，不做量级换算
            return money.format(value.setScale(2, RoundingMode.HALF_UP)) + " 亿元";
        }
        if ("percent".equals(unit)) {
            BigDecimal p = value.setScale(1, RoundingMode.HALF_UP);
            return (p.signum() > 0 ? "+" : "") + p.toPlainString() + "%";
        }
        if (unit != null && unit.matches("[A-Z]{3}")) {
            return money.format(value.setScale(2, RoundingMode.HALF_UP)) + " " + unit;
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " " + unit;
    }

    /**
     * 事实编号器：基于序列号递增生产 fact_xxx 前缀。
     * 与其他模块的 fact 引用约定一致，保证 replace/verify 全链路可解析。
     */
    private static String nextKey(int[] seq) {
        return String.format("fact_%03d", ++seq[0]);
    }

    /** 基数为 0 时 note 的基期措辞——环比沿用历史文案「对比期」（基线 prompt 逐字节稳定），同比用「同比基期」。 */
    private static String baseWording(ComparisonType ct) {
        return MetricQuerySpec.PURPOSE_COMPARE_YOY.equals(ct.purpose()) ? "同比基期" : "对比期";
    }

    private static Integer versionOf(Map<String, Integer> versions, String metricId) {
        return versions == null ? null : versions.get(metricId);
    }

    private static MetricDefinition require(Map<String, MetricDefinition> defs, String metricId) {
        MetricDefinition def = defs.get(metricId);
        if (def == null) throw new PolicyException("指标「" + metricId + "」没有语义定义");
        return def;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("规约序列化失败", e);
        }
    }
}
