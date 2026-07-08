package com.treasury.nl2sql.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.asset.ReportAssetService;
import com.treasury.nl2sql.report.domain.FactRecord;
import com.treasury.nl2sql.report.domain.MetricQuerySpec;
import com.treasury.nl2sql.report.domain.Outline;
import com.treasury.nl2sql.report.domain.Phase;
import com.treasury.nl2sql.report.domain.ReportRun;
import com.treasury.nl2sql.report.domain.RunStatus;
import com.treasury.nl2sql.report.store.ReportFactRepository;
import com.treasury.nl2sql.report.store.ReportRunRepository;
import com.treasury.nl2sql.report.store.ReportStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 6 步流水线编排器（刻意不做状态机框架，if/switch 推进 + 全量落库）。
 * 执行模型：① 同步（一次 LLM 调用，秒级）；确认大纲后 ②~⑥ 守护线程异步
 * （范式照 EvalJobService；per-run 防重复 kick；状态全落库，轮询读 DB，进程重启不丢）。
 * 两条底线的落点：任何 PolicyException → BLOCKED[POLICY]，意外异常 → BLOCKED[EXCEPTION]；
 * 每步 report_step 留痕输入输出，resume 从 run.phase 分段续跑（SPEC~FACT 整段重跑、WRITE/AUDIT 读库续跑）。
 */
@Service
public class ReportPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReportPipeline.class);
    /** RUNNING 但超过该时长无更新且不在本进程执行中 → 视为宕机遗留（stale），放开 resume。 */
    private static final Duration STALE_RUNNING = Duration.ofMinutes(2);

    private final ReportRunRepository runRepo;
    private final ReportStepRepository stepRepo;
    private final ReportFactRepository factRepo;
    private final ReportAssetService assets;
    private final OutlineStep outlineStep;
    private final SpecResolveStep specStep;
    private final FetchStep fetchStep;
    private final FactBuildStep factStep;
    private final WriteStep writeStep;
    private final AuditStep auditStep;
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public ReportPipeline(ReportRunRepository runRepo, ReportStepRepository stepRepo, ReportFactRepository factRepo,
                          ReportAssetService assets, OutlineStep outlineStep, SpecResolveStep specStep,
                          FetchStep fetchStep, FactBuildStep factStep, WriteStep writeStep, AuditStep auditStep,
                          ObjectMapper mapper) {
        this.runRepo = runRepo;
        this.stepRepo = stepRepo;
        this.factRepo = factRepo;
        this.assets = assets;
        this.outlineStep = outlineStep;
        this.specStep = specStep;
        this.fetchStep = fetchStep;
        this.factStep = factStep;
        this.writeStep = writeStep;
        this.auditStep = auditStep;
        this.mapper = mapper;
    }

    // ---------- ①（同步） + HITL 卡点1 ----------

    /** 新建运行并同步跑 ①；返回 AWAITING_OUTLINE_APPROVAL（或 BLOCKED）态的 run。 */
    public ReportRun createRun(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            throw new IllegalArgumentException("requestText 不能为空");
        }
        long runId = runRepo.insert(requestText.trim());
        runOutline(runId, requestText.trim(), null);
        return require(runId);
    }

    /** HITL 卡点1 打回：带修改意见重跑 ①（attempt+1 留痕）。 */
    public ReportRun regenerateOutline(long runId, String revisedRequest) {
        ReportRun run = require(runId);
        assertStatus(run, "打回大纲", RunStatus.AWAITING_OUTLINE_APPROVAL, RunStatus.BLOCKED);
        runOutline(runId, run.requestText(), revisedRequest);
        return require(runId);
    }

    private void runOutline(long runId, String requestText, String revisionNote) {
        long stepId = stepRepo.start(runId, Phase.OUTLINE.name(),
                toJson(Map.of("requestText", requestText, "revisionNote", String.valueOf(revisionNote))));
        try {
            Outline outline = outlineStep.run(requestText, revisionNote);
            PeriodResolver.Window current = PeriodResolver.resolve(outline.periodLabel());
            PeriodResolver.Window compare = PeriodResolver.previous(current);
            String outlineJson = toJson(outline);
            runRepo.saveOutline(runId, outline.templateId(), assets.publishedVersion(outline.templateId()),
                    current.label(),
                    current.start(), current.end(), compare.start(), compare.end(), outlineJson);
            stepRepo.finishOk(stepId, outlineJson);
        } catch (PolicyException e) {
            stepRepo.finishBlocked(stepId, e.getMessage());
            runRepo.setBlocked(runId, Phase.OUTLINE.name(), "[POLICY] " + e.getMessage());
        } catch (Exception e) {
            log.error("[RUN-{}] ① 大纲生成意外异常", runId, e);
            stepRepo.finishBlocked(stepId, String.valueOf(e));
            runRepo.setBlocked(runId, Phase.OUTLINE.name(), "[EXCEPTION] " + e.getMessage());
        }
    }

    /** HITL 卡点1 确认：以人确认的大纲版本为准落库（口径锁死），kick ②~⑥ 异步。 */
    public ReportRun approveOutline(long runId, String approver, JsonNode editedOutline) {
        ReportRun run = require(runId);
        assertStatus(run, "确认大纲", RunStatus.AWAITING_OUTLINE_APPROVAL);
        Outline outline = editedOutline == null || editedOutline.isNull()
                ? readOutline(run)
                : parseEditedOutline(run, editedOutline);
        runRepo.approveOutline(runId, blankTo(approver, "demo"), toJson(outline));
        kick(runId, Phase.SPEC);
        return require(runId);
    }

    /** 人只允许增删章节指标与调指引，模板与报告期不许改（改口径请打回重来）。 */
    private Outline parseEditedOutline(ReportRun run, JsonNode edited) {
        Outline outline;
        try {
            outline = mapper.treeToValue(edited, Outline.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("回传的大纲 JSON 无法解析: " + e.getMessage());
        }
        Outline stored = readOutline(run);
        if (!stored.templateId().equals(outline.templateId())
                || !stored.periodLabel().equals(outline.periodLabel())) {
            throw new IllegalArgumentException("确认时不允许更改模板或报告期（如需调整请打回重新生成大纲）");
        }
        if (outline.chapters() == null || outline.chapters().isEmpty()
                || outline.chapters().stream().allMatch(c -> c.metricIds().isEmpty())) {
            throw new IllegalArgumentException("确认的大纲至少要保留一个章节指标");
        }
        for (Outline.OutlineChapter ch : outline.chapters()) {
            for (String id : ch.metricIds()) {
                if (assets.metric(id).isEmpty()) {
                    throw new IllegalArgumentException("大纲包含未知指标: " + id);
                }
            }
        }
        return outline;
    }

    // ---------- ②~⑥（异步） ----------

    private void kick(long runId, Phase from) {
        if (inFlight.putIfAbsent(runId, Boolean.TRUE) != null) {
            throw new IllegalStateException("run " + runId + " 已在执行中");
        }
        Thread t = new Thread(() -> {
            try {
                runAsync(runId, from);
            } finally {
                inFlight.remove(runId);
            }
        }, "report-run-" + runId);
        t.setDaemon(true);
        t.start();
    }

    private void runAsync(long runId, Phase from) {
        Phase phase = from;
        try {
            ReportRun run = require(runId);
            Outline outline = readOutline(run);
            PeriodResolver.Window current = PeriodResolver.resolve(run.periodLabel());
            PeriodResolver.Window compare = PeriodResolver.previous(current);

            List<FactRecord> facts;
            List<String> notes;
            if (from.ordinal() <= Phase.FACT.ordinal()) {
                // ② 语义解析
                phase = Phase.SPEC;
                runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), phase.name());
                long specStepId = stepRepo.start(runId, phase.name(), toJson(outline));
                List<MetricQuerySpec> specs;
                try {
                    specs = specStep.run(outline, current, compare, assets.allMetrics());
                    stepRepo.finishOk(specStepId, toJson(specs));
                } catch (RuntimeException e) {
                    stepRepo.finishBlocked(specStepId, e.getMessage());
                    throw e;
                }

                // ③ 安全取数
                phase = Phase.FETCH;
                runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), phase.name());
                long fetchStepId = stepRepo.start(runId, phase.name(), toJson(specs));
                List<FetchStep.FetchResult> fetched;
                try {
                    fetched = fetchStep.run(specs);
                    stepRepo.finishOk(fetchStepId, toJson(fetched));
                } catch (RuntimeException e) {
                    stepRepo.finishBlocked(fetchStepId, e.getMessage());
                    throw e;
                }

                // ④ 事实构建（重建前清空旧事实，幂等）
                phase = Phase.FACT;
                runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), phase.name());
                long factStepId = stepRepo.start(runId, phase.name(), null);
                try {
                    FactBuildStep.FactBuildResult built = factStep.run(outline, fetched, assets.allMetrics());
                    factRepo.deleteByRun(runId);
                    factRepo.batchInsert(runId, built.facts());
                    facts = built.facts();
                    notes = built.notes();
                    stepRepo.finishOk(factStepId, toJson(Map.of("factCount", facts.size(), "notes", notes)));
                } catch (RuntimeException e) {
                    stepRepo.finishBlocked(factStepId, e.getMessage());
                    throw e;
                }
            } else {
                // 断点续跑 WRITE/AUDIT：事实与 notes 从库读，不重取数
                facts = factRepo.findByRun(runId);
                if (facts.isEmpty()) {
                    throw new PolicyException("断点续跑失败：库中没有该运行的事实记录（请从头重跑）");
                }
                notes = readFactNotes(runId);
            }

            // ⑤ 章节撰写
            phase = Phase.WRITE;
            runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), phase.name());
            long writeStepId = stepRepo.start(runId, phase.name(),
                    toJson(Map.of("factCount", facts.size(), "notes", notes)));
            WriteStep.Draft draft;
            try {
                draft = writeStep.run(outline, facts, notes);
                stepRepo.finishOk(writeStepId, toJson(Map.of("reportMd", draft.reportMd())));
            } catch (RuntimeException e) {
                stepRepo.finishBlocked(writeStepId, e.getMessage());
                throw e;
            }

            // ⑥ 证据审计（数字一致率 100% 硬门禁）
            phase = Phase.AUDIT;
            runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), phase.name());
            long auditStepId = stepRepo.start(runId, phase.name(), null);
            try {
                Map<String, FactRecord> byKey = factsByKey(facts);
                AuditStep.AuditOutcome outcome = auditStep.run(draft, byKey);
                stepRepo.finishOk(auditStepId, toJson(outcome.audit()));
                runRepo.saveReport(runId, outcome.reportMd(), toJson(outcome.audit()));
            } catch (RuntimeException e) {
                stepRepo.finishBlocked(auditStepId, e.getMessage());
                throw e;
            }
            log.info("[RUN-{}] ②~⑥ 完成，进入发布审批等待", runId);
        } catch (PolicyException e) {
            log.warn("[RUN-{}] 失败关闭于 {}: {}", runId, phase, e.getMessage());
            runRepo.setBlocked(runId, phase.name(), "[POLICY] " + e.getMessage());
        } catch (Exception e) {
            log.error("[RUN-{}] 意外异常于 {}", runId, phase, e);
            runRepo.setBlocked(runId, phase.name(), "[EXCEPTION] " + e.getMessage());
        }
    }

    // ---------- 断点恢复 ----------

    /** 仅 BLOCKED（或宕机遗留的 stale RUNNING）可续跑：OUTLINE 重跑①，SPEC~FACT 从②整段重跑，WRITE/AUDIT 读库续跑。 */
    public ReportRun resume(long runId) {
        ReportRun run = require(runId);
        boolean stale = RunStatus.RUNNING.name().equals(run.status())
                && !inFlight.containsKey(runId)
                && run.updatedAt() != null
                && run.updatedAt().isBefore(LocalDateTime.now().minus(STALE_RUNNING));
        if (!RunStatus.BLOCKED.name().equals(run.status()) && !stale) {
            throw new IllegalStateException("仅 BLOCKED（或已停摆的 RUNNING）可断点续跑，当前状态: " + run.status());
        }
        // 版本漂移护栏：run 固化的模板版本与注册表当前 PUBLISHED 不一致时提示。
        // ②~⑥ 全部基于 outline_json 快照续跑（内容天然固化），P1 内版本恒为 1 仅防御；
        // P2 引入页面发布新版本后升级为失败关闭或按固化版本从库回读。
        if (run.templateId() != null && run.templateVersion() != null) {
            Integer current = assets.template(run.templateId()).isPresent()
                    ? assets.publishedVersion(run.templateId()) : null;
            if (!run.templateVersion().equals(current)) {
                log.warn("[RUN-{}] 模板 {} 固化版本 v{} 与注册表当前 PUBLISHED v{} 不一致，续跑仍使用大纲快照（固化口径）",
                        runId, run.templateId(), run.templateVersion(), current);
            }
        }
        Phase phase = run.phase() == null ? Phase.OUTLINE : Phase.valueOf(run.phase());
        if (phase == Phase.OUTLINE) {
            runOutline(runId, run.requestText(), null);
        } else {
            Phase entry = phase.ordinal() <= Phase.FACT.ordinal() ? Phase.SPEC : Phase.WRITE;
            runRepo.setStatusPhase(runId, RunStatus.RUNNING.name(), entry.name());
            kick(runId, entry);
        }
        return require(runId);
    }

    // ---------- HITL 卡点2 ----------

    /** 发布审批：门禁在服务端复核（audit 必须存在且一致率 100%），不信任前端。 */
    public ReportRun approvePublish(long runId, String approver) {
        ReportRun run = require(runId);
        assertStatus(run, "发布审批", RunStatus.AWAITING_PUBLISH_APPROVAL);
        try {
            JsonNode audit = mapper.readTree(run.auditJson());
            if (!audit.path("passed").asBoolean(false)) {
                throw new IllegalStateException("审计包不是通过状态，不允许发布");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("审计包缺失或无法解析，不允许发布");
        }
        runRepo.publishApprove(runId, blankTo(approver, "demo"));
        return require(runId);
    }

    public ReportRun rejectPublish(long runId, String approver, String reason) {
        ReportRun run = require(runId);
        assertStatus(run, "发布驳回", RunStatus.AWAITING_PUBLISH_APPROVAL);
        runRepo.publishReject(runId, blankTo(approver, "demo"), blankTo(reason, "(未填写驳回原因)"));
        return require(runId);
    }

    // ---------- 查询 ----------

    public ReportRun require(long runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run " + runId + " 不存在"));
    }

    public List<ReportRun> list() {
        return runRepo.findAll();
    }

    // ---------- 工具 ----------

    private Outline readOutline(ReportRun run) {
        if (run.outlineJson() == null) {
            throw new PolicyException("运行没有已确认的大纲");
        }
        try {
            return mapper.readValue(run.outlineJson(), Outline.class);
        } catch (Exception e) {
            throw new IllegalStateException("大纲 JSON 无法解析", e);
        }
    }

    private List<String> readFactNotes(long runId) {
        try {
            String out = stepRepo.latestOkOutput(runId, Phase.FACT.name()).orElse(null);
            if (out == null) return List.of();
            List<String> notes = new ArrayList<>();
            for (JsonNode n : mapper.readTree(out).path("notes")) {
                notes.add(n.asText());
            }
            return notes;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, FactRecord> factsByKey(List<FactRecord> facts) {
        Map<String, FactRecord> byKey = new LinkedHashMap<>();
        for (FactRecord f : facts) byKey.put(f.factKey(), f);
        return byKey;
    }

    private static void assertStatus(ReportRun run, String action, RunStatus... allowed) {
        for (RunStatus s : allowed) {
            if (s.name().equals(run.status())) return;
        }
        throw new IllegalStateException("当前状态 " + run.status() + " 不允许「" + action + "」");
    }

    private static String blankTo(String s, String dft) {
        return s == null || s.isBlank() ? dft : s.trim();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
