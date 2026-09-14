package sql_compiler;

import sql_compiler.ast.AggregateCall;
import sql_compiler.ast.Assignment;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.IsNullExpr;
import sql_compiler.ast.JoinRelation;
import sql_compiler.ast.Literal;
import sql_compiler.ast.NullLiteral;
import sql_compiler.ast.OrderByItem;
import sql_compiler.ast.Relation;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.ast.TableRelation;
import sql_compiler.ast.UnaryExpr;
import sql_compiler.ast.UpdateStmt;
import utils.ColumnDef;
import utils.ColumnType;
import utils.ConstSubtype;
import utils.JoinType;
import utils.Operator;
import utils.SyntaxError;
import utils.TokenType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语法分析器（对应 plan.md 3.2）：把 Token 流解析为 AST（递归下降）。
 * 持有 Token 列表与下标，按 grammar.md 文法逐层递归下降；表达式优先级由低到高为
 * OR &lt; AND &lt; NOT &lt; 比较 &lt; 加减 &lt; 乘除 &lt; 原子，语法错误统一抛 SyntaxError
 * （位置 + 实际符号 + 期望集合）。
 */
public class Parser {
    private final List<Token> tokens; // 待解析的 Token 流
    private int index;                // 当前 token 下标
    private final Token eof;          // 输入结束哨兵

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.index = 0;
        int l;
        int c;
        if (tokens.isEmpty()) {
            l = 1;
            c = 1;
        } else {
            Token last = tokens.get(tokens.size() - 1);
            l = last.getLine();
            c = last.getCol() + last.getLexeme().length();
        }
        this.eof = new Token(TokenType.DELIMITER, "<EOF>", l, c);
    }

    // 语法分析主入口：program -> statement (';' statement)* ';'?
    public List<Statement> parseProgram() {
        List<Statement> stmts = new ArrayList<>();
        stmts.add(parseStatement());
        while (matchDelimiter(";")) {
            if (peek() == eof) {
                break;
            }
            stmts.add(parseStatement());
        }
        if (peek() != eof) {
            throw error(peek(), "';'", "<EOF>");
        }
        return stmts;
    }

    // 解析单条语句并按起始关键字分派到 CREATE/INSERT/SELECT/DELETE/UPDATE
    private Statement parseStatement() {
        Token t = peek();
        if (isKeyword(t, "CREATE")) {
            return parseCreateTable();
        }
        if (isKeyword(t, "INSERT")) {
            return parseInsert();
        }
        if (isKeyword(t, "SELECT")) {
            return parseSelect();
        }
        if (isKeyword(t, "DELETE")) {
            return parseDelete();
        }
        if (isKeyword(t, "UPDATE")) {
            return parseUpdate();
        }
        throw error(t, "CREATE", "INSERT", "SELECT", "DELETE", "UPDATE");
    }

    // 解析建表语句：CREATE TABLE identifier '(' column_def (',' column_def)* ')'
    private Statement parseCreateTable() {
        expectKeyword("CREATE");
        expectKeyword("TABLE");
        Token table = expectIdentifier();
        expectDelimiter("(");
        List<ColumnDef> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (matchDelimiter(",")) {
            columns.add(parseColumnDef());
        }
        expectDelimiter(")");
        return new CreateTableStmt(table.getLexeme(), columns);
    }

    // 解析列定义：identifier data_type（VARCHAR/CHAR 带 (n)，DECIMAL 带 (p,s)，DATE/TEXT/INT/FLOAT/BOOL 无参数）
    private ColumnDef parseColumnDef() {
        Token name = expectIdentifier();
        Token type = peek();
        if (!isKeyword(type, "INT") && !isKeyword(type, "VARCHAR")
                && !isKeyword(type, "FLOAT") && !isKeyword(type, "BOOL")
                && !isKeyword(type, "DATE") && !isKeyword(type, "DECIMAL")
                && !isKeyword(type, "CHAR") && !isKeyword(type, "TEXT")) {
            throw error(type, "INT", "VARCHAR", "FLOAT", "BOOL", "DATE", "DECIMAL", "CHAR", "TEXT");
        }
        advance();
        String kw = type.getLexeme();
        if (kw.equalsIgnoreCase("VARCHAR") || kw.equalsIgnoreCase("CHAR")) {
            expectDelimiter("(");
            int len = expectIntConst();
            expectDelimiter(")");
            return new ColumnDef(name.getLexeme(), ColumnType.fromKeyword(kw), len);
        }
        if (kw.equalsIgnoreCase("DECIMAL")) {
            expectDelimiter("(");
            int precision = expectIntConst();
            expectDelimiter(",");
            int scale = expectIntConst();
            expectDelimiter(")");
            return new ColumnDef(name.getLexeme(), ColumnType.DECIMAL, precision, scale);
        }
        return new ColumnDef(name.getLexeme(), ColumnType.fromKeyword(kw));
    }

    // 解析插入语句：INSERT INTO identifier ( '(' column_list ')' )? VALUES '(' expr (',' expr)* ')'
    private Statement parseInsert() {
        expectKeyword("INSERT");
        expectKeyword("INTO");
        Token table = expectIdentifier();
        List<String> columns = null;
        if (checkDelimiter("(")) {
            advance();
            columns = new ArrayList<>();
            columns.add(expectIdentifier().getLexeme());
            while (matchDelimiter(",")) {
                columns.add(expectIdentifier().getLexeme());
            }
            expectDelimiter(")");
        }
        expectKeyword("VALUES");
        expectDelimiter("(");
        List<Expr> values = new ArrayList<>();
        values.add(parseOr());
        while (matchDelimiter(",")) {
            values.add(parseOr());
        }
        expectDelimiter(")");
        return new InsertStmt(table.getLexeme(), columns, values);
    }

    // 解析查询语句：SELECT select_list FROM relation where_opt group_opt order_opt
    private Statement parseSelect() {
        expectKeyword("SELECT");
        List<Expr> items = new ArrayList<>();
        if (checkOperator("*")) {
            advance();
            items.add(new Star());
        } else {
            items.add(parseOr());
            while (matchDelimiter(",")) {
                items.add(parseOr());
            }
        }
        expectKeyword("FROM");
        Relation from = parseRelation();
        Expr where = parseWhereClause();

        List<Expr> groupBy = null;
        if (matchKeyword("GROUP")) {
            expectKeyword("BY");
            groupBy = new ArrayList<>();
            groupBy.add(parseOr());
            while (matchDelimiter(",")) {
                groupBy.add(parseOr());
            }
        }

        List<OrderByItem> orderBy = null;
        if (matchKeyword("ORDER")) {
            expectKeyword("BY");
            orderBy = parseOrderBy();
        }

        return new SelectStmt(items, from, where, groupBy, orderBy);
    }

    // 解析删除语句：DELETE FROM identifier where_clause?
    private Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        Token table = expectIdentifier();
        Expr where = parseWhereClause();
        return new DeleteStmt(table.getLexeme(), where);
    }

    // 解析更新语句：UPDATE identifier SET assignment (',' assignment)* where_clause?
    private Statement parseUpdate() {
        expectKeyword("UPDATE");
        Token table = expectIdentifier();
        expectKeyword("SET");
        List<Assignment> assignments = new ArrayList<>();
        assignments.add(parseAssignment());
        while (matchDelimiter(",")) {
            assignments.add(parseAssignment());
        }
        Expr where = parseWhereClause();
        return new UpdateStmt(table.getLexeme(), assignments, where);
    }

    // 解析赋值项：identifier '=' or_expr
    private Assignment parseAssignment() {
        Token col = expectIdentifier();
        if (!checkOperator("=")) {
            throw error(peek(), "'='");
        }
        advance();
        Expr value = parseOr();
        return new Assignment(col.getLexeme(), value);
    }

    // 解析 FROM 关系：table_ref (',' table_ref | join_clause)*，左结合
    private Relation parseRelation() {
        Relation left = parseTableRef();
        while (true) {
            if (matchDelimiter(",")) {
                // 逗号 = 交叉连接（内连接且无 ON）
                left = new JoinRelation(left, parseTableRef(), JoinType.INNER, null);
            } else if (matchKeyword("INNER")) {
                expectKeyword("JOIN");
                Relation right = parseTableRef();
                expectKeyword("ON");
                left = new JoinRelation(left, right, JoinType.INNER, parseOr());
            } else if (matchKeyword("LEFT")) {
                expectKeyword("JOIN");
                Relation right = parseTableRef();
                expectKeyword("ON");
                left = new JoinRelation(left, right, JoinType.LEFT, parseOr());
            } else if (matchKeyword("JOIN")) {
                // 省略 INNER 的简写 JOIN 等价于 INNER JOIN
                Relation right = parseTableRef();
                expectKeyword("ON");
                left = new JoinRelation(left, right, JoinType.INNER, parseOr());
            } else {
                break;
            }
        }
        return left;
    }

    // 解析表引用：identifier (AS identifier | identifier)?
    private Relation parseTableRef() {
        Token table = expectIdentifier();
        String alias = null;
        if (matchKeyword("AS")) {
            alias = expectIdentifier().getLexeme();
        } else if (peek().getType() == TokenType.IDENTIFIER) {
            alias = expectIdentifier().getLexeme();
        }
        return new TableRelation(table.getLexeme(), alias);
    }

    // 解析 ORDER BY 键列表：or_expr [ASC|DESC] (',' or_expr [ASC|DESC])*
    private List<OrderByItem> parseOrderBy() {
        List<OrderByItem> items = new ArrayList<>();
        items.add(parseOrderByItem());
        while (matchDelimiter(",")) {
            items.add(parseOrderByItem());
        }
        return items;
    }

    // 解析单个排序键，默认升序
    private OrderByItem parseOrderByItem() {
        Expr expr = parseOr();
        boolean asc = true;
        if (matchKeyword("ASC")) {
            asc = true;
        } else if (matchKeyword("DESC")) {
            asc = false;
        }
        return new OrderByItem(expr, asc);
    }

    // 解析可选的 WHERE 子句：WHERE or_expr | ε（无则返回 null）
    private Expr parseWhereClause() {
        if (matchKeyword("WHERE")) {
            return parseOr();
        }
        return null;
    }

    // 解析逻辑或：and_expr (OR and_expr)*，左结合
    private Expr parseOr() {
        Expr left = parseAnd();
        while (matchKeyword("OR")) {
            left = new BinaryExpr(Operator.OR, left, parseAnd());
        }
        return left;
    }

    // 解析逻辑与：not_expr (AND not_expr)*，左结合
    private Expr parseAnd() {
        Expr left = parseNot();
        while (matchKeyword("AND")) {
            left = new BinaryExpr(Operator.AND, left, parseNot());
        }
        return left;
    }

    // 解析逻辑非：NOT not_expr | comparison
    private Expr parseNot() {
        if (matchKeyword("NOT")) {
            return new UnaryExpr(Operator.NOT, parseNot());
        }
        if (!canStartPrimary(peek())) {
            throw error(peek(), "IDENTIFIER", "CONST", "'('", "NOT");
        }
        return parseComparison();
    }

    // 解析比较表达式：additive (比较运算符 additive)?，!= 与 <> 均映射为 NE；另支持 IS [NOT] NULL 后置
    private Expr parseComparison() {
        Expr left = parseAdditive();
        if (matchKeyword("IS")) {
            boolean notNull = matchKeyword("NOT");
            expectNullLiteral();
            return new IsNullExpr(left, notNull);
        }
        Operator op = null;
        if (checkOperator("=")) {
            op = Operator.EQ;
        } else if (checkOperator("!=")) {
            op = Operator.NE;
        } else if (checkOperator("<>")) {
            op = Operator.NE;
        } else if (checkOperator("<")) {
            op = Operator.LT;
        } else if (checkOperator("<=")) {
            op = Operator.LE;
        } else if (checkOperator(">")) {
            op = Operator.GT;
        } else if (checkOperator(">=")) {
            op = Operator.GE;
        }
        if (op != null) {
            advance();
            return new Comparison(op, left, parseAdditive());
        }
        return left;
    }

    // 解析加减表达式：multiplicative (('+'|'-') multiplicative)*，左结合
    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (true) {
            Operator op;
            if (checkOperator("+")) {
                op = Operator.PLUS;
            } else if (checkOperator("-")) {
                op = Operator.MINUS;
            } else {
                break;
            }
            advance();
            left = new BinaryExpr(op, left, parseMultiplicative());
        }
        return left;
    }

    // 解析乘除表达式：primary (('*'|'/') primary)*，左结合
    private Expr parseMultiplicative() {
        Expr left = parsePrimary();
        while (true) {
            Operator op;
            if (checkOperator("*")) {
                op = Operator.MUL;
            } else if (checkOperator("/")) {
                op = Operator.DIV;
            } else {
                break;
            }
            advance();
            left = new BinaryExpr(op, left, parsePrimary());
        }
        return left;
    }

    // 判断 token 能否作为 primary 起始（标识符/常量/'('/类型化字面量 DATE|DECIMAL）
    private boolean canStartPrimary(Token t) {
        return t.getType() == TokenType.IDENTIFIER
                || t.getType() == TokenType.CONST
                || isKeyword(t, "DATE")
                || isKeyword(t, "DECIMAL")
                || checkDelimiter("(");
    }

    // 解析原子表达式：identifier | constant | '(' or_expr ')' | 聚合调用 | DATE/DECIMAL 类型化字面量
    private Expr parsePrimary() {
        Token t = peek();
        if (t.getType() == TokenType.IDENTIFIER) {
            if (isAggregateFunction(t.getLexeme()) && isDelimiter(peek(1), "(")) {
                return parseAggregateCall();
            }
            return parseColumnRef();
        }
        if (isKeyword(t, "DATE") && isStringConst(peek(1))) {
            return parseTypedLiteral(ColumnType.DATE);
        }
        if (isKeyword(t, "DECIMAL") && isStringConst(peek(1))) {
            return parseTypedLiteral(ColumnType.DECIMAL);
        }
        if (t.getType() == TokenType.CONST) {
            advance();
            return toLiteral(t);
        }
        if (checkDelimiter("(")) {
            advance();
            Expr e = parseOr();
            expectDelimiter(")");
            return e;
        }
        throw error(t, "IDENTIFIER", "CONST", "'('");
    }

    // 解析聚合调用：name '(' ( '*' | or_expr ) ')'
    private Expr parseAggregateCall() {
        Token name = expectIdentifier();
        String func = name.getLexeme().toUpperCase();
        expectDelimiter("(");
        Expr arg = null;
        if (func.equals("COUNT") && checkOperator("*")) {
            advance(); // COUNT(*)
        } else {
            arg = parseOr();
        }
        expectDelimiter(")");
        return new AggregateCall(func, arg);
    }

    // 判断标识符是否为聚合函数名（COUNT/SUM/AVG/MIN/MAX，大小写不敏感）
    private static boolean isAggregateFunction(String name) {
        String u = name.toUpperCase();
        return u.equals("COUNT") || u.equals("SUM") || u.equals("AVG")
                || u.equals("MIN") || u.equals("MAX");
    }

    // 解析列引用：identifier ('.' identifier)?，无点号时 table 为 null
    private Expr parseColumnRef() {
        Token first = expectIdentifier();
        if (matchDelimiter(".")) {
            Token second = expectIdentifier();
            return new ColumnRef(first.getLexeme(), second.getLexeme());
        }
        return new ColumnRef(null, first.getLexeme());
    }

    // 把 CONST token 按子类型转换为对应表达式（NULL_CONST 转为 NullLiteral，其余转为 Literal）
    private Expr toLiteral(Token t) {
        switch (t.getConstSubtype()) {
            case INT_CONST:
                return new Literal(t.getConstValue(), ColumnType.INT);
            case FLOAT_CONST:
                return new Literal(t.getConstValue(), ColumnType.FLOAT);
            case BOOL_CONST:
                return new Literal(t.getConstValue(), ColumnType.BOOL);
            case STRING_CONST:
                return new Literal(t.getConstValue(), ColumnType.VARCHAR);
            case NULL_CONST:
                return new NullLiteral();
            default:
                return new Literal(t.getConstValue(), ColumnType.VARCHAR);
        }
    }

    // 期望当前 token 为整型常量，命中则消费并返回其值，否则报错
    private int expectIntConst() {
        Token t = peek();
        if (t.getType() != TokenType.CONST || t.getConstSubtype() != ConstSubtype.INT_CONST) {
            throw error(t, "int constant");
        }
        advance();
        return (Integer) t.getConstValue();
    }

    // 判断指定 token 是否为字符串常量
    private boolean isStringConst(Token t) {
        return t.getType() == TokenType.CONST && t.getConstSubtype() == ConstSubtype.STRING_CONST;
    }

    // 解析类型化字面量：DATE '...' / DECIMAL '...'，按目标类型解析字符串，非法格式抛 SyntaxError
    private Expr parseTypedLiteral(ColumnType type) {
        advance(); // 消费 DATE / DECIMAL 关键字
        Token str = advance(); // 消费字符串常量
        String s = (String) str.getConstValue();
        try {
            if (type == ColumnType.DATE) {
                return new Literal(LocalDate.parse(s), ColumnType.DATE);
            }
            return new Literal(new BigDecimal(s), ColumnType.DECIMAL);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new SyntaxError(str.getLine(), str.getCol(), s,
                    Arrays.asList("valid " + type.getKeyword() + " literal"));
        }
    }

    // 期望当前 token 为 NULL 字面量（NULL_CONST 常量），命中则消费，否则报错
    private void expectNullLiteral() {
        Token t = peek();
        if (t.getType() != TokenType.CONST || t.getConstSubtype() != ConstSubtype.NULL_CONST) {
            throw error(t, "NULL");
        }
        advance();
    }

    // 预读当前 token，越界返回 EOF 哨兵
    private Token peek() {
        return index < tokens.size() ? tokens.get(index) : eof;
    }

    // 预读相对当前位置偏移 offset 处的 token，越界返回 EOF 哨兵
    private Token peek(int offset) {
        int idx = index + offset;
        return idx < tokens.size() ? tokens.get(idx) : eof;
    }

    // 消费当前 token 并前移下标，越界返回 EOF 哨兵
    private Token advance() {
        if (index < tokens.size()) {
            return tokens.get(index++);
        }
        return eof;
    }

    // 判断 token 是否为指定关键字（大小写不敏感）
    private boolean isKeyword(Token t, String kw) {
        return t.getType() == TokenType.KEYWORD && t.getLexeme().equalsIgnoreCase(kw);
    }

    // 预读判断当前 token 是否为指定关键字
    private boolean checkKeyword(String kw) {
        return isKeyword(peek(), kw);
    }

    // 若当前 token 为指定关键字则消费之，否则不消费
    private boolean matchKeyword(String kw) {
        if (checkKeyword(kw)) {
            advance();
            return true;
        }
        return false;
    }

    // 预读判断当前 token 是否为指定分隔符
    private boolean checkDelimiter(String d) {
        Token t = peek();
        return t.getType() == TokenType.DELIMITER && t.getLexeme().equals(d);
    }

    // 判断指定 token 是否为指定分隔符
    private boolean isDelimiter(Token t, String d) {
        return t.getType() == TokenType.DELIMITER && t.getLexeme().equals(d);
    }

    // 若当前 token 为指定分隔符则消费之，否则不消费
    private boolean matchDelimiter(String d) {
        if (checkDelimiter(d)) {
            advance();
            return true;
        }
        return false;
    }

    // 预读判断当前 token 是否为指定运算符
    private boolean checkOperator(String op) {
        Token t = peek();
        return t.getType() == TokenType.OPERATOR && t.getLexeme().equals(op);
    }

    // 期望当前 token 为标识符，命中则消费并返回，否则报错
    private Token expectIdentifier() {
        Token t = peek();
        if (t.getType() == TokenType.IDENTIFIER) {
            return advance();
        }
        throw error(t, "identifier");
    }

    // 期望当前 token 为指定关键字，命中则消费并返回，否则报错
    private Token expectKeyword(String kw) {
        if (checkKeyword(kw)) {
            return advance();
        }
        throw error(peek(), kw);
    }

    // 期望当前 token 为指定分隔符，命中则消费并返回，否则报错
    private Token expectDelimiter(String d) {
        if (checkDelimiter(d)) {
            return advance();
        }
        throw error(peek(), "'" + d + "'");
    }

    // 构造语法错误异常（携带行号列号 + 实际符号 + 期望集合）
    private SyntaxError error(Token actual, String... expected) {
        return new SyntaxError(actual.getLine(), actual.getCol(), actual.getLexeme(),
                Arrays.asList(expected));
    }
}
