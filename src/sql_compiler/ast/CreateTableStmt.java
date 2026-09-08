package sql_compiler.ast;

import utils.ColumnDef;

import java.util.List;

/**
 * CREATE TABLE 语句。
 */
public class CreateTableStmt implements Statement {
    private final String table;
    private final List<ColumnDef> columns;

    public CreateTableStmt(String table, List<ColumnDef> columns) {
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
    public String toString() {
        return "CreateTable(" + table + ", " + columns + ")";
    }
}
