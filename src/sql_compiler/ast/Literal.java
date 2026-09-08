package sql_compiler.ast;

import utils.ColumnType;

/**
 * 字面量常量（整数 / 浮点 / 字符串）。
 */
public class Literal extends Expr {
    private final Object value;
    private final ColumnType type;

    public Literal(Object value, ColumnType type) {
        this.value = value;
        this.type = type;
    }

    public Object getValue() {
        return value;
    }

    public ColumnType getType() {
        return type;
    }

    @Override
    public String toString() {
        if (type == ColumnType.VARCHAR) {
            return "'" + value + "'";
        }
        return String.valueOf(value);
    }
}
