package com.treasury.nl2sql.expe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.expe.ExpeEvalService.EvalDetail;
import com.treasury.nl2sql.expe.ExpeEvalService.EvalInfo;
import com.treasury.nl2sql.expe.ExpeEvalService.TargetSpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** AI 评估端点（expe.html 评估区的后端）；与生成任务端点 /api/expe/tasks 平行。 */
@RestController
@RequestMapping("/api/expe/evals")
public class ExpeEvalController {

    private final ExpeEvalService service;
    private final ObjectMapper mapper;

    public ExpeEvalController(ExpeEvalService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * 创建并启动评估。targets=JSON 数组 [{"taskId":"...","group":"A"}]；
     * rulesFile 可选，缺省用服务端默认规则清单（expe.rules-path）。
     */
    @PostMapping
    public EvalInfo create(@RequestParam("targets") String targetsJson,
                           @RequestParam(value = "rulesFile", required = false) MultipartFile rulesFile)
            throws IOException {
        List<TargetSpec> targets;
        try {
            targets = mapper.readValue(targetsJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("targets 参数须为 JSON 数组 [{taskId,group}]: " + e.getMessage());
        }
        byte[] rules = (rulesFile == null || rulesFile.isEmpty()) ? null : rulesFile.getBytes();
        String rulesName = rules == null ? null : rulesFile.getOriginalFilename();
        return service.create(targets, rules, rulesName);
    }

    @GetMapping
    public List<EvalInfo> list() {
        return service.list();
    }

    @GetMapping("/{evalId}")
    public EvalDetail detail(@PathVariable String evalId) throws IOException {
        return service.detail(evalId);
    }

    @PostMapping("/{evalId}/cancel")
    public Map<String, Object> cancel(@PathVariable String evalId) {
        service.cancel(evalId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{evalId}")
    public Map<String, Object> delete(@PathVariable String evalId) throws IOException {
        service.delete(evalId);
        return Map.of("ok", true);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
