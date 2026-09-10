package sql_compiler;

import utils.ConstSubtype;
import utils.LexError;
import utils.TokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 词法分析器（对应 plan.md 3.1）：把 SQL 源字符串切分为 Token 流。
 * 持有源字符串与游标，通过 peek/advance 预读/消费字符并同步维护行号列号，
 * 按 grammar.md 的词法约定产出 Token；非法输入统一抛 LexError（含行列与原因）。
 */
public class Lexer {
    private final String source; // 待分析的源字符串
    private int pos;             // 当前字符下标
    private int line;            // 当前行号（从 1 开始）
    private int col;             // 当前列号（从 1 开始）

    private static final Set<String> KEYWORDS = new HashSet<>();

    static {
        String[] kws = {"SELECT", "FROM", "WHERE", "CREATE", "TABLE", "INSERT", "INTO",
                "VALUES", "DELETE", "INT", "VARCHAR", "FLOAT", "BOOL", "AND", "OR", "NOT"};
        for (String kw : kws) {
            KEYWORDS.add(kw);
        }
    }

    public Lexer(String source) {
        this.source = source == null ? "" : source;
        this.pos = 0;
        this.line = 1;
        this.col = 1;
    }

    // 词法分析主入口：跳过空白/注释后按字符分派，产出 Token 流
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

    // 跳过连续空白、行注释（--）与块注释（/* */），直到遇到可识别内容或输入末尾
    private void skipWhitespaceAndComments() {
        while (true) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '-' && peek(1) == '-') {
                advance();
                advance();
                while (peek() != '\0' && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
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

    // 读取标识符/关键字/布尔字面量：读完词素后按大小写不敏感查关键字表
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

    // 读取数值常量（整数/浮点），非法数字（如 1.2.3）抛 LexError
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
            sb.append(advance());
            while (isDigit(peek())) {
                sb.append(advance());
            }
        }
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

    // 读取字符串常量（单引号包裹，'' 转义为单引号），未闭合抛 LexError
    private Token readString() {
        int startLine = line;
        int startCol = col;
        int startPos = pos;
        advance();
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '\0' || c == '\n') {
                throw new LexError(startLine, startCol, "未闭合的字符串");
            }
            if (c == '\'') {
                if (peek(1) == '\'') {
                    advance();
                    advance();
                    value.append('\'');
                } else {
                    advance();
                    break;
                }
            } else {
                value.append(advance());
            }
        }
        String raw = source.substring(startPos, pos);
        Token token = new Token(TokenType.CONST, raw, startLine, startCol);
        token.setConst(ConstSubtype.STRING_CONST, value.toString());
        return token;
    }

    // 读取运算符，多字符（>= <= != <> ==）优先，否则单字符
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

    // 读取单字符分隔符（( ) , ; .）
    private Token readDelimiter() {
        int startLine = line;
        int startCol = col;
        char c = advance();
        return new Token(TokenType.DELIMITER, String.valueOf(c), startLine, startCol);
    }

    // 预读当前字符，越界返回 '\0' 哨兵
    private char peek() {
        return pos < source.length() ? source.charAt(pos) : '\0';
    }

    // 预读相对当前位置偏移 offset 处的字符，越界返回 '\0' 哨兵
    private char peek(int offset) {
        int idx = pos + offset;
        return idx < source.length() ? source.charAt(idx) : '\0';
    }

    // 消费当前字符并前移游标，同时维护行号列号
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

    // 判断字符是否为英文字母
    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // 判断字符是否为数字 0-9
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // 判断字符是否为运算符起始字符
    private static boolean isOperatorChar(char c) {
        return c == '=' || c == '>' || c == '<' || c == '+' || c == '-' || c == '*' || c == '/' || c == '!';
    }

    // 判断字符是否为分隔符
    private static boolean isDelimiterChar(char c) {
        return c == '(' || c == ')' || c == ',' || c == ';' || c == '.';
    }
}
