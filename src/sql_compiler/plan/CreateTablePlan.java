package sql_compiler.plan;

import utils.ColumnDef;

import java.util.List;

/**
 * 建表算子。
 */
public class CreateTablePlan extends PlanNode {
    private final String table;
    private final List<ColumnDef> columns;

    public CreateTablePlan(String table, List<ColumnDef> columns) {
        this.table = table;
        this.columns = columns;
    }

    public String getTable() {
        return table;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    @Override
    public String nodeName() {
        return "CreateTable[" + table + ", " + columns + "]";
    }
}
