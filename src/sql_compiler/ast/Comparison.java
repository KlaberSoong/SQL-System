package sql_compiler.ast;

import utils.Operator;

/**
 * 比较表达式：left op right，op ∈ {= != < <= > >=}，结果恒为布尔。
 */
public class Comparison extends Expr {
    private final Operator op;
    private final Expr left;
    private final Expr right;

    public Comparison(Operator op, Expr left, Expr right) {
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
