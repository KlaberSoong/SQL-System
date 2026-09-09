package tests;

import sql_compiler.Optimizer;
import sql_compiler.Planner;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import utils.ColumnType;
import utils.Operator;

import java.util.Collections;

/**
 * 计划生成 + 规则式优化测试（对应 plan.md 3.5 / 3.6）。
 *
 * 覆盖：五算子映射、计划形状、五条优化规则（常量折叠/布尔化简/投影剪枝/谓词下推/冗余消除）、
 * 优化前后结构对比。
 */
public class PlannerOptimizerTest {

    public static int run() {
        Assert a = new Assert();
        Planner planner = new Planner();
        Optimizer optimizer = new Optimizer();

        // —— 计划形状：Project -> Filter -> SeqScan ——
        SelectStmt sel = new SelectStmt(Collections.singletonList(new ColumnRef(null, "name")),
                "student", new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)));
        PlanNode plan = planner.plan(sel);
        String tree = plan.toTree();
        a.checkEquals(true, plan instanceof ProjectPlan, "SELECT 顶层为 Project");
        a.checkContains(tree, "Project[[name]]", "Project 节点");
        a.checkContains(tree, "Filter[(age > 18)]", "Filter 节点");
        a.checkContains(tree, "SeqScan[student]", "SeqScan 节点");
        a.checkEquals(true,
                tree.indexOf("Project") < tree.indexOf("Filter") && tree.indexOf("Filter") < tree.indexOf("SeqScan"),
                "计划顺序 Project->Filter->SeqScan");

        // SELECT * 哨兵值
        PlanNode star = planner.plan(new SelectStmt(Collections.singletonList(new Star()), "student", null));
        a.checkEquals(true, star instanceof ProjectPlan, "SELECT * 顶层为 Project");
        a.checkContains(star.toTree(), "Project[[*]]", "SELECT * 哨兵值");

        // 无条件 SELECT -> Project -> SeqScan（无 Filter）
        PlanNode noWhere = planner.plan(new SelectStmt(Collections.singletonList(new ColumnRef(null, "id")), "student", null));
        a.checkEquals(false, noWhere.toTree().contains("Filter"), "无条件无 Filter");

        // —— 验收示例：1=1 AND age>10+8 ——
        Expr where = new BinaryExpr(Operator.AND,
                new Comparison(Operator.EQ, new Literal(1, ColumnType.INT), new Literal(1, ColumnType.INT)),
                new Comparison(Operator.GT, new ColumnRef(null, "age"),
                        new BinaryExpr(Operator.PLUS, new Literal(10, ColumnType.INT), new Literal(8, ColumnType.INT))));
        PlanNode before = planner.plan(new SelectStmt(Collections.singletonList(new ColumnRef(null, "name")), "student", where));
        PlanNode after = optimizer.optimize(before);
        a.checkContains(before.toTree(), "1 = 1", "优化前含 1=1");
        a.checkContains(before.toTree(), "10 + 8", "优化前含 10+8");
        a.checkContains(after.toTree(), "Filter[(age > 18)]", "优化后常量折叠 age>18");
        a.checkNotContains(after.toTree(), "1 = 1", "优化后消除 1=1");
        a.checkNotContains(after.toTree(), "10 + 8", "优化后消除 10+8");
        a.checkContains(after.toTree(), "Project[[name]]", "优化后保留 Project");
        a.checkContains(after.toTree(), "SeqScan[student]", "优化后保留 SeqScan");

        // —— 冗余消除：Filter[true] ——
        PlanNode red = optimizer.optimize(new FilterPlan(new Literal(true, ColumnType.BOOL), new SeqScanPlan("student")));
        a.checkEquals("SeqScan[student]", red.toTree(), "Filter[true] 被消除");

        // —— 投影剪枝：Project["*"] ——
        PlanNode pruned = optimizer.optimize(new ProjectPlan(Collections.singletonList("*"), new SeqScanPlan("student")));
        a.checkEquals("SeqScan[student]", pruned.toTree(), "Project[*] 被剪枝");

        // —— 谓词下推：Filter -> Project -> SeqScan 变为 Project -> Filter -> SeqScan ——
        PlanNode pushed = new FilterPlan(
                new Comparison(Operator.EQ, new ColumnRef(null, "name"), new Literal("Alice", ColumnType.VARCHAR)),
                new ProjectPlan(Collections.singletonList("name"), new SeqScanPlan("student")));
        PlanNode pushedAfter = optimizer.optimize(pushed);
        a.checkEquals(true, pushed.toTree().indexOf("Filter") < pushed.toTree().indexOf("Project"), "优化前 Filter 在 Project 上");
        a.checkEquals(true, pushedAfter.toTree().indexOf("Project") < pushedAfter.toTree().indexOf("Filter"), "优化后 Project 在 Filter 上");

        return a.summary("PlannerOptimizerTest 计划与优化");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
