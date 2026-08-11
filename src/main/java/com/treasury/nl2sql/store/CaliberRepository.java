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

/**
 * 口径资产持久化仓库（口径资产的单一真相源）。
 *
 * <p>职责是数据库 CRUD 与对象映射，不承载业务缓存或规则决策；业务一致性通过 CaliberStore/服务层保证。</p>
 * <p>所有 SQL 与 `caliber_asset` 列顺序与 DDL/资源侧保持一致，主要用于：
 * <ul>
 *   <li>启动回放 ACTIVE 口径到内存索引；</li>
 *   <li>资产创建后的持久化；</li>
 *   <li>治理端列表/明细读取；</li>
 *   <li>人工驳回后的软下线。</li>
 * </ul>
 */
@Repository
public class CaliberRepository {

    /** 查询列常量，要求与 DB DDL 字段顺序保持一致，避免迁移后出现隐式列错配。 */
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

    /** 持久化依赖：复用应用 JDBC 数据源。底层异常上抛，不在仓储层吞掉错误。 */
    private final JdbcTemplate jdbc;

    /**
     * 依赖注入：仅注入 JdbcTemplate，便于事务与连接池由 Spring 管理；
     * 仓储层不做空值兜底，空值策略由上层服务/控制器处理。
     */
    public CaliberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条 ACTIVE 口径资产，返回自增主键 id。
     * <p>失败场景：数据库约束、SQL 异常会向上抛出；description 允许 null（反翻译失败不阻断沉淀）。</p>
     */
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

    /**
     * 查询全部 ACTIVE 资产。
     * 用于启动加载与冷启动补缓存，返回按 id 升序，保证加载顺序稳定（便于审计/回放比对）。
     */
    public List<CaliberAsset> findAllActive() {
        return jdbc.query(SELECT_COLS + "WHERE status = 'ACTIVE' ORDER BY id", MAPPER);
    }

    /**
     * 查询全部资产（含 DEPRECATED）。
     * 治理页/数据修复任务需要覆盖被下线历史体，因此不做状态过滤。
     */
    public List<CaliberAsset> findAll() {
        return jdbc.query(SELECT_COLS + "ORDER BY id", MAPPER);
    }

    /**
     * 按主键取单条资产（含 DEPRECATED）。
     * 返回 null 表示不存在，供上游服务统一转成 404/400 语义，避免在仓储层直接抛错。
     */
    public CaliberAsset findById(long id) {
        List<CaliberAsset> hits = jdbc.query(SELECT_COLS + "WHERE id = ?", MAPPER, id);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * 将指定资产状态置为 DEPRECATED。
     * 注意：只更新状态，不删库，符合“下线保留可追溯历史”和“审计可回放”约束。
     */
    public void deprecate(long id) {
        jdbc.update("UPDATE caliber_asset SET status = 'DEPRECATED' WHERE id = ?", id);
    }

    /** 数据库时间戳到 Java 时间类型的映射，null 值透传为 null。 */
    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
