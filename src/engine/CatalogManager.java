package engine;

import sql_compiler.Catalog;
import utils.ColumnDef;

import java.util.List;

/**
 * 系统目录管理：把 Catalog 作为特殊表（pg_catalog）通过存储引擎持久化。
 */
public class CatalogManager {
    private final StorageEngine storage;

    public CatalogManager(StorageEngine storage) {
        this.storage = storage;
    }

    /** 从磁盘加载 Catalog（目录表不存在则初始化空目录）。 */
    public Catalog loadCatalog() {
        throw new UnsupportedOperationException("TODO: 实现 loadCatalog()");
    }

    /** 注册新表到目录并持久化。 */
    public void registerTable(String name, List<ColumnDef> columns) {
        throw new UnsupportedOperationException("TODO: 实现 registerTable()");
    }

    /** 按表名取表结构（供执行引擎使用）。 */
    public List<ColumnDef> getTableSchema(String name) {
        throw new UnsupportedOperationException("TODO: 实现 getTableSchema()");
    }
}
