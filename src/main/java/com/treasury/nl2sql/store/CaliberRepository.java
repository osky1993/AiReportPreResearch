package com.treasury.nl2sql.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** 口径资产的持久层。复用应用现有 DataSource（与 SchemaService 同一个 JdbcTemplate）。 */
@Repository
public class CaliberRepository {

    private final JdbcTemplate jdbc;

    public CaliberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条 ACTIVE 资产，返回自增主键 id。 */
    public long insert(String question, String mqlJson, String createdBy) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO caliber_asset (question, mql_json, created_by, status) VALUES (?, ?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, question);
            ps.setString(2, mqlJson);
            ps.setString(3, createdBy);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 全部 ACTIVE 资产（启动时建召回索引 + 回放 few-shot）。 */
    public List<CaliberAsset> findAllActive() {
        return jdbc.query(
                "SELECT id, question, mql_json, created_by, created_at, status " +
                "FROM caliber_asset WHERE status = 'ACTIVE' ORDER BY id",
                (rs, i) -> new CaliberAsset(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("mql_json"),
                        rs.getString("created_by"),
                        toLdt(rs.getTimestamp("created_at")),
                        rs.getString("status")));
    }

    /** 作废一条资产（schema 漂移或人工驳回命中口径时使用）。 */
    public void deprecate(long id) {
        jdbc.update("UPDATE caliber_asset SET status = 'DEPRECATED' WHERE id = ?", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
