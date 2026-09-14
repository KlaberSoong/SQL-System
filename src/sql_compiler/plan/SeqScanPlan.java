package sql_compiler.plan;

/**
 * 全表顺序扫描算子；alias 非空时输出列为 "alias.column" 形式（用于连接消歧）。
 */
public class SeqScanPlan extends PlanNode {
    private final String table;
    private final String alias; // 可为 null

    public SeqScanPlan(String table) {
        this(table, null);
    }

    public SeqScanPlan(String table, String alias) {
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
    public String nodeName() {
        return alias == null ? "SeqScan[" + table + "]" : "SeqScan[" + table + " AS " + alias + "]";
    }
}
