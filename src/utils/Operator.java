package utils;

/**
 * 运算符枚举，统一管理比较 / 逻辑 / 算术运算符。
 */
public enum Operator {
    EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
    AND("AND"), OR("OR"), NOT("NOT"),
    PLUS("+"), MINUS("-"), MUL("*"), DIV("/");

    private final String text;

    Operator(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public boolean isComparison() {
        return this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
    }

    public boolean isLogical() {
        return this == AND || this == OR;
    }

    public boolean isArithmetic() {
        return this == PLUS || this == MINUS || this == MUL || this == DIV;
    }

    public boolean isUnaryLogical() {
        return this == NOT;
    }
}
