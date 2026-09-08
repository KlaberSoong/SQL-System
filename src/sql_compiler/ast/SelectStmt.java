package sql_compiler.ast;

import java.util.List;

/**
 * SELECT 语句。selectItems 元素为 ColumnRef 或 Star；where 可为 null。
 */
public class SelectStmt implements Statement {
    private final List<Expr> selectItems;
    private final String table;
    private final Expr where; // 可为 null

    public SelectStmt(List<Expr> selectItems, String table, Expr where) {
        this.selectItems = selectItems;
        this.table = table;
        this.where = where;
    }

    public List<Expr> getSelectItems() {
        return selectItems;
    }

    public String getTable() {
        return table;
    }

    public Expr getWhere() {
        return where;
    }

    @Override
    public String toString() {
        return "Select(" + selectItems + ", " + table + ", " + where + ")";
    }
}
