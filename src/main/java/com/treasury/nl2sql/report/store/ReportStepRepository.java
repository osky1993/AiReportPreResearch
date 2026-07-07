package com.treasury.nl2sql.report.store;

import com.treasury.nl2sql.report.domain.ReportStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** report_step 留痕表持久层：每步一条（重试/断点重跑 attempt 递增，只追加不覆盖）。 */
@Repository
public class ReportStepRepository {

    private final JdbcTemplate jdbc;

    public ReportStepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReportStep> MAPPER = (rs, i) -> new ReportStep(
            rs.getLong("step_id"),
            rs.getLong("run_id"),
            rs.getString("phase"),
            rs.getInt("attempt"),
            rs.getString("status"),
            rs.getString("input_json"),
            rs.getString("output_json"),
            rs.getString("error_text"),
            toLdt(rs.getTimestamp("started_at")),
            toLdt(rs.getTimestamp("finished_at")));

    /** 开始一步：attempt = 该 run 该 phase 的历史次数 + 1。 */
    public long start(long runId, String phase, String inputJson) {
        Integer prev = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) FROM report_step WHERE run_id = ? AND phase = ?",
                Integer.class, runId, phase);
        int attempt = (prev == null ? 0 : prev) + 1;
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO report_step (run_id, phase, attempt, status, input_json) VALUES (?, ?, ?, 'RUNNING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId);
            ps.setString(2, phase);
            ps.setInt(3, attempt);
            ps.setString(4, inputJson);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    public void finishOk(long stepId, String outputJson) {
        jdbc.update("UPDATE report_step SET status = 'OK', output_json = ?, finished_at = NOW() WHERE step_id = ?",
                outputJson, stepId);
    }

    public void finishBlocked(long stepId, String errorText) {
        jdbc.update("UPDATE report_step SET status = 'BLOCKED', error_text = ?, finished_at = NOW() WHERE step_id = ?",
                errorText, stepId);
    }

    public List<ReportStep> findByRun(long runId) {
        return jdbc.query("SELECT step_id, run_id, phase, attempt, status, input_json, output_json, error_text, "
                        + "started_at, finished_at FROM report_step WHERE run_id = ? ORDER BY step_id",
                MAPPER, runId);
    }

    /** 某 phase 最近一次成功的输出（断点恢复时读取前序产物）。 */
    public Optional<String> latestOkOutput(long runId, String phase) {
        List<String> list = jdbc.query(
                "SELECT output_json FROM report_step WHERE run_id = ? AND phase = ? AND status = 'OK' "
                        + "ORDER BY attempt DESC LIMIT 1",
                (rs, i) -> rs.getString(1), runId, phase);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
