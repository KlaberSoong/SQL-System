package utils;

/**
 * 词法错误：非法字符 / 未闭合字符串 / 非法数字等。
 * 输出格式：[LexError] at (行,列): 原因
 */
public class LexError extends DbException {
    private final int line;
    private final int col;

    public LexError(int line, int col, String reason) {
        super("[LexError] at (" + line + ", " + col + "): " + reason);
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
