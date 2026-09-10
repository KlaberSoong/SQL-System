package utils;

/**
 * 运算符枚举：统一管理比较 / 逻辑 / 算术运算符，供 Parser、TypeSystem、Optimizer、Executor 共用。
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

    // 是否为比较运算符（= != < <= > >=）
    public boolean isComparison() {
        return this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
    }

    // 是否为二元逻辑运算符（AND / OR）
    public boolean isLogical() {
        return this == AND || this == OR;
    }

    // 是否为算术运算符（+ - * /）
    public boolean isArithmetic() {
        return this == PLUS || this == MINUS || this == MUL || this == DIV;
    }

    // 是否为一元逻辑运算符（NOT）
    public boolean isUnaryLogical() {
        return this == NOT;
    }
}
