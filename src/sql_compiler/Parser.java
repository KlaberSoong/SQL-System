package sql_compiler;

import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.ast.UnaryExpr;
import utils.ColumnDef;
import utils.ColumnType;
import utils.ConstSubtype;
import utils.Operator;
import utils.SyntaxError;
import utils.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语法分析器：把 {@link Lexer} 产出的 Token 流解析为 AST（递归下降）。
 *
 * <p>工作方式：持有 Token 列表 {@link #tokens} 与一个向前移动的下标 {@link #index}，
 * 通过 {@link #peek()} 预读当前 token、{@link #advance()} 消费 token 并前移下标，
 * 按 grammar.md 的 EBNF 文法逐层递归下降，最终得到 {@link Statement} 树。
 *
 * <p>优先级（低 -&gt; 高）：
 * <pre>
 *   parseOr           OR
 *   parseAnd          AND
 *   parseNot          NOT
 *   parseComparison   =  !=  &lt;&gt;  &lt;  &lt;=  &gt;  &gt;=
 *   parsePrimary      列引用 / 常量 / 括号表达式
 * </pre>
 *
 * <p>契约：AST 节点（{@code ast/*}）与异常 {@link SyntaxError} 均为固定接口，本类只负责
 * 填充解析逻辑、不改动契约。所有语法错误统一抛出 {@link SyntaxError}（含行号、列号、
 * 实际符号、期望符号集合），供上层捕获展示而不崩溃。
 */
public class Parser {
    /** 待解析的 Token 流（由 Lexer 产出，按源顺序排列）。 */
    private final List<Token> tokens;
    /** 当前 token 下标（指向下一个待消费的 token，0 起始）。 */
    private int index;
    /**
     * 输入结束哨兵。词素为 {@code "<EOF>"}，用于 {@link #peek()} / {@link #advance()}
     * 越界时兜底，并在语法错误消息中展示为 {@code <EOF>}。
     */
    private final Token eof;

    /**
     * 构造语法分析器，并依据 Token 流末尾位置生成 EOF 哨兵。
     *
     * <p>哨兵位置取「最后一个 token 之后」：行号取最后 token 的行号，列号取最后 token
     * 的列号 + 词素长度（空列表时退化为 (1,1)），使缺表达式的错误定位到输入末尾。
     *
     * <p>注意：哨兵类型用 {@link TokenType#DELIMITER}（而非 IDENTIFIER / CONST），避免
     * {@link #parsePrimary()} 按「类型」把 {@code <EOF>} 误判成标识符或常量；DELIMITER 的
     * 匹配均按词素精确比较，{@code <EOF>} 不会命中任何真实分隔符。
     *
     * @param tokens 词法分析得到的 Token 流（不应为 {@code null}）
     */
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
            c = last.getCol() + last.getLexeme().length(); // 指向最后一个 token 之后
        }
        this.eof = new Token(TokenType.DELIMITER, "<EOF>", l, c);
    }

    /**
     * 语法分析主入口：program -&gt; statement (';' statement)* ';'?。
     *
     * <p>先解析一条语句，再循环解析以 {@code ';'} 分隔的后续语句；末尾允许一个多余分号。
     * 循环结束后若仍有未消费的 token，则抛出 {@link SyntaxError}（覆盖「语句间缺分号」或
     * 「输入存在多余符号」的情况）。
     *
     * @return 按源顺序排列的语句列表；源为空或仅含分号时返回空列表
     * @throws SyntaxError 任意一条语句或分隔符不满足文法时抛出（位置 + 实际符号 + 期望集合）
     */
    public List<Statement> parseProgram() {
        List<Statement> stmts = new ArrayList<>();
        stmts.add(parseStatement());
        while (matchDelimiter(";")) {
            if (peek() == eof) {
                break; // 末尾允许一个分号
            }
            stmts.add(parseStatement());
        }
        if (peek() != eof) {
            throw error(peek(), "';'", "<EOF>");
        }
        return stmts;
    }

    /**
     * 解析单条语句并分派到对应的具体解析方法。
     *
     * <p>statement := create_stmt | insert_stmt | select_stmt | delete_stmt。依据当前 token
     * 是否为关键字 {@code CREATE/INSERT/SELECT/DELETE} 决定走向；否则抛出 {@link SyntaxError}。
     *
     * @return 解析得到的 {@link Statement}（四种之一）
     * @throws SyntaxError 当前 token 不是任一语句起始关键字时抛出
     */
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
        throw error(t, "CREATE", "INSERT", "SELECT", "DELETE");
    }

    /**
     * 解析建表语句：CREATE TABLE identifier '(' column_def (',' column_def)* ')'。
     *
     * @return {@link CreateTableStmt}，携带表名与列定义列表
     * @throws SyntaxError 缺少关键字 / 表名 / 括号 / 列定义时抛出
     */
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

    /**
     * 解析列定义：column_def := identifier data_type。
     *
     * <p>data_type := INT | VARCHAR '(' INT_CONST ')' | FLOAT | BOOL。VARCHAR 必须带括号
     * 长度（长度必须是整数常量，取 {@code (Integer) token.getConstValue()}）；其余类型直接
     * 由 {@link ColumnType#fromKeyword(String)} 映射。
     *
     * @return 解析得到的 {@link ColumnDef}（含列名、类型，VARCHAR 还含长度）
     * @throws SyntaxError 列名不是标识符、数据类型不是四种关键字之一、VARCHAR 缺括号或
     *                     长度不是整数常量时抛出
     */
    private ColumnDef parseColumnDef() {
        Token name = expectIdentifier();
        Token type = peek();
        if (!isKeyword(type, "INT") && !isKeyword(type, "VARCHAR")
                && !isKeyword(type, "FLOAT") && !isKeyword(type, "BOOL")) {
            throw error(type, "INT", "VARCHAR", "FLOAT", "BOOL");
        }
        advance();
        if (type.getLexeme().equalsIgnoreCase("VARCHAR")) {
            expectDelimiter("(");
            Token len = peek();
            if (len.getType() != TokenType.CONST || len.getConstSubtype() != ConstSubtype.INT_CONST) {
                throw error(len, "int constant");
            }
            advance();
            expectDelimiter(")");
            return new ColumnDef(name.getLexeme(), ColumnType.VARCHAR, (Integer) len.getConstValue());
        }
        return new ColumnDef(name.getLexeme(), ColumnType.fromKeyword(type.getLexeme()));
    }

    /**
     * 解析插入语句：INSERT INTO identifier ( '(' column_list ')' )? VALUES
     * '(' expr (',' expr)* ')'。
     *
     * <p>列名列表可省略（此时 {@code columns == null}，语义阶段按表全列对齐）；VALUES 后的
     * 每个值按完整表达式 {@link #parseOr()} 解析（是否必须为字面量交由语义阶段检查）。
     *
     * @return {@link InsertStmt}，携带表名、列名列表（可能为 {@code null}）与值表达式列表
     * @throws SyntaxError 缺少关键字 / 表名 / 列名 / VALUES / 括号 / 值时抛出
     */
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

    /**
     * 解析查询语句：SELECT select_list FROM identifier where_clause?。
     *
     * <p>select_list := '*' | column_ref (',' column_ref)*。遇 {@code *} 产出单个 {@link Star}；
     * 否则逐个解析 {@link #parseColumnRef()}（元素可为 ColumnRef 或 Star）。
     *
     * @return {@link SelectStmt}，携带投影项列表、表名与可空的 WHERE 表达式
     * @throws SyntaxError 缺少关键字 / 投影项 / FROM / 表名时抛出
     */
    private Statement parseSelect() {
        expectKeyword("SELECT");
        List<Expr> items = new ArrayList<>();
        if (checkOperator("*")) {
            advance();
            items.add(new Star());
        } else {
            items.add(parseColumnRef());
            while (matchDelimiter(",")) {
                items.add(parseColumnRef());
            }
        }
        expectKeyword("FROM");
        Token table = expectIdentifier();
        Expr where = parseWhereClause();
        return new SelectStmt(items, table.getLexeme(), where);
    }

    /**
     * 解析删除语句：DELETE FROM identifier where_clause?。
     *
     * @return {@link DeleteStmt}，携带表名与可空的 WHERE 表达式
     * @throws SyntaxError 缺少关键字 / FROM / 表名时抛出
     */
    private Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        Token table = expectIdentifier();
        Expr where = parseWhereClause();
        return new DeleteStmt(table.getLexeme(), where);
    }

    /**
     * 解析可选的 WHERE 子句：where_clause := WHERE or_expr | ε。
     *
     * <p>命中 {@code WHERE} 则解析 {@link #parseOr()} 并返回；未命中且下一个是 {@code ';'}
     * 或输入结束则返回 {@code null}（表示无 WHERE）；否则抛出 {@link SyntaxError}（覆盖
     * 语句末尾出现无法归属的 token 的情形）。
     *
     * @return WHERE 条件表达式；无 WHERE 子句时返回 {@code null}
     * @throws SyntaxError 当前 token 既非 WHERE、也非 {@code ';'} / 输入结束时抛出
     */
    private Expr parseWhereClause() {
        if (matchKeyword("WHERE")) {
            return parseOr();
        }
        if (checkDelimiter(";") || peek() == eof) {
            return null;
        }
        throw error(peek(), "WHERE", "';'");
    }

    /**
     * 解析逻辑或：or_expr := and_expr (OR and_expr)*。
     *
     * <p>左结合，循环累积：每遇一个 {@code OR}，把左表达式与右侧 {@link #parseAnd()}
     * 合成为新的 {@link BinaryExpr}(OR, l, r)。
     *
     * @return 解析得到的表达式；无 OR 时原样返回左侧 and_expr
     */
    private Expr parseOr() {
        Expr left = parseAnd();
        while (matchKeyword("OR")) {
            left = new BinaryExpr(Operator.OR, left, parseAnd());
        }
        return left;
    }

    /**
     * 解析逻辑与：and_expr := not_expr (AND not_expr)*。
     *
     * <p>左结合，循环累积：每遇一个 {@code AND}，把左表达式与右侧 {@link #parseNot()}
     * 合成为新的 {@link BinaryExpr}(AND, l, r)。
     *
     * @return 解析得到的表达式；无 AND 时原样返回左侧 not_expr
     */
    private Expr parseAnd() {
        Expr left = parseNot();
        while (matchKeyword("AND")) {
            left = new BinaryExpr(Operator.AND, left, parseNot());
        }
        return left;
    }

    /**
     * 解析逻辑非：not_expr := NOT not_expr | comparison_expr。
     *
     * <p>遇 {@code NOT} 则递归解析右侧并包装为 {@link UnaryExpr}(NOT, operand)（支持连续
     * {@code NOT NOT ...}）；否则期望一个 comparison。此时若当前符号无法开始一个 primary，
     * 说明真正缺的是一个表达式操作数，直接在此处失败并上报 not_expr 的 FIRST 集
     * （{@code IDENTIFIER | CONST | '(' | NOT}），从而把 {@code NOT} 也纳入期望——它确实是
     * 此处的合法续写（如 {@code AND NOT deleted}）。其余情况交由 {@link #parseComparison()}。
     *
     * @return 解析得到的表达式（{@link UnaryExpr} 或比较表达式）
     * @throws SyntaxError 当前符号既非 {@code NOT}、也无法开始一个 primary 时抛出
     */
    private Expr parseNot() {
        if (matchKeyword("NOT")) {
            return new UnaryExpr(Operator.NOT, parseNot());
        }
        if (!canStartPrimary(peek())) {
            throw error(peek(), "IDENTIFIER", "CONST", "'('", "NOT");
        }
        return parseComparison();
    }

    /**
     * 解析比较表达式：comparison := primary (比较运算符 primary)?。
     *
     * <p>比较运算符集合：{@code = != <> < <= > >=}，其中 {@code !=} 与 {@code <>} 均映射为
     * {@link Operator#NE}。命中则构造 {@link Comparison}(op, l, r)，左右两侧均按
     * {@link #parsePrimary()} 解析；无运算符时原样返回左侧 primary（列引用或常量）。
     *
     * @return 解析得到的表达式（{@link Comparison} 或单个 primary）
     */
    private Expr parseComparison() {
        Expr left = parsePrimary();
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
            return new Comparison(op, left, parsePrimary());
        }
        return left;
    }

    /**
     * 判断 token 能否作为 primary 的起始：标识符、常量，或 {@code '('}。
     *
     * <p>用于在表达式解析中预判「当前位置是否真的是操作数起点」：当不能时即可立即失败，
     * 从而在更贴近语法原意的一层上报 FIRST 集（避免把非操作数位置的符号混入期望集合）。
     *
     * @param t 待判断 token
     * @return 是 primary 起始符号返回 {@code true}，否则 {@code false}
     */
    private boolean canStartPrimary(Token t) {
        return t.getType() == TokenType.IDENTIFIER
                || t.getType() == TokenType.CONST
                || checkDelimiter("(");
    }

    /**
     * 解析原子表达式：primary := identifier | constant | '(' or_expr ')'。
     *
     * <p>按当前 token 类型分派：IDENTIFIER 走 {@link #parseColumnRef()}；CONST 走
     * {@link #toLiteral(Token)}；{@code '('} 则递归解析 {@link #parseOr()} 并期望匹配
     * {@code ')'}。其余符号抛出 {@link SyntaxError}。
     *
     * @return 解析得到的表达式（{@link ColumnRef}、{@link Literal} 或括号内表达式）
     * @throws SyntaxError 当前 token 既非标识符、常量，也非 {@code '('} 时抛出
     *                     （期望标识符 / 常量 / 左括号）
     */
    private Expr parsePrimary() {
        Token t = peek();
        if (t.getType() == TokenType.IDENTIFIER) {
            return parseColumnRef();
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

    /**
     * 解析列引用：column_ref := identifier ('.' identifier)?。
     *
     * <p>读到第二个标识符且中间有 {@code '.'} 时，产出 {@code ColumnRef(table, column)}；
     * 否则产出 {@code ColumnRef(null, column)}（无表名前缀）。
     *
     * @return 解析得到的 {@link ColumnRef}
     * @throws SyntaxError 首个（或点号后的）token 不是标识符时抛出
     */
    private Expr parseColumnRef() {
        Token first = expectIdentifier();
        if (matchDelimiter(".")) {
            Token second = expectIdentifier();
            return new ColumnRef(first.getLexeme(), second.getLexeme());
        }
        return new ColumnRef(null, first.getLexeme());
    }

    /**
     * 把 CONST token 转换为 {@link Literal}：按子类型映射到对应 {@link ColumnType}。
     *
     * <p>映射关系：{@link ConstSubtype#INT_CONST} → {@link ColumnType#INT}；
     * {@link ConstSubtype#FLOAT_CONST} → {@link ColumnType#FLOAT}；
     * {@link ConstSubtype#STRING_CONST} → {@link ColumnType#VARCHAR}；
     * {@link ConstSubtype#BOOL_CONST} → {@link ColumnType#BOOL}。
     *
     * @param t 待转换的 CONST token（其 {@code getConstValue()} 已含解析后的值）
     * @return 对应类型的 {@link Literal}
     */
    private Literal toLiteral(Token t) {
        switch (t.getConstSubtype()) {
            case INT_CONST:
                return new Literal(t.getConstValue(), ColumnType.INT);
            case FLOAT_CONST:
                return new Literal(t.getConstValue(), ColumnType.FLOAT);
            case BOOL_CONST:
                return new Literal(t.getConstValue(), ColumnType.BOOL);
            case STRING_CONST:
            default:
                return new Literal(t.getConstValue(), ColumnType.VARCHAR);
        }
    }

    /**
     * 预读当前 token，不移动下标。
     *
     * @return {@link #tokens} 在 {@link #index} 处的 token；越界（已到末尾）时返回 EOF 哨兵
     */
    private Token peek() {
        return index < tokens.size() ? tokens.get(index) : eof;
    }

    /**
     * 消费当前 token 并前移下标。
     *
     * @return 被消费的 token；越界时返回 EOF 哨兵且不再前进
     */
    private Token advance() {
        if (index < tokens.size()) {
            return tokens.get(index++);
        }
        return eof;
    }

    /**
     * 判断 token 是否为指定关键字（大小写不敏感）。
     *
     * @param t  待判断 token
     * @param kw 关键字词素（大写）
     * @return token 类型为 {@link TokenType#KEYWORD} 且词素忽略大小写等于 {@code kw} 时返回
     *         {@code true}，否则 {@code false}
     */
    private boolean isKeyword(Token t, String kw) {
        return t.getType() == TokenType.KEYWORD && t.getLexeme().equalsIgnoreCase(kw);
    }

    /**
     * 预读判断当前 token 是否为指定关键字（不消费）。
     *
     * @param kw 关键字词素（大写）
     * @return 当前 token 为关键字 {@code kw} 时返回 {@code true}，否则 {@code false}
     */
    private boolean checkKeyword(String kw) {
        return isKeyword(peek(), kw);
    }

    /**
     * 若当前 token 为指定关键字则消费之。
     *
     * @param kw 关键字词素（大写）
     * @return 命中则消费并返回 {@code true}，否则不消费并返回 {@code false}
     */
    private boolean matchKeyword(String kw) {
        if (checkKeyword(kw)) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * 预读判断当前 token 是否为指定分隔符（按词素精确比较，不消费）。
     *
     * @param d 分隔符词素（如 {@code "("}、{@code ";"}、{@code "."}）
     * @return 当前 token 为分隔符 {@code d} 时返回 {@code true}，否则 {@code false}
     */
    private boolean checkDelimiter(String d) {
        Token t = peek();
        return t.getType() == TokenType.DELIMITER && t.getLexeme().equals(d);
    }

    /**
     * 若当前 token 为指定分隔符则消费之。
     *
     * @param d 分隔符词素
     * @return 命中则消费并返回 {@code true}，否则不消费并返回 {@code false}
     */
    private boolean matchDelimiter(String d) {
        if (checkDelimiter(d)) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * 预读判断当前 token 是否为指定运算符（按词素精确比较，不消费）。
     *
     * @param op 运算符词素（如 {@code ">="}、{@code "="}、{@code "*"}）
     * @return 当前 token 为运算符 {@code op} 时返回 {@code true}，否则 {@code false}
     */
    private boolean checkOperator(String op) {
        Token t = peek();
        return t.getType() == TokenType.OPERATOR && t.getLexeme().equals(op);
    }

    /**
     * 期望当前 token 为标识符，命中则消费并返回。
     *
     * @return 被消费的 IDENTIFIER token
     * @throws SyntaxError 当前 token 不是标识符时抛出（期望 {@code identifier}）
     */
    private Token expectIdentifier() {
        Token t = peek();
        if (t.getType() == TokenType.IDENTIFIER) {
            return advance();
        }
        throw error(t, "identifier");
    }

    /**
     * 期望当前 token 为指定关键字，命中则消费并返回。
     *
     * @param kw 关键字词素（大写）
     * @return 被消费的 KEYWORD token
     * @throws SyntaxError 当前 token 不是关键字 {@code kw} 时抛出
     */
    private Token expectKeyword(String kw) {
        if (checkKeyword(kw)) {
            return advance();
        }
        throw error(peek(), kw);
    }

    /**
     * 期望当前 token 为指定分隔符，命中则消费并返回。
     *
     * @param d 分隔符词素
     * @return 被消费的 DELIMITER token
     * @throws SyntaxError 当前 token 不是分隔符 {@code d} 时抛出
     */
    private Token expectDelimiter(String d) {
        if (checkDelimiter(d)) {
            return advance();
        }
        throw error(peek(), "'" + d + "'");
    }

    /**
     * 构造语法错误异常。
     *
     * @param actual   实际遇到的 token（用于取位置与词素）
     * @param expected 期望的符号集合（展示在错误消息中）
     * @return 携带行号、列号、实际符号、期望集合的 {@link SyntaxError}
     */
    private SyntaxError error(Token actual, String... expected) {
        return new SyntaxError(actual.getLine(), actual.getCol(), actual.getLexeme(),
                Arrays.asList(expected));
    }
}
