package com.treasury.nl2sql.report.store;

import com.treasury.nl2sql.report.domain.ClaimRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * report_claim 持久层（run 级状态）。
 * claims 是人工确认前的假设性语句，重跑时按 run_id 全量清空重建，实现幂等重建（不依赖单条 upsert）。
 * 读写约定：保存时允许 evidence_refs 为 CSV，读出后统一转为 list 供上层聚合。
 */
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

    /**
     * 批量入库 claim：按 map 输入顺序逐条执行 batch，失败立即整体回滚到事务边界外层。
     * evidence_refs 转 csv，便于与既有 schema 兼容，避免建额外表引入 JOIN 成本。
     */
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

    /** 按 run_id 查询所有 claim，按 id 顺序返回，供审计页/审读页稳定展示。 */
    public List<ClaimRecord> findByRun(long runId) {
        return jdbc.query("SELECT claim_id, anomaly_fact_key, attribution_level, evidence_refs, narrative, "
                        + "confirmed_by, confirmed_at FROM report_claim WHERE run_id = ? ORDER BY id",
                MAPPER, runId);
    }

    /**
     * 重跑重建路径：在同一 run 上清空旧 claim，防止历史异常残留。
     * 该方法不含 CASCADE，调用方需保证 run 重跑时机一致。
     */
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

    /**
     * 用逗号拆分 evidence_refs；空串/空白统一转空列表，简化上层判定“有没有证据链”。
     */
    private static List<String> parseRefs(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
