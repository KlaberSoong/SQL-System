package sql_compiler.ast;

/**
 * 列引用；table 可为 null（单表查询省略表名）。
 */
public class ColumnRef extends Expr {
    private final String table; // 可为 null
    private final String column;

    public ColumnRef(String table, String column) {
        this.table = table;
        this.column = column;
    }

    public String getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }

    @Override
    public String toString() {
        return table == null ? column : table + "." + column;
    }
}
