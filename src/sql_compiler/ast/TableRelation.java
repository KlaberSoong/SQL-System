package sql_compiler.ast;

/**
 * FROM 子句中的单表引用；alias 可为 null（未起别名）。
 */
public class TableRelation extends Relation {
    private final String table;
    private final String alias; // 可为 null

    public TableRelation(String table, String alias) {
        this.table = table;
        this.alias = alias;
    }

    public String getTable() {
        return table;
    }

    public String getAlias() {
        return alias;
    }

    @Override
    public String toString() {
        return alias == null ? table : table + " AS " + alias;
    }
}
