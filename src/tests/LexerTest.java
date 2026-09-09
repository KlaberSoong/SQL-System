package tests;

import sql_compiler.Lexer;
import sql_compiler.Token;
import utils.ConstSubtype;
import utils.LexError;
import utils.TokenType;

import java.util.List;

/**
 * 词法分析器测试（对应 plan.md 3.1 与「错误测试—词法」「边界测试」）。
 *
 * 覆盖：关键字/标识符/常量/运算符/分隔符/注释、位置（行列）追踪、四类词法错误不崩溃。
 */
public class LexerTest {

    public static int run() {
        Assert a = new Assert();

        // —— 关键字 / 标识符 / 大小写 ——
        List<Token> t = new Lexer("SELECT").tokenize();
        a.checkEquals(1, t.size(), "单个关键字");
        a.checkEquals(TokenType.KEYWORD, t.get(0).getType(), "SELECT 为 KEYWORD");
        a.checkEquals("SELECT", t.get(0).getLexeme(), "词素保留原样");

        a.checkEquals(TokenType.KEYWORD, new Lexer("select").tokenize().get(0).getType(), "关键字小写不敏感 select");
        a.checkEquals(TokenType.KEYWORD, new Lexer("SeLeCt").tokenize().get(0).getType(), "关键字大小写混用 SeLeCt");
        a.checkEquals("SeLeCt", new Lexer("SeLeCt").tokenize().get(0).getLexeme(), "大小写混用时词素保留原样");

        a.checkEquals(TokenType.IDENTIFIER, new Lexer("student").tokenize().get(0).getType(), "标识符");
        a.checkEquals(TokenType.IDENTIFIER, new Lexer("user_name2").tokenize().get(0).getType(), "下划线+数字标识符");

        // —— 常量 ——
        Token num = new Lexer("20").tokenize().get(0);
        a.checkEquals(TokenType.CONST, num.getType(), "整数为 CONST");
        a.checkEquals(ConstSubtype.INT_CONST, num.getConstSubtype(), "整数子类型");
        a.checkEquals(20, num.getConstValue(), "整数值");

        Token flt = new Lexer("3.14").tokenize().get(0);
        a.checkEquals(ConstSubtype.FLOAT_CONST, flt.getConstSubtype(), "浮点子类型");
        a.checkEquals(3.14f, flt.getConstValue(), "浮点值");

        Token str = new Lexer("'Alice'").tokenize().get(0);
        a.checkEquals(ConstSubtype.STRING_CONST, str.getConstSubtype(), "字符串子类型");
        a.checkEquals("Alice", str.getConstValue(), "字符串值");

        Token esc = new Lexer("'Tom''s book'").tokenize().get(0);
        a.checkEquals("Tom's book", esc.getConstValue(), "字符串内 '' 转义");

        Token boolT = new Lexer("true").tokenize().get(0);
        a.checkEquals(ConstSubtype.BOOL_CONST, boolT.getConstSubtype(), "true 为 BOOL_CONST");
        a.checkEquals(Boolean.TRUE, boolT.getConstValue(), "true 值");
        Token boolF = new Lexer("FALSE").tokenize().get(0);
        a.checkEquals(Boolean.FALSE, boolF.getConstValue(), "FALSE 大小写不敏感");

        // —— 运算符（多字符优先） / 分隔符 ——
        String[] ops = {">=", "<=", "!=", "<>", "==", "=", ">", "<", "+", "-", "*", "/"};
        for (String op : ops) {
            Token tok = new Lexer(op).tokenize().get(0);
            a.checkEquals(TokenType.OPERATOR, tok.getType(), "运算符 " + op);
            a.checkEquals(op, tok.getLexeme(), "运算符词素 " + op);
        }
        for (String d : new String[]{"(", ")", ",", ";", "."}) {
            a.checkEquals(TokenType.DELIMITER, new Lexer(d).tokenize().get(0).getType(), "分隔符 " + d);
        }

        // —— 注释 ——
        a.checkEquals(TokenType.KEYWORD, new Lexer("-- comment\nSELECT").tokenize().get(0).getType(), "行注释被跳过");
        a.checkEquals(TokenType.KEYWORD, new Lexer("/* block */ SELECT").tokenize().get(0).getType(), "块注释被跳过");

        // —— 位置（行号/列号，从 1 开始） ——
        Token pos = new Lexer("SELECT\nFROM").tokenize().get(1);
        a.checkEquals(2, pos.getLine(), "换行后行号");
        a.checkEquals(1, pos.getCol(), "换行后列号重置");
        Token pos2 = new Lexer("   SELECT").tokenize().get(0);
        a.checkEquals(4, pos2.getCol(), "前导空白列号");

        // —— 空输入 / 纯空白 ——
        a.checkEquals(0, new Lexer("").tokenize().size(), "空串产出空流");
        a.checkEquals(0, new Lexer(null).tokenize().size(), "null 产出空流");
        a.checkEquals(0, new Lexer("  \t\n ").tokenize().size(), "纯空白产出空流");

        // —— 词法错误（不崩溃，含位置+原因） ——
        LexError e1 = a.checkThrows(LexError.class, () -> new Lexer("@").tokenize(), "非法字符");
        if (e1 != null) {
            a.checkContains(e1.getMessage(), "非法字符", "非法字符消息含原因");
            a.check(e1.getLine() >= 1 && e1.getCol() >= 1, "非法字符含位置");
        }
        LexError e2 = a.checkThrows(LexError.class, () -> new Lexer("'abc").tokenize(), "字符串未闭合");
        if (e2 != null) a.checkContains(e2.getMessage(), "未闭合", "未闭合字符串消息");
        LexError e3 = a.checkThrows(LexError.class, () -> new Lexer("1.2.3").tokenize(), "非法数字");
        if (e3 != null) a.checkContains(e3.getMessage(), "非法数字", "非法数字消息");
        LexError e4 = a.checkThrows(LexError.class, () -> new Lexer("/* no end").tokenize(), "块注释未闭合");
        if (e4 != null) a.checkContains(e4.getMessage(), "未闭合", "未闭合块注释消息");

        return a.summary("LexerTest 词法分析");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
