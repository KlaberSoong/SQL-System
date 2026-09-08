package sql_compiler.ast;

import utils.Operator;

/**
 * 一元表达式：NOT operand（一元逻辑非）。
 */
public class UnaryExpr extends Expr {
    private final Operator op; // NOT
    private final Expr operand;

    public UnaryExpr(Operator op, Expr operand) {
        this.op = op;
        this.operand = operand;
    }

    public Operator getOp() {
        return op;
    }

    public Expr getOperand() {
        return operand;
    }

    @Override
    public String toString() {
        return op.getText() + " " + operand;
    }
}
