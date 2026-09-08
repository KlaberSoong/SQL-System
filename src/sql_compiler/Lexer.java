package sql_compiler;

import utils.ConstSubtype;
import utils.LexError;
import utils.TokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 词法分析器：把 SQL 源字符串切分为 Token 流。
 *
 * <p>工作方式：持有源字符串 {@link #source} 与一个向前移动的游标 {@link #pos}，
 * 通过 {@link #peek()} 预读字符、{@link #advance()} 消费字符并同步维护行号 {@link #line}
 * 与列号 {@link #col}（均从 1 开始），逐个产出 {@link Token}。
 *
 * <p>契约：Token{type, lexeme, line, col}；CONST 携带子类型与解析后的值。
 * 所有词法错误（非法字符 / 未闭合字符串 / 非法数字 / 未闭合块注释）统一抛出
 * {@link LexError}，消息含「行号、列号、原因」，供上层捕获展示而不崩溃。
 */
public class Lexer {
    /** 待分析的 SQL 源字符串（null 会被归一为空串）。 */
    private final String source;
    /** 当前字符下标（指向下一个待读取字符，0 起始）。 */
    private int pos;
    /** 当前行号（从 1 开始）。 */
    private int line;
    /** 当前列号（从 1 开始，即当前字符在其行内的位置）。 */
    private int col;

    /** 关键字集合（大小写不敏感，统一大写存储，查表时对词素转大写）。 */
    private static final Set<String> KEYWORDS = new HashSet<>();

    static {
        String[] kws = {"SELECT", "FROM", "WHERE", "CREATE", "TABLE", "INSERT", "INTO",
                "VALUES", "DELETE", "INT", "VARCHAR", "FLOAT", "BOOL", "AND", "OR", "NOT"};
        for (String kw : kws) {
            KEYWORDS.add(kw);
        }
    }

    /**
     * 构造词法分析器。
     *
     * @param source 待分析的 SQL 源字符串；若为 {@code null} 则视为空串（产出空 Token 流）
     */
    public Lexer(String source) {
        this.source = source == null ? "" : source;
        this.pos = 0;
        this.line = 1;
        this.col = 1;
    }

    /**
     * 词法分析主入口：把整段源字符串切分为 Token 流并返回。
     *
     * <p>主循环步骤：先 {@link #skipWhitespaceAndComments()} 跳过空白与注释；到达输入末尾
     * （{@link #peek()} 返回 {@code '\0'} 哨兵）则结束；否则按当前字符分派到对应分支：
     *
     * <ul>
     *   <li>字母或下划线 → {@link #readIdentifierOrKeyword()}（关键字 / 标识符）</li>
     *   <li>数字 → {@link #readNumber()}（整数 / 浮点常量）</li>
     *   <li>单引号 {@code '} → {@link #readString()}（字符串常量）</li>
     *   <li>运算符字符 → {@link #readOperator()}（多字符优先）</li>
     *   <li>分隔符字符 → {@link #readDelimiter()}</li>
     *   <li>其余 → 抛出 {@link LexError}（非法字符）</li>
     * </ul>
     *
     * <p>需处理（对应 grammar.md 词法约定）：
     *   1. 空白与注释（{@code --} 行注释；可选 {@code /* *}{@code /} 块注释）
     *   2. 关键字（大小写不敏感）与标识符
     *   3. 常量：整数 / 浮点 / 字符串（字符串内用 {@code ''} 转义单引号）
     *   4. 运算符：多字符 {@code >= <= != <> ==} 优先，再单字符 {@code = > < + - * /}
     *   5. 分隔符：{@code ( ) , ;}（另含 {@code .}，供 {@code table.column} 使用）
     *   6. 非法字符 / 未闭合字符串 / 非法数字 → 抛 {@link LexError}（含行号列号）
     *
     * @return 按源顺序排列的 Token 列表；源为空时返回空列表
     * @throws LexError 遇到第一个词法错误即抛出（携带行号、列号、原因）
     */
    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (peek() == '\0') {
                break;
            }
            char c = peek();
            if (isLetter(c) || c == '_') {
                out.add(readIdentifierOrKeyword());
            } else if (isDigit(c)) {
                out.add(readNumber());
            } else if (c == '\'') {
                out.add(readString());
            } else if (isOperatorChar(c)) {
                out.add(readOperator());
            } else if (isDelimiterChar(c)) {
                out.add(readDelimiter());
            } else {
                throw new LexError(line, col, "非法字符 '" + c + "'");
            }
        }
        return out;
    }

    /**
     * 跳过连续出现的空白与注释，直到遇到一个既非空白也非注释起始的字符（或输入末尾）。
     *
     * <p>识别的三类可跳过内容：
     * <ul>
     *   <li>空白：空格 {@code ' '}、制表符 {@code '\t'}、回车 {@code '\r'}、换行 {@code '\n'}
     *       （换行会由 {@link #advance()} 同步维护行号列号）</li>
     *   <li>行注释：{@code --} 起，吞到行尾（含换行）或输入末尾</li>
     *   <li>块注释：{@code /*} 起，吞到 {@code *}{@code /}；内部换行同样更新行列</li>
     * </ul>
     *
     * <p>注意：{@code -} / {@code /} 后跟的不是注释起始符时立即返回，留给
     * {@link #readOperator()} 处理（避免把减号、除号误当注释吞掉）。
     *
     * @throws LexError 块注释未闭合（遇到输入末尾仍未出现 {@code *}{@code /}）时抛出，
     *                  位置定位在 {@code /*} 起始处
     */
    private void skipWhitespaceAndComments() {
        while (true) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '-' && peek(1) == '-') {
                // 行注释：吞到行尾（含换行）或输入末尾
                advance();
                advance();
                while (peek() != '\0' && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                // 块注释：吞到 */
                int startLine = line;
                int startCol = col;
                advance();
                advance();
                while (true) {
                    if (peek() == '\0') {
                        throw new LexError(startLine, startCol, "未闭合的块注释");
                    }
                    if (peek() == '*' && peek(1) == '/') {
                        advance();
                        advance();
                        break;
                    }
                    advance();
                }
            } else {
                return;
            }
        }
    }

    /**
     * 读取一个标识符、关键字或布尔字面量。
     *
     * <p>标识符文法：以字母或下划线开头，后跟任意个字母 / 数字 / 下划线。读完整个词素后，
     * 先特判布尔字面量 {@code true} / {@code false}（大小写不敏感，产出 CONST，使二者成为
     * 保留字）；否则将词素转大写去 {@link #KEYWORDS} 查表：命中则产出 {@link TokenType#KEYWORD}，
     * 否则产出 {@link TokenType#IDENTIFIER}。词素值保留源文本的原始大小写。
     *
     * @return 新建的 Token，type 为 CONST（布尔）、KEYWORD 或 IDENTIFIER，位置为词素首字符的行列
     */
    private Token readIdentifierOrKeyword() {
        int startLine = line;
        int startCol = col;
        StringBuilder sb = new StringBuilder();
        while (isLetter(peek()) || isDigit(peek()) || peek() == '_') {
            sb.append(advance());
        }
        String lexeme = sb.toString();
        if (lexeme.equalsIgnoreCase("true") || lexeme.equalsIgnoreCase("false")) {
            Token token = new Token(TokenType.CONST, lexeme, startLine, startCol);
            token.setConst(ConstSubtype.BOOL_CONST, lexeme.equalsIgnoreCase("true"));
            return token;
        }
        TokenType type = KEYWORDS.contains(lexeme.toUpperCase()) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        return new Token(type, lexeme, startLine, startCol);
    }

    /**
     * 读取一个数值常量（整数或浮点）。
     *
     * <p>规则：
     * <ul>
     *   <li>先读连续数字作为整数部分；</li>
     *   <li>若紧跟 {@code '.'} 且其后再跟一个数字，则把 {@code '.'} 与后续数字一并读入，
     *       标记为浮点（如 {@code 3.14}）；</li>
     *   <li>若整数/浮点读完后又遇到 {@code '.'} 后跟数字（形如 {@code 1.2.3}），
     *       视为非法数字并抛出 {@link LexError}；</li>
     *   <li>{@code '.'} 后跟的不是数字时（如 {@code 1.}）不当作小数点，{@code '.'}
     *       留待主循环作为分隔符处理。</li>
     * </ul>
     *
     * <p>产出 CONST token，并通过 {@link Token#setConst} 设置子类型与值：
     * 整数为 {@link ConstSubtype#INT_CONST} + {@code Integer}，浮点为
     * {@link ConstSubtype#FLOAT_CONST} + {@code Float}。
     *
     * @return 新建的 CONST Token，位置为数字首字符的行列
     * @throws LexError 数字格式非法（如 {@code 1.2.3}）时抛出，位置定位在数字起始处
     */
    private Token readNumber() {
        int startLine = line;
        int startCol = col;
        StringBuilder sb = new StringBuilder();
        while (isDigit(peek())) {
            sb.append(advance());
        }
        boolean isFloat = false;
        if (peek() == '.' && isDigit(peek(1))) {
            isFloat = true;
            sb.append(advance()); // 吞 '.'
            while (isDigit(peek())) {
                sb.append(advance());
            }
        }
        // 已结束整数/浮点部分后，若再遇到 '.' 且后跟数字，即形如 1.2.3 的非法数字
        if (peek() == '.' && isDigit(peek(1))) {
            throw new LexError(startLine, startCol, "非法数字 '" + sb + "." + peek(1) + "...'");
        }
        String lexeme = sb.toString();
        Token token = new Token(TokenType.CONST, lexeme, startLine, startCol);
        if (isFloat) {
            token.setConst(ConstSubtype.FLOAT_CONST, Float.parseFloat(lexeme));
        } else {
            token.setConst(ConstSubtype.INT_CONST, Integer.parseInt(lexeme));
        }
        return token;
    }

    /**
     * 读取一个字符串常量（单引号包裹）。
     *
     * <p>规则：
     * <ul>
     *   <li>以 {@code '} 开头，逐个读字符直到遇到成对的结束单引号；</li>
     *   <li>相邻两个单引号 {@code ''} 转义为一个单引号字符（如 {@code 'Tom''s book'}
     *       对应的值为 {@code Tom's book}）；</li>
     *   <li>读到换行或输入末尾仍未闭合 → 抛出 {@link LexError}。</li>
     * </ul>
     *
     * <p>词素（lexeme）保留含两侧引号的原始文本；通过 {@link Token#setConst} 设置
     * {@link ConstSubtype#STRING_CONST} 与「反转义后的字符串值」（不含外层引号）。
     *
     * @return 新建的 CONST Token，位置为起始单引号的行列
     * @throws LexError 字符串未闭合（读到换行或输入末尾）时抛出，位置定位在起始单引号处
     */
    private Token readString() {
        int startLine = line;
        int startCol = col;
        int startPos = pos;
        advance(); // 吞起始单引号
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '\0' || c == '\n') {
                throw new LexError(startLine, startCol, "未闭合的字符串");
            }
            if (c == '\'') {
                if (peek(1) == '\'') {
                    // '' 转义为一个单引号
                    advance();
                    advance();
                    value.append('\'');
                } else {
                    advance(); // 吞结束单引号
                    break;
                }
            } else {
                value.append(advance());
            }
        }
        String raw = source.substring(startPos, pos); // 含两侧引号的原始词素
        Token token = new Token(TokenType.CONST, raw, startLine, startCol);
        token.setConst(ConstSubtype.STRING_CONST, value.toString());
        return token;
    }

    /**
     * 读取一个运算符，多字符优先。
     *
     * <p>先尝试匹配两字符运算符 {@code >= <= != <> ==}（注意 {@code <} 同时参与
     * {@code <=} 与 {@code <>} 两种组合）；不匹配则退化为单字符运算符 {@code = > < + - * /}。
     * 无论哪种，均产出 {@link TokenType#OPERATOR} token，词素为源文本原样
     * （{@code ==}、{@code <>} 也照常产出，其合法性由后续语法 / 语义阶段判定）。
     *
     * @return 新建的 OPERATOR Token，位置为运算符首字符的行列
     */
    private Token readOperator() {
        int startLine = line;
        int startCol = col;
        char c = peek();
        char next = peek(1);
        String lexeme;
        if ((c == '>' && next == '=') || (c == '<' && next == '=')
                || (c == '!' && next == '=') || (c == '<' && next == '>')
                || (c == '=' && next == '=')) {
            lexeme = String.valueOf(advance()) + advance();
        } else {
            lexeme = String.valueOf(advance());
        }
        return new Token(TokenType.OPERATOR, lexeme, startLine, startCol);
    }

    /**
     * 读取一个分隔符（单字符）。
     *
     * <p>可识别的分隔符：{@code ( ) , ;}，另含 {@code .} 以支持 {@code table.column}。
     * 均产出 {@link TokenType#DELIMITER} token。
     *
     * @return 新建的 DELIMITER Token，位置为该分隔符的行列
     */
    private Token readDelimiter() {
        int startLine = line;
        int startCol = col;
        char c = advance();
        return new Token(TokenType.DELIMITER, String.valueOf(c), startLine, startCol);
    }

    /**
     * 预读当前字符，不移动游标。
     *
     * @return {@code source} 在 {@link #pos} 处的字符；越界（已到末尾）时返回 {@code '\0'} 哨兵
     */
    private char peek() {
        return pos < source.length() ? source.charAt(pos) : '\0';
    }

    /**
     * 预读相对当前位置偏移 {@code offset} 处的字符，不移动游标。
     *
     * <p>常用于判断多字符组合，如运算符 {@code >=}、注释 {@code --}、块注释 {@code /*}、
     * 字符串转义 {@code ''} 等。
     *
     * @param offset 相对 {@link #pos} 的偏移量（0 表示当前字符，1 表示下一个字符，可超出末尾）
     * @return {@code pos + offset} 处的字符；越界时返回 {@code '\0'} 哨兵
     */
    private char peek(int offset) {
        int idx = pos + offset;
        return idx < source.length() ? source.charAt(idx) : '\0';
    }

    /**
     * 消费当前字符并向前移动游标，同时维护行号列号。
     *
     * <p>若消费的是换行 {@code '\n'}，则行号 {@link #line} 加一、列号 {@link #col} 重置为 1；
     * 否则列号加一。调用方应确保 {@code pos} 未越界（本方法不做越界检查）。
     *
     * @return 被消费的字符
     */
    private char advance() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    /**
     * 判断字符是否为英文字母（大小写）。
     *
     * @param c 待判断字符
     * @return 是英文字母返回 {@code true}，否则 {@code false}
     */
    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 判断字符是否为数字 {@code 0-9}。
     *
     * @param c 待判断字符
     * @return 是数字返回 {@code true}，否则 {@code false}
     */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * 判断字符是否为运算符起始字符。
     *
     * <p>运算符字符集合：{@code = > < + - * / !}（分隔符与运算符分开判断；其中 {@code !}
     * 仅作为多字符运算符 {@code !=} 的起始字符出现）。
     *
     * @param c 待判断字符
     * @return 属于运算符字符返回 {@code true}，否则 {@code false}
     */
    private static boolean isOperatorChar(char c) {
        return c == '=' || c == '>' || c == '<' || c == '+' || c == '-' || c == '*' || c == '/' || c == '!';
    }

    /**
     * 判断字符是否为分隔符。
     *
     * <p>分隔符集合：{@code ( ) , ;} 以及 {@code .}（供 {@code table.column} 使用）。
     *
     * @param c 待判断字符
     * @return 属于分隔符返回 {@code true}，否则 {@code false}
     */
    private static boolean isDelimiterChar(char c) {
        return c == '(' || c == ')' || c == ',' || c == ';' || c == '.';
    }
}
