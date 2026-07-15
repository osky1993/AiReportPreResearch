package com.treasury.nl2sql.report.api;

import com.treasury.nl2sql.store.CaliberAsset;
import com.treasury.nl2sql.store.CaliberRepository;
import com.treasury.nl2sql.store.CaliberStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 口径资产的报告层端点（升格桥 + 治理）。
 * 端点放在报告层（而非底座 QueryController）：底座代码/端点零改动是硬约束，
 * 报告层复用底座 CaliberRepository / CaliberStore。
 * <ul>
 *   <li>升格桥（P2-T8）：只读列出 ACTIVE 口径，供「升格为指标 →」直入向导参数化步。</li>
 *   <li>治理（前端资产治理工作台）：全量列表 / 详情 / 下架。下架必须走
 *       {@link CaliberStore#deprecate}（库置 DEPRECATED + 移出内存召回索引），
 *       直接打 Repository 会让已下架口径在重启前仍被智能问数召回。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/report")
public class CaliberBridgeController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaliberBridgeController.class);

    private final CaliberRepository calibers;
    private final CaliberStore store;

    public CaliberBridgeController(CaliberRepository calibers, CaliberStore store) {
        this.calibers = calibers;
        this.store = store;
    }

    /**
     * 口径资产列表，按沉淀时间倒序。缺省仅 ACTIVE（升格桥的既有契约，保持向后兼容）；
     * {@code ?status=all} 返回含 DEPRECATED 的全量（治理页）。
     */
    @GetMapping("/calibers")
    public List<CaliberAsset> list(@RequestParam(required = false) String status) {
        List<CaliberAsset> rows = "all".equalsIgnoreCase(status)
                ? calibers.findAll()
                : calibers.findAllActive();
        return rows.stream().sorted(Comparator.comparingLong(CaliberAsset::id).reversed()).toList();
    }

    /** 单条详情（含 DEPRECATED，供治理页）。 */
    @GetMapping("/calibers/{id}")
    public CaliberAsset get(@PathVariable long id) {
        CaliberAsset asset = calibers.findById(id);
        if (asset == null) {
            throw new IllegalArgumentException("口径资产不存在: id=" + id);
        }
        return asset;
    }

    public record DeprecateRequest(String operator) {}

    /** 下架口径：走 CaliberStore（库 + 内存召回索引联动），operator 仅留痕日志、零 schema 变更。 */
    @PostMapping("/calibers/{id}/deprecate")
    public Map<String, Object> deprecate(@PathVariable long id, @RequestBody(required = false) DeprecateRequest req) {
        CaliberAsset asset = calibers.findById(id);
        if (asset == null) {
            throw new IllegalArgumentException("口径资产不存在: id=" + id);
        }
        if ("DEPRECATED".equals(asset.status())) {
            throw new IllegalArgumentException("口径资产已是下架状态: id=" + id);
        }
        store.deprecate(id);
        log.info("口径资产下架: id={} 操作人={}", id, req == null ? null : req.operator());
        return Map.of("id", id, "status", "DEPRECATED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
