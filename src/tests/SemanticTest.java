package tests;

import sql_compiler.Catalog;
import sql_compiler.SemanticAnalyzer;
import sql_compiler.TypeSystem;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Operator;
import utils.SemanticError;

import java.util.Arrays;
import java.util.Collections;

/**
 * 语义分析器 + 类型系统测试（对应 plan.md 3.3 / 3.4 与「错误测试—语义」）。
 *
 * 覆盖：表/列存在性、重复建表/重复列、INSERT 列数/类型/长度、WHERE 类型、类型系统规则。
 */
public class SemanticTest {

    public static int run() {
        Assert a = new Assert();

        Catalog catalog = new Catalog();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);

        // 建表 student(id INT, name VARCHAR(20), age INT)
        CreateTableStmt create = new CreateTableStmt("student", Arrays.asList(
                new ColumnDef("id", ColumnType.INT),
                new ColumnDef("name", ColumnType.VARCHAR, 20),
                new ColumnDef("age", ColumnType.INT)));
        analyzer.analyze(Collections.singletonList(create));
        a.checkEquals(true, catalog.containsTable("student"), "建表后注册进 Catalog");

        // 合法查询通过
        analyzer.analyze(Collections.singletonList(
                new SelectStmt(Collections.singletonList(new ColumnRef(null, "id")),
                        "student", new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)))));
        a.check(true, "合法 SELECT 通过");

        // 重复建表
        SemanticError dup = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(create)), "重复建表");
        if (dup != null) a.checkEquals("DuplicateTable", dup.getErrorType(), "重复建表错误类型");

        // 建表重复列名
        CreateTableStmt dupCol = new CreateTableStmt("t1", Arrays.asList(
                new ColumnDef("a", ColumnType.INT), new ColumnDef("a", ColumnType.INT)));
        SemanticError dc = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(dupCol)), "建表重复列名");
        if (dc != null) a.checkEquals("DuplicateColumn", dc.getErrorType(), "重复列名错误类型");

        // 表不存在
        SemanticError tnf = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new SelectStmt(Collections.singletonList(new ColumnRef(null, "id")), "nosuch", null))),
                "查询不存在的表");
        if (tnf != null) a.checkEquals("TableNotFound", tnf.getErrorType(), "表不存在错误类型");

        // 列不存在
        SemanticError cnf = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new SelectStmt(Collections.singletonList(new ColumnRef(null, "score")), "student", null))),
                "查询不存在的列");
        if (cnf != null) a.checkEquals("ColumnNotFound", cnf.getErrorType(), "列不存在错误类型");

        // INSERT 列数不一致
        SemanticError ccm = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new InsertStmt("student", null, Arrays.asList(new Literal(1, ColumnType.INT))))),
                "INSERT 列数不一致");
        if (ccm != null) a.checkEquals("ColumnCountMismatch", ccm.getErrorType(), "列数不一致错误类型");

        // INSERT 类型不匹配（VARCHAR 值写入 INT 列）
        SemanticError te = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new InsertStmt("student", null, Arrays.asList(
                                new Literal("x", ColumnType.VARCHAR),
                                new Literal("Alice", ColumnType.VARCHAR),
                                new Literal(20, ColumnType.INT))))),
                "INSERT 类型不匹配");
        if (te != null) a.checkEquals("TypeError", te.getErrorType(), "类型不匹配错误类型");

        // VARCHAR 超长
        SemanticError vtl = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new InsertStmt("student", null, Arrays.asList(
                                new Literal(1, ColumnType.INT),
                                new Literal("this-name-is-way-too-long", ColumnType.VARCHAR),
                                new Literal(20, ColumnType.INT))))),
                "VARCHAR 超长");
        if (vtl != null) a.checkEquals("ValueTooLong", vtl.getErrorType(), "VARCHAR 超长错误类型");

        // WHERE 非 BOOL
        SemanticError nb = a.checkThrows(SemanticError.class,
                () -> analyzer.analyze(Collections.singletonList(
                        new SelectStmt(Collections.singletonList(new ColumnRef(null, "id")), "student",
                                new ColumnRef(null, "age")))),
                "WHERE 非 BOOL");
        if (nb != null) a.checkEquals("TypeError", nb.getErrorType(), "WHERE 非 BOOL 错误类型");

        // INT -> FLOAT 拓宽合法
        catalog.createTable("f", Collections.singletonList(new ColumnDef("v", ColumnType.FLOAT)));
        analyzer.analyze(Collections.singletonList(
                new InsertStmt("f", null, Collections.singletonList(new Literal(1, ColumnType.INT)))));
        a.check(true, "INT 写入 FLOAT 列合法（拓宽）");

        // —— 类型系统规则 ——
        a.checkEquals(ColumnType.INT, TypeSystem.arithmetic(ColumnType.INT, Operator.PLUS, ColumnType.INT), "INT+INT=INT");
        a.checkEquals(ColumnType.FLOAT, TypeSystem.arithmetic(ColumnType.INT, Operator.DIV, ColumnType.INT), "INT/INT=FLOAT");
        a.checkEquals(ColumnType.FLOAT, TypeSystem.arithmetic(ColumnType.FLOAT, Operator.PLUS, ColumnType.INT), "FLOAT 参与=FLOAT");
        a.checkThrows(IllegalArgumentException.class,
                () -> TypeSystem.arithmetic(ColumnType.INT, Operator.PLUS, ColumnType.VARCHAR), "INT+VARCHAR 报错");
        a.checkThrows(IllegalArgumentException.class,
                () -> TypeSystem.checkComparison(ColumnType.VARCHAR, Operator.GT, ColumnType.VARCHAR), "VARCHAR 不支持 >");
        a.checkThrows(IllegalArgumentException.class,
                () -> TypeSystem.checkLogical(ColumnType.INT, Operator.AND, ColumnType.INT), "非 BOOL 逻辑运算报错");
        a.checkThrows(IllegalArgumentException.class,
                () -> TypeSystem.checkUnaryLogical(ColumnType.INT), "NOT 非 BOOL 报错");
        boolean ok = true;
        try {
            TypeSystem.checkComparison(ColumnType.INT, Operator.GT, ColumnType.INT);
            TypeSystem.checkComparison(ColumnType.VARCHAR, Operator.EQ, ColumnType.VARCHAR);
            TypeSystem.checkAssignable(ColumnType.FLOAT, ColumnType.INT);
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        a.check(ok, "合法比较/赋值不抛异常");

        return a.summary("SemanticTest 语义分析");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
