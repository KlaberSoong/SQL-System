package sql_compiler.ast;

/**
 * 后置一元表达式：{@code operand IS [NOT] NULL}，结果恒为布尔。
 */
public class IsNullExpr extends Expr {
    private final Expr operand;
    private final boolean notNull; // true = IS NOT NULL

    public IsNullExpr(Expr operand, boolean notNull) {
        this.operand = operand;
        this.notNull = notNull;
    }

    public Expr getOperand() {
        return operand;
    }

    public boolean isNotNull() {
        return notNull;
    }

    @Override
    public String toString() {
        return operand + (notNull ? " IS NOT NULL" : " IS NULL");
    }
}
