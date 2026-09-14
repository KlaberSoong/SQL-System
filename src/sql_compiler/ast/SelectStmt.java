package sql_compiler.ast;

import java.util.List;

/**
 * SELECT 语句；from 为 FROM 关系（单表或连接），groupBy / orderBy 可为 null。
 */
public class SelectStmt implements Statement {
    private final List<Expr> selectItems;
    private final Relation from;
    private final Expr where;             // 可为 null
    private final List<Expr> groupBy;     // 可为 null
    private final List<OrderByItem> orderBy; // 可为 null

    public SelectStmt(List<Expr> selectItems, Relation from, Expr where,
                      List<Expr> groupBy, List<OrderByItem> orderBy) {
        this.selectItems = selectItems;
        this.from = from;
        this.where = where;
        this.groupBy = groupBy;
        this.orderBy = orderBy;
    }

    // 兼容单表构造器（无 GROUP BY / ORDER BY）
    public SelectStmt(List<Expr> selectItems, String table, Expr where) {
        this(selectItems, new TableRelation(table, null), where, null, null);
    }

    public List<Expr> getSelectItems() {
        return selectItems;
    }

    public Relation getFrom() {
        return from;
    }

    public Expr getWhere() {
        return where;
    }

    public List<Expr> getGroupBy() {
        return groupBy;
    }

    public List<OrderByItem> getOrderBy() {
        return orderBy;
    }

    // 便捷：FROM 为单表时返回表名，否则返回 null
    public String getTable() {
        return from instanceof TableRelation ? ((TableRelation) from).getTable() : null;
    }

    @Override
    public String toString() {
        return "Select(" + selectItems + ", " + from + ", " + where + ")";
    }
}
