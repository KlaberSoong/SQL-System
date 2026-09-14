package sql_compiler.ast;

/**
 * ORDER BY 的一个排序键：表达式 + 升/降序。
 */
public class OrderByItem {
    private final Expr expr;
    private final boolean asc;

    public OrderByItem(Expr expr, boolean asc) {
        this.expr = expr;
        this.asc = asc;
    }

    public Expr getExpr() {
        return expr;
    }

    public boolean isAsc() {
        return asc;
    }

    @Override
    public String toString() {
        return expr + (asc ? " ASC" : " DESC");
    }
}
