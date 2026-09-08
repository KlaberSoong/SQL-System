package utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 语法错误：输出位置 + 实际符号 + 期望符号集，三段式展示（可定位、可解释）。
 *
 * <p>消息格式：
 * <pre>
 * SyntaxError at line 3, column 19
 * unexpected token: ';'
 * expected: IDENTIFIER | CONST | '(' | NOT
 * </pre>
 * 三段分别对应「位置」「实际符号」「期望集合」，供上层捕获后原样打印而不崩溃。
 *
 * <p>同样通过 {@link #getLine()} / {@link #getCol()} / {@link #getActual()} /
 * {@link #getExpected()} 暴露结构化信息，便于需要定位或做修复建议的调用方使用。
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

    /** 构造三段式错误消息：位置 / 实际符号 / 期望集合。 */
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
