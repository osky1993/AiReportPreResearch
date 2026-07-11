package com.treasury.nl2sql.report.store;

import com.treasury.nl2sql.report.domain.ReportRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** report_run 主表持久层（范式照 CaliberRepository：JdbcTemplate + GeneratedKeyHolder + RowMapper）。 */
@Repository
public class ReportRunRepository {

    private final JdbcTemplate jdbc;

    public ReportRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "run_id, request_text, template_id, template_version, metric_versions_json, "
            + "period_label, period_start, period_end, "
            + "compare_start, compare_end, yoy_start, yoy_end, "
            + "status, phase, outline_json, charts_json, report_md, audit_json, blocked_reason, "
            + "outline_approved_by, outline_approved_at, publish_approved_by, publish_approved_at, created_at, updated_at";

    private static final RowMapper<ReportRun> MAPPER = (rs, i) -> new ReportRun(
            rs.getLong("run_id"),
            rs.getString("request_text"),
            rs.getString("template_id"),
            rs.getObject("template_version", Integer.class),
            rs.getString("metric_versions_json"),
            rs.getString("period_label"),
            toLd(rs, "period_start"),
            toLd(rs, "period_end"),
            toLd(rs, "compare_start"),
            toLd(rs, "compare_end"),
            toLd(rs, "yoy_start"),
            toLd(rs, "yoy_end"),
            rs.getString("status"),
            rs.getString("phase"),
            rs.getString("outline_json"),
            rs.getString("charts_json"),
            rs.getString("report_md"),
            rs.getString("audit_json"),
            rs.getString("blocked_reason"),
            rs.getString("outline_approved_by"),
            toLdt(rs.getTimestamp("outline_approved_at")),
            rs.getString("publish_approved_by"),
            toLdt(rs.getTimestamp("publish_approved_at")),
            toLdt(rs.getTimestamp("created_at")),
            toLdt(rs.getTimestamp("updated_at")));

    /** 新建一次运行（初始状态 RUNNING，① 同步跑完后再落大纲或 BLOCKED）。 */
    public long insert(String requestText) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO report_run (request_text, status, phase) VALUES (?, 'RUNNING', 'OUTLINE')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestText);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    public Optional<ReportRun> findById(long runId) {
        List<ReportRun> list = jdbc.query("SELECT " + COLS + " FROM report_run WHERE run_id = ?", MAPPER, runId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<ReportRun> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM report_run ORDER BY run_id DESC", MAPPER);
    }

    /** ① 成功：落大纲与周期窗口（含模板版本固化；同比窗口仅声明了同比的模板非空），进入 HITL 卡点1 等待。 */
    public void saveOutline(long runId, String templateId, Integer templateVersion, String periodLabel,
                            LocalDate periodStart, LocalDate periodEnd,
                            LocalDate compareStart, LocalDate compareEnd,
                            LocalDate yoyStart, LocalDate yoyEnd, String outlineJson) {
        jdbc.update("UPDATE report_run SET template_id = ?, template_version = ?, period_label = ?, "
                        + "period_start = ?, period_end = ?, "
                        + "compare_start = ?, compare_end = ?, yoy_start = ?, yoy_end = ?, outline_json = ?, "
                        + "status = 'AWAITING_OUTLINE_APPROVAL', phase = 'OUTLINE', blocked_reason = NULL "
                        + "WHERE run_id = ?",
                templateId, templateVersion, periodLabel, toDate(periodStart), toDate(periodEnd),
                toDate(compareStart), toDate(compareEnd), toDate(yoyStart), toDate(yoyEnd), outlineJson, runId);
    }

    /** HITL 卡点1 确认：以人确认的大纲版本为准落库（同时固化指标版本快照——口径锁死时点），转 RUNNING。 */
    public void approveOutline(long runId, String approver, String outlineJson, String metricVersionsJson) {
        jdbc.update("UPDATE report_run SET status = 'RUNNING', phase = 'SPEC', outline_json = ?, "
                        + "metric_versions_json = ?, "
                        + "outline_approved_by = ?, outline_approved_at = NOW(), blocked_reason = NULL "
                        + "WHERE run_id = ?",
                outlineJson, metricVersionsJson, approver, runId);
    }

    /** ④ 后落已绑定的图表（重跑覆盖，幂等）。 */
    public void saveCharts(long runId, String chartsJson) {
        jdbc.update("UPDATE report_run SET charts_json = ? WHERE run_id = ?", chartsJson, runId);
    }

    public void setStatusPhase(long runId, String status, String phase) {
        jdbc.update("UPDATE report_run SET status = ?, phase = ? WHERE run_id = ?", status, phase, runId);
    }

    /** 失败关闭：停止并留痕，等人。 */
    public void setBlocked(long runId, String phase, String reason) {
        jdbc.update("UPDATE report_run SET status = 'BLOCKED', phase = ?, blocked_reason = ? WHERE run_id = ?",
                phase, reason, runId);
    }

    /** ⑥ 审计通过：落终稿与审计包，进入 HITL 卡点2 等待。 */
    public void saveReport(long runId, String reportMd, String auditJson) {
        jdbc.update("UPDATE report_run SET report_md = ?, audit_json = ?, "
                        + "status = 'AWAITING_PUBLISH_APPROVAL', phase = 'AUDIT', blocked_reason = NULL "
                        + "WHERE run_id = ?",
                reportMd, auditJson, runId);
    }

    public void publishApprove(long runId, String approver) {
        jdbc.update("UPDATE report_run SET status = 'PUBLISHED', "
                        + "publish_approved_by = ?, publish_approved_at = NOW() WHERE run_id = ?",
                approver, runId);
    }

    public void publishReject(long runId, String approver, String reason) {
        jdbc.update("UPDATE report_run SET status = 'REJECTED', blocked_reason = ?, "
                        + "publish_approved_by = ?, publish_approved_at = NOW() WHERE run_id = ?",
                reason, approver, runId);
    }

    private static LocalDate toLd(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Date toDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
