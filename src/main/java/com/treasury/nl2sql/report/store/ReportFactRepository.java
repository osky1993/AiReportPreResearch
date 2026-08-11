package com.treasury.nl2sql.report.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.nl2sql.report.domain.FactRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * report_fact 事实表持久层。
 * 约定：
 * - dimensions 作为事实分桶维度，写入时 null/空序列化为 {}，读出时再回转为 null；
 * - 一个 run 的 facts 可被多次重算，写入前由上层执行 deleteByRun 达到幂等重建；
 * - 同一 run 的 fact_key 由 FactBuildStep 保证唯一性，本表不做二次 dedupe。
 */
@Repository
public class ReportFactRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<FactRecord> rowMapper;

    public ReportFactRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, i) -> new FactRecord(
                rs.getString("fact_key"),
                rs.getString("metric_id"),
                rs.getObject("metric_version", Integer.class),
                rs.getString("metric_name"),
                rs.getString("chapter_id"),
                rs.getString("fact_type"),
                rs.getBigDecimal("value_num"),
                rs.getString("unit"),
                rs.getString("display_value"),
                rs.getString("period_label"),
                parseDimensions(rs.getString("dimensions_json")),
                rs.getString("spec_json"),
                rs.getString("sql_text"),
                rs.getString("sql_hash"),
                rs.getString("result_hash"),
                rs.getString("derived_from"),
                rs.getString("quality_status"),
                rs.getString("quality_note"));
    }

    /**
     * 批量落地阶段④产物。
     * 这里不使用 UPSERT：上游在断点重跑前会先清空 run 数据，实现“重算覆盖”而非“更新覆盖”。
     * 失败模式：任何一条失败会触发 batch 返回异常，上游事务应统一回滚，避免部分成功。
     */
    public void batchInsert(long runId, List<FactRecord> facts) {
        jdbc.batchUpdate("INSERT INTO report_fact (run_id, fact_key, metric_id, metric_version, metric_name, chapter_id, fact_type, "
                        + "value_num, unit, display_value, period_label, dimensions_json, spec_json, "
                        + "sql_text, sql_hash, result_hash, derived_from, quality_status, quality_note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                facts, facts.size(), (ps, f) -> {
                    ps.setLong(1, runId);
                    ps.setString(2, f.factKey());
                    ps.setString(3, f.metricId());
                    ps.setObject(4, f.metricVersion());
                    ps.setString(5, f.metricName());
                    ps.setString(6, f.chapterId());
                    ps.setString(7, f.factType());
                    ps.setBigDecimal(8, f.value());
                    ps.setString(9, f.unit());
                    ps.setString(10, f.displayValue());
                    ps.setString(11, f.periodLabel());
                    ps.setString(12, renderDimensions(f.dimensions()));
                    ps.setString(13, f.specJson());
                    ps.setString(14, f.sqlText());
                    ps.setString(15, f.sqlHash());
                    ps.setString(16, f.resultHash());
                    ps.setString(17, f.derivedFrom());
                    ps.setString(18, f.qualityStatus());
                    ps.setString(19, f.qualityNote());
                });
    }

    /**
     * 依据 run_id 拉取全部事实，按入库主键排序保证渲染与审计稳定。
     * 上游用于重跑恢复、审计核对、导出组装，顺序稳定可提升“同样输入一致输出”可追溯性。
     */
    public List<FactRecord> findByRun(long runId) {
        return jdbc.query("SELECT fact_key, metric_id, metric_version, metric_name, chapter_id, fact_type, value_num, unit, "
                        + "display_value, period_label, dimensions_json, spec_json, sql_text, sql_hash, result_hash, derived_from, "
                        + "quality_status, quality_note FROM report_fact WHERE run_id = ? ORDER BY id",
                rowMapper, runId);
    }

    /** 断点重跑 ④ 前清空该 run 的全部事实（幂等：事实只有一份，不叠加）。 */
    public void deleteByRun(long runId) {
        jdbc.update("DELETE FROM report_fact WHERE run_id = ?", runId);
    }

    /** dimensions 空集合规约为 {}，是为了和查询层“空集合”与“空值缺失”语义保持一致。 */
    private String renderDimensions(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(dimensions);
        } catch (Exception e) {
            throw new IllegalStateException("dimensions 序列化失败", e);
        }
    }

    private Map<String, String> parseDimensions(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
        try {
            Map<String, String> m = mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            return m.isEmpty() ? null : m;
        } catch (Exception e) {
            throw new IllegalStateException("dimensions_json 无法解析: " + json, e);
        }
    }
}
