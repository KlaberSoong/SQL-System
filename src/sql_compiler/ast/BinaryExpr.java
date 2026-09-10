package sql_compiler.ast;

import utils.Operator;

/**
 * 二元表达式：left op right，op 为逻辑（AND/OR）或算术（+ - * /）运算符。
 */
public class BinaryExpr extends Expr {
    private final Operator op;
    private final Expr left;
    private final Expr right;

    public BinaryExpr(Operator op, Expr left, Expr right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }

    public Operator getOp() {
        return op;
    }

    public Expr getLeft() {
        return left;
    }

    public Expr getRight() {
        return right;
    }

    @Override
    public String toString() {
        return "(" + left + " " + op.getText() + " " + right + ")";
    }
}
