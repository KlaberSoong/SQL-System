package sql_compiler.ast;

/**
 * UPDATE 的 SET 子句项：列名 = 值表达式。
 */
public class Assignment {
    private final String column;
    private final Expr value;

    public Assignment(String column, Expr value) {
        this.column = column;
        this.value = value;
    }

    public String getColumn() {
        return column;
    }

    public Expr getValue() {
        return value;
    }

    @Override
    public String toString() {
        return column + " = " + value;
    }
}
