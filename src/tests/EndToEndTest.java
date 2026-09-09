package tests;

import cli.Main;
import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;

import java.io.File;

/**
 * 端到端测试（对应 plan.md 第十节验证方式）：建表→插入→查询→删除→再查询 + 重启持久化 + 错误样例。
 *
 * 直接复用 {@link Main#executeAndFormat} 走完整「词法→语法→语义→计划→优化→执行」流水线，
 * 断言文本输出，验证关键验收场景与「非法输入不崩溃」。
 */
public class EndToEndTest {

    public static int run() {
        Assert a = new Assert();
        File dir = TestFiles.tempDir("e2e");
        try {
            StorageEngine storage = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm = new CatalogManager(storage);
            Catalog catalog = cm.loadCatalog();

            a.checkContains(run(storage, cm, catalog, "CREATE TABLE student(id INT, name VARCHAR(20), age INT);"),
                    "CREATE TABLE", "建表");
            a.checkContains(run(storage, cm, catalog, "INSERT INTO student(id,name,age) VALUES (1,'Alice',20);"),
                    "INSERT 1", "插入 Alice");
            a.checkContains(run(storage, cm, catalog, "INSERT INTO student(id,name,age) VALUES (2,'Bob',17);"),
                    "INSERT 1", "插入 Bob");

            String sel = run(storage, cm, catalog, "SELECT id,name FROM student WHERE age > 18;");
            a.checkContains(sel, "'Alice'", "查询返回 Alice");
            a.checkNotContains(sel, "'Bob'", "查询不含 Bob");

            a.checkContains(run(storage, cm, catalog, "DELETE FROM student WHERE id = 1;"), "DELETE 1", "删除 id=1");

            String all = run(storage, cm, catalog, "SELECT * FROM student;");
            a.checkContains(all, "'Bob'", "删除后剩 Bob");
            a.checkNotContains(all, "'Alice'", "删除后无 Alice");

            // —— 错误样例（均不崩溃，返回错误消息） ——
            a.checkContains(run(storage, cm, catalog, "SELECT score FROM student;"), "ColumnNotFound", "列不存在错误");
            a.checkContains(run(storage, cm, catalog, "INSERT INTO student VALUES (1,2,3,4);"), "ColumnCountMismatch", "列数不一致错误");
            a.checkContains(run(storage, cm, catalog, "'abc"), "未闭合", "未闭合字符串错误");

            // —— 重启持久化 ——
            StorageEngine storage2 = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm2 = new CatalogManager(storage2);
            Catalog catalog2 = cm2.loadCatalog();
            String after = run(storage2, cm2, catalog2, "SELECT * FROM student;");
            a.checkContains(after, "'Bob'", "重启后数据仍在");

        } finally {
            TestFiles.deleteRecursively(dir);
        }
        return a.summary("EndToEndTest 端到端");
    }

    private static String run(StorageEngine storage, CatalogManager cm, Catalog catalog, String sql) {
        return Main.executeAndFormat(sql, storage, cm, catalog);
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
