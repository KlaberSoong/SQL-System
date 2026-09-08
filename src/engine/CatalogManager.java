package engine;

import sql_compiler.Catalog;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 系统目录管理：把 Catalog 实现为一张特殊表 {@code pg_catalog}，本身也通过存储引擎读写，
 * 因此 Catalog 与表数据一起持久化。
 *
 * <p>目录表结构（每行 = 某张表的一个列）：
 * <pre>
 *   pg_catalog( table_name VARCHAR, column_name VARCHAR, type INT, varchar_len INT, position INT )
 * </pre>
 * {@code type} 为 {@link ColumnType} 的枚举序号；{@code position} 为列在建表顺序中的下标（0 起）。
 */
public class CatalogManager {
    /** 系统目录特殊表名（与 utils.Constants.CATALOG_FILE 一致）。 */
    public static final String CATALOG_TABLE = Constants.CATALOG_FILE;

    /** 目录表自身的列结构。 */
    public static final List<ColumnDef> CATALOG_SCHEMA = Arrays.asList(
            new ColumnDef("table_name", ColumnType.VARCHAR, 64),
            new ColumnDef("column_name", ColumnType.VARCHAR, 64),
            new ColumnDef("type", ColumnType.INT),
            new ColumnDef("varchar_len", ColumnType.INT),
            new ColumnDef("position", ColumnType.INT));

    private final StorageEngine storage;

    public CatalogManager(StorageEngine storage) {
        this.storage = storage;
    }

    /**
     * 从磁盘加载 Catalog（目录表不存在则初始化空目录）。
     * 同时把每张表的结构登记回存储引擎（供其序列化 / 反序列化）。
     */
    public Catalog loadCatalog() {
        Catalog catalog = new Catalog();
        if (!storage.tableExists(CATALOG_TABLE)) {
            storage.createTable(CATALOG_TABLE, CATALOG_SCHEMA);
            return catalog;
        }
        storage.registerSchema(CATALOG_TABLE, CATALOG_SCHEMA);

        // 读取目录行并按 [表名, 列位置] 聚合
        Map<String, Map<Integer, ColumnDef>> tables = new LinkedHashMap<>();
        for (List<Object> row : storage.scanTable(CATALOG_TABLE)) {
            String table = (String) row.get(0);
            String column = (String) row.get(1);
            int typeOrdinal = (Integer) row.get(2);
            int varcharLen = (Integer) row.get(3);
            int position = (Integer) row.get(4);
            ColumnDef def = new ColumnDef(column, ColumnType.values()[typeOrdinal], varcharLen);
            tables.computeIfAbsent(table, k -> new TreeMap<>()).put(position, def);
        }

        for (Map.Entry<String, Map<Integer, ColumnDef>> e : tables.entrySet()) {
            List<ColumnDef> columns = new ArrayList<>(e.getValue().values());
            catalog.createTable(e.getKey(), columns);
            storage.registerSchema(e.getKey(), columns);
        }
        return catalog;
    }

    /** 注册新表到目录并持久化（每列写一行）。 */
    public void registerTable(String name, List<ColumnDef> columns) {
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef c = columns.get(i);
            storage.insertRow(CATALOG_TABLE, Arrays.asList(
                    name,
                    c.getName(),
                    c.getType().ordinal(),
                    c.getVarcharLength(),
                    i));
        }
    }

    /** 按表名取表结构（供执行引擎使用）。 */
    public List<ColumnDef> getTableSchema(String name) {
        return storage.getSchema(name);
    }
}
