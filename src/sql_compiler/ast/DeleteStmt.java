package sql_compiler.ast;

/**
 * DELETE FROM 语句；where 可为 null（表示删除全表）。
 */
public class DeleteStmt implements Statement {
    private final String table;
    private final Expr where; // 可为 null

    public DeleteStmt(String table, Expr where) {
        this.table = table;
        this.where = where;
    }

    public String getTable() {
        return table;
    }

    public Expr getWhere() {
        return where;
    }

    @Override
    public String toString() {
        return "Delete(" + table + ", " + where + ")";
    }
}
