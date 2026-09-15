package sql_compiler;

import utils.ColumnDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式目录（编译原理中的符号表）：table_name -> (column_name -> ColumnDef)，用 LinkedHashMap 保持列顺序。
 */
public class Catalog {
    private final Map<String, LinkedHashMap<String, ColumnDef>> tables = new LinkedHashMap<>();

    // 注册新表；表已存在返回 false，否则注册成功返回 true
    public boolean createTable(String name, List<ColumnDef> columns) {
        if (tables.containsKey(name)) {
            return false;
        }
        LinkedHashMap<String, ColumnDef> map = new LinkedHashMap<>();
        for (ColumnDef c : columns) {
            map.put(c.getName(), c);
        }
        tables.put(name, map);
        return true;
    }

    // 判断表是否存在
    public boolean containsTable(String name) {
        return tables.containsKey(name);
    }

    // 判断表内某列是否存在
    public boolean containsColumn(String table, String column) {
        Map<String, ColumnDef> m = tables.get(table);
        return m != null && m.containsKey(column);
    }

    // 按列名取列定义；表或列不存在返回 null
    public ColumnDef getColumn(String table, String column) {
        Map<String, ColumnDef> m = tables.get(table);
        return m == null ? null : m.get(column);
    }

    // 返回表的列定义（按建表顺序）；表不存在返回 null
    public List<ColumnDef> getColumns(String table) {
        Map<String, ColumnDef> m = tables.get(table);
        if (m == null) {
            return null;
        }
        return new ArrayList<>(m.values());
    }

    // 返回所有已注册的表名
    public List<String> getTableNames() {
        return new ArrayList<>(tables.keySet());
    }

    /**
     * 符号表快照：只能交给 {@link #restore(Snapshot)} 用，本身不提供任何读接口。
     * 存在的理由见 {@link #restore(Snapshot)}。
     */
    public static final class Snapshot {
        private final Map<String, LinkedHashMap<String, ColumnDef>> tables;

        private Snapshot(Map<String, LinkedHashMap<String, ColumnDef>> tables) {
            this.tables = tables;
        }
    }

    /**
     * 取当前符号表的快照。
     *
     * <p>{@link SemanticAnalyzer} 在**分析阶段**就把 CREATE TABLE 注册进本表（符号表的教科书做法），
     * 而执行阶段才可能失败。若语句失败后不回滚，符号表就会留下一个"编译期认为存在、
     * 存储引擎里并不存在"的表——之后同一会话里重建该表会被误报为 DuplicateTable，
     * 而 INSERT/SELECT 又会从引擎层抛"table does not exist"，用户看到的两个错误互相矛盾，
     * 且这张表在整个会话内再也建不出来（实测：一次失败的 CREATE 之后，t 被永久卡住）。
     * 因此语句的执行方（{@code cli.Main}）必须在失败时用本快照回滚。
     */
    public Snapshot snapshot() {
        Map<String, LinkedHashMap<String, ColumnDef>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, ColumnDef>> e : tables.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return new Snapshot(copy);
    }

    /** 把符号表恢复到 {@link #snapshot()} 时的状态（列定义是不可变对象，浅拷贝即可）。 */
    public void restore(Snapshot snapshot) {
        tables.clear();
        for (Map.Entry<String, LinkedHashMap<String, ColumnDef>> e : snapshot.tables.entrySet()) {
            tables.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
    }
}
