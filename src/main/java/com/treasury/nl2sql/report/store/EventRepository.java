package com.treasury.nl2sql.report.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.EventRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** report_event 持久层（单行可编辑 + updated_by/at 留痕；不上多版本——契约2 拍板）。 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<EventRecord> rowMapper;

    public EventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, i) -> new EventRecord(
                rs.getLong("event_id"),
                rs.getString("title"),
                rs.getDate("event_date").toLocalDate(),
                parseDimensions(rs.getString("dimensions_json")),
                parseMetrics(rs.getString("related_metrics")),
                rs.getString("description"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static final String COLS = "event_id, title, event_date, dimensions_json, related_metrics, "
            + "description, source, status, created_by, created_at, updated_by, updated_at";

    /**
     * 管理端展示与对账需要稳定排序；用事件日期降序优先保证“最近事件”排前。
     * 同日再次按 event_id 降序补齐稳定顺序，避免分页抖动。
     */
    public List<EventRecord> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM report_event ORDER BY event_date DESC, event_id DESC", rowMapper);
    }

    /** EventMatcher 的候选池：ACTIVE 且事件日期落在窗口内（闭区间）。 */
    public List<EventRecord> findActiveBetween(LocalDate from, LocalDate to) {
        // 仅保留 ACTIVE，是让逻辑层不关心已过期事件；闭区间防止边界日期被漏判。
        return jdbc.query("SELECT " + COLS + " FROM report_event WHERE status = 'ACTIVE' "
                        + "AND event_date BETWEEN ? AND ? ORDER BY event_date DESC, event_id DESC",
                rowMapper, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * 按主键查询；不存在则 Optional.empty，调用方可区分“缺失”与“空字段”。
     */
    public Optional<EventRecord> findById(long eventId) {
        List<EventRecord> list = jdbc.query("SELECT " + COLS + " FROM report_event WHERE event_id = ?",
                rowMapper, eventId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 插入新事件；返回自增主键，供上层 update/关联和审计使用。 */
    public long insert(EventRecord e) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO report_event (title, event_date, dimensions_json, related_metrics, "
                            + "description, source, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, e.title());
            ps.setDate(2, Date.valueOf(e.eventDate()));
            ps.setString(3, renderDimensions(e.dimensions()));
            ps.setString(4, renderMetrics(e.relatedMetrics()));
            ps.setString(5, e.description());
            ps.setString(6, e.source());
            ps.setString(7, e.createdBy());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    /**
     * 全量更新单条事件。
     * 设计上不更新 created_*，updated_by 与 updated_at 由数据库更新动作补齐，避免创建人与更新者混淆。
     */
    public void update(long eventId, EventRecord e, String updatedBy) {
        jdbc.update("UPDATE report_event SET title = ?, event_date = ?, dimensions_json = ?, "
                        + "related_metrics = ?, description = ?, source = ?, updated_by = ? WHERE event_id = ?",
                e.title(), Date.valueOf(e.eventDate()), renderDimensions(e.dimensions()),
                renderMetrics(e.relatedMetrics()), e.description(), e.source(), updatedBy, eventId);
    }

    /**
     * 仅修改事件生命周期状态，不改动事实内容。
     * 用于治理/归档场景，避免状态变更和内容编辑在同一事务中混淆审计边界。
     */
    public void updateStatus(long eventId, String status, String updatedBy) {
        jdbc.update("UPDATE report_event SET status = ?, updated_by = ? WHERE event_id = ?",
                status, updatedBy, eventId);
    }

    /**
     * 事件维度写入序列化为 JSON；空集合返回 null，保持数据库中“无维度信息”语义一致。
     */
    private String renderDimensions(Map<String, String> dims) {
        if (dims == null || dims.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(dims);
        } catch (Exception ex) {
            throw new IllegalStateException("dimensions 序列化失败", ex);
        }
    }

    /**
     * 解析事件维度 JSON；null/空白/解析失败都转换成显式失败异常，避免带着脏维度静默进入匹配。
     */
    private Map<String, String> parseDimensions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> m = mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            return m.isEmpty() ? null : m;
        } catch (Exception ex) {
            throw new IllegalStateException("report_event.dimensions_json 无法解析: " + json, ex);
        }
    }

    private static String renderMetrics(List<String> metrics) {
        // 关联指标以 CSV 落库；空列表保持 null，避免查询端把 null 和空值混淆。
        return metrics == null || metrics.isEmpty() ? null : String.join(",", metrics);
    }

    private static List<String> parseMetrics(String csv) {
        // 与落库约定对齐：null/空字符串表示“无关联指标”。
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
