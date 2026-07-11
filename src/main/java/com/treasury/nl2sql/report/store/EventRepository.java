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

    public List<EventRecord> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM report_event ORDER BY event_date DESC, event_id DESC", rowMapper);
    }

    /** EventMatcher 的候选池：ACTIVE 且事件日期落在窗口内（闭区间）。 */
    public List<EventRecord> findActiveBetween(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + COLS + " FROM report_event WHERE status = 'ACTIVE' "
                        + "AND event_date BETWEEN ? AND ? ORDER BY event_date DESC, event_id DESC",
                rowMapper, Date.valueOf(from), Date.valueOf(to));
    }

    public Optional<EventRecord> findById(long eventId) {
        List<EventRecord> list = jdbc.query("SELECT " + COLS + " FROM report_event WHERE event_id = ?",
                rowMapper, eventId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

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

    public void update(long eventId, EventRecord e, String updatedBy) {
        jdbc.update("UPDATE report_event SET title = ?, event_date = ?, dimensions_json = ?, "
                        + "related_metrics = ?, description = ?, source = ?, updated_by = ? WHERE event_id = ?",
                e.title(), Date.valueOf(e.eventDate()), renderDimensions(e.dimensions()),
                renderMetrics(e.relatedMetrics()), e.description(), e.source(), updatedBy, eventId);
    }

    public void updateStatus(long eventId, String status, String updatedBy) {
        jdbc.update("UPDATE report_event SET status = ?, updated_by = ? WHERE event_id = ?",
                status, updatedBy, eventId);
    }

    private String renderDimensions(Map<String, String> dims) {
        if (dims == null || dims.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(dims);
        } catch (Exception ex) {
            throw new IllegalStateException("dimensions 序列化失败", ex);
        }
    }

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
        return metrics == null || metrics.isEmpty() ? null : String.join(",", metrics);
    }

    private static List<String> parseMetrics(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
