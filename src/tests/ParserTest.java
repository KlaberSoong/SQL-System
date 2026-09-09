package tests;

import sql_compiler.Lexer;
import sql_compiler.Parser;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.ast.UnaryExpr;
import utils.ColumnType;
import utils.Operator;
import utils.SyntaxError;

import java.util.List;

/**
 * 语法分析器测试（对应 plan.md 3.2 与「错误测试—语法」「边界测试」）。
 *
 * 覆盖：四类语句、运算符优先级（AND 高于 OR）、NOT 绑定、表名.列名、大小写混用、多语句、
 * 语法错误三段式（位置+实际符号+期望集合）。
 */
public class ParserTest {

    public static int run() {
        Assert a = new Assert();

        // —— 四类语句正常解析 ——
        List<Statement> stmts = parse("SELECT * FROM student;");
        a.checkEquals(1, stmts.size(), "SELECT * 解析为单条语句");
        SelectStmt sel = (SelectStmt) stmts.get(0);
        a.checkEquals("student", sel.getTable(), "SELECT 表名");
        a.checkEquals(true, sel.getSelectItems().get(0) instanceof Star, "SELECT * 投影为 Star");
        a.checkEquals(null, sel.getWhere(), "无 WHERE 为 null");

        SelectStmt sel2 = (SelectStmt) parse("SELECT id, name FROM student WHERE age > 18;").get(0);
        a.checkEquals(2, sel2.getSelectItems().size(), "多列投影");
        a.checkEquals("id", ((ColumnRef) sel2.getSelectItems().get(0)).getColumn(), "投影列 id");
        Comparison cmp = (Comparison) sel2.getWhere();
        a.checkEquals(Operator.GT, cmp.getOp(), "WHERE 比较运算符");
        a.checkEquals("age", ((ColumnRef) cmp.getLeft()).getColumn(), "比较左列");
        a.checkEquals(18, ((Literal) cmp.getRight()).getValue(), "比较右值");

        CreateTableStmt ct = (CreateTableStmt) parse("CREATE TABLE student(id INT, name VARCHAR(20), age INT);").get(0);
        a.checkEquals("student", ct.getTable(), "建表表名");
        a.checkEquals(3, ct.getColumns().size(), "建表列数");
        a.checkEquals(ColumnType.VARCHAR, ct.getColumns().get(1).getType(), "VARCHAR 列类型");
        a.checkEquals(20, ct.getColumns().get(1).getVarcharLength(), "VARCHAR 长度");

        InsertStmt ins = (InsertStmt) parse("INSERT INTO student(id,name,age) VALUES (1,'Alice',20);").get(0);
        a.checkEquals(3, ins.getColumns().size(), "INSERT 显式列数");
        a.checkEquals("name", ins.getColumns().get(1), "INSERT 列名");
        a.checkEquals(3, ins.getValues().size(), "INSERT 值数");
        a.checkEquals("Alice", ((Literal) ins.getValues().get(1)).getValue(), "INSERT 字符串值");

        InsertStmt ins2 = (InsertStmt) parse("INSERT INTO student VALUES (1,'Alice',20);").get(0);
        a.checkEquals(null, ins2.getColumns(), "INSERT 省略列名列表为 null");

        DeleteStmt del = (DeleteStmt) parse("DELETE FROM student WHERE id = 1;").get(0);
        a.checkEquals("student", del.getTable(), "DELETE 表名");
        a.checkEquals(true, del.getWhere() instanceof Comparison, "DELETE 条件");
        a.checkEquals(null, ((DeleteStmt) parse("DELETE FROM student;").get(0)).getWhere(), "DELETE 无条件为 null");

        // —— 优先级：AND 高于 OR ——
        SelectStmt prec = (SelectStmt) parse("SELECT * FROM t WHERE a=1 OR b=2 AND c=3;").get(0);
        BinaryExpr or = (BinaryExpr) prec.getWhere();
        a.checkEquals(Operator.OR, or.getOp(), "最外层为 OR");
        BinaryExpr and = (BinaryExpr) or.getRight();
        a.checkEquals(Operator.AND, and.getOp(), "AND 在 OR 下方");

        // —— 算术表达式：age > 10 + 8 ——
        SelectStmt arith = (SelectStmt) parse("SELECT * FROM t WHERE age > 10 + 8;").get(0);
        Comparison acmp = (Comparison) arith.getWhere();
        a.checkEquals(Operator.GT, acmp.getOp(), "算术比较运算符");
        a.checkEquals(true, acmp.getRight() instanceof BinaryExpr, "比较右端为算术表达式");
        BinaryExpr add = (BinaryExpr) acmp.getRight();
        a.checkEquals(Operator.PLUS, add.getOp(), "算术为加法");
        a.checkEquals(10, ((Literal) add.getLeft()).getValue(), "加法左值");
        a.checkEquals(8, ((Literal) add.getRight()).getValue(), "加法右值");

        // —— 算术优先级：乘法高于加法（1 + 2 * 3 -> 1 + (2*3)） ——
        SelectStmt prec2 = (SelectStmt) parse("SELECT * FROM t WHERE x = 1 + 2 * 3;").get(0);
        BinaryExpr outer = (BinaryExpr) ((Comparison) prec2.getWhere()).getRight();
        a.checkEquals(Operator.PLUS, outer.getOp(), "1+2*3 外层为加法");
        a.checkEquals(true, outer.getRight() instanceof BinaryExpr, "乘法在加法下方");
        a.checkEquals(Operator.MUL, ((BinaryExpr) outer.getRight()).getOp(), "乘法运算符");

        // —— 括号改变结合顺序：(1 + 2) * 3 ——
        SelectStmt paren = (SelectStmt) parse("SELECT * FROM t WHERE x = (1 + 2) * 3;").get(0);
        BinaryExpr p = (BinaryExpr) ((Comparison) paren.getWhere()).getRight();
        a.checkEquals(Operator.MUL, p.getOp(), "括号后外层为乘法");
        a.checkEquals(true, p.getLeft() instanceof BinaryExpr, "括号内加法为乘法左端");

        // —— 除法解析（结果类型由语义阶段按 FLOAT 处理） ——
        SelectStmt div = (SelectStmt) parse("SELECT * FROM t WHERE a = 8 / 2;").get(0);
        a.checkEquals(true, ((Comparison) div.getWhere()).getRight() instanceof BinaryExpr, "除法解析为算术");

        // —— 投影算术：SELECT 列表可为完整表达式 ——
        SelectStmt projArith = (SelectStmt) parse("SELECT id * 2, name FROM student;").get(0);
        a.checkEquals(2, projArith.getSelectItems().size(), "投影算术 + 普通列共 2 项");
        a.checkEquals(true, projArith.getSelectItems().get(0) instanceof BinaryExpr, "投影首项为算术表达式");
        BinaryExpr pm = (BinaryExpr) projArith.getSelectItems().get(0);
        a.checkEquals(Operator.MUL, pm.getOp(), "投影算术为乘法");
        a.checkEquals(true, pm.getLeft() instanceof ColumnRef, "投影乘法左端为列");
        a.checkEquals("id", ((ColumnRef) pm.getLeft()).getColumn(), "投影乘法左列 id");
        a.checkEquals(true, projArith.getSelectItems().get(1) instanceof ColumnRef, "投影第二项为普通列");

        // —— 投影算术优先级：id * 2 + 1 -> (id * 2) + 1 ——
        SelectStmt projPrec = (SelectStmt) parse("SELECT id * 2 + 1 FROM student;").get(0);
        BinaryExpr pp = (BinaryExpr) projPrec.getSelectItems().get(0);
        a.checkEquals(Operator.PLUS, pp.getOp(), "id*2+1 外层为加法");
        a.checkEquals(true, pp.getLeft() instanceof BinaryExpr, "乘法在加法下方");
        a.checkEquals(Operator.MUL, ((BinaryExpr) pp.getLeft()).getOp(), "下层为乘法");

        // —— NOT 绑定 ——
        SelectStmt not = (SelectStmt) parse("SELECT * FROM t WHERE NOT a = 1;").get(0);
        UnaryExpr un = (UnaryExpr) not.getWhere();
        a.checkEquals(Operator.NOT, un.getOp(), "NOT 运算符");
        a.checkEquals(true, un.getOperand() instanceof Comparison, "NOT 作用于比较");

        // —— 表名.列名 ——
        ColumnRef cr = (ColumnRef) ((SelectStmt) parse("SELECT s.id FROM s;").get(0)).getSelectItems().get(0);
        a.checkEquals("s", cr.getTable(), "列引用表前缀");
        a.checkEquals("id", cr.getColumn(), "列引用列名");

        // —— 边界：大小写混用 / 多语句 / 末尾分号 ——
        a.checkEquals(1, parse("sElEcT * fRoM student;").size(), "整句大小写混用");
        a.checkEquals(2, parse("CREATE TABLE a(x INT); INSERT INTO a VALUES (1);").size(), "多语句");
        a.checkEquals(1, parse("SELECT * FROM student").size(), "单语句允许省略末尾分号");

        // —— 语法错误（不崩溃，含位置+实际符号+期望集） ——
        SyntaxError s1 = a.checkThrows(SyntaxError.class, () -> parse("SELECT FROM student;"), "缺投影列");
        if (s1 != null) {
            a.checkContains(s1.getMessage(), "unexpected", "语法错误含实际符号");
            a.checkContains(s1.getMessage(), "expected", "语法错误含期望集");
            a.check(s1.getLine() >= 1 && s1.getCol() >= 1, "语法错误含位置");
        }
        a.checkThrows(SyntaxError.class, () -> parse("CREATE TABLE t(id INT;"), "括号不匹配");
        a.checkThrows(SyntaxError.class, () -> parse("FOO bar;"), "未知语句起始");
        a.checkThrows(SyntaxError.class, () -> parse("SELECT * FROM a SELECT * FROM b;"), "多语句缺分号");

        return a.summary("ParserTest 语法分析");
    }

    private static List<Statement> parse(String sql) {
        return new Parser(new Lexer(sql).tokenize()).parseProgram();
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
