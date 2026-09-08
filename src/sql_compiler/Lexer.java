package sql_compiler;

import java.util.List;

/**
 * 词法分析器：把 SQL 源字符串切分为 Token 流。
 * 契约：Token{type, lexeme, line, col}；CONST 携带子类型与解析后的值。
 */
public class Lexer {
    private final String source;
    private int pos;   // 当前字符下标
    private int line;  // 当前行号（从 1 开始）
    private int col;   // 当前列号（从 1 开始）

    public Lexer(String source) {
        this.source = source == null ? "" : source;
        this.pos = 0;
        this.line = 1;
        this.col = 1;
    }

    /**
     * 词法分析主入口：返回全部 Token。
     *
     * 需处理：
     *   1. 空白与注释（-- 行注释；可选块注释）
     *   2. 关键字（大小写不敏感）与标识符
     *   3. 常量：整数 / 浮点 / 字符串（字符串内用 '' 转义单引号）
     *   4. 运算符：多字符 >= <= != <> == 优先，再单字符 = > < + - * /
     *   5. 分隔符：( ) , ;
     *   6. 非法字符 / 未闭合字符串 / 非法数字 -> 抛 utils.LexError（含行号列号）
     */
    public List<Token> tokenize() {
        throw new UnsupportedOperationException("TODO: 实现 tokenize()");
    }

    private void skipWhitespaceAndComments() {
        throw new UnsupportedOperationException("TODO: 跳过空白与 -- 注释");
    }

    private Token readIdentifierOrKeyword() {
        throw new UnsupportedOperationException("TODO: 读关键字/标识符（大小写不敏感）");
    }

    private Token readNumber() {
        throw new UnsupportedOperationException("TODO: 读整数/浮点常量");
    }

    private Token readString() {
        throw new UnsupportedOperationException("TODO: 读字符串常量（处理 '' 转义与未闭合报错）");
    }

    private Token readOperator() {
        throw new UnsupportedOperationException("TODO: 读运算符（多字符优先）");
    }
}
