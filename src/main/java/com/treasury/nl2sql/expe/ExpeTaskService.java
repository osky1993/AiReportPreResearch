package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 实验任务编排：上传的每份提示词文件 = 一个任务；任务在线程池内并发执行，
 * 同时启动 A/B/C/D 多组时组间调用自然交错（对齐实验方案 §9.3「不允许先跑完 A 再跑 B」）。
 *
 * 每个任务落盘一个目录（{runs-dir}/{taskId}/），构成可复查的最小证据包：
 * <pre>
 *   prompt.md            上传的提示词原文（原样保留）
 *   data.json            本次任务实际使用的数据包冻结副本
 *   assembled_prompt.txt 实际送模的完整拼接文本（提示词 + 数据包）
 *   result_NNN.md        第 N 次生成的模型输出正文（含失败次序号的占位说明）
 *   raw_NNN.json         第 N 次调用的原始响应报文（失败且无响应时不存在）
 *   meta.json            任务参数、哈希、逐次调用记录（user_id/耗时/token/错误）
 * </pre>
 * 状态在内存 + meta.json 双写；重启后从 meta.json 恢复历史任务（执行中被打断的标记 INTERRUPTED）。
 * 失败的调用不重试、不删除、原样计入——挑样本与静默重试都会破坏实验可信度。
 *
 * <p>失败与一致性约束：
 * <ul>
 *   <li>任何异常只归入该任务调用记录，不回写系统状态为成功</li>
 *   <li>每次调用生成与原始响应都落盘，后续可逐条复核</li>
 *   <li>任务状态通过 meta.json 与内存双写恢复；重启不自动续跑防止实验半成品被误读</li>
 * </ul>
 */
@Service
public class ExpeTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExpeTaskService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 逐次调用记录：status=OK/FAILED；FAILED 时 error 保留异常信息，token 字段可为 null。
     *  cacheHitTokens=命中供应商前缀缓存的输入 token——随机 user_id 隔离生效时应恒为 0，非 0 即隔离失效告警 */
    public record CallInfo(int index, String userId, String startedAt, Long durationMs,
                           Integer promptTokens, Integer completionTokens, Integer cacheHitTokens,
                           String status, String error) {}

    /** 任务快照（同时是 meta.json 的持久化结构与 API 返回结构） */
    public record TaskInfo(String taskId, String label, String promptFileName, String promptSha256,
                           String dataSha256, String model, double temperature, Integer maxTokens,
                           int iterations, String status, String createdAt, String finishedAt,
                           int okCount, int failedCount, List<CallInfo> calls) {}

    /** 上传的提示词文件（controller 传入） */
    public record UploadedPrompt(String fileName, byte[] content) {}

    /** 可变任务状态；所有读写经 synchronized 方法，快照后对外只读 */
    private static final class TaskState {
        final String taskId;
        final String label;
        final String promptFileName;
        final String promptSha256;
        final String dataSha256;
        final String model;
        final double temperature;
        final Integer maxTokens;
        final int iterations;
        final String createdAt;
        final List<CallInfo> calls = new ArrayList<>();
        String status;
        String finishedAt;
        volatile boolean cancelRequested;

        TaskState(String taskId, String label, String promptFileName, String promptSha256,
                  String dataSha256, String model, double temperature, Integer maxTokens,
                  int iterations, String createdAt, String status) {
            this.taskId = taskId;
            this.label = label;
            this.promptFileName = promptFileName;
            this.promptSha256 = promptSha256;
            this.dataSha256 = dataSha256;
            this.model = model;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.iterations = iterations;
            this.createdAt = createdAt;
            this.status = status;
        }

        static TaskState fromInfo(TaskInfo info) {
            TaskState s = new TaskState(info.taskId(), info.label(), info.promptFileName(), info.promptSha256(),
                    info.dataSha256(), info.model(), info.temperature(), info.maxTokens(),
                    info.iterations(), info.createdAt(), info.status());
            s.finishedAt = info.finishedAt();
            if (info.calls() != null) s.calls.addAll(info.calls());
            return s;
        }

        synchronized TaskInfo snapshot() {
            int ok = 0, failed = 0;
            for (CallInfo c : calls) {
                if ("OK".equals(c.status())) ok++; else failed++;
            }
            // 迭代并行执行后完成顺序不定，快照按序号排序保证展示与评估遍历稳定
            List<CallInfo> sorted = calls.stream()
                    .sorted(Comparator.comparingInt(CallInfo::index)).toList();
            return new TaskInfo(taskId, label, promptFileName, promptSha256, dataSha256, model,
                    temperature, maxTokens, iterations, status, createdAt, finishedAt, ok, failed,
                    sorted);
        }

        synchronized void addCall(CallInfo call) { calls.add(call); }

        synchronized void finish(String finalStatus) {
            this.status = finalStatus;
            this.finishedAt = LocalDateTime.now().format(TS);
        }
    }

    private final ExpeProperties props;
    private final ExpeLlmCaller caller;
    private final ObjectMapper mapper;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private ExecutorService executor;
    /** 生成调用共享池：所有任务的迭代都扇出到这里，全局并发 = expe.llm-concurrency */
    private ExecutorService callExecutor;

    public ExpeTaskService(ExpeProperties props, ExpeLlmCaller caller, ObjectMapper mapper) {
        this.props = props;
        this.caller = caller;
        this.mapper = mapper;
    }

    /**
     * 组件初始化：
     * <ul>
     *   <li>构建任务调度池（任务级并发）与 LLM 调用池（全局并发）</li>
     *   <li>保证 runs 目录存在</li>
     *   <li>重放 meta.json，恢复历史任务状态与失败标记</li>
     * </ul>
     */
    @PostConstruct
    void init() throws IOException {
        executor = Executors.newFixedThreadPool(Math.max(1, props.getWorkerThreads()), r -> {
            Thread t = new Thread(r, "expe-worker");
            t.setDaemon(true);
            return t;
        });
        callExecutor = Executors.newFixedThreadPool(Math.max(1, props.getLlmConcurrency()), r -> {
            Thread t = new Thread(r, "expe-llm-call");
            t.setDaemon(true);
            return t;
        });
        Files.createDirectories(Path.of(props.getRunsDir()));
        restoreFromDisk();
    }

    /** 停机前关闭线程池，避免非 daemon 线程阻塞应用退出。 */
    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
        if (callExecutor != null) callExecutor.shutdownNow();
    }

    /**
     * 重启恢复：扫描 runs 目录下所有 meta.json 恢复任务。
     * <p>若历史状态是 RUNNING/QUEUED，则改为 INTERRUPTED 并保留证据，避免“未完成任务”被误判为已完成。
     */
    private void restoreFromDisk() {
        try (var dirs = Files.list(Path.of(props.getRunsDir()))) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path meta = dir.resolve("meta.json");
                if (!Files.exists(meta)) return;
                try {
                    TaskInfo info = mapper.readValue(meta.toFile(), TaskInfo.class);
                    if ("RUNNING".equals(info.status()) || "QUEUED".equals(info.status())) {
                        info = new TaskInfo(info.taskId(), info.label(), info.promptFileName(), info.promptSha256(),
                                info.dataSha256(), info.model(), info.temperature(), info.maxTokens(),
                                info.iterations(), "INTERRUPTED", info.createdAt(), info.finishedAt(),
                                info.okCount(), info.failedCount(), info.calls());
                    }
                    TaskState state = TaskState.fromInfo(info);
                    tasks.put(state.taskId, state);
                    saveMeta(state);
                } catch (Exception e) {
                    log.warn("[expe] 恢复任务失败，跳过 {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[expe] 扫描历史任务目录失败: {}", e.getMessage());
        }
        if (!tasks.isEmpty()) {
            log.info("[expe] 已从磁盘恢复 {} 个历史实验任务", tasks.size());
        }
    }

    /**
     * 查询公共数据包状态，用于“创建前自检”与页面展示（路径、是否存在、哈希、供应商配置）。
     * 数据包不存在时返回 exists=false，不会抛异常以便前端给提示并阻断提交按钮。
     */
    public Map<String, Object> dataPackInfo() {
        Path p = Path.of(props.getDataJsonPath());
        if (!Files.exists(p)) {
            return Map.of("exists", false, "path", props.getDataJsonPath());
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Map.of("exists", true, "path", props.getDataJsonPath(),
                    "sizeBytes", bytes.length, "sha256", sha256(bytes),
                    "model", caller.model(), "baseUrl", caller.baseUrl());
        } catch (IOException e) {
            return Map.of("exists", false, "path", props.getDataJsonPath(), "error", e.getMessage());
        }
    }

    /**
     * 创建并启动一批任务：每个提示词文件对应一个实验任务。
     * <ul>
     *   <li>读取并冻结 data.json，记录输入哈希</li>
     *   <li>预写 prompt/data/assembled_prompt，保证证据包可追溯</li>
     *   <li>立即提交任务到 worker 池；任务内再按 iterations 扇出到 LLM 调用池</li>
     * </ul>
     */
    public List<TaskInfo> createTasks(List<UploadedPrompt> prompts, int iterations,
                                      double temperature, Integer maxTokens) throws IOException {
        Path dataPath = Path.of(props.getDataJsonPath());
        if (!Files.exists(dataPath)) {
            throw new IllegalStateException("公共数据包不存在: " + props.getDataJsonPath());
        }
        byte[] dataBytes = Files.readAllBytes(dataPath);
        String dataSha = sha256(dataBytes);
        String dataText = new String(dataBytes, StandardCharsets.UTF_8);

        List<TaskInfo> created = new ArrayList<>();
        for (UploadedPrompt p : prompts) {
            String promptText = new String(p.content(), StandardCharsets.UTF_8);
            if (promptText.isBlank()) {
                throw new IllegalArgumentException("提示词文件为空: " + p.fileName());
            }
            String label = stripExtension(p.fileName());
            String taskId = LocalDateTime.now().format(ID_TS) + "-" + sanitize(label)
                    + "-" + HexFormat.of().toHexDigits(random.nextInt(), 4);
            Path dir = Path.of(props.getRunsDir(), taskId);
            Files.createDirectories(dir);

            // 证据包固化：提示词原文、数据包冻结副本、实际送模全文
            Files.write(dir.resolve("prompt.md"), p.content());
            Files.write(dir.resolve("data.json"), dataBytes);
            String assembled = promptText + "\n\n" + dataText;
            Files.writeString(dir.resolve("assembled_prompt.txt"), assembled, StandardCharsets.UTF_8);

            TaskState state = new TaskState(taskId, label, p.fileName(), sha256(p.content()), dataSha,
                    caller.model(), temperature, maxTokens, iterations,
                    LocalDateTime.now().format(TS), "QUEUED");
            tasks.put(taskId, state);
            saveMeta(state);
            executor.submit(() -> runTask(state, assembled));
            created.add(state.snapshot());
        }
        return created;
    }

    /**
     * 任务主流程：全部迭代扇出到共享调用池并行执行（全局并发受 expe.llm-concurrency 约束），
     * 每次仍是独立全新会话、随机 user_id、无重试；失败原样记录。多任务同时扇出时组间调用自然交错。
     */
    private void runTask(TaskState state, String assembledPrompt) {
        synchronized (state) {
            if (state.cancelRequested) { state.finish("CANCELLED"); saveMeta(state); return; }
            state.status = "RUNNING";
        }
        saveMeta(state);
        Path dir = Path.of(props.getRunsDir(), state.taskId);

        CountDownLatch latch = new CountDownLatch(state.iterations);
        for (int i = 1; i <= state.iterations; i++) {
            final int idx = i;
            callExecutor.submit(() -> {
                try {
                    if (!state.cancelRequested) {
                        // 异步执行每次调用；通过 latch 确保一次任务全部迭代结束后再落 DONE。
                        runOneCall(state, dir, idx, assembledPrompt);
                        saveMeta(state);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        state.finish(state.cancelRequested ? "CANCELLED" : "DONE");
        saveMeta(state);
        log.info("[expe] 任务 {} 结束，状态 {}", state.taskId, state.status);
    }

    /**
     * 单次生成调用：
     * <ul>
     *   <li>生成成功：写 result_NNN.md（纯文本）与 raw_NNN.json（原始响应），并记录 token 与延迟</li>
     *   <li>生成失败：写失败占位文件，记录错误文本，不做重试</li>
     *   <li>命中前缀缓存 token 非 0 时报警，提示 user_id 隔离策略失效</li>
     * </ul>
     */
    private void runOneCall(TaskState state, Path dir, int i, String assembledPrompt) {
        String userId = "expe-" + UUID.randomUUID();
        String startedAt = LocalDateTime.now().format(TS);
        long t0 = System.nanoTime();
        try {
            ExpeLlmCaller.CallResult r = caller.call(assembledPrompt, state.temperature, state.maxTokens, userId);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Files.writeString(dir.resolve(resultFileName(i)), r.content(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(String.format("raw_%03d.json", i)), r.rawResponse(), StandardCharsets.UTF_8);
            state.addCall(new CallInfo(i, userId, startedAt, ms, r.promptTokens(), r.completionTokens(),
                    r.cacheHitTokens(), "OK", null));
            if (r.cacheHitTokens() != null && r.cacheHitTokens() > 0) {
                log.warn("[expe] 任务 {} 第 {} 次调用命中前缀缓存 {} token——user_id 隔离未生效，请核查",
                        state.taskId, i, r.cacheHitTokens());
            }
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String error = abbreviate(e.getMessage(), 2000);
            try {
                Files.writeString(dir.resolve(resultFileName(i)),
                        "[本次调用失败，无模型输出]\n" + error, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 结果目录不可写时错误已进 meta，不再级联
            }
            state.addCall(new CallInfo(i, userId, startedAt, ms, null, null, null, "FAILED", error));
            log.warn("[expe] 任务 {} 第 {}/{} 次调用失败: {}", state.taskId, i, state.iterations, error);
        }
    }

    /** 列举所有任务快照：按创建时间倒序返回，供前端任务列表与状态页展示。 */
    public List<TaskInfo> listTasks() {
        return tasks.values().stream()
                .map(TaskState::snapshot)
                .sorted(Comparator.comparing(TaskInfo::createdAt).reversed()
                        .thenComparing(TaskInfo::taskId))
                .toList();
    }

    /**
     * 获取任务快照（只读视图）。不存在直接抛 400 级异常，由上层 controller 映射为客户端错误。
     */
    public TaskInfo getTask(String taskId) {
        TaskState s = tasks.get(taskId);
        if (s == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        return s.snapshot();
    }

    /**
     * 软中止：不直接杀线程，而是打取消标志；正在进行的运行会在各自线程/循环内检查后退出。
     */
    public void cancel(String taskId) {
        TaskState s = tasks.get(taskId);
        if (s == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        s.cancelRequested = true;
    }

    /**
     * 读取第 N 次生成的结果。
     * <p>返回路径按固定文件名读取，失败时自动兼容历史后缀，禁止路径穿越：taskId 已经做任务注册表匹配。
     */
    public byte[] readResult(String taskId, int index) throws IOException {
        getTask(taskId);
        Path f = Path.of(props.getRunsDir(), taskId, resultFileName(index));
        if (!Files.exists(f)) {
            // 兼容改后缀前的历史任务（result_NNN.txt）
            f = Path.of(props.getRunsDir(), taskId, String.format("result_%03d.txt", index));
        }
        if (!Files.exists(f)) throw new IllegalArgumentException("结果不存在: 第 " + index + " 次");
        return Files.readAllBytes(f);
    }

    /** 删除任务：内存注册 + 磁盘目录（含全部结果与证据）一并清理；执行中/排队中须先取消 */
    public void delete(String taskId) throws IOException {
        TaskState s = tasks.get(taskId);
        if (s == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        String status = s.snapshot().status();
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            throw new IllegalStateException("任务执行中，请先取消再删除");
        }
        Path dir = Path.of(props.getRunsDir(), taskId);
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        tasks.remove(taskId);
        log.info("[expe] 已删除任务 {} 及其落盘目录", taskId);
    }

    /** 打包任务全部产物（结果 md + 原始响应 + 提示词 + 数据包 + meta）为 zip 流 */
    public void writeZip(String taskId, OutputStream out) throws IOException {
        getTask(taskId);
        Path dir = Path.of(props.getRunsDir(), taskId);
        try (ZipOutputStream zip = new ZipOutputStream(out);
             var files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(f.getFileName().toString()));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
    }

    /**
     * 每次 call 结束或状态变更后都会调用该方法；
     * 单任务下会有多次异步写线程并发调用 saveMeta，必须用 state 锁串行化更新 meta.json，
     * 否则快照交叉写回会造成任务恢复窗口状态错读。
     */
    private void saveMeta(TaskState state) {
        synchronized (state) {
            try {
                Path meta = Path.of(props.getRunsDir(), state.taskId, "meta.json");
                mapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), state.snapshot());
            } catch (IOException e) {
                log.warn("[expe] 写 meta.json 失败 {}: {}", state.taskId, e.getMessage());
            }
        }
    }

    private static String resultFileName(int index) {
        return String.format("result_%03d.md", index);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 目录名安全化：仅保留中英文、数字、下划线、连字符 */
    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[^\\w\\u4e00-\\u9fa5-]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(无错误信息)";
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
