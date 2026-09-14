package sql_compiler.plan;

import sql_compiler.ast.Expr;

import java.util.List;

/**
 * 分组聚合算子：按 groupBy（可空=全局聚合）对子算子输出分组，
 * 对每个分组按 selectItems 求值（聚合调用或分组键），直接产出最终结果行。
 */
public class AggregatePlan extends PlanNode {
    private final List<Expr> selectItems;
    private final List<Expr> groupBy; // 可为 null

    public AggregatePlan(List<Expr> selectItems, List<Expr> groupBy, PlanNode child) {
        this.selectItems = selectItems;
        this.groupBy = groupBy;
        if (child != null) {
            children.add(child);
        }
    }

    public List<Expr> getSelectItems() {
        return selectItems;
    }

    public List<Expr> getGroupBy() {
        return groupBy;
    }

    @Override
    public String nodeName() {
        return "Aggregate[groupBy=" + groupBy + "]";
    }
}
