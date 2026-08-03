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
            return new TaskInfo(taskId, label, promptFileName, promptSha256, dataSha256, model,
                    temperature, maxTokens, iterations, status, createdAt, finishedAt, ok, failed,
                    List.copyOf(calls));
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

    public ExpeTaskService(ExpeProperties props, ExpeLlmCaller caller, ObjectMapper mapper) {
        this.props = props;
        this.caller = caller;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() throws IOException {
        executor = Executors.newFixedThreadPool(Math.max(1, props.getWorkerThreads()), r -> {
            Thread t = new Thread(r, "expe-worker");
            t.setDaemon(true);
            return t;
        });
        Files.createDirectories(Path.of(props.getRunsDir()));
        restoreFromDisk();
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    /** 重启恢复：扫描 runs 目录下所有 meta.json；被打断的执行中任务标记 INTERRUPTED（不自动续跑） */
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

    /** 公共数据包当前状态（页面展示 + 创建前校验） */
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

    /** 创建并启动一批任务：每份提示词文件一个任务，立即提交线程池 */
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

    /** 任务主循环：串行逐次生成，全新会话、无重试；失败原样记录后继续下一次 */
    private void runTask(TaskState state, String assembledPrompt) {
        synchronized (state) {
            if (state.cancelRequested) { state.finish("CANCELLED"); saveMeta(state); return; }
            state.status = "RUNNING";
        }
        saveMeta(state);
        Path dir = Path.of(props.getRunsDir(), state.taskId);

        for (int i = 1; i <= state.iterations; i++) {
            if (state.cancelRequested) break;
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
            saveMeta(state);
        }
        state.finish(state.cancelRequested ? "CANCELLED" : "DONE");
        saveMeta(state);
        log.info("[expe] 任务 {} 结束，状态 {}", state.taskId, state.status);
    }

    public List<TaskInfo> listTasks() {
        return tasks.values().stream()
                .map(TaskState::snapshot)
                .sorted(Comparator.comparing(TaskInfo::createdAt).reversed()
                        .thenComparing(TaskInfo::taskId))
                .toList();
    }

    public TaskInfo getTask(String taskId) {
        TaskState s = tasks.get(taskId);
        if (s == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        return s.snapshot();
    }

    public void cancel(String taskId) {
        TaskState s = tasks.get(taskId);
        if (s == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        s.cancelRequested = true;
    }

    /** 读取第 N 次生成的结果（taskId 必须是已注册任务，index 由 controller 校验为数字——无路径穿越面） */
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

    private void saveMeta(TaskState state) {
        try {
            Path meta = Path.of(props.getRunsDir(), state.taskId, "meta.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), state.snapshot());
        } catch (IOException e) {
            log.warn("[expe] 写 meta.json 失败 {}: {}", state.taskId, e.getMessage());
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
