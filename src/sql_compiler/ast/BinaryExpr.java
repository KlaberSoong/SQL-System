package sql_compiler.ast;

import utils.Operator;

/**
 * 二元表达式：left op right。
 * op 可为逻辑运算符 AND/OR，也可为算术运算符 +,-,*,/（用于支持常量折叠，如 age > 10+8）。
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
