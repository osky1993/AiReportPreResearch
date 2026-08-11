package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeRuleSet.Rule;
import com.treasury.nl2sql.expe.ExpeRuleSet.RuleVerdict;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 评估任务编排：对已完成生成任务的多轮输出做「逐份 × 逐规则」判定，
 * 支撑组内纵向（同组 N 次的分布与稳定性）与跨组横向（A/B/C/D 汇总对比）评估。
 *
 * 判定分流（已确认设计）：PROGRAM 规则走 {@link ExpeProgramChecker} 确定性判定（零 LLM）；
 * AI 规则每份输出一次 {@link ExpeJudgeCaller} 调用逐条判定；HUMAN 规则标记待人工、不进任何自动汇总。
 *
 * 落盘目录（{evals-dir}/{evalId}/）：rules.json 规则清单冻结副本、meta.json 全部判定明细、
 * judge_raw/ 每次 judge 调用的原始响应。评估只读生成任务目录，绝不修改原始生成证据。
 *
 * <p>设计约束：
 * <ul>
 *   <li>任何单份输出异常不阻断整次评估，异常分支记 ERROR 并可复评</li>
 *   <li>HUMAN 规则不进自动汇总，仅保留可见明细</li>
 *   <li>评分与规则执行的单一事实源为 meta.json 的逐条 verdict</li>
 * </ul>
 */
@Service
public class ExpeEvalService {

    private static final Logger log = LoggerFactory.getLogger(ExpeEvalService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 创建请求：待评生成任务 + 其组别（A/B/B-Pad/C/D/D-Clean/D-Conflict，决定适用规则层） */
    public record TargetSpec(String taskId, String group) {}

    public record EvalTarget(String taskId, String label, String group) {}

    /** 单份输出的评估结果：verdicts 按适用规则清单顺序对齐；quality=judge 统一业务质量盲评（可为 null） */
    public record OutputEval(String taskId, int index, List<RuleVerdict> verdicts,
                             ExpeJudgeCaller.QualityScore quality, String judgeError) {}

    /**
     * 评估任务快照（meta.json 持久化结构 + API 返回结构）。
     * doneOutputs 直接由已输出判定条目数推导，用于任务进度与恢复。
     */
    public record EvalInfo(String evalId, String createdAt, String finishedAt, String status,
                           String judgeModel, String rulesFileName, String rulesSha256, int ruleCount,
                           List<EvalTarget> targets, int totalOutputs, int doneOutputs,
                           List<OutputEval> outputs, String error) {}

    /** 规则行（热力图行头） */
    public record RuleRow(String ruleId, String ruleText, String level, boolean shared,
                          String introducedIn, String checkType, double weight) {}

    /** 单目标（组）聚合指标；比率均为 0~1，Core 分为 0~100；extraAdherence 无新增层时为 null；
     *  qualityMean=统一业务质量盲评均分（1~5，四组同一把尺子，跨组横向可比；无有效打分时为 null） */
    public record TargetAggregate(String taskId, String label, String group, int evaluated,
                                  double ssrMean, double ssrMedian, double hsrRate,
                                  double coreWeightedMean, double coreUnweightedMean,
                                  double stablePassRate, int p0FailedOutputs, Double extraAdherenceMean,
                                  Double qualityMean, int judgeErrorOutputs) {}

    public record EvalDetail(EvalInfo info, List<RuleRow> rules, List<TargetAggregate> aggregates) {}

    private static final class EvalState {
        final String evalId;
        final String createdAt;
        final String judgeModel;
        final String rulesFileName;
        final String rulesSha256;
        final int ruleCount;
        final List<EvalTarget> targets;
        final int totalOutputs;
        final List<OutputEval> outputs = new ArrayList<>();
        String status;
        String finishedAt;
        String error;
        volatile boolean cancelRequested;

        EvalState(String evalId, String createdAt, String judgeModel, String rulesFileName, String rulesSha256,
                  int ruleCount, List<EvalTarget> targets, int totalOutputs, String status) {
            this.evalId = evalId;
            this.createdAt = createdAt;
            this.judgeModel = judgeModel;
            this.rulesFileName = rulesFileName;
            this.rulesSha256 = rulesSha256;
            this.ruleCount = ruleCount;
            this.targets = targets;
            this.totalOutputs = totalOutputs;
            this.status = status;
        }

        static EvalState fromInfo(EvalInfo info) {
            EvalState s = new EvalState(info.evalId(), info.createdAt(), info.judgeModel(), info.rulesFileName(),
                    info.rulesSha256(), info.ruleCount(), info.targets(), info.totalOutputs(), info.status());
            s.finishedAt = info.finishedAt();
            s.error = info.error();
            if (info.outputs() != null) s.outputs.addAll(info.outputs());
            return s;
        }

        synchronized EvalInfo snapshot() {
            // 逐份评估并行执行后完成顺序不定，快照按（任务, 序号）排序保证热力图列序稳定
            List<OutputEval> sorted = outputs.stream()
                    .sorted(Comparator.comparing(OutputEval::taskId).thenComparingInt(OutputEval::index))
                    .toList();
            return new EvalInfo(evalId, createdAt, finishedAt, status, judgeModel, rulesFileName, rulesSha256,
                    ruleCount, targets, totalOutputs, sorted.size(), sorted, error);
        }

        synchronized void addOutput(OutputEval o) { outputs.add(o); }

        synchronized void finish(String finalStatus, String err) {
            this.status = finalStatus;
            this.error = err;
            this.finishedAt = LocalDateTime.now().format(TS);
        }
    }

    private final ExpeProperties props;
    private final ExpeTaskService taskService;
    private final ExpeProgramChecker checker;
    private final ExpeJudgeCaller judge;
    private final ObjectMapper mapper;
    private final Map<String, EvalState> evals = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private ExecutorService executor;
    /** judge 调用共享池：所有评估任务的逐份判定都扇出到这里，全局并发 = expe.judge-concurrency */
    private ExecutorService judgeExecutor;

    public ExpeEvalService(ExpeProperties props, ExpeTaskService taskService, ExpeProgramChecker checker,
                           ExpeJudgeCaller judge, ObjectMapper mapper) {
        this.props = props;
        this.taskService = taskService;
        this.checker = checker;
        this.judge = judge;
        this.mapper = mapper;
    }

    /**
     * 组件初始化：
     * <ul>
     *   <li>创建评估任务调度池（固定 2 线程，防止过载）</li>
     *   <li>创建 judge 调用池（并发受 expe.judge-concurrency 控制）</li>
     *   <li>重建目录并扫描历史 meta，保障服务重启后可读状态</li>
     * </ul>
     */
    @PostConstruct
    void init() throws IOException {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "expe-eval-worker");
            t.setDaemon(true);
            return t;
        });
        judgeExecutor = Executors.newFixedThreadPool(Math.max(1, props.getJudgeConcurrency()), r -> {
            Thread t = new Thread(r, "expe-judge-call");
            t.setDaemon(true);
            return t;
        });
        Files.createDirectories(Path.of(props.getEvalsDir()));
        restoreFromDisk();
    }

    /** 关闭执行池，避免退出时留存未中断的非守护线程。 */
    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
        if (judgeExecutor != null) judgeExecutor.shutdownNow();
    }

    /**
     * 重启恢复：扫描 evals 目录下 meta.json；
     * 运行中/排队中的历史任务会标记 INTERRUPTED，不自动继续执行，防止重复计数。
     */
    private void restoreFromDisk() {
        try (var dirs = Files.list(Path.of(props.getEvalsDir()))) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path meta = dir.resolve("meta.json");
                if (!Files.exists(meta)) return;
                try {
                    EvalInfo info = mapper.readValue(meta.toFile(), EvalInfo.class);
                    EvalState state = EvalState.fromInfo(info);
                    if ("RUNNING".equals(state.status) || "QUEUED".equals(state.status)) {
                        state.finish("INTERRUPTED", "服务重启打断");
                    }
                    evals.put(state.evalId, state);
                    saveMeta(state);
                } catch (Exception e) {
                    log.warn("[expe-eval] 恢复评估任务失败，跳过 {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[expe-eval] 扫描评估目录失败: {}", e.getMessage());
        }
    }

    /**
     * 创建并启动评估。
     * <p>校验规则：
     * <ul>
     *   <li>待评任务不能为空</li>
     *   <li>组别必须在合法集合内</li>
     *   <li>任务不能重复选择</li>
     *   <li>任务必须有成功输出，否则不能形成评估目标</li>
     * </ul>
     */
    public EvalInfo create(List<TargetSpec> targetSpecs, byte[] rulesBytes, String rulesFileName) throws IOException {
        if (targetSpecs == null || targetSpecs.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个待评的生成任务");
        }
        if (rulesBytes == null) {
            Path p = Path.of(props.getRulesPath());
            if (!Files.exists(p)) throw new IllegalStateException("默认规则清单不存在: " + props.getRulesPath());
            rulesBytes = Files.readAllBytes(p);
            rulesFileName = p.getFileName().toString();
        }
        ExpeRuleSet ruleSet = ExpeRuleSet.parse(rulesBytes, mapper);

        List<EvalTarget> targets = new ArrayList<>();
        int totalOutputs = 0;
        Set<String> seen = new HashSet<>();
        for (TargetSpec spec : targetSpecs) {
            if (!ExpeRuleSet.validGroups().contains(spec.group())) {
                throw new IllegalArgumentException("组别非法: " + spec.group() + "，合法值 " + ExpeRuleSet.validGroups());
            }
            if (!seen.add(spec.taskId())) throw new IllegalArgumentException("任务重复选择: " + spec.taskId());
            ExpeTaskService.TaskInfo t = taskService.getTask(spec.taskId());
            if (t.okCount() == 0) throw new IllegalArgumentException("任务无成功输出，无法评估: " + t.label());
            targets.add(new EvalTarget(t.taskId(), t.label(), spec.group()));
            totalOutputs += t.okCount();
        }

        String evalId = LocalDateTime.now().format(ID_TS) + "-eval-" + HexFormat.of().toHexDigits(random.nextInt(), 4);
        Path dir = Path.of(props.getEvalsDir(), evalId);
        Files.createDirectories(dir.resolve("judge_raw"));
        Files.write(dir.resolve("rules.json"), rulesBytes);

        EvalState state = new EvalState(evalId, LocalDateTime.now().format(TS), judge.model(), rulesFileName,
                ruleSet.sha256(), ruleSet.rules().size(), List.copyOf(targets), totalOutputs, "QUEUED");
        evals.put(evalId, state);
        saveMeta(state);
        executor.submit(() -> runEval(state, ruleSet));
        return state.snapshot();
    }

    /**
     * 评估执行主循环。
     * <p>每个目标先准备 data 根节点与可用 evidence 集合，再将所有「任务+输出序号」组装成 Job 后并发扇出；
     * 全部判定完成后再统一落状态，避免中间态被前端误读为已完成。
     */
    private void runEval(EvalState state, ExpeRuleSet ruleSet) {
        synchronized (state) {
            if (state.cancelRequested) { state.finish("CANCELLED", null); saveMeta(state); return; }
            state.status = "RUNNING";
        }
        saveMeta(state);
        Path dir = Path.of(props.getEvalsDir(), state.evalId);
        try {
            // 先为每个目标准备上下文，再把全部「待评输出」一次性扇出到 judge 共享池并行判定
            record Job(EvalTarget target, int index, String dataJson, Set<String> validIds, JsonNode dataRoot,
                       List<Rule> applicable, List<Rule> aiRules) {}
            List<Job> jobs = new ArrayList<>();
            for (EvalTarget target : state.targets) {
                ExpeTaskService.TaskInfo task = taskService.getTask(target.taskId());
                String dataJson = Files.readString(
                        Path.of(props.getRunsDir(), target.taskId(), "data.json"), StandardCharsets.UTF_8);
                Set<String> validIds = collectEvidenceIds(dataJson);
                JsonNode dataRoot = mapper.readTree(dataJson);
                List<Rule> applicable = ruleSet.applicableTo(target.group());
                List<Rule> aiRules = applicable.stream().filter(r -> "AI".equals(r.checkType())).toList();
                for (ExpeTaskService.CallInfo call : task.calls()) {
                    if ("OK".equals(call.status())) {
                        jobs.add(new Job(target, call.index(), dataJson, validIds, dataRoot, applicable, aiRules));
                    }
                }
            }

            CountDownLatch latch = new CountDownLatch(jobs.size());
            for (Job job : jobs) {
                judgeExecutor.submit(() -> {
                    try {
                        if (state.cancelRequested) return;
                        OutputEval oe;
                        try {
                            oe = evalOneOutput(state, dir, job.target(), job.index(), job.dataJson(),
                                    job.validIds(), job.dataRoot(), job.applicable(), job.aiRules());
                        } catch (Exception e) {
                            // 单份评估异常不拖垮整个评估：该份全部规则记 ERROR，可复评
                            log.warn("[expe-eval] 评估 {} 任务 {} 第 {} 次异常: {}",
                                    state.evalId, job.target().label(), job.index(), e.getMessage());
                            oe = new OutputEval(job.target().taskId(), job.index(),
                                    job.applicable().stream().map(r -> new RuleVerdict(r.ruleId(), "ERROR", null,
                                            "评估异常: " + abbreviate(e.getMessage(), 200))).toList(),
                                    null, abbreviate(e.getMessage(), 1000));
                        }
                        state.addOutput(oe);
                        saveMeta(state);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            state.finish(state.cancelRequested ? "CANCELLED" : "DONE", null);
        } catch (Exception e) {
            log.warn("[expe-eval] 评估 {} 异常终止: {}", state.evalId, e.getMessage(), e);
            state.finish("FAILED", e.getMessage());
        }
        saveMeta(state);
        log.info("[expe-eval] 评估 {} 结束，状态 {}", state.evalId, state.status);
    }

    /**
     * 执行单份输出（某 task 的某次输出）的完整判定：
     * 1) PROGRAM/HUMAN 规则先本地确定性计算
     * 2) AI 规则调用 judge（若启用），judge 异常不会抛给上游，改记 ERROR，整份继续入库
     */
    private OutputEval evalOneOutput(EvalState state, Path dir, EvalTarget target, int index, String dataJson,
                                     Set<String> validIds, JsonNode dataRoot,
                                     List<Rule> applicable, List<Rule> aiRules) throws IOException {
        String content = new String(taskService.readResult(target.taskId(), index), StandardCharsets.UTF_8);
        ExpeProgramChecker.ParsedOutput parsed = checker.parse(content);

        Map<String, RuleVerdict> byId = new HashMap<>();
        for (Rule r : applicable) {
            if ("PROGRAM".equals(r.checkType())) {
                byId.put(r.ruleId(), checker.check(r, parsed, validIds, dataRoot));
            } else if ("HUMAN".equals(r.checkType())) {
                byId.put(r.ruleId(), new RuleVerdict(r.ruleId(), "HUMAN", null, "需人工判定，不进自动汇总"));
            }
        }

        String judgeError = null;
        ExpeJudgeCaller.QualityScore quality = null;
        if (!aiRules.isEmpty()) {
            try {
                ExpeJudgeCaller.JudgeResult jr = judge.judge(dataJson, content, aiRules);
                Files.writeString(dir.resolve("judge_raw").resolve("judge_" + target.taskId() + "_" + index + ".json"),
                        jr.rawResponse(), StandardCharsets.UTF_8);
                for (RuleVerdict v : jr.verdicts()) byId.put(v.ruleId(), v);
                quality = jr.quality();
            } catch (Exception e) {
                judgeError = abbreviate(e.getMessage(), 1000);
                for (Rule r : aiRules) {
                    byId.put(r.ruleId(), new RuleVerdict(r.ruleId(), "ERROR", null, "judge 调用失败"));
                }
                log.warn("[expe-eval] 评估 {} 任务 {} 第 {} 次 judge 调用失败: {}",
                        state.evalId, target.label(), index, judgeError);
            }
        }

        List<RuleVerdict> ordered = applicable.stream().map(r -> byId.get(r.ruleId())).toList();
        return new OutputEval(target.taskId(), index, ordered, quality, judgeError);
    }

    /**
     * 从冻结 data.json 收集合法 evidence ID 全集（indicator_id/industry_id/subregion_id/risk_id）。
     * 仅用于校验 PROGRAM 的 evidence 真实引用，不依赖实时数据库，保证每次评估可复现。
     */
    static Set<String> collectEvidenceIds(String dataJson) {
        Set<String> ids = new HashSet<>();
        try {
            JsonNode root = new ObjectMapper().readTree(dataJson);
            collectIds(root, ids);
        } catch (Exception e) {
            throw new IllegalStateException("数据包解析失败: " + e.getMessage());
        }
        return ids;
    }

    /**
     * 深度扫描 data.json：对象与数组都递归，忽略其它结构和非文本值。
     * 约束仅收集固定四类 key，避免把规则文本中的相似字段误识别为 evidence 引用。
     */
    private static void collectIds(JsonNode node, Set<String> ids) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (Set.of("indicator_id", "industry_id", "subregion_id", "risk_id").contains(e.getKey())
                        && e.getValue().isTextual()) {
                    ids.add(e.getValue().asText());
                }
                collectIds(e.getValue(), ids);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectIds(n, ids));
        }
    }

    /** 列出当前内存中的评估快照（倒序展示），返回值不直接依赖磁盘扫描。 */
    public List<EvalInfo> list() {
        return evals.values().stream()
                .map(EvalState::snapshot)
                .sorted(Comparator.comparing(EvalInfo::createdAt).reversed().thenComparing(EvalInfo::evalId))
                .toList();
    }

    /**
     * 明细视图 = 全量判定矩阵 + 规则行头 + 各组聚合指标。
     * 聚合使用当前快照内的所有明细计算，单一事实源为 meta 内存档（持久化副本）。
     */
    public EvalDetail detail(String evalId) throws IOException {
        EvalState s = evals.get(evalId);
        if (s == null) throw new IllegalArgumentException("评估不存在: " + evalId);
        EvalInfo info = s.snapshot();
        ExpeRuleSet ruleSet = ExpeRuleSet.parse(
                Files.readAllBytes(Path.of(props.getEvalsDir(), evalId, "rules.json")), mapper);
        List<RuleRow> rows = ruleSet.rules().stream()
                .map(r -> new RuleRow(r.ruleId(), r.ruleText(), r.level(), r.shared(),
                        r.introducedIn(), r.checkType(), r.weight()))
                .toList();
        List<TargetAggregate> aggregates = new ArrayList<>();
        for (EvalTarget t : info.targets()) {
            aggregates.add(aggregate(t, info.outputs(), ruleSet));
        }
        return new EvalDetail(info, rows, aggregates);
    }

    /**
     * 按目标（组）聚合：将该组所有输出按规则维度统计。
     * <p>统计口径：
     * <ul>
     *   <li>ssrMean/ssrMedian：单份可自动评分得分均值与中位数</li>
     *   <li>hsrRate：目标内（可评分）全部规则 PASS 的比例</li>
     *   <li>coreWeightedMean/coreUnweightedMean：共享约束（shared）加权/不加权平均</li>
     *   <li>judgeErrorOutputs：judge API 异常次数，用于快速排查可复评样本</li>
     * </ul>
     */
    private TargetAggregate aggregate(EvalTarget target, List<OutputEval> allOutputs, ExpeRuleSet ruleSet) {
        List<OutputEval> outputs = allOutputs.stream().filter(o -> o.taskId().equals(target.taskId())).toList();
        // 评分范围排除 HUMAN 规则（不可自动判定）；ERROR 计 0 分并单列 judgeErrorOutputs 提示
        List<Rule> scored = ruleSet.applicableTo(target.group()).stream()
                .filter(r -> !"HUMAN".equals(r.checkType())).toList();
        Set<String> scoredIds = new HashSet<>(scored.stream().map(Rule::ruleId).toList());
        Set<String> sharedIds = new HashSet<>(scored.stream().filter(Rule::shared).map(Rule::ruleId).toList());
        Set<String> p0Ids = new HashSet<>(scored.stream().filter(r -> "P0".equals(r.level())).map(Rule::ruleId).toList());
        Map<String, Double> weightById = new HashMap<>();
        scored.forEach(r -> weightById.put(r.ruleId(), r.weight()));
        Set<String> extraIds = new HashSet<>(ruleSet.introducedAtTop(target.group()).stream()
                .filter(r -> !"HUMAN".equals(r.checkType())).map(Rule::ruleId).toList());

        List<Double> ssrs = new ArrayList<>();
        int hsr = 0, stable = 0, p0Failed = 0, judgeErrors = 0;
        double coreWeightedSum = 0, coreUnweightedSum = 0;
        List<Double> extraMeans = new ArrayList<>();
        List<Double> qualityOveralls = new ArrayList<>();

        for (OutputEval o : outputs) {
            if (o.judgeError() != null) judgeErrors++;
            Double q = qualityOverall(o.quality());
            if (q != null) qualityOveralls.add(q);
            double sum = 0, coreW = 0, coreWTotal = 0, coreSum = 0, extraSum = 0;
            int n = 0, coreN = 0, extraN = 0;
            boolean allPass = true, sharedAllPass = true, p0AllPass = true;
            for (RuleVerdict v : o.verdicts()) {
                if (!scoredIds.contains(v.ruleId())) continue;
                double score = switch (v.verdict()) {
                    case "PASS" -> 1;
                    case "PARTIAL" -> 0.5;
                    default -> 0;
                };
                sum += score;
                n++;
                if (score < 1) allPass = false;
                if (sharedIds.contains(v.ruleId())) {
                    double w = weightById.get(v.ruleId());
                    coreW += w * score;
                    coreWTotal += w;
                    coreSum += score;
                    coreN++;
                    if (score < 1) sharedAllPass = false;
                }
                if (p0Ids.contains(v.ruleId()) && score < 1) p0AllPass = false;
                if (extraIds.contains(v.ruleId())) { extraSum += score; extraN++; }
            }
            ssrs.add(n == 0 ? 0 : sum / n);
            if (allPass) hsr++;
            if (sharedAllPass) stable++;
            if (!p0AllPass) p0Failed++;
            coreWeightedSum += coreWTotal == 0 ? 0 : coreW / coreWTotal * 100;
            coreUnweightedSum += coreN == 0 ? 0 : coreSum / coreN * 100;
            if (extraN > 0) extraMeans.add(extraSum / extraN);
        }

        int count = outputs.size();
        return new TargetAggregate(target.taskId(), target.label(), target.group(), count,
                mean(ssrs), median(ssrs),
                count == 0 ? 0 : (double) hsr / count,
                count == 0 ? 0 : coreWeightedSum / count,
                count == 0 ? 0 : coreUnweightedSum / count,
                count == 0 ? 0 : (double) stable / count,
                p0Failed,
                extraMeans.isEmpty() ? null : mean(extraMeans),
                qualityOveralls.isEmpty() ? null : mean(qualityOveralls),
                judgeErrors);
    }

    /** 单份输出的统一质量总分 = 四维有效分均值（全部缺失时为 null） */
    private static Double qualityOverall(ExpeJudgeCaller.QualityScore q) {
        if (q == null) return null;
        List<Integer> dims = new ArrayList<>();
        if (q.structure() != null) dims.add(q.structure());
        if (q.analysis() != null) dims.add(q.analysis());
        if (q.expression() != null) dims.add(q.expression());
        if (q.usability() != null) dims.add(q.usability());
        return dims.isEmpty() ? null : dims.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    private static double mean(List<Double> xs) {
        return xs.isEmpty() ? 0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        List<Double> sorted = xs.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** 请求取消：仅置位 cancelRequested，不做硬中断；各提交任务会在执行前/中检查标记。 */
    public void cancel(String evalId) {
        EvalState s = evals.get(evalId);
        if (s == null) throw new IllegalArgumentException("评估不存在: " + evalId);
        s.cancelRequested = true;
    }

    /** 删除已结束的评估：清理内存登记 + 落盘目录；运行中状态需先取消。 */
    public void delete(String evalId) throws IOException {
        EvalState s = evals.get(evalId);
        if (s == null) throw new IllegalArgumentException("评估不存在: " + evalId);
        String status = s.snapshot().status();
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            throw new IllegalStateException("评估执行中，请先取消再删除");
        }
        Path dir = Path.of(props.getEvalsDir(), evalId);
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        evals.remove(evalId);
        log.info("[expe-eval] 已删除评估 {} 及其落盘目录", evalId);
    }

    /**
     * 评估执行中的状态快照落盘点：每次 output 追加或状态推进后更新 meta.json。
     * 采用状态对象锁同步，避免并发任务写 meta.json 时交错覆盖（元信息不幂等可否重放依赖它）。
     */
    private void saveMeta(EvalState state) {
        synchronized (state) {
            try {
                Path meta = Path.of(props.getEvalsDir(), state.evalId, "meta.json");
                mapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), state.snapshot());
            } catch (IOException e) {
                log.warn("[expe-eval] 写 meta.json 失败 {}: {}", state.evalId, e.getMessage());
            }
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(无错误信息)";
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }
}
