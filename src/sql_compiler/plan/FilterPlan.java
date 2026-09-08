package sql_compiler.plan;

import sql_compiler.ast.Expr;

/**
 * 过滤算子：对子算子输出的记录按 WHERE 条件过滤。
 */
public class FilterPlan extends PlanNode {
    private final Expr condition;

    public FilterPlan(Expr condition, PlanNode child) {
        this.condition = condition;
        if (child != null) {
            children.add(child);
        }
    }

    public Expr getCondition() {
        return condition;
    }

    @Override
    public String nodeName() {
        return "Filter[" + condition + "]";
    }
}
