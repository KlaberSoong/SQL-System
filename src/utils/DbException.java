package utils;

/**
 * 数据库系统所有自定义异常（词法/语法/语义错误）的统一基类。
 */
public class DbException extends RuntimeException {
    public DbException(String message) {
        super(message);
    }

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
