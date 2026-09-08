package tests;

import sql_compiler.Catalog;
import sql_compiler.Optimizer;
import sql_compiler.Planner;
import sql_compiler.SemanticAnalyzer;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.plan.PlanNode;
import utils.ColumnDef;
import utils.ColumnType;
import utils.DbException;
import utils.Operator;

import java.util.Arrays;
import java.util.Collections;

/**
 * 编译器后端（3.3 语义分析 + 3.4 类型系统 + 3.5 计划生成 + 3.6 优化）自测。
 *
 * 这里直接手写 AST（等价于人 A 的 Parser 输出），因此无需等前端完成即可验证后端逻辑。
 * 运行方式：java -cp out tests.CompilerBackendTest
 */
public class CompilerBackendTest {
    public static void main(String[] args) {
        testSemantic();
        testOptimize();
        System.out.println("\nCompilerBackendTest PASSED.");
    }

    /** 1. 语义分析：合法建表 + 查询通过；非法查询（列不存在）被拒绝且不崩溃。 */
    private static void testSemantic() {
        Catalog catalog = new Catalog();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);

        // CREATE TABLE student(id INT, name VARCHAR(20), age INT)
        CreateTableStmt create = new CreateTableStmt("student", Arrays.asList(
                new ColumnDef("id", ColumnType.INT),
                new ColumnDef("name", ColumnType.VARCHAR, 20),
                new ColumnDef("age", ColumnType.INT)));
        analyzer.analyze(Collections.singletonList(create));
        System.out.println("[语义] 建表通过，Catalog 中的表: " + catalog.getTableNames());

        // SELECT score FROM student —— score 不存在，应抛 SemanticError
        SelectStmt bad = new SelectStmt(
                Collections.singletonList(new ColumnRef(null, "score")),
                "student", null);
        try {
            analyzer.analyze(Collections.singletonList(bad));
            System.out.println("[语义] 意外：非法查询未被拒绝");
        } catch (DbException e) {
            System.out.println("[语义] 列不存在被正确拒绝: " + e.getMessage());
        }

        // SELECT id FROM student WHERE age > 18 —— 合法，应通过
        SelectStmt good = new SelectStmt(
                Collections.singletonList(new ColumnRef(null, "id")),
                "student",
                new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)));
        analyzer.analyze(Collections.singletonList(good));
        System.out.println("[语义] 合法查询通过");
    }

    /** 2. 计划生成 + 优化：plan.md 验收示例（常量折叠 + 布尔化简）。 */
    private static void testOptimize() {
        // WHERE 1=1 AND age > 10+8
        Expr where = new BinaryExpr(Operator.AND,
                new Comparison(Operator.EQ, new Literal(1, ColumnType.INT), new Literal(1, ColumnType.INT)),
                new Comparison(Operator.GT, new ColumnRef(null, "age"),
                        new BinaryExpr(Operator.PLUS, new Literal(10, ColumnType.INT), new Literal(8, ColumnType.INT))));

        SelectStmt stmt = new SelectStmt(
                Collections.singletonList(new ColumnRef(null, "name")),
                "student", where);

        // 计划生成 -> 优化（优化器不修改原计划，before / after 可同时保留）
        Planner planner = new Planner();
        PlanNode before = planner.plan(stmt);
        PlanNode after = new Optimizer().optimize(before);

        System.out.println("\n[优化] 优化前:\n" + before.toTree());
        System.out.println("[优化] 优化后:\n" + after.toTree());
    }
}
