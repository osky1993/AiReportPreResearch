package com.treasury.nl2sql.expe;

import com.treasury.nl2sql.expe.ExpeTaskService.TaskInfo;
import com.treasury.nl2sql.expe.ExpeTaskService.UploadedPrompt;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prompt 过载验证实验端点（演示页 expe.html 的后端）。
 * 与问数/报告端点完全独立；风格照 ReportController：record DTO + /api 前缀 + 参数错误统一 400。
 */
@RestController
@RequestMapping("/api/expe")
public class ExpeController {

    private final ExpeTaskService service;

    public ExpeController(ExpeTaskService service) {
        this.service = service;
    }

    /** 页面初始化信息：数据包状态（存在性/哈希/大小）+ 当前模型 */
    @GetMapping("/data")
    public Map<String, Object> dataPack() {
        return service.dataPackInfo();
    }

    /**
     * 创建并启动实验任务：每份提示词文件一个任务。
     * 建议把 A/B/C/D 各组文件一次性同时上传——任务在线程池内并发执行，组间调用自然交错。
     */
    @PostMapping("/tasks")
    public List<TaskInfo> createTasks(@RequestParam("files") List<MultipartFile> files,
                                      @RequestParam("iterations") int iterations,
                                      @RequestParam("temperature") double temperature,
                                      @RequestParam(value = "maxTokens", required = false) Integer maxTokens)
            throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一份提示词文件");
        }
        if (iterations < 1 || iterations > 200) {
            throw new IllegalArgumentException("迭代次数须在 1~200 之间");
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 须在 0~2 之间");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens 须为正整数（留空则用供应商默认值）");
        }
        List<UploadedPrompt> prompts = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) {
                throw new IllegalArgumentException("提示词文件为空: " + f.getOriginalFilename());
            }
            prompts.add(new UploadedPrompt(f.getOriginalFilename(), f.getBytes()));
        }
        return service.createTasks(prompts, iterations, temperature, maxTokens);
    }

    @GetMapping("/tasks")
    public List<TaskInfo> listTasks() {
        return service.listTasks();
    }

    @GetMapping("/tasks/{taskId}")
    public TaskInfo getTask(@PathVariable String taskId) {
        return service.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Map<String, Object> cancel(@PathVariable String taskId) {
        service.cancel(taskId);
        return Map.of("ok", true);
    }

    /** 第 N 次生成结果，md 下载（前端 fetch 同一端点做在线预览） */
    @GetMapping("/tasks/{taskId}/results/{index}")
    public ResponseEntity<byte[]> result(@PathVariable String taskId, @PathVariable int index) throws IOException {
        byte[] bytes = service.readResult(taskId, index);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(String.format("%s_result_%03d.md", taskId, index), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    /** 删除任务及其全部落盘产物；执行中/排队中任务返回 400（须先取消） */
    @DeleteMapping("/tasks/{taskId}")
    public Map<String, Object> delete(@PathVariable String taskId) throws IOException {
        service.delete(taskId);
        return Map.of("ok", true);
    }

    /** 任务全部产物打包下载（结果 md + 原始响应 + 提示词 + 数据包副本 + meta.json） */
    @GetMapping("/tasks/{taskId}/results.zip")
    public ResponseEntity<StreamingResponseBody> zip(@PathVariable String taskId) {
        service.getTask(taskId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(taskId + ".zip", StandardCharsets.UTF_8)
                .build());
        StreamingResponseBody body = out -> service.writeZip(taskId, out);
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
