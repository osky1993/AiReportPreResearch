package com.treasury.nl2sql.report.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 版本化资产仓库基类（report_template / report_metric 两表同构，仅表名与业务 id 列名不同）。
 * 范式照 CaliberRepository：JdbcTemplate + lambda RowMapper，不用 Spring Data。
 * 行不可变纪律的落点：本类只提供 insertNewVersion 与 updateStatus，不存在任何改写 body_json 的方法。
 */
abstract class VersionedAssetRepository {

    private final JdbcTemplate jdbc;
    private final String table;
    private final String idColumn;
    private final RowMapper<AssetRow> mapper;

    protected VersionedAssetRepository(JdbcTemplate jdbc, String table, String idColumn) {
        this.jdbc = jdbc;
        this.table = table;
        this.idColumn = idColumn;
        this.mapper = (rs, i) -> new AssetRow(
                rs.getLong("id"),
                rs.getString(idColumn),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("body_json"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("created_by"),
                toLdt(rs.getTimestamp("created_at")),
                rs.getString("remark"));
    }

    /**
     * 写入下一个版本（版本号 = 当前最大版本 + 1），返回新版本号。
     * 并发写同一资产时由唯一键 (id, version) 兜底：后到者抛 DuplicateKeyException（demo 可接受）。
     */
    public int insertNewVersion(String assetId, String name, String bodyJson,
                                String status, String source, String createdBy, String remark) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class, assetId);
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("INSERT INTO " + table + " (" + idColumn + ", version, name, body_json, status, source, created_by, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                assetId, version, name, bodyJson, status, source, createdBy, remark);
        return version;
    }

    /** 全部 PUBLISHED 版本行（启动装缓存用；同一资产 id 是否出现多个 PUBLISHED 由调用方检测并拒绝启动）。 */
    public List<AssetRow> findPublished() {
        return jdbc.query("SELECT id, " + idColumn + ", version, name, body_json, status, source, created_by, created_at, remark "
                + "FROM " + table + " WHERE status = 'PUBLISHED' ORDER BY " + idColumn, mapper);
    }

    /** 指定版本行（resume 追溯 / 版本历史查看）。 */
    public Optional<AssetRow> findByIdAndVersion(String assetId, int version) {
        List<AssetRow> rows = jdbc.query("SELECT id, " + idColumn + ", version, name, body_json, status, source, created_by, created_at, remark "
                + "FROM " + table + " WHERE " + idColumn + " = ? AND version = ?", mapper, assetId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 状态流转（DRAFT→PUBLISHED→DEPRECATED；流转守卫在服务层，本方法只执行）。 */
    public void updateStatus(String assetId, int version, String status) {
        jdbc.update("UPDATE " + table + " SET status = ? WHERE " + idColumn + " = ? AND version = ?",
                status, assetId, version);
    }

    /** 该业务 id 是否已存在任何版本（种子幂等判定：DEPRECATED 也算存在，人为下架不被种子复活）。 */
    public boolean existsById(String assetId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?", Integer.class, assetId);
        return n != null && n > 0;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
