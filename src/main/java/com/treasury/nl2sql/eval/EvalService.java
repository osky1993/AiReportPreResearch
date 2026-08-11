package com.treasury.nl2sql.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.service.Nl2SqlService;
import com.treasury.nl2sql.service.NlQueryResult;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行准确率（execution accuracy）评估。
 * 金标准 = 人工核对过的参考 SQL；评估时分别执行「参考 SQL」与「LLM 生成的 SQL」，
 * 比对结果集是否一致。比对按「值多重集」进行——忽略列名/列序/别名差异，只看数据。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final Nl2SqlService nl2sql;
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    /** few-shot 近重复排除阈值：评估时排除与评估问题相似度≥该值的示例，防答案泄漏。 */
    private final double fewshotLeakageThreshold;
    /** case 并行度：1=串行；调大前先确认 LLM 供应商 QPS 配额（限流会把准确率打成假象）。 */
    private final int parallelism;

    /**
     * 构造器：注入执行器、解析器与阈值配置。
     * parallelism 低于 1 时自动修正为 1，避免线程池异常。
     */
    public EvalService(Nl2SqlService nl2sql, DSLContext dsl, ObjectMapper mapper,
                       @org.springframework.beans.factory.annotation.Value(
                               "${eval.fewshot-leakage-threshold:0.9}") double fewshotLeakageThreshold,
                       @org.springframework.beans.factory.annotation.Value(
                               "${eval.parallelism:3}") int parallelism) {
        this.nl2sql = nl2sql;
        this.dsl = dsl;
        this.mapper = mapper;
        this.fewshotLeakageThreshold = fewshotLeakageThreshold;
        this.parallelism = Math.max(1, parallelism);
    }

    public record EvalCase(String id, String question, String referenceSql, boolean expectFailure) {}

    /** 单次运行的结果 */
    private record RunOutcome(boolean pass, String generatedSql, List<String> errors,
                             int actualRows, int actualCols, int fixRounds, long latencyMs) {}

    public record CaseResult(String id, String question, boolean pass,
                             String generatedSql, List<String> errors,
                             int expectedRows, int actualRows, int fixRounds,
                             int runs, int passes, double passRate,
                             boolean ordered, String category,
                             int expectedCols, int actualCols, long latencyMs) {}

    public record EvalReport(int total, int passed, double accuracy, double avgPassRate,
                             double avgLatencyMs, Map<String, Double> accuracyByCategory,
                             List<CaseResult> cases) {}

    /** few-shot A/B 对比报告：on=按评估隔离阈值注入示例，off=完全不注入示例。 */
    public record AbReport(EvalReport withFewshot, EvalReport withoutFewshot,
                           double deltaAccuracy, double deltaAvgPassRate) {}

    /** 逐 case 进度回调（供异步评估任务对外报告进度）。 */
    @FunctionalInterface
    public interface ProgressListener {
        /** 每条 case 开跑前回调：done=已完成条数，total=总条数，question=即将执行的问题。 */
        void onCase(int done, int total, String question);
    }

    private static final ProgressListener NO_PROGRESS = (d, t, q) -> {};

    /** 默认单次跑（向后兼容）。 */
    public EvalReport run() {
        return run(1);
    }

    /** 每条 case 跑 k 次（few-shot 用评估隔离阈值注入）。 */
    public EvalReport run(int k) {
        return run(k, fewshotLeakageThreshold);
    }

    /** 每条 case 跑 k 次并逐条上报进度（few-shot 用评估隔离阈值注入）。 */
    public EvalReport run(int k, ProgressListener progress) {
        return run(k, fewshotLeakageThreshold, progress);
    }

    /**
     * few-shot 增益 A/B：同一评估集分别在「注入 few-shot（隔离阈值）」与「不注入 few-shot」下各跑 k 次，
     * 对比通过率差异以量化 few-shot 净增益（评估隔离已就绪，差值可信）。
     */
    public AbReport runAb(int k) {
        EvalReport on = run(k, fewshotLeakageThreshold);
        EvalReport off = run(k, Double.NEGATIVE_INFINITY);   // -∞：任何示例都被排除 = few-shot 关闭
        return new AbReport(on, off,
                on.accuracy() - off.accuracy(),
                on.avgPassRate() - off.avgPassRate());
    }

    /** 每条 case 跑 k 次，统计 pass@k 通过率（k≥1）。expected 只算一次。 */
    public EvalReport run(int k, double fewshotMaxSim) {
        return run(k, fewshotMaxSim, NO_PROGRESS);
    }

    /**
     * 每条 case 跑 k 次，统计 pass@k 通过率（k≥1）。expected 只算一次。
     * case 间按 {@code eval.parallelism} 并行（case 内的 k 次仍串行）：整条生成链路无共享可变状态、
     * 各组件查询期只读，故 worker 只并发调用 {@link #evalCase}，聚合统计回到单线程做、保持 case 顺序。
     */
    public EvalReport run(int k, double fewshotMaxSim, ProgressListener progress) {
        int runs = Math.max(1, k);
        List<EvalCase> cases = loadCases();
        int n = cases.size();
        CaseResult[] results = new CaseResult[n];      // 按 case 下标写入，天然无竞争、保持顺序
        AtomicLong sumLatency = new AtomicLong();
        AtomicInteger done = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final EvalCase c = cases.get(i);
                futures.add(pool.submit(() -> {
                    progress.onCase(done.get(), n, c.question());
                    results[idx] = evalCase(c, runs, fewshotMaxSim, sumLatency);
                    progress.onCase(done.incrementAndGet(), n, c.question());
                }));
            }
            for (Future<?> f : futures) f.get();   // 等全部完成并传播 worker 异常（如参考 SQL 执行失败）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("评估被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("评估 case 执行异常: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }

        // 聚合统计：单线程遍历结果数组，无并发问题
        int strictPassed = 0;
        double sumRate = 0;
        Map<String, int[]> byCat = new LinkedHashMap<>();   // category -> [strictPassed, total]
        for (CaseResult r : results) {
            if (r.pass()) strictPassed++;
            sumRate += r.passRate();
            int[] cat = byCat.computeIfAbsent(r.category(), x -> new int[2]);
            if (r.pass()) cat[0]++;
            cat[1]++;
        }
        long totalRuns = (long) n * runs;
        double acc = n == 0 ? 0 : (double) strictPassed / n;
        double avgRate = n == 0 ? 0 : sumRate / n;
        double avgLatency = totalRuns == 0 ? 0 : (double) sumLatency.get() / totalRuns;
        Map<String, Double> accByCat = new LinkedHashMap<>();
        byCat.forEach((cat, pt) -> accByCat.put(cat, pt[1] == 0 ? 0 : (double) pt[0] / pt[1]));
        return new EvalReport(n, strictPassed, acc, avgRate, avgLatency, accByCat, List.of(results));
    }

    /** 完整评一条 case（含参考 SQL 取 expected + k 次生成执行），供并行 worker 调用；latency 累加进共享计数器。 */
    private CaseResult evalCase(EvalCase c, int runs, double fewshotMaxSim, AtomicLong sumLatency) {
        boolean negative = c.expectFailure();
        List<String> expectedSig;
        int expectedCols;
        boolean ordered;
        String category;
        if (negative) {
            // 负例：期望系统「拒答/校验失败」，不执行参考 SQL
            expectedSig = List.of();
            expectedCols = 0;
            ordered = false;
            category = "负例";
        } else {
            List<Map<String, Object>> expectedRows;
            try {
                expectedRows = dsl.fetch(c.referenceSql()).intoMaps();
            } catch (Exception e) {
                // 参考 SQL 本身执行失败（典型：环境 MySQL < 8.0 不支持窗口函数）：
                // 该 case 记失败并继续，避免一条毒丸中止整个评估、报告出不来
                String msg = "参考 SQL 执行失败（金标准/环境问题，非模型问题）: " + e.getMessage();
                log.warn("[EVAL] {} {}", c.id(), msg);
                return new CaseResult(c.id(), c.question(), false, null, List.of(msg),
                        0, 0, 0, runs, 0, 0,
                        isOrdered(c.referenceSql()), classify(c.referenceSql()), 0, 0, 0);
            }
            expectedSig = signature(expectedRows);
            expectedCols = colCount(expectedRows);
            ordered = isOrdered(c.referenceSql());
            category = classify(c.referenceSql());
        }

        int passes = 0;
        RunOutcome last = null;
        for (int i = 0; i < runs; i++) {
            RunOutcome o = negative ? runNegativeOnce(c, fewshotMaxSim)
                                    : runOnce(c, expectedSig, expectedCols, ordered, fewshotMaxSim);
            if (o.pass()) passes++;
            sumLatency.addAndGet(o.latencyMs());
            last = o;
        }
        double rate = (double) passes / runs;
        boolean allPass = passes == runs;
        log.info("[EVAL] {} {} pass@{}={}/{} cat={} ordered={}", c.id(), c.question(),
                runs, passes, runs, category, ordered);
        return new CaseResult(c.id(), c.question(), allPass, last.generatedSql(), last.errors(),
                expectedSig.size(), last.actualRows(), last.fixRounds(), runs, passes, rate,
                ordered, category, expectedCols, last.actualCols(), last.latencyMs());
    }

    /** 跑一次某 case：生成→执行→比对（含保序/列数校验）。 */
    private RunOutcome runOnce(EvalCase c, List<String> expectedSig, int expectedCols, boolean ordered,
                              double fewshotMaxSim) {
        long t0 = System.nanoTime();
        try {
            NlQueryResult out = nl2sql.query(c.question(), fewshotMaxSim);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (!out.success()) {
                return new RunOutcome(false, out.sql(), out.errors(), 0, 0, out.fixRounds(), ms);
            }
            List<String> actualSig = signature(out.rows());
            int actualCols = colCount(out.rows());
            boolean valuesOk = ordered ? orderedEquals(expectedSig, actualSig)
                                       : multisetEquals(expectedSig, actualSig);
            boolean colsOk = expectedCols == 0 || actualCols == 0 || expectedCols == actualCols;
            boolean ok = valuesOk && colsOk;
            List<String> errs = ok ? List.of()
                    : !valuesOk ? List.of(ordered ? "结果顺序/内容不一致" : "结果不一致")
                                : List.of("列数不一致: 期望 " + expectedCols + " 实际 " + actualCols);
            return new RunOutcome(ok, out.sql(), errs, actualSig.size(), actualCols, out.fixRounds(), ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new RunOutcome(false, null, List.of("执行异常: " + e.getMessage()), 0, 0, 0, ms);
        }
    }

    /** 跑一次负例：期望系统拒答/校验失败（success=false）。 */
    private RunOutcome runNegativeOnce(EvalCase c, double fewshotMaxSim) {
        long t0 = System.nanoTime();
        try {
            NlQueryResult out = nl2sql.query(c.question(), fewshotMaxSim);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = !out.success();   // 拒答即通过
            int aRows = out.rows() == null ? 0 : out.rows().size();
            int aCols = out.rows() == null ? 0 : colCount(out.rows());
            return new RunOutcome(ok, out.sql(),
                    ok ? List.of() : List.of("应拒答，但系统生成了可执行 SQL"),
                    aRows, aCols, out.fixRounds(), ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new RunOutcome(true, null, List.of(), 0, 0, 0, ms);  // 抛错=未产出错误SQL，视为优雅失败
        }
    }

    /** 参考 SQL 含 order by 则需保序比对。 */
    static boolean isOrdered(String referenceSql) {
        return referenceSql != null && referenceSql.toLowerCase().contains("order by");
    }

    /** 按参考 SQL 文本分类：多表(含 join) / 聚合(group by 或聚合函数) / 单表。 */
    static String classify(String referenceSql) {
        if (referenceSql == null) return "单表";
        String s = referenceSql.toLowerCase();
        if (s.contains(" join ")) return "多表";
        if (s.contains("group by") || s.matches("(?s).*\\b(sum|count|avg|min|max)\\s*\\(.*")) return "聚合";
        return "单表";
    }

    /** 结果列数（取首行列数；空结果返回 0）。 */
    static int colCount(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? 0 : rows.get(0).keySet().size();
    }

    /** 保序比对：行序也必须一致。 */
    static boolean orderedEquals(List<String> a, List<String> b) {
        return a.equals(b);
    }

    private List<EvalCase> loadCases() {
        try (var in = new ClassPathResource("eval/golden-queries.json").getInputStream()) {
            return Arrays.asList(mapper.readValue(in, EvalCase[].class));
        } catch (Exception e) {
            throw new RuntimeException("加载金标准集失败", e);
        }
    }

    /** 把结果集转成「行签名的多重集」：每行=各列值归一化后排序拼接，忽略列名与列序 */
    static List<String> signature(List<Map<String, Object>> rows) {
        List<String> sigs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> vals = new ArrayList<>();
            for (Object v : row.values()) vals.add(norm(v));
            Collections.sort(vals);
            sigs.add(String.join("|", vals));
        }
        return sigs;
    }

    /** 数值归一化（消除 1 vs 1.00、Long vs BigDecimal 等差异），其余按字符串 */
    static String norm(Object v) {
        if (v == null) return "∅";
        if (v instanceof Number || v instanceof BigDecimal) {
            try {
                return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignore) {
                return v.toString();
            }
        }
        if (v instanceof Temporal || v instanceof java.util.Date) return v.toString();
        return v.toString().trim();
    }

    static boolean multisetEquals(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        List<String> x = new ArrayList<>(a);
        List<String> y = new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }
}
