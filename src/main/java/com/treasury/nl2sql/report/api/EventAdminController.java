package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.report.asset.EventAdminService;
import com.treasury.nl2sql.report.domain.EventRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 事件知识库 CRUD（Phase05 B 轨）。校验在服务端（EventAdminService），前端仅展示。 */
@RestController
@RequestMapping("/api/report/events")
public class EventAdminController {

    private final EventAdminService service;

    /** 构造器：事件服务不可为空，避免控制器空壳运行。 */
    public EventAdminController(EventAdminService service) {
        this.service = service;
    }

    /** 事件提交请求体：事件体本身 + 操作人，操作人用于审计与更新日志留痕。 */
    public record SaveRequest(EventRecord event, String operator) {}

    /**
     * 事件列表查询，返回全部未下线/可视化实体。
     * <p>用于 Phase05 B 轨事件知识库管理；按服务层默认排序返回，控制器不做二次排序。</p>
     */
    @GetMapping
    public List<EventRecord> list() {
        return service.list();
    }

    /**
     * 新建事件。
     * <p>落库成功后返回新事件 ID；若事件字段缺失/冲突，服务层抛异常并映射为 400。</p>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody SaveRequest req) {
        long id = service.create(req.event(), req.operator());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("eventId", id));
    }

    /**
     * 全量更新事件。
     * <p>按 id 覆盖版本化事件记录（保留原主键）；与部分更新不同，调用方需提交完整 EventRecord。</p>
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody SaveRequest req) {
        service.update(id, req.event(), req.operator());
        return Map.of("eventId", id, "status", "updated");
    }

    /**
     * 逻辑下线事件。
     * <p>只变更状态不删除行，确保历史审计、复盘与回放仍可回溯到已下线事件。</p>
     */
    @PostMapping("/{id}/deprecate")
    public Map<String, Object> deprecate(@PathVariable long id, @RequestBody SaveRequest req) {
        service.deprecate(id, req == null ? null : req.operator());
        return Map.of("eventId", id, "status", "DEPRECATED");
    }

    /**
     * 非法请求参数统一映射。
     * <p>包括事件不存在、字段校验失败等，返回可展示的错误描述。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
