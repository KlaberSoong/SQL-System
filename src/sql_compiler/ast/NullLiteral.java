package sql_compiler.ast;

/**
 * SQL NULL 字面量（无类型）。
 */
public class NullLiteral extends Expr {
    @Override
    public String toString() {
        return "NULL";
    }
}
