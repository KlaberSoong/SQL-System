package sql_compiler.plan;

import sql_compiler.ast.Expr;

/**
 * 删除算子。condition 为 null 表示删除全表。
 * 执行引擎实现时可按「扫描 + 过滤 + 标记删除」落地。
 */
public class DeletePlan extends PlanNode {
    private final String table;
    private final Expr condition; // 可为 null

    public DeletePlan(String table, Expr condition) {
        this.table = table;
        this.condition = condition;
    }

    public String getTable() {
        return table;
    }

    public Expr getCondition() {
        return condition;
    }

    @Override
    public String nodeName() {
        return "Delete[" + table + ", " + condition + "]";
    }
}
