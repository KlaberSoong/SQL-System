package sql_compiler.plan;

import java.util.List;

/**
 * 投影算子：对子算子输出的记录按 SELECT 列投影。
 */
public class ProjectPlan extends PlanNode {
    private final List<String> columns;

    public ProjectPlan(List<String> columns, PlanNode child) {
        this.columns = columns;
        if (child != null) {
            children.add(child);
        }
    }

    public List<String> getColumns() {
        return columns;
    }

    @Override
    public String nodeName() {
        return "Project[" + columns + "]";
    }
}
