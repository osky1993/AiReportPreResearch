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

    /**
     * 候选模板。
     * @param templateId 模板 ID
     * @param name 模板名称
     * @param keywordHits 关键词命中数量
     * @param cosine 与请求文本的语义相似度
     * @param score 展示用综合分（keywordHits + cosine）
     */
    public record Candidate(String templateId, String name, int keywordHits, double cosine, double score) {}

    private final EmbeddingClient embedding;
    private final ReportAssetService assets;
    private final double tau;

    /** 模板向量缓存（注册表 reload 后由 {@link #refresh()} 整体失效重算）。 */
    private final Map<String, float[]> templateVectors = new ConcurrentHashMap<>();

    /**
     * 资产发布/下架导致注册表重载后，先清理全量向量缓存（下次 recall 按新模板画像重算）。
     * <p>必须全量清空而非按 templateId 逐条删，因为发布动作可能改变文本 token 分布和向量语义，旧向量会污染召回排序。
     */
    public void refresh() {
        templateVectors.clear();
    }

    /**
     * @param tau 语义阈值
     */
    public TemplateMatcher(EmbeddingClient embedding, ReportAssetService assets,
                           @Value("${report.match.tau:0.60}") double tau) {
        this.embedding = embedding;
        this.assets = assets;
        this.tau = tau;
    }

    /**
     * Spring 使用侧便捷入口：对注册表中全部 PUBLISHED 模板召回。
     * 这条路径用于 ONLINE 召回（模板治理完成后可直接进入生产路由）。
     */
    public List<Candidate> recall(String requestText) {
        return recall(requestText, assets.allTemplates());
    }

    /**
     * 核心召回（不依赖注册表状态，可离线单测）。
     * <p>规则：
     * <ul>
     *   <li>keyword 只看模板文本直接 contains，命中数量大者优先</li>
     *   <li>cosine 达阈值才兜底进入候选</li>
     *   <li>同分时 keyword 优先，再 cosine 逆序</li>
     * </ul>
     * @param requestText 用户请求文本
     * @param templates 召回输入集（测试可传定制列表）
     */
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
        // 同分时 keyword 优先，可解释性优先于语义相似度：关键字命中意味着口径语义更确定；
        // 相同命中的情况下再看 cosine，避免排序抖动，供人工复盘与测试复现。
        out.sort(Comparator.comparingInt(Candidate::keywordHits).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::cosine).reversed()));
        return out;
    }

    /**
     * BLOCKED 时给人看的清单（无候选时给前端“候选集全部模板”用于定位失败原因，有候选时给“候选+分数”用于复盘）。
     */
    public static String describe(List<Candidate> candidates) {
        return candidates.stream()
                .map(c -> c.templateId() + "(" + c.name() + ", 分=" + String.format("%.2f", c.score()) + ")")
                .collect(Collectors.joining("、"));
    }

    /**
     * 关键词匹配：区分大小写 contains，排除空关键字；高优先级第一段的硬约束之一。
     * <p>空命中可继续靠 embedding；全部 0 且 cos<tau 会被过滤。
     */
    private static int keywordHits(String requestText, ReportTemplateDef tpl) {
        if (tpl.keywords() == null) return 0;
        int n = 0;
        for (String kw : tpl.keywords()) {
            if (kw != null && !kw.isBlank() && requestText.contains(kw)) n++;
        }
        return n;
    }

    /**
     * 模板画像向量按 templateId 缓存；重复召回共享向量，减少 embedding 调用。
     * @param tpl 目标模板
     */
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
