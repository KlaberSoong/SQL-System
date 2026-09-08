package sql_compiler;

import sql_compiler.ast.Expr;
import sql_compiler.ast.Statement;

import java.util.List;

/**
 * 语法分析器：把 Token 流解析为 AST（递归下降）。
 *
 * 优先级（低 -> 高）：
 *   parseOr           OR
 *   parseAnd          AND
 *   parseNot          NOT
 *   parseComparison   = != < <= > >=
 *   parseAdd          + -
 *   parseMul          * /
 *   parsePrimary      列引用 / 常量 / 括号表达式
 */
public class Parser {
    private final List<Token> tokens;
    private int index; // 当前 token 下标

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.index = 0;
    }

    /**
     * 语法分析主入口：program -> statement (';' statement)* 。
     * 语法错误时抛 utils.SyntaxError（含位置 + 实际符号 + 期望符号集）。
     */
    public List<Statement> parseProgram() {
        throw new UnsupportedOperationException("TODO: 实现 parseProgram()");
    }

    private Statement parseStatement() {
        throw new UnsupportedOperationException("TODO: 按首 token 分发到 CREATE/INSERT/SELECT/DELETE");
    }

    private Statement parseCreateTable() {
        throw new UnsupportedOperationException("TODO: CREATE TABLE 语句");
    }

    private Statement parseInsert() {
        throw new UnsupportedOperationException("TODO: INSERT INTO 语句");
    }

    private Statement parseSelect() {
        throw new UnsupportedOperationException("TODO: SELECT 语句");
    }

    private Statement parseDelete() {
        throw new UnsupportedOperationException("TODO: DELETE FROM 语句");
    }

    private Expr parseOr() {
        throw new UnsupportedOperationException("TODO: or_expr");
    }

    private Expr parseAnd() {
        throw new UnsupportedOperationException("TODO: and_expr");
    }

    private Expr parseNot() {
        throw new UnsupportedOperationException("TODO: not_expr");
    }

    private Expr parseComparison() {
        throw new UnsupportedOperationException("TODO: comparison");
    }

    private Expr parseAdd() {
        throw new UnsupportedOperationException("TODO: add_expr");
    }

    private Expr parseMul() {
        throw new UnsupportedOperationException("TODO: mul_expr");
    }

    private Expr parsePrimary() {
        throw new UnsupportedOperationException("TODO: primary（列引用/常量/括号）");
    }

    private Token peek() {
        throw new UnsupportedOperationException("TODO: 返回当前 token（越界时返回 EOF 哨兵）");
    }

    private Token advance() {
        throw new UnsupportedOperationException("TODO: 返回当前 token 并前移下标");
    }
}
