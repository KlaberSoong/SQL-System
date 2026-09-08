package sql_compiler.plan;

/**
 * 全表顺序扫描算子。
 */
public class SeqScanPlan extends PlanNode {
    private final String table;

    public SeqScanPlan(String table) {
        this.table = table;
    }

    public String getTable() {
        return table;
    }

    @Override
    public String nodeName() {
        return "SeqScan[" + table + "]";
    }
}
