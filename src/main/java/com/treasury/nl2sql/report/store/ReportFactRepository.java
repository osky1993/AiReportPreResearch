package com.treasury.nl2sql.report.store;

import com.treasury.nl2sql.report.domain.FactRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** report_fact 事实表持久层。dimensions MVP 恒为 {}，落库时写常量。 */
@Repository
public class ReportFactRepository {

    private final JdbcTemplate jdbc;

    public ReportFactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FactRecord> MAPPER = (rs, i) -> new FactRecord(
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
            rs.getString("spec_json"),
            rs.getString("sql_text"),
            rs.getString("sql_hash"),
            rs.getString("result_hash"),
            rs.getString("derived_from"),
            rs.getString("quality_status"),
            rs.getString("quality_note"));

    public void batchInsert(long runId, List<FactRecord> facts) {
        jdbc.batchUpdate("INSERT INTO report_fact (run_id, fact_key, metric_id, metric_version, metric_name, chapter_id, fact_type, "
                        + "value_num, unit, display_value, period_label, dimensions_json, spec_json, "
                        + "sql_text, sql_hash, result_hash, derived_from, quality_status, quality_note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?, ?, ?, ?, ?, ?)",
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
                    ps.setString(12, f.specJson());
                    ps.setString(13, f.sqlText());
                    ps.setString(14, f.sqlHash());
                    ps.setString(15, f.resultHash());
                    ps.setString(16, f.derivedFrom());
                    ps.setString(17, f.qualityStatus());
                    ps.setString(18, f.qualityNote());
                });
    }

    public List<FactRecord> findByRun(long runId) {
        return jdbc.query("SELECT fact_key, metric_id, metric_version, metric_name, chapter_id, fact_type, value_num, unit, "
                        + "display_value, period_label, spec_json, sql_text, sql_hash, result_hash, derived_from, "
                        + "quality_status, quality_note FROM report_fact WHERE run_id = ? ORDER BY id",
                MAPPER, runId);
    }

    /** 断点重跑 ④ 前清空该 run 的全部事实（幂等：事实只有一份，不叠加）。 */
    public void deleteByRun(long runId) {
        jdbc.update("DELETE FROM report_fact WHERE run_id = ?", runId);
    }
}
