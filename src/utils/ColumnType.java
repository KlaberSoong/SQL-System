package utils;

/**
 * 字段数据类型（对应 grammar.md 的 data_type），供整个编译链路与执行引擎共用。
 */
public enum ColumnType {
    INT("int"),
    FLOAT("float"),
    VARCHAR("varchar"),
    BOOL("bool"),
    // 新增类型必须追加在 BOOL 之后，保持 pg_catalog 里 type 的枚举序（0-3）不变
    DATE("date"),
    DECIMAL("decimal"),
    CHAR("char"),
    TEXT("text");

    private final String keyword;

    ColumnType(String keyword) {
        this.keyword = keyword;
    }

    public String getKeyword() {
        return keyword;
    }

    // 根据 SQL 关键字（大小写不敏感）解析类型；无法识别返回 null
    public static ColumnType fromKeyword(String kw) {
        switch (kw.toUpperCase()) {
            case "INT": return INT;
            case "FLOAT": return FLOAT;
            case "VARCHAR": return VARCHAR;
            case "BOOL": return BOOL;
            case "DATE": return DATE;
            case "DECIMAL": return DECIMAL;
            case "CHAR": return CHAR;
            case "TEXT": return TEXT;
            default: return null;
        }
    }
}
