package utils;

/**
 * 连接类型：内连接 / 左外连接。
 */
public enum JoinType {
    INNER, LEFT;

    @Override
    public String toString() {
        return name() + " JOIN";
    }
}
