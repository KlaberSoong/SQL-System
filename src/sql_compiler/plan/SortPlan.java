package sql_compiler.plan;

import sql_compiler.ast.OrderByItem;

import java.util.List;

/**
 * 排序算子：按 ORDER BY 键列表对子算子输出排序。
 */
public class SortPlan extends PlanNode {
    private final List<OrderByItem> keys;

    public SortPlan(List<OrderByItem> keys, PlanNode child) {
        this.keys = keys;
        if (child != null) {
            children.add(child);
        }
    }

    public List<OrderByItem> getKeys() {
        return keys;
    }

    @Override
    public String nodeName() {
        return "Sort[" + keys + "]";
    }
}
