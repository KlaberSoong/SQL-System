package sql_compiler;

import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Statement;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;

/**
 * 执行计划生成器（对应 plan.md 3.5）：把 AST 转换为逻辑执行计划（算子树）。
 * 转换规则：CreateTableStmt→CreateTablePlan、InsertStmt→InsertPlan、
 * SelectStmt→Project→[Filter]→SeqScan、DeleteStmt→DeletePlan。
 * SELECT * 的 Project 投影表达式为单个 Star，由执行引擎结合 schema 展开。
 */
public class Planner {

    // 单条语句 -> 逻辑计划根节点
    public PlanNode plan(Statement stmt) {
        if (stmt instanceof CreateTableStmt) {
            CreateTableStmt c = (CreateTableStmt) stmt;
            return new CreateTablePlan(c.getTable(), c.getColumns());
        }
        if (stmt instanceof InsertStmt) {
            InsertStmt i = (InsertStmt) stmt;
            return new InsertPlan(i.getTable(), i.getColumns(), i.getValues());
        }
        if (stmt instanceof SelectStmt) {
            return planSelect((SelectStmt) stmt);
        }
        if (stmt instanceof DeleteStmt) {
            DeleteStmt d = (DeleteStmt) stmt;
            return new DeletePlan(d.getTable(), d.getWhere());
        }
        throw new IllegalArgumentException("cannot plan unknown statement: " + stmt.getClass().getSimpleName());
    }

    // SELECT 计划：自底向上构造 SeqScan -> (Filter) -> Project
    private PlanNode planSelect(SelectStmt sel) {
        PlanNode node = new SeqScanPlan(sel.getTable());
        if (sel.getWhere() != null) {
            node = new FilterPlan(sel.getWhere(), node);
        }
        node = new ProjectPlan(sel.getSelectItems(), node);
        return node;
    }
}
