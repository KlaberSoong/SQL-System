package utils;

/**
 * 列定义：列名 + 类型 +（VARCHAR 时的）长度。
 */
public class ColumnDef {
    private final String name;
    private final ColumnType type;
    private final int varcharLength; // 仅 VARCHAR 有效，其余为 0

    public ColumnDef(String name, ColumnType type) {
        this(name, type, 0);
    }

    public ColumnDef(String name, ColumnType type, int varcharLength) {
        this.name = name;
        this.type = type;
        this.varcharLength = varcharLength;
    }

    public String getName() {
        return name;
    }

    public ColumnType getType() {
        return type;
    }

    public int getVarcharLength() {
        return varcharLength;
    }

    @Override
    public String toString() {
        String base = name + " " + type.getKeyword();
        if (type == ColumnType.VARCHAR) {
            base += "(" + varcharLength + ")";
        }
        return base;
    }
}
