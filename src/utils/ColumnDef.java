package utils;

/**
 * 列定义：列名 + 类型 +（可选）类型参数。
 *
 * <p>{@link #length} 对 VARCHAR(n)/CHAR(n) 表示长度，对 DECIMAL(p,s) 表示精度 p；{@link #scale}
 * 仅 DECIMAL 的标度 s 有效，其余为 0。旧方法 {@link #getVarcharLength()} 保留为 {@link #getLength()}
 * 的别名以兼容既有调用点。
 */
public class ColumnDef {
    private final String name;
    private final ColumnType type;
    private final int length; // VARCHAR/CHAR 长度；DECIMAL 精度；其余 0
    private final int scale;  // DECIMAL 标度；其余 0

    public ColumnDef(String name, ColumnType type) {
        this(name, type, 0, 0);
    }

    public ColumnDef(String name, ColumnType type, int length) {
        this(name, type, length, 0);
    }

    public ColumnDef(String name, ColumnType type, int precision, int scale) {
        this.name = name;
        this.type = type;
        this.length = precision;
        this.scale = scale;
    }

    public String getName() {
        return name;
    }

    public ColumnType getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public int getScale() {
        return scale;
    }

    /** 兼容旧调用点的别名：返回长度（VARCHAR/CHAR）。 */
    public int getVarcharLength() {
        return length;
    }

    /**
     * 用于 pg_catalog 持久化的单个参数整数：DECIMAL 打包为 {@code precision * 1000 + scale}，
     * 其余返回 {@link #length}。
     */
    public int persistedParam() {
        if (type == ColumnType.DECIMAL) {
            return length * 1000 + scale;
        }
        return length;
    }

    /** 从 pg_catalog 读回的单个参数整数还原列定义（DECIMAL 解包精度/标度）。 */
    public static ColumnDef fromPersisted(String name, ColumnType type, int param) {
        if (type == ColumnType.DECIMAL) {
            return new ColumnDef(name, type, param / 1000, param % 1000);
        }
        return new ColumnDef(name, type, param);
    }

    @Override
    public String toString() {
        String base = name + " " + type.getKeyword();
        if (type == ColumnType.VARCHAR || type == ColumnType.CHAR) {
            base += "(" + length + ")";
        } else if (type == ColumnType.DECIMAL) {
            base += "(" + length + "," + scale + ")";
        }
        return base;
    }
}
