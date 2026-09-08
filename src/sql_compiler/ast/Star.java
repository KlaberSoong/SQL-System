package sql_compiler.ast;

/**
 * SELECT * 中的星号（投影全部列）。
 */
public class Star extends Expr {
    @Override
    public String toString() {
        return "*";
    }
}
