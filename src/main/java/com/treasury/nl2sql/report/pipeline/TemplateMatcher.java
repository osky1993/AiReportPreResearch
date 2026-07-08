package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.embedding.EmbeddingClient;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.asset.ReportTemplateDef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模板匹配召回（① 三段式的第一段，纯程序、零 LLM）。
 * 候选 = keywords 命中（需求文本 contains，命中即强信号）∪ embedding 余弦 ≥ tau（语义兜底）。
 * 排序 (keywordHits desc, cosine desc) 确定性可断言；召回给不出候选 → 上层失败关闭，
 * LLM 只在候选 id 中单选、选不出同样失败关闭——匹配不到不猜。
 */
@Component
public class TemplateMatcher {

    /** @param score 展示用综合分（keywordHits + cosine），BLOCKED 候选清单里给人看 */
    public record Candidate(String templateId, String name, int keywordHits, double cosine, double score) {}

    private final EmbeddingClient embedding;
    private final ReportAssetService assets;
    private final double tau;

    /** 模板向量缓存（注册表 reload 后由 {@link #refresh()} 整体失效重算）。 */
    private final Map<String, float[]> templateVectors = new ConcurrentHashMap<>();

    /** 资产发布/下架导致注册表重载后失效向量缓存（下次 recall 按新模板画像重算）。 */
    public void refresh() {
        templateVectors.clear();
    }

    public TemplateMatcher(EmbeddingClient embedding, ReportAssetService assets,
                           @Value("${report.match.tau:0.60}") double tau) {
        this.embedding = embedding;
        this.assets = assets;
        this.tau = tau;
    }

    /** Spring 使用侧便捷入口：对注册表中全部 PUBLISHED 模板召回。 */
    public List<Candidate> recall(String requestText) {
        return recall(requestText, assets.allTemplates());
    }

    /** 核心召回（不依赖注册表状态，可离线单测）。 */
    public List<Candidate> recall(String requestText, List<ReportTemplateDef> templates) {
        float[] query = embedding.embed(requestText);
        List<Candidate> out = new ArrayList<>();
        for (ReportTemplateDef tpl : templates) {
            int hits = keywordHits(requestText, tpl);
            double cos = EmbeddingClient.cosine(query, vectorOf(tpl));
            if (hits > 0 || cos >= tau) {
                out.add(new Candidate(tpl.templateId(), tpl.name(), hits, cos, hits + cos));
            }
        }
        out.sort(Comparator.comparingInt(Candidate::keywordHits).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::cosine).reversed()));
        return out;
    }

    /** BLOCKED 时给人看的清单（无候选时列全部可用模板，有候选时列候选与分数）。 */
    public static String describe(List<Candidate> candidates) {
        return candidates.stream()
                .map(c -> c.templateId() + "(" + c.name() + ", 分=" + String.format("%.2f", c.score()) + ")")
                .collect(Collectors.joining("、"));
    }

    private static int keywordHits(String requestText, ReportTemplateDef tpl) {
        if (tpl.keywords() == null) return 0;
        int n = 0;
        for (String kw : tpl.keywords()) {
            if (kw != null && !kw.isBlank() && requestText.contains(kw)) n++;
        }
        return n;
    }

    private float[] vectorOf(ReportTemplateDef tpl) {
        return templateVectors.computeIfAbsent(tpl.templateId(), id -> embedding.embed(vectorText(tpl)));
    }

    /** 模板的语义画像文本：名称 + 关键词 + 各章标题。 */
    private static String vectorText(ReportTemplateDef tpl) {
        StringBuilder sb = new StringBuilder(tpl.name());
        if (tpl.keywords() != null) {
            sb.append(' ').append(String.join(" ", tpl.keywords()));
        }
        for (ReportTemplateDef.ChapterDef ch : tpl.chapters()) {
            sb.append(' ').append(ch.title());
        }
        return sb.toString();
    }
}
