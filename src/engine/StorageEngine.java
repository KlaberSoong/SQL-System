package engine;

import sql_compiler.ast.Expr;
import utils.ColumnDef;

import java.util.List;

/**
 * 存储引擎：实现记录（Row）与页（Page）之间的映射、序列化，管理空闲页与表扩展。
 */
public class StorageEngine {
    /** 建表（创建数据文件 / 分配页 / 记录结构）。 */
    public void createTable(String name, List<ColumnDef> columns) {
        throw new UnsupportedOperationException("TODO: 实现 createTable()");
    }

    /** 插入一行（Row -> 字节 -> 页）。 */
    public void insertRow(String table, List<Object> values) {
        throw new UnsupportedOperationException("TODO: 实现 insertRow()");
    }

    /** 全表扫描，返回所有行（每行是值列表）。 */
    public List<List<Object>> scanTable(String table) {
        throw new UnsupportedOperationException("TODO: 实现 scanTable()");
    }

    /** 按条件删除行，返回删除行数。 */
    public int deleteRows(String table, Expr condition) {
        throw new UnsupportedOperationException("TODO: 实现 deleteRows()");
    }
}
