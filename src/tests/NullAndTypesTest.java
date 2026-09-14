package tests;

import cli.Main;
import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;

import java.io.File;

/**
 * NULL（三值逻辑）与新数据类型（DATE / DECIMAL(p,s) / CHAR(n) / TEXT）端到端测试。
 *
 * 复用 {@link Main#executeAndFormat} 走完整流水线，断言文本输出，重点覆盖：
 * 字符串 {@code 'NULL'} 与 SQL NULL、空串 {@code ''} 的区分；三值逻辑；算术/聚合的 NULL
 * 传播；新类型的插入回读、比较、排序、CHAR 补空格/截断；以及非法输入的错误样例。
 */
public class NullAndTypesTest {

    public static int run() {
        Assert a = new Assert();
        File dir = TestFiles.tempDir("nulltypes");
        try {
            StorageEngine storage = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm = new CatalogManager(storage);
            Catalog catalog = cm.loadCatalog();

            // —— 建表：含全部新类型 ——
            exec(a, storage, cm, catalog,
                    "CREATE TABLE t(id INT, name VARCHAR(20), d DATE, price DECIMAL(10,2), code CHAR(4), note TEXT);",
                    "CREATE TABLE 't'", "建表 t");

            // —— 插入：SQL NULL / 字符串 'NULL' / 空串 '' / 类型化字面量 ——
            exec(a, storage, cm, catalog,
                    "INSERT INTO t VALUES (1,'Alice',DATE '2024-01-01',DECIMAL '19.99','A','hello world');",
                    "INSERT 1", "插入 Alice");
            exec(a, storage, cm, catalog,
                    "INSERT INTO t VALUES (2,'NULL',NULL,NULL,NULL,NULL);",
                    "INSERT 1", "插入字符串 NULL + 真 NULL");
            exec(a, storage, cm, catalog,
                    "INSERT INTO t VALUES (3,'',DATE '2024-03-15',DECIMAL '5.50','XY','');",
                    "INSERT 1", "插入空串 '' + 第二组日期/小数");

            // —— NULL 回读：真 NULL 显示为 NULL，字符串 'NULL' 带引号 ——
            String all = run(storage, cm, catalog, "SELECT id, name, d, price FROM t;");
            a.checkContains(all, "'NULL'", "字符串 'NULL' 带引号显示");
            a.checkContains(all, "NULL", "真 NULL 显示为 NULL");

            // —— 字符串 'NULL' vs SQL NULL vs 空串 '' 三者区分 ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE name = 'NULL';"),
                    "\n2\n", "name='NULL' 命中字符串 NULL（id=2）");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE name = '';"),
                    "\n3\n", "name='' 命中空串（id=3）");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE name IS NULL;"),
                    "(0 row", "name IS NULL 无命中（name 全非空）");

            // —— IS NULL / IS NOT NULL ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d IS NULL;"),
                    "\n2\n", "d IS NULL 命中 id=2");
            String notNull = run(storage, cm, catalog, "SELECT id FROM t WHERE d IS NOT NULL;");
            a.checkContains(notNull, "\n1\n", "d IS NOT NULL 含 id=1");
            a.checkContains(notNull, "\n3\n", "d IS NOT NULL 含 id=3");
            a.checkNotContains(notNull, "\n2\n", "d IS NOT NULL 不含 id=2");

            // —— WHERE x = NULL 恒 UNKNOWN（空结果） ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d = NULL;"),
                    "(0 row", "d = NULL 空结果（UNKNOWN）");

            // —— 三值逻辑：NULL OR TRUE = TRUE；NULL AND TRUE = UNKNOWN ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d = NULL OR id = 1;"),
                    "\n1\n", "NULL OR TRUE 命中 id=1");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d = NULL AND id = 1;"),
                    "(0 row", "NULL AND TRUE 空结果");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE NOT (d = NULL);"),
                    "(0 row", "NOT(UNKNOWN) 仍为 UNKNOWN");

            // —— 算术 NULL 传播 ——
            a.checkContains(run(storage, cm, catalog, "SELECT price + 1 FROM t WHERE id = 2;"),
                    "NULL", "NULL 参与算术传播为 NULL");

            // —— 聚合：COUNT(*) vs COUNT(col)；SUM 跳 NULL；SUM 全空返回 NULL ——
            a.checkContains(run(storage, cm, catalog, "SELECT COUNT(*) FROM t;"),
                    "\n3\n", "COUNT(*) = 3");
            a.checkContains(run(storage, cm, catalog, "SELECT COUNT(d) FROM t;"),
                    "\n2\n", "COUNT(d) 跳 NULL = 2");
            a.checkContains(run(storage, cm, catalog, "SELECT SUM(price) FROM t;"),
                    "25.49", "SUM(DECIMAL) = 25.49");

            exec(a, storage, cm, catalog, "CREATE TABLE n(v INT);", "CREATE TABLE 'n'", "建表 n");
            exec(a, storage, cm, catalog, "INSERT INTO n VALUES (NULL);", "INSERT 1", "插入全空表 n");
            a.checkContains(run(storage, cm, catalog, "SELECT SUM(v) FROM n;"),
                    "NULL", "SUM 全空组返回 NULL");
            a.checkContains(run(storage, cm, catalog, "SELECT COUNT(v) FROM n;"),
                    "\n0\n", "COUNT(v) 全空 = 0");

            // —— LEFT JOIN 补空 + IS NULL 命中 ——
            exec(a, storage, cm, catalog, "CREATE TABLE a(id INT);", "CREATE TABLE 'a'", "建表 a");
            exec(a, storage, cm, catalog, "CREATE TABLE b(id INT);", "CREATE TABLE 'b'", "建表 b");
            exec(a, storage, cm, catalog, "INSERT INTO a VALUES (1);", "INSERT 1", "a 插入 1");
            exec(a, storage, cm, catalog, "INSERT INTO a VALUES (2);", "INSERT 1", "a 插入 2");
            exec(a, storage, cm, catalog, "INSERT INTO b VALUES (1);", "INSERT 1", "b 插入 1");
            String lj = run(storage, cm, catalog,
                    "SELECT a.id FROM a LEFT JOIN b ON a.id = b.id WHERE b.id IS NULL;");
            a.checkContains(lj, "\n2\n", "LEFT JOIN 未匹配行 b.id IS NULL 命中 id=2");
            a.checkNotContains(lj, "\n1\n", "LEFT JOIN 已匹配行被 IS NULL 过滤");

            // —— DATE 比较与排序 ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d = DATE '2024-01-01';"),
                    "\n1\n", "DATE 精确比较命中 id=1");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d > DATE '2024-01-15';"),
                    "\n3\n", "DATE 大于比较命中 id=3");
            String byDate = run(storage, cm, catalog, "SELECT id FROM t ORDER BY d DESC;");
            int p3 = byDate.indexOf("\n3\n");
            int p1 = byDate.indexOf("\n1\n");
            int p2 = byDate.indexOf("\n2\n");
            a.check(p3 >= 0 && p1 >= 0 && p2 >= 0 && p3 < p1 && p1 < p2,
                    "ORDER BY d DESC：id3 在 id1 前，NULL(id2) 最小排最后");

            // —— DECIMAL 精确比较 ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE price = DECIMAL '19.99';"),
                    "\n1\n", "DECIMAL 精确比较命中 id=1");

            // —— CHAR 补空格 / 截断 ——
            a.checkContains(run(storage, cm, catalog, "SELECT code FROM t WHERE id = 1;"),
                    "'A   '", "CHAR(4) 补空格为 'A   '");
            exec(a, storage, cm, catalog,
                    "INSERT INTO t VALUES (4,'X',DATE '2024-01-01',DECIMAL '1.00','ABCDE','x');",
                    "INSERT 1", "插入 CHAR 超长值 ABCDE");
            a.checkContains(run(storage, cm, catalog, "SELECT code FROM t WHERE id = 4;"),
                    "'ABCD'", "CHAR(4) 超长截断为 'ABCD'");

            // —— TEXT 长文本回读 ——
            a.checkContains(run(storage, cm, catalog, "SELECT note FROM t WHERE id = 1;"),
                    "'hello world'", "TEXT 回读长文本");

            // —— 错误样例（均不崩溃） ——
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE d = DATE 'not-a-date';"),
                    "valid date literal", "非法 DATE 字面量报语法错误");
            a.checkContains(run(storage, cm, catalog, "SELECT id FROM t WHERE price = DECIMAL 'abc';"),
                    "valid decimal literal", "非法 DECIMAL 字面量报语法错误");
            a.checkContains(run(storage, cm, catalog, "SELECT id + name FROM t;"),
                    "TypeError", "INT + VARCHAR 报类型错误");

        } finally {
            TestFiles.deleteRecursively(dir);
        }
        return a.summary("NullAndTypesTest NULL 与新类型");
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
