package sql_compiler.plan;

import sql_compiler.ast.Expr;
import utils.JoinType;

/**
 * 连接算子：左右两个子算子按条件连接（INNER 或 LEFT）。
 */
public class JoinPlan extends PlanNode {
    private final JoinType type;
    private final Expr condition; // 可为 null（交叉连接）

    public JoinPlan(PlanNode left, PlanNode right, JoinType type, Expr condition) {
        this.type = type;
        this.condition = condition;
        if (left != null) {
            children.add(left);
        }
        if (right != null) {
            children.add(right);
        }
    }

    public PlanNode getLeft() {
        return children.get(0);
    }

    public PlanNode getRight() {
        return children.get(1);
    }

    public JoinType getType() {
        return type;
    }

    public Expr getCondition() {
        return condition;
    }

    @Override
    public String nodeName() {
        return "Join[" + type + ", " + condition + "]";
    }
}
