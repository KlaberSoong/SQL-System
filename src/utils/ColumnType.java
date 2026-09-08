package utils;

/**
 * 字段数据类型（对应 grammar.md 的 data_type）。
 */
public enum ColumnType {
    INT("int"),
    FLOAT("float"),
    VARCHAR("varchar"),
    BOOL("bool");

    private final String keyword;

    ColumnType(String keyword) {
        this.keyword = keyword;
    }

    public String getKeyword() {
        return keyword;
    }

    /** 根据 SQL 关键字解析类型（大小写不敏感），无法识别返回 null。 */
    public static ColumnType fromKeyword(String kw) {
        switch (kw.toUpperCase()) {
            case "INT": return INT;
            case "FLOAT": return FLOAT;
            case "VARCHAR": return VARCHAR;
            case "BOOL": return BOOL;
            default: return null;
        }
    }
}
