package sql_compiler.ast;

import java.util.List;

/**
 * INSERT INTO 语句；columns 为 null 表示省略列名列表（值按建表顺序对齐）。
 */
public class InsertStmt implements Statement {
    private final String table;
    private final List<String> columns; // 可为 null
    private final List<Expr> values;

    public InsertStmt(String table, List<String> columns, List<Expr> values) {
        this.table = table;
        this.columns = columns;
        this.values = values;
    }

    public String getTable() {
        return table;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<Expr> getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Insert(" + table + ", " + columns + ", " + values + ")";
    }
}
