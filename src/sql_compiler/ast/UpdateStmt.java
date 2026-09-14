package sql_compiler.ast;

import java.util.List;

/**
 * UPDATE 语句：表名 + SET 赋值列表 + 可选 WHERE。
 */
public class UpdateStmt implements Statement {
    private final String table;
    private final List<Assignment> assignments;
    private final Expr where; // 可为 null

    public UpdateStmt(String table, List<Assignment> assignments, Expr where) {
        this.table = table;
        this.assignments = assignments;
        this.where = where;
    }

    public String getTable() {
        return table;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public Expr getWhere() {
        return where;
    }

    @Override
    public String toString() {
        return "Update(" + table + ", " + assignments + ", " + where + ")";
    }
}
