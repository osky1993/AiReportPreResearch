package com.treasury.nl2sql.report.store;

import com.treasury.nl2sql.report.domain.ClaimRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** report_claim 持久层（run 级状态，重跑清空重建幂等——范式照 ReportFactRepository）。 */
@Repository
public class ClaimRepository {

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ClaimRecord> MAPPER = (rs, i) -> new ClaimRecord(
            rs.getString("claim_id"),
            rs.getString("anomaly_fact_key"),
            rs.getString("attribution_level"),
            parseRefs(rs.getString("evidence_refs")),
            rs.getString("narrative"),
            rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime());

    public void batchInsert(long runId, List<ClaimRecord> claims) {
        jdbc.batchUpdate("INSERT INTO report_claim (run_id, claim_id, anomaly_fact_key, attribution_level, "
                        + "evidence_refs, narrative) VALUES (?, ?, ?, ?, ?, ?)",
                claims, claims.size(), (ps, c) -> {
                    ps.setLong(1, runId);
                    ps.setString(2, c.claimId());
                    ps.setString(3, c.anomalyFactKey());
                    ps.setString(4, c.attributionLevel());
                    ps.setString(5, c.evidenceRefs() == null ? null : String.join(",", c.evidenceRefs()));
                    ps.setString(6, c.narrative());
                });
    }

    public List<ClaimRecord> findByRun(long runId) {
        return jdbc.query("SELECT claim_id, anomaly_fact_key, attribution_level, evidence_refs, narrative, "
                        + "confirmed_by, confirmed_at FROM report_claim WHERE run_id = ? ORDER BY id",
                MAPPER, runId);
    }

    public void deleteByRun(long runId) {
        jdbc.update("DELETE FROM report_claim WHERE run_id = ?", runId);
    }

    /**
     * 卡点2 人工确认（T0 拍板：勾选 + 两列留痕，不建独立工作流）：仅 hypothesis 可升 confirmed。
     * @return 影响行数（0 = claim 不存在或等级不允许）
     */
    public int confirm(long runId, String claimId, String confirmedBy) {
        return jdbc.update("UPDATE report_claim SET attribution_level = 'confirmed', "
                        + "confirmed_by = ?, confirmed_at = NOW() "
                        + "WHERE run_id = ? AND claim_id = ? AND attribution_level = 'hypothesis'",
                confirmedBy, runId, claimId);
    }

    private static List<String> parseRefs(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
