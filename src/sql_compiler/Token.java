package sql_compiler;

import utils.ConstSubtype;
import utils.TokenType;

/**
 * 词法单元，携带种别码、词素值、位置；CONST 类型额外携带子类型与解析后的值。
 */
public class Token {
    private final TokenType type;
    private final String lexeme;
    private final int line;
    private final int col;
    private ConstSubtype constSubtype; // 仅 CONST 有效
    private Object constValue;         // 仅 CONST 有效：Integer / Float / String

    public Token(TokenType type, String lexeme, int line, int col) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.col = col;
    }

    public TokenType getType() {
        return type;
    }

    public String getLexeme() {
        return lexeme;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }

    /** 仅对 CONST token 调用：设置子类型与解析后的值。 */
    public void setConst(ConstSubtype subtype, Object value) {
        this.constSubtype = subtype;
        this.constValue = value;
    }

    public ConstSubtype getConstSubtype() {
        return constSubtype;
    }

    public Object getConstValue() {
        return constValue;
    }

    /** 四元式输出：[种别码, 词素值, 行号, 列号]。 */
    @Override
    public String toString() {
        return "[" + type + ", " + lexeme + ", " + line + ", " + col + "]";
    }
}
