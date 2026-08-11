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

    /**
     * 新建一次 run。
     * 约束：入库时直接进入 RUNNING/OUTLINE，为异步流水线开辟主键。
     * run_id 由 DB 自增回传，供任务生命周期后续步进。
     */
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

    /** 按 run_id 精准查询；不存在时返回 Optional.empty，避免 findByXxxOrElseThrow 的异常副作用。 */
    public Optional<ReportRun> findById(long runId) {
        List<ReportRun> list = jdbc.query("SELECT " + COLS + " FROM report_run WHERE run_id = ?", MAPPER, runId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 全量列表按主键倒序，便于分页和重放场景按“最近任务”优先展示。 */
    public List<ReportRun> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM report_run ORDER BY run_id DESC", MAPPER);
    }

    /**
     * 卡点1 前置落库：保存模板+周期窗口。
     * 特点：
     * 1) 锁定模板版本号，形成不可回溯的口径锚点；
     * 2) 同时清空 BLOCKED_REASON（因为已达可审批阶段）；
     * 3) 状态变更到 AWAITING_OUTLINE_APPROVAL，进入 HITL 第一道门禁。
     */
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

    /**
     * HITL 卡点1通过时落地：
     * - 以人工签核后的 outline_json 覆盖草稿；
     * - metric_versions_json 作为口径快照入库（后续 Fetch / FactBuild 使用该快照，而非动态最新）。
     */
    public void approveOutline(long runId, String approver, String outlineJson, String metricVersionsJson) {
        jdbc.update("UPDATE report_run SET status = 'RUNNING', phase = 'SPEC', outline_json = ?, "
                        + "metric_versions_json = ?, "
                        + "outline_approved_by = ?, outline_approved_at = NOW(), blocked_reason = NULL "
                        + "WHERE run_id = ?",
                outlineJson, metricVersionsJson, approver, runId);
    }

    /**
     * 保存用户选定图表绑定关系。
     * 重跑时该字段会重新覆盖，但不追加新行，因此设计为“幂等更新”而非“append-only”。
     */
    public void saveCharts(long runId, String chartsJson) {
        jdbc.update("UPDATE report_run SET charts_json = ? WHERE run_id = ?", chartsJson, runId);
    }

    /**
     * 仅更新 status/phase 的通用入口。
     * 用于“断点续跑”将运行放回某阶段继续执行时，不带副作用地修改运行态。
     */
    public void setStatusPhase(long runId, String status, String phase) {
        jdbc.update("UPDATE report_run SET status = ?, phase = ? WHERE run_id = ?", status, phase, runId);
    }

    /**
     * 失败关闭写入口：统一落 status=BLOCKED。
     * 约定要求 blocked_reason 必须可读且带前缀类型，便于运维/UI 区分策略性失败与异常失败。
     */
    public void setBlocked(long runId, String phase, String reason) {
        jdbc.update("UPDATE report_run SET status = 'BLOCKED', phase = ?, blocked_reason = ? WHERE run_id = ?",
                phase, reason, runId);
    }

    /**
     * 审计通过写库：落 report_md 与 audit_json。
     * 同时把状态推进 AWAITING_PUBLISH_APPROVAL 与 phase=AUDIT，进入卡点2。
     */
    public void saveReport(long runId, String reportMd, String auditJson) {
        jdbc.update("UPDATE report_run SET report_md = ?, audit_json = ?, "
                        + "status = 'AWAITING_PUBLISH_APPROVAL', phase = 'AUDIT', blocked_reason = NULL "
                + "WHERE run_id = ?",
                reportMd, auditJson, runId);
    }

    /**
     * 人工签发通过：落签发人和签发时间。
     * 一旦签发，报告进入 PUBLISHED，不再进入任何自动重算路径。
     */
    public void publishApprove(long runId, String approver) {
        jdbc.update("UPDATE report_run SET status = 'PUBLISHED', "
                        + "publish_approved_by = ?, publish_approved_at = NOW() WHERE run_id = ?",
                approver, runId);
    }

    /**
     * 人工签发驳回：转 REJECTED 并写入 reason 与签发人留痕。
     * 驳回后可由上层按策略发起新轮运行，不会自动复用旧 run。
     */
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
