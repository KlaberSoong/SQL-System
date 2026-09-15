# SQL 编译器运作原理

> 一本把 **编译原理** 与 **数据库系统** 两门课缝合起来的「教材」。
> 阅读对象：已经知道「编译器分前端后端」「数据库有查询优化」但还没亲手做过一个完整 SQL 编译器的读者。
> 全文以本仓库 `src/sql_compiler/` 的实现为蓝本，逐阶段说明：**它做了什么、为什么这么做、背后对应哪条理论**。

---

## 目录

- [第 0 章 引言：SQL 编译器与传统编译器的同与不同](#第-0-章-引言sql-编译器与传统编译器的同与不同)
- [第 1 章 词法分析：从字符流到 Token 流](#第-1-章-词法分析从字符流到-token-流)
- [第 2 章 语法分析：从 Token 流到抽象语法树](#第-2-章-语法分析从-token-流到抽象语法树)
- [第 3 章 语义分析：从「合法语法」到「有意义的程序」](#第-3-章-语义分析从合法语法到有意义的程序)
- [第 4 章 类型系统：编译器里的裁判](#第-4-章-类型系统编译器里的裁判)
- [第 5 章 执行计划生成：从 AST 到关系代数](#第-5-章-执行计划生成从-ast-到关系代数)
- [第 6 章 规则式查询优化：等价的等价变换](#第-6-章-规则式查询优化等价的等价变换)
- [第 7 章 从计划到执行：编译器如何「把手伸向」数据库引擎](#第-7-章-从计划到执行编译器如何把手伸向数据库引擎)
- [第 8 章 错误处理：贯穿三阶段的错误报告体系](#第-8-章-错误处理贯穿三阶段的错误报告体系)
- [附录 端到端：一条 SQL 的完整生命周期](#附录-端到端一条-sql-的完整生命周期)

---

## 第 0 章 引言：SQL 编译器与传统编译器的同与不同

### 0.1 一句大白话

所谓 **SQL 编译器**，就是把一段人类可读的 SQL 文本，翻译成数据库引擎能够直接执行的**机器表示**（在本项目里，就是一棵「逻辑执行计划」算子树的翻译器。它和 C 语言编译器共享同一套前端骨架，但目标产物截然不同：C 编译器产出 CPU 指令，SQL 编译器产出的是**关系代数算子**。

### 0.2 传统编译器 vs SQL 编译器的对照

| 阶段 | 传统编译器（如 C） | 本项目（MiniDB） | 共同的理论内核 |
|------|-------------------|------------------|----------------|
| 词法分析 | 字符 → Token（`int`、标识符…） | 字符 → Token（`SELECT`、列名…） | 正则表达式 / 有限自动机 |
| 语法分析 | Token → 抽象语法树 | Token → 抽象语法树（AST） | 上下文无关文法 / 递归下降 |
| 语义分析 | 名字绑定 + 类型检查（符号表） | 名字绑定 + 类型检查（Catalog 符号表） | 符号表 / 属性文法 |
| 中间表示 | 三地址码 / IR | **逻辑执行计划（算子树）** | 中间代码生成 |
| 优化 | 常量折叠、死代码消除… | 谓词下推、投影剪枝、常量折叠… | 数据流分析 / 等价重写 |
| 目标代码 | 机器码 | 交给执行引擎逐算子执行 | 目标代码生成 |

你会发现一个漂亮的对称性：**数据库查询引擎的「前端」本质上就是一个编译器**。这就是为什么数据库教材讲查询处理时，总是先讲一遍词法、语法、语义——它们是同一门学问。

### 0.3 本项目的完整数据流

```
SQL 文本  "SELECT name FROM student WHERE age > 18;"
   │
   │  [1] Lexer.tokenize()          词法分析
   ▼
Token 流   [KEYWORD, SELECT, 1, 1] [IDENTIFIER, name, 1, 8] ...
   │
   │  [2] Parser.parseProgram()     语法分析（递归下降）
   ▼
AST        SelectStmt(items=[ColumnRef(name)], from=TableRelation(student), where=Comparison(>))
   │
   │  [3] SemanticAnalyzer.analyze()  语义分析（符号表 + 类型检查）
   ▼
Typed AST   (列名已绑定到 Catalog，类型已推导，CREATE TABLE 已注册)
   │
   │  [4] Planner.plan()            计划生成（AST → 关系代数）
   ▼
Logical Plan   Project → Filter → SeqScan
   │
   │  [5] Optimizer.optimize()      规则式优化（5 条等价规则到不动点）
   ▼
Optimized Plan   Filter → SeqScan   （谓词下推后）
   │
   │  [6] Executor.execute()        执行引擎逐算子求值
   ▼
QueryResult / 操作提示
```

这条流水线在 [Main.java:95-104](src/cli/Main.java#L95-L104) 里被原样串联起来：

```java
List<Token> tokens = new Lexer(sql).tokenize();                      // 1 词法
List<Statement> statements = new Parser(tokens).parseProgram();      // 2 语法
for (Statement stmt : statements) {
    new SemanticAnalyzer(catalog).analyze(singletonList(stmt));      // 3 语义
    PlanNode plan = new Planner().plan(stmt);                        // 4 计划
    plan = new Optimizer().optimize(plan);                           // 5 优化
    Object result = new Executor(storage, catalogManager).execute(plan); // 6 执行
}
```

后面的每一章，就是把这六步拆开讲透。

---

## 第 1 章 词法分析：从字符流到 Token 流

### 1.1 理论基础：正则表达式与有限自动机

词法分析的任务是**把一串字符切分成一个个有意义的「词」**（词法单元，Token）。在编译原理里，这一步的严格工具是：

- **正则表达式** 描述每种词法的「长什么样」。例如标识符是 `[A-Za-z_][A-Za-z0-9_]*`，整数是 `[0-9]+`。
- **有限自动机（DFA/NFA）** 是识别这些正则表达式的机器。理论上你应该先把各正则拼成一个大 NFA，再确定化、最小化成 DFA，然后驱动它读字符。

教科书走「正则 → NFA → DFA → 表驱动扫描」的正规流程；**工程实践里，当词法简单、种类少时，人们常常直接手写一个扫描器**——本项目就是后者。手写扫描器的本质仍然是那个有限自动机，只是把「状态转移」直接用 `if/else` 和循环写了出来。

### 1.2 Token 的四元式表示

一个 Token 要回答四个问题：**它是什么（种别码）、它长什么样（词素）、它在哪（行、列）**。这正是教科书里的「四元式」`<种别码, 属性值, 行号, 列号>`。

[Token.java](src/sql_compiler/Token.java) 的字段与之逐一对应：

| 字段 | 含义 | 对应四元式元素 |
|------|------|----------------|
| `type` | 种别码：`KEYWORD / IDENTIFIER / CONST / OPERATOR / DELIMITER` | 种别码 |
| `lexeme` | 词素（源代码里的原始文本） | 属性值 |
| `line` / `col` | 行列号（从 1 开始） | 行号 / 列号 |
| `constSubtype` / `constValue` | 仅 `CONST` 有效：常量子类型 + 解析后的值 | 属性（词法阶段的提前求值） |

种别码定义在 [TokenType.java](src/utils/TokenType.java)，常量子类型在 [ConstSubtype.java](src/utils/ConstSubtype.java)。

> **为什么给 `CONST` 多带两个字段？** 因为词法阶段顺手就能把 `"20"` 解析成整数 `20`、`"'abc'"` 解析成字符串 `"abc"`。把这种「机械的、局部的」求值前移到词法层，后续语法、语义阶段就不用再碰字符串，直接拿结构化数据。这是「把能早做的提前做」的经典分层思路。

### 1.3 手写扫描器的骨架

[Lexer.java](src/sql_compiler/Lexer.java) 的核心是三个游标原语 + 一个主循环：

- [peek() / peek(offset)](src/sql_compiler/Lexer.java#L204-L212)：预读当前（或偏移 offset 处）字符，越界返回 `'\0'` 哨兵。**只读不动**。
- [advance()](src/sql_compiler/Lexer.java#L215-L224)：消费当前字符、前移游标，并**同步维护行号列号**（遇 `\n` 行号 +1、列号归 1，否则列号 +1）。
- [tokenize()](src/sql_compiler/Lexer.java#L41-L64)：主循环——跳过空白注释 → 看**首字符**分派到对应读取器。

主循环的分派逻辑，本质就是那个 DFA 的「初始状态」：

```java
char c = peek();
if (isLetter(c) || c == '_')        readIdentifierOrKeyword(); // 字母/下划线 → 标识符或关键字
else if (isDigit(c))                 readNumber();             // 数字 → 数值常量
else if (c == '\'')                  readString();             // 引号 → 字符串
else if (isOperatorChar(c))          readOperator();           // 运算符起始字符
else if (isDelimiterChar(c))         readDelimiter();          // 分隔符
else                                 throw new LexError(...);  // 非法字符 → 报错
```

**只根据首字符即可决定走哪条路**，这正是「词法规则互不冲突」带来的好处——也是正则语言能被 DFA 高效识别的直观体现。

### 1.4 各类词法成分的识别

**(1) 标识符 / 关键字 / 布尔与 NULL 字面量** — [readIdentifierOrKeyword()](src/sql_compiler/Lexer.java#L101-L116)

读完整个「字母数字下划线」词素后，分四种归宿：

- 词素是 `true` / `false`（大小写不敏感）→ 直接生成 `BOOL_CONST`，值就是布尔本身；
- 词素是 `null`（大小写不敏感）→ 直接生成 `NULL_CONST`，值就是 SQL NULL（`null`）；
- 词素大写后命中关键字表 `KEYWORDS` → `KEYWORD`；
- 否则 → `IDENTIFIER`。

关键字表在 [Lexer.java:23-31](src/sql_compiler/Lexer.java#L23-L31) 静态初始化，**大小写不敏感**（`select` 和 `SELECT` 等价），这也符合 SQL 惯例。

> **设计细节：为什么 `true/false`、`null` 在关键字判断之前特殊处理？** 因为它们按语法其实是**常量**（`WHERE active = true`、`WHERE age IS NULL`），而不是关键字。把它们归到 `CONST/BOOL_CONST`、`CONST/NULL_CONST`，后续 `parsePrimary` 才能把它们当字面量处理。这也是「词法层分辨种别码」比「语法层再猜」更省事的典型例子。

**(2) 数值常量** — [readNumber()](src/sql_compiler/Lexer.java#L119-L145)

读整数部分 → 若遇到 `.` 且后跟数字则读小数部分 → 标记 `isFloat`。特别地，第二个 `if (peek() == '.' && isDigit(peek(1)))` 专门捕获 `1.2.3` 这种**非法数字**并报错（读完 `1.2` 后又发现 `.3`）。整数用 `Integer.parseInt`，浮点用 `Float.parseFloat`，把词素直接变成值。

**(3) 字符串常量** — [readString()](src/sql_compiler/Lexer.java#L148-L176)

单引号包裹；`''` 表示转义后的单引号（读到一个 `'` 时若下一个还是 `'`，则吞掉两个并写入一个 `'`）；遇到行末或 EOF 仍未闭合则抛「未闭合的字符串」。注意词素 `raw` 保留的是**带引号的原文**，而 `constValue` 是**去掉引号、还原转义后的真实值**——两件事分开存。

**(4) 运算符** — [readOperator()](src/sql_compiler/Lexer.java#L179-L193)

**多字符优先匹配**（最长匹配原则）：`>= <= != <> ==` 两个字符一起读，否则读单字符。`<>` 是 `!=` 的 SQL 方言别名，二者都会在语法层被归一化为 `NE`。

**(5) 分隔符** — [readDelimiter()](src/sql_compiler/Lexer.java#L196-L201)

单字符：`( ) , ; .`。

### 1.5 空白与注释

[skipWhitespaceAndComments()](src/sql_compiler/Lexer.java#L67-L98) 处理三类「垃圾字符」：

- 空白 ` \t\r\n`；
- 行注释 `--`（到行尾）；
- 块注释 `/* ... */`（不嵌套，未闭合抛「未闭合的块注释」）。

词法分析器把空白注释当作「不存在」，只负责把它们从字符流里吃掉。这样语法分析器拿到的 Token 流是**干净、连续、无噪声**的——这是前端分层带来的一个直接红利。

### 1.6 词法错误处理

所有词法错误统一抛 [LexError](src/utils/LexError.java)，格式 `[LexError] at (行,列): 原因`。错误点有三处：非法字符、未闭合字符串/块注释、非法数字。关键是**不崩溃**——抛异常让上层（Main 的 try/catch）捕获后把错误消息展示给用户。

### 1.7 一个完整实例

输入：

```sql
SELECT name FROM student WHERE age > 18;
```

词法分析产出（示意，`(行,列)` 省略）：

```
[KEYWORD, SELECT, 1, 1]
[IDENTIFIER, name, 1, 8]
[KEYWORD, FROM, 1, 13]
[IDENTIFIER, student, 1, 18]
[KEYWORD, WHERE, 1, 26]
[IDENTIFIER, age, 1, 32]
[OPERATOR, >, 1, 36]
[CONST, 18, 1, 38]      (constSubtype=INT_CONST, constValue=18)
[DELIMITER, ;, 1, 40]
```

注意 `SELECT` 是 `KEYWORD`、`name/student/age` 是 `IDENTIFIER`、`>` 是 `OPERATOR`、`18` 是带值的 `CONST`——**种别码的区分已经在词法层完成**，语法分析器接下来只需要关心「是什么类别」，不必再看字符。

---

## 第 2 章 语法分析：从 Token 流到抽象语法树

### 2.1 理论基础：上下文无关文法（CFG）

语法分析要回答的问题是：**这些 Token 按什么结构组合在一起？** 描述这个结构的工具是**上下文无关文法**（CFG），它由一组**产生式**（`非终结符 → 一串符号`）构成。本项目的形式化文法写在 [grammar.md](grammar.md)，其中顶层规则：

```
statement      -> create_stmt | insert_stmt | select_stmt | delete_stmt | update_stmt ;
select_stmt    -> SELECT select_list FROM relation where_opt group_opt order_opt ';' ;
relation       -> table_ref ( (',' table_ref) | (INNER|LEFT)? JOIN table_ref ON condition )* ;
update_stmt    -> UPDATE identifier SET assignment (',' assignment)* where_opt ';' ;
expression     := comparison
comparison     := additive (('='|'!='|'<>'|'<'|'<='|'>'|'>=') additive)?
                | additive IS [NOT] NULL
additive       := multiplicative (('+'|'-') multiplicative)*
multiplicative := primary (('*'|'/') primary)*
primary        := identifier ('.' identifier)? | CONST | '(' expression ')'
                | NULL | DATE STRING_CONST | DECIMAL STRING_CONST | aggregate_call
aggregate_call := (COUNT | SUM | AVG | MIN | MAX) '(' ('*' | expression) ')'
...
```

**推导**（derivation）就是从文法开始符号出发，反复把非终结符替换成其产生式右部，最终得到一个只含终结符（Token）的串。语法分析就是**反向地**找出这个推导过程，并据此建出树。

### 2.2 递归下降 + 优先级爬升

实现语法分析器的主流方法有两大类：**自顶向下**（LL，递归下降是其中最常见的手写形式）与**自底向上**（LR，如 yacc/bison）。本项目选 **递归下降**——原因直白：文法简单、没有左递归、便于手写、报错信息好定制。

递归下降的基本套路：**每个非终结符写一个方法**，方法体内按产生式右部**依次匹配 Token**。本项目 [Parser.java](src/sql_compiler/Parser.java) 的方法与文法一一对应：

| 方法 | 对应文法（产生式） | 优先级 |
|------|-------------------|--------|
| [parseProgram()](src/sql_compiler/Parser.java#L54) | `statement (';' statement)* ';'?` | 顶层 |
| [parseStatement()](src/sql_compiler/Parser.java#L70) | 按关键字分派 CREATE/INSERT/SELECT/DELETE/UPDATE | — |
| [parseOr()](src/sql_compiler/Parser.java#L190) | `and_expr (OR and_expr)*` | 最低 |
| [parseAnd()](src/sql_compiler/Parser.java#L199) | `not_expr (AND not_expr)*` | |
| [parseNot()](src/sql_compiler/Parser.java#L208) | `NOT not_expr \| comparison` | |
| [parseComparison()](src/sql_compiler/Parser.java#L219) | `additive (比较运算符 additive)? \| additive IS [NOT] NULL` | |
| [parseAdditive()](src/sql_compiler/Parser.java#L245) | `multiplicative (('+'\|'-') multiplicative)*` | |
| [parseMultiplicative()](src/sql_compiler/Parser.java#L263) | `primary (('*'\|'/') primary)*` | |
| [parsePrimary()](src/sql_compiler/Parser.java#L288) | `identifier \| constant \| NULL \| DATE/DECIMAL 类型化字面量 \| '(' or_expr ')' \| 聚合调用` | 最高 |

### 2.3 优先级与结合性是怎么「自动」实现的

递归下降写表达式有个精妙之处：**运算符优先级由「调用链的嵌套层次」自然编码**。看这条调用链：

```
parseOr → parseAnd → parseNot → parseComparison → parseAdditive → parseMultiplicative → parsePrimary
```

优先级越**低**的运算符，其方法在调用链上越靠**外**（越晚被「收紧」），因而越晚结合。以 `a OR b AND c` 为例：`parseOr` 先调 `parseAnd` 把 `b AND c` 先拼成一颗子树，再把 `a OR (b AND c)` 拼起来——`AND` 比 `OR` 结合得更紧，优先级更高。**不需要任何优先级表**。

**结合性**则由循环决定：`parseAdditive` 里的 `while (true) { left = new BinaryExpr(op, left, parseMultiplicative()) }` 是**左结合**（`a - b - c` → `(a - b) - c`），因为每来一个新运算符，就把已解析的 `left` 包成左子树。而 `parseNot` 里的 `NOT` 是**右结合/前缀**（`NOT NOT x` → `NOT (NOT x)`），因为它是 `new UnaryExpr(NOT, parseNot())` 递归调用自身。

### 2.4 抽象语法树（AST）

语法分析器的输出不是「推导树」本身，而是**抽象语法树**（AST）——剥掉 `;`、`(`、`)` 这些只为消除歧义而存在、却不承载语义的符号，只保留**真正有意义的结构**。本项目的 AST 节点在 [src/sql_compiler/ast/](src/sql_compiler/ast/)：

- **语句**（实现 `Statement` 接口）：`CreateTableStmt / InsertStmt / SelectStmt / DeleteStmt / UpdateStmt`。
- **关系**（继承抽象类 `Relation`）：`TableRelation`（表 + 别名）、`JoinRelation`（左/右关系 + 连接类型 + ON）。
- **表达式**（继承抽象类 `Expr`）：`ColumnRef`（列引用）、`Literal`（字面量）、`NullLiteral`（SQL NULL）、`IsNullExpr`（`IS [NOT] NULL`）、`Comparison`（比较）、`BinaryExpr`（二元算术/逻辑）、`UnaryExpr`（`NOT`）、`Star`（`SELECT *` 的哨兵）、`AggregateCall`（`COUNT/SUM/AVG/MIN/MAX`）。
- **其他**：`OrderByItem`（排序键）、`Assignment`（UPDATE 赋值项）。

设计上把「表达式」统一在 `Expr` 之下，是因为 WHERE、SELECT 列表、INSERT 的值列表**都是表达式**，语义分析和优化器可以**对表达式递归处理**，而不用为每种语句各写一遍逻辑。这是 AST 设计的核心价值：**用类型层次表达共性，让下游用统一代码处理异构结构**。

### 2.5 语法错误处理：期望集合

递归下降的报错难点是「卡住了怎么说」。本项目的做法是：每个 `expectXXX` 失败时，用 [error()](src/sql_compiler/Parser.java#L410-L413) 构造一个**三段式** [SyntaxError](src/utils/SyntaxError.java)：

```
SyntaxError at line 3, column 19
unexpected token: ';'
expected: IDENTIFIER | CONST | '(' | NOT
```

三段分别是：**位置**（精确到行列）、**实际符号**（`unexpected token`）、**期望集合**（下一步该出现什么，给用户修复线索）。这个「期望集合」是**手工按上下文指定的**——例如 `parseNot` 检查到当前 token 无法开始一个 primary 时，期望就是 `IDENTIFIER | CONST | '(' | NOT`。比起笼统的「syntax error」，它给出了可操作的信息。

### 2.6 一个完整实例

继续第 1.7 的例子，`SELECT name FROM student WHERE age > 18;` 经递归下降得到：

```
SelectStmt
├── selectItems: [ ColumnRef(name) ]
├── from: TableRelation(table="student")
└── where: Comparison(>, ColumnRef(age), Literal(18, INT))
```

它的构造过程是：`parseSelect` 依次 `expectKeyword("SELECT")` → 读到 `*`？不是，于是 `parseOr()` 解析出 `ColumnRef(name)` → `expectKeyword("FROM")` → `expectIdentifier` 得 `student` → `parseWhereClause` 读到 `WHERE` 于是 `parseOr()` 解析出 `Comparison(age > 18)`。每一步都精确对应文法里的一个符号。

---

## 第 3 章 语义分析：从「合法语法」到「有意义的程序」

### 3.1 理论基础：符号表、名字绑定、属性文法

语法分析只能保证「**长得对**」。至于 `age` 这列到底存在不存在、`age > 'abc'` 这种类型错不错，它一概不知。回答这些问题的阶段叫**语义分析**，它依赖两件编译原理经典武器：

- **符号表（symbol table）**：记录「名字 → 名字的属性」。在这里，表名/列名就是名字，列的**类型、长度**就是属性。
- **属性文法 / 类型推导**：沿着 AST **自底向上递归**地为每个表达式节点计算属性（这里是「类型」），并沿途做约束检查。

一句话：**语法分析关心「结构」，语义分析关心「意义」**。

### 3.2 Catalog：本项目里的符号表

[Catalog.java](src/sql_compiler/Catalog.java) 就是符号表，结构是 `表名 → (列名 → ColumnDef)`：

```java
private final Map<String, LinkedHashMap<String, ColumnDef>> tables = new LinkedHashMap<>();
```

用 `LinkedHashMap` 保存列顺序（`SELECT *` 展开、INSERT 按列序取值都依赖这个顺序）。接口包括 `createTable / containsTable / containsColumn / getColumn / getColumns / getTableNames`。**关键点**：Catalog 在编译期由 `CREATE TABLE` 语句「写入」，是编译期的模式元数据；执行期另有一份持久化的 `CatalogManager`（见第 7 章），两者在数据上对得上。

### 3.3 语义分析的五类语句检查

[SemanticAnalyzer.java](src/sql_compiler/SemanticAnalyzer.java) 的 [analyze()](src/sql_compiler/SemanticAnalyzer.java#L37-L41) 按语句类型分发：

| 方法 | 检查项 |
|------|--------|
| [analyzeCreate()](src/sql_compiler/SemanticAnalyzer.java#L59-L71) | 表是否已存在（重复建表）→ 列名是否重复 → **注册进 Catalog** |
| [analyzeInsert()](src/sql_compiler/SemanticAnalyzer.java#L74-L108) | 表存在 → 目标列存在 → **列数一致** → 值类型与列类型**兼容** → VARCHAR 长度不超限 |
| [analyzeSelect()](src/sql_compiler/SemanticAnalyzer.java#L122-L132) | FROM 各表存在 → 投影/分组/排序列**类型推导**（含列名绑定） → WHERE 必须为 BOOL → **聚合与分组合法性** |
| [analyzeDelete()](src/sql_compiler/SemanticAnalyzer.java#L135-L139) | 表存在 → WHERE 必须为 BOOL |
| [analyzeUpdate()](src/sql_compiler/SemanticAnalyzer.java) | 表存在 → SET 列存在 → 值类型可赋值 → WHERE 为 BOOL |

注意到 `analyzeCreate` 有一个**副作用**：把合法的表**注册进 Catalog**。这正是「语义分析不只是检查，还会**填符号表**」——后续语句（比如 `INSERT INTO student ...`）能否通过检查，依赖前面 `CREATE TABLE` 已经登记了 `student` 及其列。

**多表作用域**：`SELECT` 的 `FROM` 会被折叠成一个 `Scope`（别名→表名映射 + 参与表列表）。有 `GROUP BY` 或聚合时，SELECT 项必须是聚合调用或等于某个分组键，否则报 `SemanticError`。

### 3.4 名字绑定：把列引用「接」到符号表

核心是 [resolveColumn()](src/sql_compiler/SemanticAnalyzer.java#L207-L216)：一个 `ColumnRef` 要么带表前缀（`student.age`），要么不带（`age`）。带前缀时先经 `Scope` 的别名映射解析出真实表名；不带时单表直接解析、多表则要求唯一命中（多命中报 `AmbiguousColumn`）。然后查 Catalog——表不存在报 `TableNotFound`，列不存在报 `ColumnNotFound`，都命中则返回列的**类型**。这一步就是把 AST 里「悬空的名字」**绑定到具体声明**上，是符号表最典型的用途。

### 3.5 类型推导：属性文法的实现

[inferType()](src/sql_compiler/SemanticAnalyzer.java#L153-L204) 是语义分析的心脏，它**自底向上递归**地为表达式算类型：

- `Literal` → 直接返回字面量类型；
- `NullLiteral` → 返回 `null` 作「无类型」哨兵（可赋给任意列）；
- `IsNullExpr` → 先推导操作数类型，恒返回 `BOOL`；
- `ColumnRef` → `resolveColumn` 查出来的列类型；
- `Comparison` → 先递归算左右类型，再 `TypeSystem.checkComparison` 校验，恒返回 `BOOL`（任一侧为 NULL 字面量则跳过比较检查）；
- `BinaryExpr` → 逻辑运算走 `checkLogical` 返回 `BOOL`，算术运算走 `TypeSystem.arithmetic` 返回结果类型；
- `UnaryExpr` → `checkUnaryLogical` 校验，返回 `BOOL`。

这就是一个**属性文法求值器**：每个节点的「综合属性」（类型）由子节点的属性组合而来，且校验规则集中在 `TypeSystem`（下一章）里。**类型规则与遍历逻辑分离**，是本设计最值得学习的一点。

### 3.6 语义错误处理

语义错误统一抛 [SemanticError](src/utils/SemanticError.java)，格式 `[错误类型, 位置, 原因说明]`，例如 `[ColumnNotFound, (0,0): column 'xxx' does not exist...]`。位置字段因 AST 节点暂不携带行列号而用 `(0,0)` 占位——这是一个已知的、诚实的取舍（见 [SemanticAnalyzer.java:227-230](src/sql_compiler/SemanticAnalyzer.java#L227-L230) 的 `fail()`）。

---

## 第 4 章 类型系统：编译器里的裁判

### 4.1 为什么要单独一个类型系统

类型规则如果散落在 `SemanticAnalyzer`、`Executor` 各处的 `if/else` 里，就会变成「改一处漏一处」的噩梦。本项目的选择是：**把所有「运算符作用在两类上产生什么结果 / 是否合法」的规则收敛到 [TypeSystem.java](src/sql_compiler/TypeSystem.java) 一个类里**，语义分析器只「查询」，执行引擎也复用它（保证编译期检查与运行期求值**用同一套规则**，二者永不打架）。

### 4.2 本项目类型规则一览

类型有八种：`INT / FLOAT / VARCHAR / BOOL / DATE / DECIMAL / CHAR / TEXT`（见 [ColumnType.java](src/utils/ColumnType.java)）。

| 表达式 / 场景 | 规则 | 对应方法 |
|---------------|------|----------|
| `INT + INT`（`+ - *`） | `INT` | [arithmetic()](src/sql_compiler/TypeSystem.java#L22-L34) |
| `INT / INT` | **`FLOAT`**（除法恒为浮点，避免整除截断） | 同上 |
| 含 `FLOAT` 的算术 | `FLOAT` | 同上 |
| 含 `DECIMAL` 的算术（含除法） | `DECIMAL`（运行时用 `BigDecimal` 精确运算） | 同上 |
| 数值比较（INT/FLOAT/DECIMAL 混比） | `BOOL`，任意比较符均可 | [checkComparison()](src/sql_compiler/TypeSystem.java#L37-L50) |
| `DATE` 全序比较 | `BOOL`，任意比较符均可 | 同上 |
| 字符串族（VARCHAR/CHAR/TEXT）`=` / `!=` | `BOOL`，**仅支持 `=` / `!=`**（无大小序） | 同上 |
| 字符串族/`BOOL` 的 `< <= > >=` | **报错** | 同上 |
| `BOOL AND/OR BOOL`、`NOT BOOL` | `BOOL` | [checkLogical()](src/sql_compiler/TypeSystem.java#L53-L59) / [checkUnaryLogical()](src/sql_compiler/TypeSystem.java#L62-L67) |
| `INT + VARCHAR` | **报错** | `arithmetic()` |
| 数值族互赋 / 字符串族互赋 | **合法** | [checkAssignable()](src/sql_compiler/TypeSystem.java#L70-L79) |
| `DATE ← VARCHAR`、`DECIMAL ← 数值/VARCHAR` | **合法**（运行时 coerce 解析） | 同上 |

> **NULL 的三值逻辑**：比较/算术遇 `NULL` 结果仍为 `NULL`（UNKNOWN）；`IS [NOT] NULL` 恒返回确定的 `BOOL`；
> `WHERE/ON` 中 UNKNOWN 按「非真」处理（对应 Kleene 三值逻辑）。因此在类型检查里 `NULL` 字面量被当作「无类型」
> 哨兵——可赋给任意列、参与任意比较（见第 3.5 节）。

这些规则对应数据库类型系统的两条经典思想：

1. **隐式类型提升（type promotion）**：`INT` 和 `FLOAT`（以及 `DECIMAL`）混合运算时，较窄类型被「提升」为较宽类型，结果取更宽的类型——与 C/Java 的数值提升一脉相承，也是 SQL 标准的常见行为。
2. **类型约束（type constraint）**：字符串族、`BOOL` 没有「大小」这一偏序，所以 `< <= > >=` 对它们无定义，直接拒绝——**在编译期拒绝，而不是运行期才崩**，这正是静态类型检查的意义。

类型不匹配时抛 `IllegalArgumentException`，由调用方（语义分析器）捕获后**包装成带位置信息的 `SemanticError`**——底层只管「规则对不对」，上层负责「报给用户怎么看」。

### 4.3 类型系统与语义分析、执行引擎的关系

```
        SemanticAnalyzer（编译期）          Executor / ExpressionEvaluator（运行期）
                     │                                │
                     └────────── 共同查询 TypeSystem ──┘
                               （同一套规则，单一真源）
```

运行期 [ExpressionEvaluator.java](src/engine/ExpressionEvaluator.java) 的 `arithmetic()` 在求值时采用与 `TypeSystem.arithmetic` 完全一致的提升规则（含 DECIMAL 即 DECIMAL、除法（无 DECIMAL）恒 FLOAT、含 FLOAT 即 FLOAT）。**编译期保证「能过类型检查就一定能正确求值」，运行期就不必再做防御性判断**。

---

## 第 5 章 执行计划生成：从 AST 到关系代数

### 5.1 理论基础：关系代数算子

到这里，编译器从「通用编译原理」的领域，跨入了「数据库系统」的领域。数据库教科书里的查询，最终都被表达成**关系代数（relational algebra）**——一组作用在「表（关系）」上的算子：

- **σ（选择 / selection）**：按条件过滤行 → 本项目 `Filter`；
- **π（投影 / projection）**：挑出若干列 → 本项目 `Project`；
- **⋈（连接 / join）**：按条件合并两张表 → 本项目 `Join`（INNER/LEFT）；
- **γ（分组 / aggregation）**：按分组键分桶 + 聚合 → 本项目 `Aggregate`；
- **τ（排序 / sort）**：按键排序 → 本项目 `Sort`；
- **顺序扫描**：读整张表 → 本项目 `SeqScan`；
- 以及插入、删除、更新、建表等更新算子。

**逻辑执行计划（logical plan）就是一棵以这些算子为节点的树**，自底向上表示「先扫描，再过滤，最后投影」的数据流。数据库教材里那句「查询优化就是在关系代数表达式上做等价变换」，其对象正是这棵树。

### 5.2 Planner：AST → 算子树

[Planner.java](src/sql_compiler/Planner.java) 的 [plan()](src/sql_compiler/Planner.java#L25-L42) 是**纯函数式**的映射，没有复杂逻辑：

| AST | 逻辑计划 |
|-----|----------|
| `CreateTableStmt` | `CreateTablePlan` |
| `InsertStmt` | `InsertPlan` |
| `SelectStmt` | `Project/Sort/Aggregate → [Filter] → [Join] → SeqScan` |
| `DeleteStmt` | `DeletePlan` |
| `UpdateStmt` | `UpdatePlan` |

`SELECT` 的转换在 [planSelect()](src/sql_compiler/Planner.java#L45-L52) 里**自底向上**构造：

```java
PlanNode node = planRelation(sel.getFrom());            // 最底层：SeqScan / Join
if (sel.getWhere() != null) node = new FilterPlan(sel.getWhere(), node); // 中间：过滤
if (hasAggregate || hasGroupBy)
    node = new AggregatePlan(sel.getSelectItems(), sel.getGroupBy(), node);
else
    node = new ProjectPlan(sel.getSelectItems(), node); // 上层：投影/聚合
if (hasOrderBy) node = new SortPlan(sel.getOrderBy(), node); // 顶层：排序
```

对应关系代数表达式 `π_selectItems(σ_where(student))`（有聚合时是 `γ_groupBy(σ_where(...))`）。注意：非聚合时 `Sort` 位于 `Project` **之下**（ORDER BY 可引用任意 FROM 列），聚合时位于 `Aggregate` **之上**。

### 5.3 算子节点的设计

算子都继承 [PlanNode](src/sql_compiler/plan/PlanNode.java)，带 `children` 列表，并提供 [toTree()](src/sql_compiler/plan/PlanNode.java#L20-L24) 输出 ASCII 树形结构——这是为了**优化前后对比展示**（见第 6 章末尾）。

各算子字段一览：

| 算子 | 字段 | 说明 |
|------|------|------|
| `SeqScanPlan` | `table, alias(可null)` | 全表顺序扫描（无子节点，最底层） |
| `FilterPlan` | `condition` + 子节点 | 按 WHERE 过滤（单子节点） |
| `ProjectPlan` | `expressions`（`List<Expr>`）+ 子节点 | 按 SELECT 列表投影（单子节点） |
| `SortPlan` | `keys`（`List<OrderByItem>`）+ 子节点 | 按 ORDER BY 键排序（单子节点） |
| `AggregatePlan` | `selectItems, groupBy(可null)` + 子节点 | 分组聚合（单子节点） |
| `JoinPlan` | `left, right, type, condition(可null)` | INNER/LEFT 连接（双子节点） |
| `CreateTablePlan` | `table, columns` | 建表（无子节点） |
| `InsertPlan` | `table, columns(可null), values` | 插入（无子节点） |
| `DeletePlan` | `table, condition(可null)` | 删除（无子节点） |
| `UpdatePlan` | `table, assignments, condition(可null)` | 更新（无子节点） |

### 5.4 `SELECT *` 的哨兵设计

`SELECT *` 生成的是 `ProjectPlan` 里**单个 `Star` 表达式**，而不是在编译期就展开成所有列。为什么？因为 `*` 的含义依赖表结构，而「展开」这件事可以推迟到真正需要知道列清单的时候（执行引擎结合 schema 展开，优化器做投影剪枝时也依赖 `*` 这个「记号」）。**用一个哨兵对象代表「全部列」这种延迟语义**，是这类编译器里常见的手法。

---

## 第 6 章 规则式查询优化：等价的等价变换

### 6.1 理论基础：查询优化与等价规则

编译器的优化阶段，是把「正确但低效」的中间表示改写成「正确且高效」的等价形式。数据库查询优化的特殊之处在于：它的优化对象是**关系代数**，而关系代数有一整套**可证明的等价律**（交换律、结合律、分配律），例如：

```
σ_p(π_X(R))  ≡  π_X(σ_p(R))         —— 选择与投影可交换（当 p 只涉及 X 中的列）
σ_{p∧q}(R)   ≡  σ_p(σ_q(R))         —— 选择的合取可拆分
σ_{true}(R)  ≡  R                    —— 恒真选择可消除
```

本项目的 [Optimizer.java](src/sql_compiler/Optimizer.java) 就是在这套等价律上做**规则式重写（rule-based rewriting）**：每条规则识别一种可优化的「模式」，命中就重写成更优的等价形式。

### 6.2 五条重写规则

[applyAllRules()](src/sql_compiler/Optimizer.java#L45-L52) 依次应用五条规则：

**(1) 常量折叠** — [constantFold()](src/sql_compiler/Optimizer.java#L55-L87) + [foldExpr()](src/sql_compiler/Optimizer.java#L90-L124)

把「全是常量的算术子表达式」在编译期算出结果。`age > 10 + 8` → `age > 18`。对应编译原理的**编译期常量求值（constant folding）**。

**(2) 布尔化简** — [booleanSimplify()](src/sql_compiler/Optimizer.java#L151-L183) + [simplifyExpr()](src/sql_compiler/Optimizer.java#L186-L236)

两步走：先把**常量比较**求值成布尔字面量（`1 = 1` → `true`），再套用**布尔恒等式**：

```
true AND X → X      false AND X → false
false OR X → X      true OR X  → true
NOT true   → false  NOT false  → true
```

于是 `1=1 AND age > 18` 化简为 `age > 18`。这对应编译原理里的**代数化简 / 恒等式化简**。

**(3) 投影剪枝** — [projectPruning()](src/sql_compiler/Optimizer.java#L271-L277)

`SELECT *` 产生的 `Project["*"]` 没有任何实际投影动作（就是原样透传所有列），所以直接**剪掉这个冗余节点**。

**(4) 谓词下推** — [predicatePushdown()](src/sql_compiler/Optimizer.java#L280-L294)

这是数据库优化里**最著名的一条**：把 `Filter` 从 `Project` 的**上方**移到**下方**（更靠近 `SeqScan`）。好处是**先过滤、后投影**——被过滤掉的行不必再经历投影计算，减少了投影算子要处理的数据量：

```
优化前：  Project → Filter → SeqScan
优化后：  Filter → Project → SeqScan    （或更优时直接压到 SeqScan 上）
```

但下推有个**安全性前提**：Filter 的条件里引用的列，必须在 Project 的投影列表里还**保留着**（否则下推后那些列就没了）。代码里 [referencedColumns()](src/sql_compiler/Optimizer.java#L367-L371) 收集条件引用的列，只有当 `Project` 是 `*` 或包含所有这些列时才下推。**这个安全性检查，就是「等价变换必须保证等价」这条铁律的落地。**

**(5) 冗余消除** — [removeRedundant()](src/sql_compiler/Optimizer.java#L297-L303)

去掉恒真（`true`）的 `Filter`——因为 `σ_true(R) ≡ R`，它不筛任何行，纯属冗余。

### 6.3 不动点迭代

[optimize()](src/sql_compiler/Optimizer.java#L32-L42) 把五条规则**反复应用**，直到计划结构不再变化（**不动点**）：

```java
for (int pass = 0; pass < MAX_PASSES; pass++) {
    PlanNode next = applyAllRules(current);
    if (next.toTree().equals(current.toTree())) return next; // 结构不变 → 收敛
    current = next;
}
```

为什么要迭代？因为一条规则的输出可能是另一条规则的输入——例如常量折叠后 `age > 18 AND age > 18` 可能进一步被布尔化简。反复应用直到「没得可改」才停，`MAX_PASSES = 4` 是防死循环的兜底。这也是**数据流优化里「迭代到稳定状态」思路**的直接应用。

### 6.4 「纯」重写：为什么要重建节点而不是原地改

优化器所有规则都遵循一个铁律——[mapChildren()](src/sql_compiler/Optimizer.java#L306-L321) **不修改传入节点，而是重建新节点返回**。这带来一个硬性收益：**优化前、优化后两棵树可以同时保留**，用于对比展示（这是项目的验收点之一）。更深层地说，**纯函数式变换**让每条规则「可组合、可复用、易测试」，是函数式编程思想在编译器实现里的经典体现。

### 6.5 优化实例

对 `SELECT name, age + 1 FROM student WHERE age > 18 AND 1 = 1;`：

```
优化前：                         优化后：
Project[name, age + 1]           Project[name, age + 1]
+- Filter[(age > 18) AND true]   +- Filter[age > 18]
   +- SeqScan[student]              +- SeqScan[student]
```

优化过程：常量折叠无变化 → 布尔化简把 `(age > 18) AND true` 变成 `age > 18` → 谓词下推把 `Filter` 移到 `Project` 之下（这里 `age` 仍被保留）→ 最终更紧凑。

---

## 第 7 章 从计划到执行：编译器如何「把手伸向」数据库引擎

编译器的终点是「产出可执行的中间表示」，而**谁来执行**，是数据库引擎的事。本章简要说明两者如何衔接，让「编译器」这条线完整落地。

### 7.1 执行引擎：逐算子解释计划

[Executor.java](src/engine/Executor.java) 的 [execute()](src/engine/Executor.java#L42-L62) 按计划节点类型分发，每个算子各有一个 `executeXxx`：

- `executeCreate` → 调存储引擎建表 + 注册进 `CatalogManager`；
- `executeInsert` → 把值表达式求值后，调存储引擎插一行；
- `executeDelete` → 调存储引擎按条件删行；
- `executeUpdate` → 调存储引擎按条件改行（SET 值基于原行同时求值）；
- `executeSeqScan` → 扫全表，产出 `QueryResult`（列名 + 行）；
- `executeFilter` → 对子节点结果逐行求值条件，保留满足者；
- `executeProject` → 对子节点结果逐行按表达式求值，挑出投影列；
- `executeSort` → 物化子结果后按 ORDER BY 键稳定排序；
- `executeAggregate` → 按分组键分桶，对每桶求聚合 / 分组键；
- `executeJoin` → 左右子结果嵌套循环连接（LEFT JOIN 未匹配补 NULL）。

注意这里执行引擎采用的是**物化模型（materialization）**：每个算子把子算子的结果**整体取到内存**再处理（`QueryResult` 一次性返回所有行）。数据库教材里更经典的是**火山模型（Volcano / 迭代器）**——每个算子只提供 `next()`，逐行向上拉取、延迟计算。二者是执行引擎的两种实现策略，本项目选了更简单、更易实现的物化模型。

### 7.2 表达式求值器：运行期的「解释器」

[ExpressionEvaluator.java](src/engine/ExpressionEvaluator.java) 是对 AST 表达式的一个**递归解释器**。它的 [eval()](src/engine/ExpressionEvaluator.java#L47-L78) 与语义分析器的 `inferType()` 结构几乎同构：

- `Literal` → 直接取值；
- `ColumnRef` → 按列名查到列下标，从当前**行**里取值（求值上下文 = 列名→下标映射 + 一行值）；
- `Comparison / BinaryExpr / UnaryExpr` → 递归求值子表达式后做比较/算术/逻辑运算。

它和编译器前端共享同一套类型规则（第 4 章已述），所以**编译期通过了类型检查的表达式，运行期一定能安全求值**。

### 7.3 与存储引擎、系统目录的关系

- **存储引擎**（`StorageEngine`）负责真正读写磁盘上的页（见 `storage/` 包的页式存储），执行引擎只是它的调用者。
- **系统目录**（`CatalogManager`）是**执行期持久化**的元数据（建的表要落盘、重启后还在），与**编译期**的 `Catalog` 符号表是「同一份元数据的两个视图」：编译期靠它做名字绑定，执行期靠它取表结构。

### 7.4 端到端回顾

至此可以画出一条**完整的、闭环的**流水线：

```
SQL 文本
  → [词法] Token 流
  → [语法] AST
  → [语义] 有类型、名字已绑定的 AST（+ 符号表）
  → [计划] 关系代数算子树（逻辑计划）
  → [优化] 等价的更优算子树
  → [执行] 逐算子求值，读写存储引擎
  → 结果集 / 操作提示
```

前五步是**编译器**（`sql_compiler/`），第六步是**数据库引擎**（`engine/` + `storage/`）。编译器负责「把 SQL 变成引擎认识的算子树」，引擎负责「把算子树变成磁盘上的操作和屏幕上的结果」。

---

## 第 8 章 错误处理：贯穿三阶段的错误报告体系

一个可用的编译器，错误信息比「能不能编译通过」更影响体验。本项目的错误体系按**阶段**分层，各有各的格式：

| 异常 | 基类 | 阶段 | 输出格式 |
|------|------|------|----------|
| [LexError](src/utils/LexError.java) | `DbException` | 词法 | `[LexError] at (行,列): 原因` |
| [SyntaxError](src/utils/SyntaxError.java) | `DbException` | 语法 | 三段式：位置 / 实际符号 / 期望集合 |
| [SemanticError](src/utils/SemanticError.java) | `DbException` | 语义 | `[错误类型, 位置, 原因说明]` |

它们都继承统一的 [DbException](src/utils/DbException.java)（`RuntimeException` 子类），所以上层 [Main.executeAndFormat()](src/cli/Main.java#L91-L118) 只需一个 `catch (DbException e)` 就能兜住**所有编译期 + 执行期错误**，把消息展示给用户而不崩溃；外层再补一个 `catch (RuntimeException)` 兜底未知的运行时异常（见 [Main.java:112-116](src/cli/Main.java#L112-L116)）。

**设计哲学**：

1. **错误即异常**：非法输入不是「返回 null」而是「抛异常」，让错误沿调用栈自然冒泡，避免层层 `if (fail) return` 的样板代码。
2. **错误信息可定位**：词法、语法都带精确行列号，语义错误也预留了位置字段（当前 `(0,0)` 占位，待 AST 携带位置信息后即可精确定位）。
3. **越早报错越好**：词法错在词法层报、语法错在语法层报、语义错在语义层报，不把问题「留到后面才爆发」。

---

## 附录 端到端：一条 SQL 的完整生命周期

输入：

```sql
CREATE TABLE student (id INT, name VARCHAR(20), age INT, gpa FLOAT);
INSERT INTO student (id, name, age) VALUES (1, 'Alice', 20);
SELECT name, age + 1 FROM student WHERE age > 18 AND id != 0;
```

**阶段 1 · 词法**：三句话被切成一长串 Token（关键字、标识符、常量、运算符、分隔符各归其位，`1`/`20` 已被解析成 `INT` 常量，`'Alice'` 已是字符串）。

**阶段 2 · 语法**：得到三棵 AST——`CreateTableStmt`、`InsertStmt`、`SelectStmt`。第三条的 where 是一棵 `AND( Comparison(age>18), Comparison(id!=0) )` 表达式树。

**阶段 3 · 语义**：
- `CREATE TABLE` 检查不重复后，把 `student(id, name, age, gpa)` 注册进 `Catalog`；
- `INSERT` 检查列存在、3 列对应 3 个值、`1→INT`、`'Alice'→VARCHAR(20)`（长度 5 ≤ 20）、`20→INT` 均合法；
- `SELECT` 推导 `name→VARCHAR`、`age+1→INT`、`WHERE→BOOL`，`id`/`age` 绑定到 `student` 的列。

**阶段 4 · 计划**：第三条生成 `Project[name, age+1] → Filter[AND(...)] → SeqScan[student]`。

**阶段 5 · 优化**：
- 常量折叠：`age + 1` 中 `1` 是常量但 `age` 不是，无法折叠，保持原样；
- 布尔化简：无常量比较可求值，保持；
- 谓词下推：`Filter` 移到 `Project` 之下（`age`、`id` 仍在投影输出中）；
- 最终：`Project → Filter → SeqScan`。

**阶段 6 · 执行**：`SeqScan` 扫出 `student` 全部行 → `Filter` 逐行求值 `age > 18 AND id != 0`，留下 `(1, 'Alice', 20)` → `Project` 对每行求值 `name`、`age+1`，输出：

```
name   | age + 1
-------|--------
'Alice'| 21
(1 row)
```

至此，一条 SQL 从**文本**走完**词法 → 语法 → 语义 → 计划 → 优化 → 执行**六个阶段，变成了屏幕上的结果——这就是一个 SQL 编译器的全部故事。

---

## 结语：一条贯穿始终的主线

如果只记一句话，请记住这个对称性：

> **SQL 编译器的前端 = 传统编译器（词法/语法/语义/类型），后端 = 数据库查询处理器（关系代数/查询优化）。**

词法靠**正则/自动机**，语法靠**上下文无关文法/递归下降**，语义靠**符号表/属性文法**，计划靠**关系代数**，优化靠**等价规则重写**，执行靠**算子解释**。每一层都「只做自己该做的事、只依赖上一层的契约」，这就是编译器工程与数据库系统设计最根本的相通之处——**分层、抽象、单一职责**。

---

*本文档对应 `src/sql_compiler/` 的编译器实现；存储系统（`storage/`）与引擎细节（`engine/`）只作衔接性说明。模块级代码结构速查见 [CODE_STRUCTURE.md](CODE_STRUCTURE.md)，形式化文法见 [grammar.md](grammar.md)。*
