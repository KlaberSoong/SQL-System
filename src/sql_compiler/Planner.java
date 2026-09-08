package sql_compiler;

import sql_compiler.ast.Statement;
import sql_compiler.plan.PlanNode;

/**
 * 执行计划生成器：把 AST 转换为逻辑执行计划（算子树）。
 */
public class Planner {
    /**
     * 计划生成主入口。转换规则：
     *   CreateTableStmt -> CreateTablePlan
     *   InsertStmt      -> InsertPlan
     *   SelectStmt      -> ProjectPlan -> FilterPlan(有 WHERE 时) -> SeqScanPlan
     *   DeleteStmt      -> DeletePlan
     */
    public PlanNode plan(Statement stmt) {
        throw new UnsupportedOperationException("TODO: 实现 plan()");
    }
}
