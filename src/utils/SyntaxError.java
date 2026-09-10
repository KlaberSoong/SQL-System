package utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 语法错误：三段式输出「位置 / 实际符号 / 期望集合」（对应 plan.md 3.2）。
 * 消息形如：
 *   SyntaxError at line 3, column 19
 *   unexpected token: ';'
 *   expected: IDENTIFIER | CONST | '(' | NOT
 */
public class SyntaxError extends DbException {
    private final int line;
    private final int col;
    private final String actual;
    private final List<String> expected;

    public SyntaxError(int line, int col, String actual, List<String> expected) {
        super(format(line, col, actual, expected));
        this.line = line;
        this.col = col;
        this.actual = actual;
        this.expected = new ArrayList<>(expected);
    }

    // 拼接三段式错误消息：位置 + 实际符号 + 期望集合
    private static String format(int line, int col, String actual, List<String> expected) {
        return "SyntaxError at line " + line + ", column " + col
                + "\nunexpected token: '" + actual + "'"
                + "\nexpected: " + String.join(" | ", expected);
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }

    public String getActual() {
        return actual;
    }

    public List<String> getExpected() {
        return expected;
    }
}
