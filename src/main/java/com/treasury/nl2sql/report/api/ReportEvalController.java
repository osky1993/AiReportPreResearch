package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.report.eval.ReportEvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 报告层回归评测端点（P2-T7）。只读评测，不创建 run、不写任何状态表。
 *  - POST /api/report/eval/run?layer=deterministic  确定性层（②③④ 取数等价，零 LLM，秒级）
 *  - POST /api/report/eval/run?layer=llm            LLM 层（① 匹配/失败关闭，烧 token，分钟级，手动触发）
 * 同步返回评测报告 JSON；Phase06 影子回归将复用本服务作为资产发布门禁。
 */
@RestController
@RequestMapping("/api/report/eval")
public class ReportEvalController {

    private final ReportEvalService eval;

    /**
     * 构造器。评测服务不会改写状态表，故 Controller 仅做参数透传 + 响应分类。
     */
    public ReportEvalController(ReportEvalService eval) {
        this.eval = eval;
    }

    /** 同步执行评测：deterministic 为无 LLM 基线链路，llm 为人工路径（含 token 成本）。 */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam String layer) {
        return switch (layer.toLowerCase()) {
            case "deterministic" -> ResponseEntity.ok(eval.runDeterministic());
            case "llm" -> ResponseEntity.ok(eval.runLlm());
            default -> ResponseEntity.badRequest()
                    .body(Map.of("error", "layer 只支持 deterministic / llm，当前: " + layer));
        };
    }
}
