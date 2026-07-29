package com.treasury.nl2sql.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.eval.EvalJobService;
import com.treasury.nl2sql.eval.EvalService;
import com.treasury.nl2sql.fewshot.FewShotSelector;
import com.treasury.nl2sql.glossary.GlossaryService;
import com.treasury.nl2sql.schema.LinkingEvalService;
import com.treasury.nl2sql.schema.SchemaLinker;
import com.treasury.nl2sql.schema.SchemaService;
import com.treasury.nl2sql.service.AssistedQueryService;
import com.treasury.nl2sql.service.AssistedResponse;
import com.treasury.nl2sql.service.MqlExplainService;
import com.treasury.nl2sql.service.Nl2SqlService;
import com.treasury.nl2sql.service.NlQueryResult;
import com.treasury.nl2sql.store.CaliberStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final Nl2SqlService service;
    private final EvalService evalService;
    private final EvalJobService evalJobs;
    private final SchemaLinker schemaLinker;
    private final FewShotSelector fewShot;
    private final GlossaryService glossary;
    private final LinkingEvalService linkingEval;
    private final SchemaService schemaService;
    private final AssistedQueryService assisted;
    private final CaliberStore caliberStore;
    private final MqlExplainService explainService;
    private final ObjectMapper mapper;

    public QueryController(Nl2SqlService service, EvalService evalService, EvalJobService evalJobs,
                           SchemaLinker schemaLinker, FewShotSelector fewShot, GlossaryService glossary,
                           LinkingEvalService linkingEval, SchemaService schemaService,
                           AssistedQueryService assisted, CaliberStore caliberStore,
                           MqlExplainService explainService, ObjectMapper mapper) {
        this.service = service;
        this.evalService = evalService;
        this.evalJobs = evalJobs;
        this.schemaLinker = schemaLinker;
        this.fewShot = fewShot;
        this.glossary = glossary;
        this.linkingEval = linkingEval;
        this.schemaService = schemaService;
        this.assisted = assisted;
        this.caliberStore = caliberStore;
        this.explainService = explainService;
        this.mapper = mapper;
    }

    /** 调试：查看 chatbi 库的整库表结构（表/列/类型/注释/主外键），供前端 ER 图与表格视图渲染 */
    @GetMapping("/schema")
    public SchemaService.SchemaSnapshot schema() {
        return schemaService.snapshot();
    }

    /** 调试：查看已加载的业务术语口径字典 */
    @GetMapping("/glossary")
    public List<GlossaryService.Term> glossary() {
        return glossary.all();
    }

    /** 调试：查看某问题按相关性选中的术语与相似度（术语数 ≤ top-n 时全量、score=1.0 占位） */
    @GetMapping("/glossary/select")
    public List<GlossaryService.Selected> glossarySelect(@RequestParam String question) {
        return glossary.select(question);
    }

    /** 调试：查看某问题命中的表与相似度分数 */
    @GetMapping("/linking")
    public SchemaLinker.LinkingResult linking(@RequestParam String question) {
        return schemaLinker.select(question);
    }

    /** 调试：lexical vs vector 召回质量对比（命中率 + 注入文本量），探针集见 eval/linking-probes.json */
    @GetMapping("/linking/eval")
    public LinkingEvalService.CompareReport linkingEval() {
        return linkingEval.run();
    }

    /** 调试：查看某问题动态选中的 few-shot 示例与相似度 */
    @GetMapping("/fewshot")
    public List<FewShotSelector.Selected> fewshot(@RequestParam String question) {
        return fewShot.select(question);
    }

    /** 跑金标准评估集（同步，全量调 LLM 耗时数分钟，适合服务器上 curl 直连；页面走 /eval/start）。 */
    @PostMapping("/eval")
    public EvalService.EvalReport eval(@RequestParam(defaultValue = "1") int k) {
        return evalService.run(k);
    }

    /** 异步启动评估任务，立即返回；已有任务在跑则不重复启动（started=false）。 */
    @PostMapping("/eval/start")
    public EvalJobService.StartResult evalStart(@RequestParam(defaultValue = "1") int k) {
        return evalJobs.start(k);
    }

    /** 轮询评估任务进度；结束后 report / error 二选一有值。 */
    @GetMapping("/eval/status")
    public EvalJobService.JobStatus evalStatus() {
        return evalJobs.status();
    }

    /** few-shot 增益 A/B：对比「注入 vs 不注入 few-shot」的通过率，量化 few-shot 净增益 */
    @PostMapping("/eval/ab")
    public EvalService.AbReport evalAb(@RequestParam(defaultValue = "1") int k) {
        return evalService.runAb(k);
    }

    public record QueryRequest(String question) {}

    /** 自然语言查询：纯生成链路，返回 MQL、SQL、结果与中间过程（供评估/对照，无口径召回）。 */
    @PostMapping("/query")
    public NlQueryResult query(@RequestBody QueryRequest req) {
        return service.query(req.question());
    }

    // ==================== 人在回路 / 可演进 ====================

    public record AskRequest(String question, Boolean bypassCaliber) {}

    /** 带人在回路的提问：先召回已核验口径分档（命中直取 / 候选澄清 / 未命中生成）。 */
    @PostMapping("/ask")
    public AssistedResponse ask(@RequestBody AskRequest req) {
        boolean bypass = req.bypassCaliber() != null && req.bypassCaliber();
        return assisted.ask(req.question(), bypass);
    }

    public record ReuseRequest(long assetId, String question) {}

    /** 候选澄清后「复用该候选口径」：按 assetId 直取执行。 */
    @PostMapping("/reuse")
    public AssistedResponse reuse(@RequestBody ReuseRequest req) {
        return assisted.reuse(req.assetId(), req.question());
    }

    public record VerifyRequest(String question, JsonNode mql, boolean accept, String createdBy) {}

    /** 核验闸门：采纳（沉淀为口径资产）或驳回（丢弃）。采纳时服务端重校验 MQL。 */
    @PostMapping("/verify")
    public AssistedQueryService.VerifyResult verify(@RequestBody VerifyRequest req) throws Exception {
        String mqlJson = req.mql() == null ? null : mapper.writeValueAsString(req.mql());
        return assisted.verify(req.question(), mqlJson, req.accept(), req.createdBy());
    }

    /** 调试：查看某问题的口径召回分档与相似度，用于标定 tau-hit/tau-miss 阈值。 */
    @GetMapping("/caliber/recall")
    public CaliberStore.Recall caliberRecall(@RequestParam String question) {
        return caliberStore.recall(question);
    }

    public record ExplainRequest(JsonNode mql) {}

    /**
     * 口径说明：把结果里的 MQL 反翻译成业务人员可读的中文口径描述（即席语境）。
     * 纯展示层辅助——不进取数链路、不改 {@link NlQueryResult} 评估契约；服务端重校验回传 MQL，失败关闭。
     */
    @PostMapping("/explain")
    public MqlExplainService.Explanation explain(@RequestBody ExplainRequest req) {
        return explainService.explain(req.mql(), MqlExplainService.Mode.AD_HOC);
    }

    /** 反翻译前置闸/解析失败等业务性拒绝 → 400 {"error": 原因}（非 500）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
