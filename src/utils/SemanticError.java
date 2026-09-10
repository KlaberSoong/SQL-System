package utils;

/**
 * 语义错误：表/列存在性、类型一致性、列数列序检查失败（对应 plan.md 3.3）。
 * 输出格式：[错误类型, 位置, 原因说明]
 */
public class SemanticError extends DbException {
    private final String errorType;
    private final int line;
    private final int col;

    public SemanticError(String errorType, int line, int col, String reason) {
        super("[" + errorType + ", (" + line + ", " + col + "): " + reason + "]");
        this.errorType = errorType;
        this.line = line;
        this.col = col;
    }

    public String getErrorType() {
        return errorType;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }
}
