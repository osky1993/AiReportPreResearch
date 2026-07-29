package com.treasury.nl2sql.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/** 口径资产的持久层。复用应用现有 DataSource（与 SchemaService 同一个 JdbcTemplate）。 */
@Repository
public class CaliberRepository {

    private static final String SELECT_COLS =
            "SELECT id, question, mql_json, description, created_by, created_at, status FROM caliber_asset ";

    private static final RowMapper<CaliberAsset> MAPPER = (rs, i) -> new CaliberAsset(
            rs.getLong("id"),
            rs.getString("question"),
            rs.getString("mql_json"),
            rs.getString("description"),
            rs.getString("created_by"),
            toLdt(rs.getTimestamp("created_at")),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public CaliberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条 ACTIVE 资产，返回自增主键 id。description 可为 null（反翻译失败不阻断沉淀）。 */
    public long insert(String question, String mqlJson, String description, String createdBy) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO caliber_asset (question, mql_json, description, created_by, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, question);
            ps.setString(2, mqlJson);
            if (description == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, description);
            ps.setString(4, createdBy);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 全部 ACTIVE 资产（启动时建召回索引 + 回放 few-shot）。 */
    public List<CaliberAsset> findAllActive() {
        return jdbc.query(SELECT_COLS + "WHERE status = 'ACTIVE' ORDER BY id", MAPPER);
    }

    /** 全部资产（含 DEPRECATED，供治理页列表）。 */
    public List<CaliberAsset> findAll() {
        return jdbc.query(SELECT_COLS + "ORDER BY id", MAPPER);
    }

    /** 按 id 取单条（含 DEPRECATED，供治理页详情），不存在返回 null。 */
    public CaliberAsset findById(long id) {
        List<CaliberAsset> hits = jdbc.query(SELECT_COLS + "WHERE id = ?", MAPPER, id);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 作废一条资产（schema 漂移或人工驳回命中口径时使用）。 */
    public void deprecate(long id) {
        jdbc.update("UPDATE caliber_asset SET status = 'DEPRECATED' WHERE id = ?", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
