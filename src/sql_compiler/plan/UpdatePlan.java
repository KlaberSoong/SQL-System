package sql_compiler.plan;

import sql_compiler.ast.Assignment;
import sql_compiler.ast.Expr;

import java.util.List;

/**
 * 更新算子；condition 为 null 表示更新全表。
 */
public class UpdatePlan extends PlanNode {
    private final String table;
    private final List<Assignment> assignments;
    private final Expr condition; // 可为 null

    public UpdatePlan(String table, List<Assignment> assignments, Expr condition) {
        this.table = table;
        this.assignments = assignments;
        this.condition = condition;
    }

    public String getTable() {
        return table;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public Expr getCondition() {
        return condition;
    }

    @Override
    public String nodeName() {
        return "Update[" + table + ", " + condition + "]";
    }
}
