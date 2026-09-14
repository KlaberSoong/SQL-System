package sql_compiler.ast;

import utils.JoinType;

/**
 * 连接关系：left/right 为左右操作数，type 为连接类型，on 可为 null（交叉连接）。
 */
public class JoinRelation extends Relation {
    private final Relation left;
    private final Relation right;
    private final JoinType type;
    private final Expr on; // 可为 null

    public JoinRelation(Relation left, Relation right, JoinType type, Expr on) {
        this.left = left;
        this.right = right;
        this.type = type;
        this.on = on;
    }

    public Relation getLeft() {
        return left;
    }

    public Relation getRight() {
        return right;
    }

    public JoinType getType() {
        return type;
    }

    public Expr getOn() {
        return on;
    }

    @Override
    public String toString() {
        return "(" + left + " " + type + " " + right + (on == null ? "" : " ON " + on) + ")";
    }
}
