package utils;

import java.util.List;

/**
 * 语法错误：输出位置 + 实际符号 + 期望符号集。
 * 输出格式：[SyntaxError] at (行,列): unexpected 'X', expected [A, B, C]
 */
public class SyntaxError extends DbException {
    private final int line;
    private final int col;

    public SyntaxError(int line, int col, String actual, List<String> expected) {
        super("[SyntaxError] at (" + line + ", " + col + "): unexpected '" + actual
                + "', expected " + expected);
        this.line = line;
        this.col = col;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }
}
