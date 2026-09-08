package sql_compiler;

import utils.ColumnDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式目录（编译原理中的符号表）。
 * 结构：table_name -> (column_name -> ColumnDef)，用 LinkedHashMap 保持列顺序。
 */
public class Catalog {
    private final Map<String, LinkedHashMap<String, ColumnDef>> tables = new LinkedHashMap<>();

    /** 注册新表；表已存在返回 false，否则注册成功返回 true。 */
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

    public boolean containsTable(String name) {
        return tables.containsKey(name);
    }

    public boolean containsColumn(String table, String column) {
        Map<String, ColumnDef> m = tables.get(table);
        return m != null && m.containsKey(column);
    }

    /** 按列名取列定义；表或列不存在返回 null。 */
    public ColumnDef getColumn(String table, String column) {
        Map<String, ColumnDef> m = tables.get(table);
        return m == null ? null : m.get(column);
    }

    /** 返回表的列定义（按建表顺序）；表不存在返回 null。 */
    public List<ColumnDef> getColumns(String table) {
        Map<String, ColumnDef> m = tables.get(table);
        if (m == null) {
            return null;
        }
        return new ArrayList<>(m.values());
    }

    /** 所有已注册的表名。 */
    public List<String> getTableNames() {
        return new ArrayList<>(tables.keySet());
    }
}
