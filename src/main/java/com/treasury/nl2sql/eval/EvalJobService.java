package com.treasury.nl2sql.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评估集异步任务。/eval 同步接口要逐条调 LLM、全量耗时数分钟，
 * 经反代/网关访问时会被读超时截断（后端照跑、前端收到 HTML 错误页）。
 * 故提供 start（立即返回）+ status（轮询进度）两个端点，前端据此渲染进度条。
 * 单任务槽：同一时刻只允许一个评估在跑，重复 start 直接返回「已在运行」（demo 规模足够）。
 */
@Service
public class EvalJobService {

    private static final Logger log = LoggerFactory.getLogger(EvalJobService.class);

    private final EvalService evalService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int done;
    private volatile int total;
    private volatile String currentQuestion;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile EvalService.EvalReport report;
    private volatile String error;

    /** 状态仅有单槽执行器，注入执行服务后用于防并发起跑。 */
    public EvalJobService(EvalService evalService) {
        this.evalService = evalService;
    }

    public record StartResult(boolean started, String message) {}

    /** running=跑动中；结束后 report / error 二选一有值，进度字段保留末态供前端展示。 */
    public record JobStatus(boolean running, int done, int total, String currentQuestion,
                            long elapsedMs, EvalService.EvalReport report, String error) {}

    /** 启动一次全量评估（每条 case 跑 k 次），立即返回；已有任务在跑则不重复启动。 */
    public StartResult start(int k) {
        if (!running.compareAndSet(false, true)) {
            return new StartResult(false, "已有评估任务在运行，转为展示其进度");
        }
        done = 0; total = 0; currentQuestion = null; report = null; error = null;
        startedAt = System.currentTimeMillis(); finishedAt = 0;
        Thread t = new Thread(() -> {
            try {
                report = evalService.run(k, (d, tot, q) -> { done = d; total = tot; currentQuestion = q; });
                done = total;
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
                log.error("[EVAL-JOB] 评估任务异常终止", e);
            } finally {
                currentQuestion = null;
                finishedAt = System.currentTimeMillis();
                running.set(false);
            }
        }, "eval-job");
        t.setDaemon(true);
        t.start();
        return new StartResult(true, "评估任务已启动");
    }

    /**
     * 查询当前任务状态。运行中返回 done/total/currentQuestion，完成后带 report 或 error。
     */
    public JobStatus status() {
        long elapsed = startedAt == 0 ? 0
                : (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt;
        return new JobStatus(running.get(), done, total, currentQuestion, elapsed, report, error);
    }
}
