package tests;

import engine.CatalogManager;
import engine.Executor;
import engine.QueryResult;
import engine.StorageEngine;
import sql_compiler.Catalog;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Literal;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Operator;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * 数据库引擎测试（对应 plan.md 第五章：存储引擎 + 执行引擎 + 目录持久化）。
 *
 * 覆盖：建表/插入/扫描、目录持久化（重启重载）、按条件删除、执行引擎五算子。
 */
public class EngineTest {

    private static final List<ColumnDef> STUDENT = Arrays.asList(
            new ColumnDef("id", ColumnType.INT),
            new ColumnDef("name", ColumnType.VARCHAR, 20),
            new ColumnDef("age", ColumnType.INT));

    public static int run() {
        Assert a = new Assert();
        File dir = TestFiles.tempDir("engine");
        try {
            // —— 建表 + 插入 + 扫描 ——
            StorageEngine storage = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm = new CatalogManager(storage);
            cm.loadCatalog(); // 初始化 pg_catalog
            a.checkEquals(true, storage.tableExists("pg_catalog"), "启动后目录表已创建");

            storage.createTable("student", STUDENT);
            cm.registerTable("student", STUDENT);
            storage.insertRow("student", Arrays.asList(1, "Alice", 20));
            storage.insertRow("student", Arrays.asList(2, "Bob", 17));

            List<List<Object>> rows = storage.scanTable("student");
            a.checkEquals(2, rows.size(), "扫描行数");
            a.checkEquals(Arrays.asList(1, "Alice", 20), rows.get(0), "首行内容");
            a.checkEquals(Arrays.asList(2, "Bob", 17), rows.get(1), "次行内容");

            // —— 持久化：新引擎重载目录 ——
            StorageEngine storage2 = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm2 = new CatalogManager(storage2);
            Catalog catalog2 = cm2.loadCatalog();
            a.checkEquals(true, catalog2.containsTable("student"), "重启后目录含 student");
            a.checkEquals(2, storage2.scanTable("student").size(), "重启后数据不丢");

            // —— 删除（有条件） ——
            int n = storage2.deleteRows("student",
                    new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)));
            a.checkEquals(1, n, "删除行数");
            a.checkEquals(Arrays.asList(Arrays.asList(2, "Bob", 17)), storage2.scanTable("student"), "删除后剩余 Bob");

            // —— 执行引擎：五算子 ——
            Executor exec = new Executor(storage2, cm2);
            a.checkContains(String.valueOf(exec.execute(new CreateTablePlan("t2", STUDENT))), "CREATE TABLE", "CreateTable 算子");
            exec.execute(new InsertPlan("t2", null, Arrays.asList(
                    new Literal(1, ColumnType.INT), new Literal("Alice", ColumnType.VARCHAR), new Literal(20, ColumnType.INT))));
            exec.execute(new InsertPlan("t2", null, Arrays.asList(
                    new Literal(2, ColumnType.INT), new Literal("Bob", ColumnType.VARCHAR), new Literal(17, ColumnType.INT))));

            QueryResult qr = (QueryResult) exec.execute(new SeqScanPlan("t2"));
            a.checkEquals(Arrays.asList("id", "name", "age"), qr.getColumns(), "SeqScan 列名");
            a.checkEquals(2, qr.getRows().size(), "SeqScan 行数");

            // Project -> Filter -> SeqScan
            PlanNode plan = new ProjectPlan(Arrays.asList(new ColumnRef(null, "id"), new ColumnRef(null, "name")),
                    new FilterPlan(new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)),
                            new SeqScanPlan("t2")));
            QueryResult pr = (QueryResult) exec.execute(plan);
            a.checkEquals(Arrays.asList("id", "name"), pr.getColumns(), "Project 列名");
            a.checkEquals(1, pr.getRows().size(), "Filter 行数");
            a.checkEquals(Arrays.asList(1, "Alice"), pr.getRows().get(0), "Filter 结果");

            // 投影算术：INT*INT 返回 Integer，INT/INT 返回 Float
            PlanNode arith = new ProjectPlan(Arrays.asList(
                    new BinaryExpr(Operator.MUL, new ColumnRef(null, "id"), new Literal(2, ColumnType.INT))),
                    new SeqScanPlan("t2"));
            QueryResult ar = (QueryResult) exec.execute(arith);
            a.checkEquals(2, ar.getRows().size(), "投影算术行数");
            a.checkEquals(true, ar.getRows().get(0).get(0) instanceof Integer, "INT*INT 结果为 Integer");
            a.checkEquals(2, ((Number) ar.getRows().get(0).get(0)).intValue(), "id=1 -> id*2 = 2");
            PlanNode divPlan = new ProjectPlan(Arrays.asList(
                    new BinaryExpr(Operator.DIV, new ColumnRef(null, "id"), new Literal(2, ColumnType.INT))),
                    new SeqScanPlan("t2"));
            QueryResult dr = (QueryResult) exec.execute(divPlan);
            a.checkEquals(true, dr.getRows().get(0).get(0) instanceof Float, "INT/INT 结果为 Float");

            // Delete 算子
            a.checkEquals("DELETE 1", String.valueOf(exec.execute(new DeletePlan("t2",
                    new Comparison(Operator.EQ, new ColumnRef(null, "id"), new Literal(1, ColumnType.INT))))), "Delete 算子");
            a.checkEquals(1, storage2.scanTable("t2").size(), "删除后 t2 剩 1 行");
        } finally {
            TestFiles.deleteRecursively(dir);
        }
        return a.summary("EngineTest 引擎");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
