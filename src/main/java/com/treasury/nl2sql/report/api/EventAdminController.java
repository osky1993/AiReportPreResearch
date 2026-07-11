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

    public EventAdminController(EventAdminService service) {
        this.service = service;
    }

    public record SaveRequest(EventRecord event, String operator) {}

    @GetMapping
    public List<EventRecord> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody SaveRequest req) {
        long id = service.create(req.event(), req.operator());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("eventId", id));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody SaveRequest req) {
        service.update(id, req.event(), req.operator());
        return Map.of("eventId", id, "status", "updated");
    }

    @PostMapping("/{id}/deprecate")
    public Map<String, Object> deprecate(@PathVariable long id, @RequestBody SaveRequest req) {
        service.deprecate(id, req == null ? null : req.operator());
        return Map.of("eventId", id, "status", "DEPRECATED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
