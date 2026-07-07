package com.treasury.nl2sql.schema;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 运行时从 information_schema 反射目标库的表结构。
 * 作用：
 *  1) 给 LLM 提供 schema 描述（schema linking 的最简版：全量注入；表多时可换成向量召回）
 *  2) 给校验器提供「表/列白名单」，拦截幻觉字段
 * 换库换表零改代码——重启即重新反射。
 */
@Service
public class SchemaService {

    private final JdbcTemplate jdbc;
    private final String database;
    /** 元数据表排除集（如口径资产表本身）：不进白名单、不进 prompt schema。 */
    private final Set<String> excludeTables;

    /** table -> (column -> 类型/注释) */
    private final Map<String, LinkedHashMap<String, ColumnMeta>> tables = new LinkedHashMap<>();

    /** table -> 表级注释（中文表名，语义召回的强信号） */
    private final Map<String, String> tableComments = new LinkedHashMap<>();

    /** 外键关系（用于提示 LLM 可连接路径） */
    private final List<ForeignKey> foreignKeys = new ArrayList<>();

    public record ForeignKey(String table, String column, String refTable, String refColumn) {}

    public SchemaService(JdbcTemplate jdbc, @Value("${schema.database}") String database,
                         @Value("${schema.exclude-tables:}") String excludeTablesCsv) {
        this.jdbc = jdbc;
        this.database = database;
        Set<String> ex = new HashSet<>();
        if (excludeTablesCsv != null) {
            for (String t : excludeTablesCsv.split(",")) {
                String s = t.trim();
                if (!s.isEmpty()) ex.add(s);
            }
        }
        this.excludeTables = ex;
    }

    public record ColumnMeta(String name, String dataType, String comment) {}

    @PostConstruct
    public void load() {
        tables.clear();
        tableComments.clear();
        List<Map<String, Object>> tbls = jdbc.queryForList(
                "SELECT table_name, table_comment FROM information_schema.tables " +
                "WHERE table_schema = ?", database);
        for (Map<String, Object> r : tbls) {
            String table = str(r, "table_name");
            String comment = str(r, "table_comment");
            if (table != null && !excludeTables.contains(table)) {
                tableComments.put(table, comment == null ? "" : comment);
            }
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, column_name, data_type, column_comment, ordinal_position " +
                "FROM information_schema.columns WHERE table_schema = ? " +
                "ORDER BY table_name, ordinal_position", database);
        for (Map<String, Object> r : rows) {
            String table = String.valueOf(r.get("TABLE_NAME") != null ? r.get("TABLE_NAME") : r.get("table_name"));
            if (excludeTables.contains(table)) continue;
            String col = String.valueOf(r.get("COLUMN_NAME") != null ? r.get("COLUMN_NAME") : r.get("column_name"));
            String type = String.valueOf(r.get("DATA_TYPE") != null ? r.get("DATA_TYPE") : r.get("data_type"));
            Object c = r.get("COLUMN_COMMENT") != null ? r.get("COLUMN_COMMENT") : r.get("column_comment");
            tables.computeIfAbsent(table, k -> new LinkedHashMap<>())
                  .put(col, new ColumnMeta(col, type, c == null ? "" : c.toString()));
        }

        foreignKeys.clear();
        List<Map<String, Object>> fks = jdbc.queryForList(
                "SELECT table_name, column_name, referenced_table_name, referenced_column_name " +
                "FROM information_schema.key_column_usage " +
                "WHERE table_schema = ? AND referenced_table_name IS NOT NULL", database);
        for (Map<String, Object> r : fks) {
            foreignKeys.add(new ForeignKey(
                    str(r, "table_name"), str(r, "column_name"),
                    str(r, "referenced_table_name"), str(r, "referenced_column_name")));
        }
    }

    private static String str(Map<String, Object> r, String key) {
        Object v = r.get(key.toUpperCase());
        if (v == null) v = r.get(key);
        return v == null ? null : v.toString();
    }

    public boolean hasTable(String table) {
        return table != null && tables.containsKey(table);
    }

    public boolean hasColumn(String table, String column) {
        LinkedHashMap<String, ColumnMeta> cols = tables.get(table);
        return cols != null && cols.containsKey(column);
    }

    /** 列的 data_type（小写，如 decimal/bigint/varchar）；列不存在返回 null。 */
    public String columnType(String table, String column) {
        LinkedHashMap<String, ColumnMeta> cols = tables.get(table);
        if (cols == null) return null;
        ColumnMeta cm = cols.get(column);
        return cm == null ? null : (cm.dataType() == null ? null : cm.dataType().toLowerCase());
    }

    /** 列是否为数值类型（可做 sum/avg）。 */
    public boolean isNumericColumn(String table, String column) {
        return isNumericType(columnType(table, column));
    }

    static boolean isNumericType(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase();
        return t.contains("int") || t.contains("decimal") || t.contains("numeric")
                || t.contains("double") || t.contains("float") || t.contains("real")
                || t.contains("bit");
    }

    /** 列是否为日期/时间类型（可做 timeColumns 的时间截断）。 */
    public boolean isTemporalColumn(String table, String column) {
        return isTemporalType(columnType(table, column));
    }

    static boolean isTemporalType(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase();
        return t.contains("date") || t.contains("timestamp") || t.equals("time");
    }

    public Set<String> tableNames() {
        return tables.keySet();
    }

    public List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }

    /** 与给定表通过外键直接相连的表（双向） */
    public Set<String> fkNeighbors(String table) {
        Set<String> n = new HashSet<>();
        for (ForeignKey fk : foreignKeys) {
            if (fk.table().equals(table)) n.add(fk.refTable());
            if (fk.refTable().equals(table)) n.add(fk.table());
        }
        return n;
    }

    /**
     * 外键闭包（多跳）：从 seeds 出发，沿外键双向 BFS 最多扩展 maxHops 跳，返回含 seeds 在内的可达表集合。
     * maxHops=1 等价于「seeds + 直接邻居」（与旧的一跳行为一致）；maxHops=0 只返回 seeds 自身。
     */
    public Set<String> fkClosure(Collection<String> seeds, int maxHops) {
        return fkClosure(foreignKeys, seeds, maxHops);
    }

    /** 纯函数版（便于脱离 DB 单测）：基于给定外键列表做多跳闭包 BFS。 */
    static Set<String> fkClosure(List<ForeignKey> fks, Collection<String> seeds, int maxHops) {
        Set<String> visited = new LinkedHashSet<>(seeds);
        Set<String> frontier = new LinkedHashSet<>(seeds);
        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (String t : frontier) {
                for (ForeignKey fk : fks) {
                    if (fk.table().equals(t) && visited.add(fk.refTable())) next.add(fk.refTable());
                    if (fk.refTable().equals(t) && visited.add(fk.table())) next.add(fk.table());
                }
            }
            frontier = next;
        }
        return visited;
    }

    /** 单张表的检索文本（表名 + 表注释 + 列名/列注释），供 lexical/embedding 计算相关度 */
    public String searchText(String table) {
        StringBuilder sb = new StringBuilder(table);
        String tc = tableComments.get(table);
        if (tc != null && !tc.isEmpty()) sb.append(' ').append(tc);
        LinkedHashMap<String, ColumnMeta> cols = tables.get(table);
        if (cols != null) {
            for (ColumnMeta cm : cols.values()) {
                sb.append(' ').append(cm.name());
                if (cm.comment() != null && !cm.comment().isEmpty()) sb.append(' ').append(cm.comment());
            }
        }
        return sb.toString();
    }

    /** 单张表的结构描述块 */
    public String tableBlock(String table) {
        StringBuilder sb = new StringBuilder("表 ").append(table);
        String tc = tableComments.get(table);
        if (tc != null && !tc.isEmpty()) sb.append("   // ").append(tc);
        sb.append(" {\n");
        LinkedHashMap<String, ColumnMeta> cols = tables.get(table);
        if (cols != null) {
            for (ColumnMeta cm : cols.values()) {
                sb.append("  ").append(cm.name()).append(" : ").append(cm.dataType());
                if (cm.comment() != null && !cm.comment().isEmpty()) sb.append("   // ").append(cm.comment());
                sb.append('\n');
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** 把给定若干张表组装成给 LLM 的 schema 描述（含这些表之间的外键关系） */
    public String assemble(Collection<String> selected) {
        Set<String> set = new LinkedHashSet<>(selected);
        StringBuilder sb = new StringBuilder();
        for (String t : set) sb.append(tableBlock(t));
        List<ForeignKey> rel = foreignKeys.stream()
                .filter(fk -> set.contains(fk.table()) && set.contains(fk.refTable()))
                .toList();
        if (!rel.isEmpty()) {
            sb.append("\n可连接关系(外键):\n");
            for (ForeignKey fk : rel) {
                sb.append("  ").append(fk.table()).append('.').append(fk.column())
                  .append(" -> ").append(fk.refTable()).append('.').append(fk.refColumn()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 整库描述（全量注入，full 模式） */
    public String describeForPrompt() {
        return assemble(tables.keySet());
    }

    // ---- 结构快照（供前端 ER 图 / 表格视图使用） ----

    /** 单列的展示信息：列名 / 类型 / 注释 / 是否主键 / 是否外键 */
    public record ColumnInfo(String name, String dataType, String comment, boolean primaryKey, boolean foreignKey) {}

    /** 单表的展示信息：表名 / 表注释 / 列 */
    public record TableInfo(String name, String comment, List<ColumnInfo> columns) {}

    /** 整库结构快照：库名 / 所有表 / 外键关系 */
    public record SchemaSnapshot(String database, List<TableInfo> tables, List<ForeignKey> foreignKeys) {}

    /** 主键列集合（table -> 主键列名），从 information_schema 现取。 */
    private Map<String, Set<String>> primaryKeys() {
        Map<String, Set<String>> pk = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, column_name FROM information_schema.key_column_usage " +
                "WHERE table_schema = ? AND constraint_name = 'PRIMARY'", database);
        for (Map<String, Object> r : rows) {
            String t = str(r, "table_name");
            String c = str(r, "column_name");
            if (t != null && c != null) pk.computeIfAbsent(t, k -> new HashSet<>()).add(c);
        }
        return pk;
    }

    /** 返回整库结构快照：给前端渲染 ER 图与表格视图。 */
    public SchemaSnapshot snapshot() {
        Map<String, Set<String>> pk = primaryKeys();
        Set<String> fkCols = new HashSet<>();
        for (ForeignKey fk : foreignKeys) fkCols.add(fk.table() + "." + fk.column());

        List<TableInfo> ts = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, ColumnMeta>> e : tables.entrySet()) {
            String table = e.getKey();
            Set<String> tpk = pk.getOrDefault(table, Set.of());
            List<ColumnInfo> cols = new ArrayList<>();
            for (ColumnMeta cm : e.getValue().values()) {
                cols.add(new ColumnInfo(cm.name(), cm.dataType(), cm.comment(),
                        tpk.contains(cm.name()), fkCols.contains(table + "." + cm.name())));
            }
            ts.add(new TableInfo(table, tableComments.getOrDefault(table, ""), cols));
        }
        return new SchemaSnapshot(database, ts, new ArrayList<>(foreignKeys));
    }
}
