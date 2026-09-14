package tests;

import cli.Main;
import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;

import java.io.File;

/**
 * 高级 SQL 特性端到端测试：UPDATE / ORDER BY / GROUP BY(聚合) / JOIN(INNER+LEFT+交叉)。
 *
 * 复用 {@link Main#executeAndFormat} 走完整流水线，断言文本输出，覆盖每个特性的关键场景
 * 与边界（聚合+分组、ORDER BY 非投影列、LEFT JOIN 未匹配补 NULL 等）。
 */
public class AdvancedSqlTest {

    public static int run() {
        Assert a = new Assert();
        File dir = TestFiles.tempDir("advanced");
        try {
            StorageEngine storage = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm = new CatalogManager(storage);
            Catalog catalog = cm.loadCatalog();

            exec(a, storage, cm, catalog, "CREATE TABLE emp(id INT, name VARCHAR(20), age INT, dept VARCHAR(10), dept_id INT);",
                    "CREATE TABLE 'emp'", "建表 emp");
            exec(a, storage, cm, catalog, "CREATE TABLE dept(id INT, dept_name VARCHAR(20));",
                    "CREATE TABLE 'dept'", "建表 dept");

            String[] inserts = {
                    "INSERT INTO emp VALUES (1,'Alice',20,'RD',1);",
                    "INSERT INTO emp VALUES (2,'Bob',17,'QA',2);",
                    "INSERT INTO emp VALUES (3,'Carol',25,'RD',1);",
                    "INSERT INTO emp VALUES (4,'Dave',30,'QA',2);",
                    "INSERT INTO emp VALUES (5,'Eve',28,'QA',9);",
                    "INSERT INTO dept VALUES (1,'Research');",
                    "INSERT INTO dept VALUES (2,'Quality');",
            };
            for (String sql : inserts) {
                a.checkContains(run(storage, cm, catalog, sql), "INSERT 1", "插入 " + sql);
            }

            // —— UPDATE ——
            String upd = run(storage, cm, catalog, "UPDATE emp SET age = age + 1 WHERE dept = 'RD';");
            a.checkContains(upd, "UPDATE 2", "UPDATE 命中 2 行（Alice/Carol）");

            // 更新后按 age 升序查询验证增量生效
            String afterUpd = run(storage, cm, catalog, "SELECT name, age FROM emp WHERE id = 1;");
            a.checkContains(afterUpd, "'Alice'", "更新后 Alice 仍在");
            a.checkContains(afterUpd, "21", "Alice age 20->21");

            // —— ORDER BY（投影列 + 非投影列） ——
            String byAge = run(storage, cm, catalog, "SELECT name FROM emp ORDER BY age DESC;");
            int dave = byAge.indexOf("'Dave'");
            int bob = byAge.indexOf("'Bob'");
            a.check(dave >= 0 && bob >= 0 && dave < bob, "ORDER BY age DESC：Dave 在 Bob 前");

            String byAsc = run(storage, cm, catalog, "SELECT name, age FROM emp ORDER BY age ASC;");
            int alice = byAsc.indexOf("'Alice'");
            int daveAsc = byAsc.indexOf("'Dave'");
            a.check(alice >= 0 && daveAsc >= 0 && alice < daveAsc, "ORDER BY age ASC：Alice 在 Dave 前");

            // —— GROUP BY + COUNT ——
            String group = run(storage, cm, catalog, "SELECT dept, COUNT(*) FROM emp GROUP BY dept;");
            a.checkContains(group, "'RD' | 2", "RD 部门计数 2");
            a.checkContains(group, "'QA' | 3", "QA 部门计数 3");
            a.checkContains(group, "2 rows", "GROUP BY 产生 2 组");

            // —— 全局聚合（无 GROUP BY） ——
            String cnt = run(storage, cm, catalog, "SELECT COUNT(*) FROM emp;");
            a.checkContains(cnt, "count(*)", "全局 COUNT(*) 列名");
            a.checkContains(cnt, "5", "全局行数 5");
            a.checkContains(cnt, "(1 row)", "全局聚合单行");

            // —— SUM/AVG/MIN/MAX ——
            String agg = run(storage, cm, catalog, "SELECT SUM(age), AVG(age), MIN(age), MAX(age) FROM emp;");
            a.checkContains(agg, "122", "SUM(age)=122");
            a.checkContains(agg, "24.4", "AVG(age)=24.4");
            a.checkContains(agg, "17", "MIN(age)=17");
            a.checkContains(agg, "30", "MAX(age)=30");

            // —— LEFT JOIN：未匹配右表补 NULL ——
            String left = run(storage, cm, catalog,
                    "SELECT a.name, b.dept_name FROM emp a LEFT JOIN dept b ON a.dept_id = b.id;");
            a.checkContains(left, "'Alice'", "LEFT JOIN 含 Alice");
            a.checkContains(left, "'Eve'", "LEFT JOIN 含未匹配的 Eve");
            a.checkContains(left, "NULL", "LEFT JOIN 未匹配补 NULL");
            a.checkContains(left, "5 rows", "LEFT JOIN 返回 5 行");

            // —— INNER JOIN：未匹配行被剔除 ——
            String inner = run(storage, cm, catalog,
                    "SELECT a.name, b.dept_name FROM emp a INNER JOIN dept b ON a.dept_id = b.id;");
            a.checkContains(inner, "'Alice'", "INNER JOIN 含 Alice");
            a.checkNotContains(inner, "'Eve'", "INNER JOIN 剔除 Eve");
            a.checkNotContains(inner, "NULL", "INNER JOIN 无 NULL");
            a.checkContains(inner, "4 rows", "INNER JOIN 返回 4 行");

            // —— 交叉连接（逗号） ——
            String cross = run(storage, cm, catalog, "SELECT e.name, d.dept_name FROM emp e, dept d;");
            a.checkContains(cross, "10 rows", "交叉连接 5*2=10 行");

            // —— 别名 + 限定列 + WHERE ——
            String alias = run(storage, cm, catalog,
                    "SELECT e.name FROM emp e WHERE e.age > 20;");
            a.checkContains(alias, "'Dave'", "别名+限定列 WHERE 命中 Dave");
            a.checkNotContains(alias, "'Bob'", "别名+限定列 WHERE 不含 Bob");

            // —— 错误样例：聚合混排非分组列应报语义错误 ——
            a.checkContains(run(storage, cm, catalog, "SELECT dept, name FROM emp GROUP BY dept;"),
                    "SemanticError", "GROUP BY 下非分组列报错");

        } finally {
            TestFiles.deleteRecursively(dir);
        }
        return a.summary("AdvancedSqlTest 高级特性");
    }

    private static void exec(Assert a, StorageEngine storage, CatalogManager cm,
                             Catalog catalog, String sql, String expect, String name) {
        a.checkContains(run(storage, cm, catalog, sql), expect, name);
    }

    private static String run(StorageEngine storage, CatalogManager cm, Catalog catalog, String sql) {
        return Main.executeAndFormat(sql, storage, cm, catalog);
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
