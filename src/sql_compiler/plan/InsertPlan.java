package sql_compiler.plan;

import sql_compiler.ast.Expr;

import java.util.List;

/**
 * 插入算子；columns 为 null 表示按建表顺序插入。
 */
public class InsertPlan extends PlanNode {
    private final String table;
    private final List<String> columns; // 可为 null
    private final List<Expr> values;

    public InsertPlan(String table, List<String> columns, List<Expr> values) {
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
    public String nodeName() {
        return "Insert[" + table + "]";
    }
}
