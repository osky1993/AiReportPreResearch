package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.report.domain.EventRecord;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.store.EventRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 事件候选生成（Phase05 契约3，纯程序）：对每条异动按「时间窗 ∈ 报告期∪基期 + related_metrics 命中
 * + 维度交集（贡献 top 维度值）」三条件加权排序，候选 ≤5/异动；**零候选就是零候选**（不放宽凑数）。
 * 输出编号化候选包（EVT-n）给 AttributionStep——LLM 只见编号与转义后的摘要（注入第二道闸，
 * 不依赖录入白名单，防御纵深）。
 */
@Component
public class EventMatcher {

    /** 每条异动的候选上限。 */
    static final int MAX_CANDIDATES = 5;
    /** 事件描述展示截断长度；短于此长度直接保留，过长则切断避免 prompt 膨胀。 */
    private static final int DESC_TRUNCATE = 120;

    /** 按时间段检索事件事实的仓储；仅依赖已持久化事件，不访问其他外部。 */
    private final EventRepository repo;

    /** 注入事件仓储，保证可测试可替换数据源。 */
    public EventMatcher(EventRepository repo) {
        this.repo = repo;
    }

    /** @param ref 候选引用号（EVT-<eventId>）——ClaimRecord.evidenceRefs 只准引用这些编号 */
    public record Candidate(String ref, EventRecord event, int score) {}

    /**
     * 先检索报告期与基期联合时间窗，再按“相关指标命中+当前期命中+top 维度交集”打分。
     * 按 score 降序、事件日期降序取前 N；score≤0 的事件不返回，避免注入无关候选。
     * 设计目标：输出“少量高置信候选”，零候选即可直接 observed + 待查，不允许凭空补数。
     *
     * @param topContribDim 贡献拆解的最大贡献维度值（如 "CNY"；无贡献拆解传 null）
     * @param current 报告期窗口；base 该异动 basis 对应的基期窗口
     */
    public List<Candidate> match(AnomalyDetector.Anomaly anomaly,
                                 PeriodResolver.Window current, PeriodResolver.Window base,
                                 String topContribDim) {
        LocalDate from = base.start().isBefore(current.start()) ? base.start() : current.start();
        LocalDate to = base.end().isAfter(current.end()) ? base.end() : current.end();
        List<Candidate> scored = new ArrayList<>();
        // 事件检索在「报告期∪基期」联合窗内，避免只看本期导致同比解释断链；
        // 本期内命中加权，避免用基期旧事解释本期异常。
        for (EventRecord e : repo.findActiveBetween(from, to)) {
            int score = 0;
            if (e.relatedMetrics() != null && e.relatedMetrics().contains(anomaly.fact().metricId())) {
                score += 2;
            }
            boolean inCurrent = !e.eventDate().isBefore(current.start()) && !e.eventDate().isAfter(current.end());
            if (inCurrent) score += 1;
            if (topContribDim != null && e.dimensions() != null
                    && e.dimensions().containsValue(topContribDim)) {
                score += 1;
            }
            if (score > 0) {
                scored.add(new Candidate("EVT-" + e.eventId(), e, score));
            }
        }
        scored.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(c -> c.event().eventDate(), Comparator.reverseOrder()));
        return scored.size() > MAX_CANDIDATES ? scored.subList(0, MAX_CANDIDATES) : scored;
    }

    /** 贡献 fact 组里的最大贡献维度值（|贡献额| 最大者），供维度交集加权；无贡献 fact → null。 */
    public static String topContributionDim(List<FactRecord> contributionFacts) {
        return contributionFacts.stream()
                .filter(f -> f.factKey().endsWith("_contrib") && f.dimensions() != null)
                .max(Comparator.comparing(f -> f.value().abs()))
                .map(f -> f.dimensions().values().iterator().next())
                .orElse(null);
    }

    /**
     * 编号化候选包（进 prompt 的唯一形态）：一行一候选。第二道注入闸——标题与描述转义 + 截断，
     * 不依赖录入校验（防御纵深）。
     */
    public static String renderCandidates(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            sb.append(c.ref()).append("｜").append(c.event().eventDate())
                    .append("｜").append(sanitize(c.event().title(), 40))
                    .append("｜").append(sanitize(c.event().description(), DESC_TRUNCATE))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 转义 + 截断：模板/围栏/标签字符一律替换为全角星号（数据不当指令）。 */
    static String sanitize(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[{}`<>\\\\|\\p{Cntrl}]", "＊");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "…";
    }
}
