# 一条 SQL 的编译之旅：从字符串到执行计划

> 本文面向**正在学习编译原理的学生**，按**代码真实执行顺序**串讲一条 SQL 从输入到执行完成的
> 全过程（**仅覆盖 SQL 编译阶段**，即词法 → 语法 → 语义 → 计划生成 → 规则优化；执行引擎只作终点带过）。
>
> 与 [CODE_STRUCTURE.md](CODE_STRUCTURE.md) 的关系：那份文档是「按模块查字典」，告诉你每个类里有什么；
> 本文是「按时间线看动画」，跟着一次真实调用，看数据在六个阶段之间如何一层层变形。
> 建议两篇对照阅读。

---

## 目录

- [〇、贯穿全篇的示例](#〇贯穿全篇的示例)
- [一、起点：编译的入口](#一起点编译的入口)
- [二、阶段一：词法分析（字符串 → Token 流）](#二阶段一词法分析字符串--token-流)
- [三、阶段二：语法分析（Token 流 → AST）](#三阶段二语法分析token-流--ast)
- [四、阶段三：语义分析（AST → 类型化的合法 AST + 符号表）](#四阶段三语义分析ast--类型化的合法-ast--符号表)
- [五、阶段四：计划生成（AST → 逻辑计划）](#五阶段四计划生成ast--逻辑计划)
- [六、阶段五：规则优化（逻辑计划 → 等价计划）](#六阶段五规则优化逻辑计划--等价计划)
- [七、终点：交给执行引擎](#七终点交给执行引擎)
- [附录：编译原理术语 ↔ 本实现对照表](#附录编译原理术语--本实现对照表)

---

## 〇、贯穿全篇的示例

为了让每一步都有「看得见的输入/输出」，本文全程追踪下面这段 SQL 中的第二条语句：

```sql
CREATE TABLE student (id INT, name VARCHAR(20), age INT, gpa FLOAT);
SELECT name, age FROM student WHERE age > 20 - 2 AND true;
```

选择它是因为它恰好能「踩中」编译链路的每一个阶段，而且能演示优化器两处可见的变化：

- 词法：`20`、`2` 是常量，`true` 是布尔常量，`>` 和 `-` 是运算符，`;` 是分隔符；
- 语法：`age > 20 - 2 AND true` 涉及**比较、加减、逻辑与**三个优先级层级，能展示递归下降的逐层调用；
- 语义：`age`、`name` 必须能在符号表 `student` 中查到（依赖第一条 CREATE TABLE 注册的结果）；
- 计划：生成 `Project → Filter → SeqScan` 的算子树；
- 优化：`20 - 2` 被**常量折叠**成 `18`，`... AND true` 被**布尔化简**删掉。

下面正式开始。

---

## 一、起点：编译的入口

编译不是从 `Lexer` 开始的，而是从命令行入口 [Main.java](src/cli/Main.java) 的
[executeAndFormat()](src/cli/Main.java#L95) 开始的。看清它的结构，就看清了整个编译器的「总装配线」：

```java
List<Token> tokens = new Lexer(sql).tokenize();          // ① 词法分析（整段 SQL，一次完成）
if (tokens.isEmpty()) return "";
List<Statement> statements = new Parser(tokens).parseProgram(); // ② 语法分析（整段，一次完成）

for (Statement stmt : statements) {                      // ③ 以下按「语句」逐条执行
    Catalog.Snapshot before = catalog.snapshot();
    try {
        new SemanticAnalyzer(catalog).analyze(...);      //    语义分析
        PlanNode plan = new Planner().plan(stmt);        //    计划生成
        plan = new Optimizer().optimize(plan);           //    规则优化
        Object result = new Executor(...).execute(plan); //    执行（编译阶段到此为止）
        ...
    } catch (DbException e) {
        catalog.restore(before);                         //    失败回滚符号表
        break;
    }
}
```

这里有三个值得注意的、也是编译原理里反复出现的设计：

1. **前端一次、后端逐条**。词法、语法是「整体」的——它们一次把整段 SQL 变成 Token 流和语句列表，
   因为它们只关心「形状」，不关心「含义」；而语义分析、计划、优化、执行是「逐条」的，
   因为语义依赖前面语句留下的状态（比如 `student` 表是第一条语句建出来的，第二条才查得到）。
   这正是编译原理里「前端（front end）与后端（back end）」的划分。

2. **符号表跨语句共享，但可回滚**。`catalog` 是编译期符号表，语义分析阶段会往里写（CREATE TABLE 注册表），
   执行阶段也可能失败（比如磁盘上数据文件已存在）。所以每条语句执行前先 `snapshot()`，失败就 `restore()`，
   避免符号表里留下一个「编译期认为存在、引擎里其实不存在」的表。这是「编译期副作用要可撤销」的经典做法。

3. **一句话对应一段异常边界**。词法/语法错误发生在①②，此时还没进入逐条循环，符号表无需回滚；
   语义/计划/执行错误发生在循环内，需要回滚并 `break`（一条失败，其后语句不再执行）。

> 编译原理映射：这一段就是「**驱动程序 / Driver**」的角色——它不自己处理任何语法，只是按固定顺序
> 把数据从上一个阶段搬运到下一个阶段，并决定出错时如何恢复。

---

## 二、阶段一：词法分析（字符串 → Token 流）

**入口**：[Lexer.java](src/sql_compiler/Lexer.java) 的 [tokenize()](src/sql_compiler/Lexer.java#L44)。

词法分析器（Lexer）的任务是**把一段无结构的字符串，切成一个个有结构的「词」（Token）**。
Token 就是编译原理里的「词法单元」，本实现用一个**四元式**表示：`[种别码, 词素值, 行号, 列号]`（见 [Token.java](src/sql_compiler/Token.java)）。

### 2.1 Lexer 的内部状态

Lexer 是典型的「手写词法分析器」，只维护四个状态（[Lexer.java:17-21](src/sql_compiler/Lexer.java#L17-L21)）：

| 字段 | 含义 |
|------|------|
| `source` | 待分析的源字符串（只读） |
| `pos` | 当前字符下标（游标） |
| `line` / `col` | 当前行号/列号（用于报错定位） |

它靠三个方法像「打字机读头」一样工作（这正是手写词法器与自动生成（如 `lex`）的本质区别——手动控制游标）：

- `peek()`：**预读**当前字符但不消费（越界返回 `'\0'` 哨兵）；
- `peek(offset)`：预读游标后第 offset 个字符（用于识别 `>=`、`--`、`/*` 等多字符序列）；
- `advance()`：**消费**当前字符并前移游标，同步更新 `line/col`。

### 2.2 主循环：按首字符分派

[tokenize()](src/sql_compiler/Lexer.java#L44) 的结构是一层「按首字符分派」的循环：

```java
while (true) {
    skipWhitespaceAndComments();     // 先跳过空白与注释
    if (peek() == '\0') break;       // 到末尾，结束
    char c = peek();
    if      (是字母或 '_')  readIdentifierOrKeyword();  // 标识符 / 关键字 / true / false / null
    else if (是数字)        readNumber();              // 整数 / 浮点
    else if (c == '\'')    readString();              // 字符串（'' 转义）
    else if (是运算符字符)  readOperator();             // 多字符优先，如 >= != <> ==
    else if (是分隔符字符)  readDelimiter();            // ( ) , ; .
    else                    throw LexError(非法字符);   // 任何其它字符 → 词法错误
}
```

`skipWhitespaceAndComments()` 里同时处理了空白、`--` 行注释、`/* */` 块注释——注意**注释在词法阶段就被丢弃**，
之后的语法分析器根本不知道注释存在过。这是「词法分析负责屏蔽低级噪音」的典型体现。

### 2.3 我们示例的词法输出

对第二条语句 `SELECT name, age FROM student WHERE age > 20 - 2 AND true;`，Lexer 产出：

| 词素 | 种别码 | 额外信息 | 行 | 列 |
|------|--------|----------|----|----|
| `SELECT` | KEYWORD | — | 1 | 1 |
| `name` | IDENTIFIER | — | 1 | 8 |
| `,` | DELIMITER | — | 1 | 12 |
| `age` | IDENTIFIER | — | 1 | 14 |
| `FROM` | KEYWORD | — | 1 | 18 |
| `student` | IDENTIFIER | — | 1 | 23 |
| `WHERE` | KEYWORD | — | 1 | 31 |
| `age` | IDENTIFIER | — | 1 | 37 |
| `>` | OPERATOR | — | 1 | 41 |
| `20` | CONST | INT_CONST, 值=20 | 1 | 43 |
| `-` | OPERATOR | — | 1 | 46 |
| `2` | CONST | INT_CONST, 值=2 | 1 | 48 |
| `AND` | KEYWORD | — | 1 | 50 |
| `true` | CONST | BOOL_CONST, 值=true | 1 | 54 |
| `;` | DELIMITER | — | 1 | 58 |

几个细节值得对照源码体会：

- **`true` 不是关键字而是常量**：`readIdentifierOrKeyword()` 先把整个词素读完，再特判 `true/false/null`，
  把它们标成 `CONST` 并**当场解析出 Java 值**（`Boolean`/`null`）。这叫作「把字面量的语义提前到词法层解析」。
- **`>` 只用一个字符判定**：`readOperator()` 会先看 `peek(1)`，命中 `>= <= != <> ==` 这类双字符才算运算符，
  否则退化为单字符。这对应编译原理里的「**最长匹配（maximal munch）**」原则。
- **`20` 和 `2` 当场解析成 `Integer`**：`readNumber()` 读数字串后用 `Integer.parseInt` / `Float.parseFloat`
  转成值。也就是说 Token 不仅知道「这是常量」，还知道「它是 20」——为后续的常量折叠埋下伏笔。

> 编译原理映射：Token 的 `TokenType` 是「词类」（种别码），`lexeme` 是「词素值」，两者分离正是
> 词法分析的经典抽象：**同一个词类可以有无限多个词素**（所有标识符都是 `IDENTIFIER`，但词素各不相同）。
> 关键字查表（`KEYWORDS` 集合）实现的就是教材里的「保留字表」。

**错误**：非法字符、未闭合字符串/注释、非法数字（如 `1.2.3`）统一抛 [LexError](src/utils/LexError.java)，
带行号列号与原因，格式 `[LexError] at (行,列): 原因`。

---

## 三、阶段二：语法分析（Token 流 → AST）

**入口**：[Parser.java](src/sql_compiler/Parser.java) 的 [parseProgram()](src/sql_compiler/Parser.java#L67)。

语法分析器（Parser）的任务是**把扁平的 Token 流，组装成一棵有层次、可遍历的抽象语法树（AST）**。
本实现用的是编译原理最经典的**递归下降（recursive descent）**：**每一个非终结符对应一个方法**，
文法规则写到哪里，方法就嵌套调用到哪里。

### 3.1 顶层结构

[parseProgram()](src/sql_compiler/Parser.java#L67) 对应文法 `program -> statement (';' statement)* ';'?`：

```java
stmts.add(parseStatement());           // 第一条语句
while (matchDelimiter(";")) {          // 遇 ';' 继续
    if (peek() == eof) break;
    stmts.add(parseStatement());
}
if (peek() != eof) throw error(...);   // 末尾还有多余 token → 语法错误
```

[parseStatement()](src/sql_compiler/Parser.java#L83) 只做一件事：**看当前 token 的首关键字**，
分派到 `CREATE/INSERT/SELECT/DELETE/UPDATE` 五个子方法之一。这对应「预测分析」里的**向前看一个符号（LL(1)）**来决定走哪条产生式。

### 3.2 表达式的优先级 = 方法的嵌套层级

表达式解析是整个递归下降最精妙的部分。它的文法按**优先级从低到高**排成一条链：

```
parseOr           -> and_expr (OR and_expr)*           // 优先级最低
parseAnd          -> not_expr (AND not_expr)*
parseNot          -> NOT not_expr | comparison
parseComparison   -> additive (比较运算符 additive)?
parseAdditive     -> multiplicative (('+'|'-') multiplicative)*
parseMultiplicative -> primary (('*'|'/') primary)*
parsePrimary      -> 标识符 | 常量 | '(' or_expr ')' | 聚合调用 ...  // 优先级最高
```

**方法之间的调用方向，就是优先级的顺序**：`parseOr` 调 `parseAnd`，`parseAnd` 调 `parseNot`，
…… 一路降到 `parsePrimary`。于是「优先级越高的运算符，越在调用栈的底层被先合并」。
这就是教材里「**算符优先 / 优先级爬升**」在递归下降中的自然实现——优先级不再需要一张表，
而是**直接编码在方法调用的嵌套结构里**。

**左结合**也在这条链里实现：每个循环体里写的是 `left = new BinaryExpr(op, left, 下一层())`，
即「左边先绑紧」，例如 `a - b - c` 被解析成 `(a - b) - c`。唯一例外是 `NOT`：
[parseNot()](src/sql_compiler/Parser.java#L333) 里 `NOT NOT x` 通过**递归调用自身**实现右结合。

### 3.3 追踪示例的 WHERE 子句

语句 `... WHERE age > 20 - 2 AND true` 的解析，实际发生的调用栈（按执行先后）：

```
parseSelect
 └─ parseWhereClause            → 见 WHERE，调 parseOr
     └─ parseOr
         ├─ parseAnd            → 先解析左边
         │   ├─ parseNot
         │   │   └─ parseComparison
         │   │       ├─ parseAdditive → parseMultiplicative → parsePrimary → ColumnRef(age)
         │   │       ├─ 遇 '>'  → 记录 op=GT，调 parseAdditive 解析右操作数
         │   │       │   └─ parseMultiplicative → parsePrimary → Literal(20)
         │   │       │       └─ 遇 '-'  → op=MINUS，parseMultiplicative → Literal(2)
         │   │       │           → 得 BinaryExpr(MINUS, 20, 2) = (20 - 2)
         │   │       └─ 得 Comparison(GT, age, (20 - 2)) = (age > (20 - 2))
         │   └─ 返回 parseAnd 的 left
         ├─ 遇 'AND' → 调 parseNot → parseComparison → parsePrimary → Literal(true)
         └─ 得 BinaryExpr(AND, (age > (20 - 2)), true)
```

注意两处关键事实：

- **`>` 出现在 `-` 之前，但 `-` 先被合并**。因为 `parseComparison` 先向下调 `parseAdditive` 把
  `20 - 2` 整个吃掉，`>` 的右操作数是一个完整的 `additive`，而不是孤零零的 `20`。
  这保证了 `age > 20 - 2` 被理解为 `age > (20 - 2)` 而不是 `(age > 20) - 2`。
- **`AND` 出现在 `>` 之后，但 `>` 先被合并**。因为 `parseOr`/`parseAnd` 层级高于比较层级。

### 3.4 最终 AST

第二条语句的 AST（只画表达式部分，`selectItems` 是两个 `ColumnRef`）：

```
SelectStmt
 ├─ selectItems = [ ColumnRef(name), ColumnRef(age) ]
 ├─ from        = TableRelation(table=student, alias=null)
 └─ where       = BinaryExpr(AND)
                   ├─ Comparison(GT)
                   │    ├─ ColumnRef(age)
                   │    └─ BinaryExpr(MINUS)
                   │         ├─ Literal(20, INT)
                   │         └─ Literal(2,  INT)
                   └─ Literal(true, BOOL)
```

> 编译原理映射：这里产出的是**抽象语法树（AST），不是具体语法树（CST）**。
> CST 会保留 `;`、`(`、`FROM`、`WHERE` 这些纯语法符号；AST 只保留「有语义的部分」。
> 例如分隔符 `;` 在 `parseProgram` 里被消费后**没有成为任何节点**，括号 `(` `)` 只用来分组、
> 分组完成后也不进树——`(a + b) * c` 和 `a + b * c` 的 AST 分别通过树的形状体现结合关系，
> 而不需要括号节点。

**错误**：语法错误是「三段式」的 [SyntaxError](src/utils/SyntaxError.java)：位置 + 实际符号 + 期望集合，
由 `error()` 统一构造，例如 `unexpected token: ';'` / `expected: IDENTIFIER | CONST | '(' | NOT`。
这等价于教材里的「**带错误恢复信息的报错**」——告诉用户「你在哪、我看到了什么、我期望看到什么」。

---

## 四、阶段三：语义分析（AST → 类型化的合法 AST + 符号表）

**入口**：[SemanticAnalyzer.java](src/sql_compiler/SemanticAnalyzer.java) 的 [analyze()](src/sql_compiler/SemanticAnalyzer.java#L48)。

语法分析只保证「句子长得像 SQL」；语义分析保证「句子**说得通**」。这一阶段做三件编译原理的核心工作：
**名字绑定（name binding）**、**类型检查（type checking）**、以及**符号表（symbol table）的读写**。

### 4.1 Catalog：编译期的符号表

[Catalog.java](src/sql_compiler/Catalog.java) 就是本实现的**符号表**，结构是：

```
table_name  ->  (column_name -> ColumnDef)
```

即一张「表名 → 列名 → 列定义」的两级映射，用 `LinkedHashMap` 保持列顺序。列定义 [ColumnDef](src/utils/ColumnDef.java)
携带列名、类型、长度/精度等属性——这正是符号表条目（symbol table entry）里存的东西：**名字 + 关于这个名字的全部类型信息**。

它提供符号表的标准接口：`createTable`（登记）、`containsTable`/`containsColumn`（查询存在性）、
`getColumn`/`getColumns`（取定义）、`getTableNames`（遍历）。

### 4.2 作用域与名字绑定

SQL 的 `FROM` 里可以有别名和多张表，所以名字解析需要**作用域（scope）**。本实现在
[Scope](src/sql_compiler/SemanticAnalyzer.java#L392) 里维护两张表：

- `aliasToTable`：别名/表名 → 真实表名（`student s` 里 `s → student`）；
- `tables`：参与查询的所有表名列表。

[resolveColumnDef()](src/sql_compiler/SemanticAnalyzer.java#L305) 实现名字绑定的规则：

1. 带表限定 `t.col`：先查 `aliasToTable` 把别名翻译成真实表，再查列；查不到报 `TableNotFound` / `ColumnNotFound`。
2. 单表、无限定：直接在该表里查列。
3. 多表、无限定：**在所有表里找**，唯一命中可用；命中多个表里同名列则报 `AmbiguousColumn`（列名歧义）。

这就是教材里「**作用域嵌套 / 重名解析规则**」的落地：先局部（表限定）后全局（多表扫描），
冲突时报歧义错误。

### 4.3 类型检查：inferType 的递归推导

[inferType()](src/sql_compiler/SemanticAnalyzer.java#L224) 是语义分析的心脏。它**自底向上递归遍历表达式树**，
一边完成名字绑定（遇到 `ColumnRef` 就查符号表），一边推导每个子表达式的类型，最终给出整棵树的类型。
所有「什么运算能作用在什么类型上」的规则，都集中在独立的 [TypeSystem.java](src/sql_compiler/TypeSystem.java) 里
（编译原理里叫「把类型规则集中成一张表，而非散落在 if/else 里」）：

- `INT + INT → INT`，`INT / INT → FLOAT`，含 `FLOAT`/`DECIMAL` 的算术向宽类型提升；
- 数值可全序比较；字符串族只允许 `= / !=`；`AND/OR/NOT` 要求操作数是 `BOOL`；
- 赋值兼容（INSERT/UPDATE）：数值族互赋、字符串族互赋、`DATE ← VARCHAR` 等。

对示例的 WHERE，`inferType` 推导出：

```
inferType(age > 20 - 2 AND true)
  = 推导 BinaryExpr(AND)：
      左 = Comparison(GT)：age 是 INT、20-2 是 INT → 比较合法 → BOOL
      右 = Literal(true) → BOOL
      AND 两侧都是 BOOL → 合法 → 整体 BOOL
```

于是 `checkWhere` 确认「WHERE 条件类型 = BOOL」通过。若有人写 `WHERE age`（age 是 INT 不是 BOOL），
这里就会抛 `TypeError: WHERE condition must be BOOL, but got INT`。

对 `selectItems` 里的 `name`、`age`，`inferType` 分别解析出 `VARCHAR`、`INT`——这一步同时验证了
「列真的存在」，因为 `resolveColumn` 查不到列会直接报错。

### 4.4 语义分析还会「写」符号表

语义分析不止读符号表，**CREATE TABLE 在这一阶段就把表注册进 Catalog**（[analyzeCreate()](src/sql_compiler/SemanticAnalyzer.java#L72)）：

```
CREATE TABLE student (...)  →  catalog.createTable("student", [id, name, age, gpa])
```

所以我们的示例能跑通，靠的是这条执行顺序：

1. 第一条语句 `CREATE TABLE student ...` 的语义分析把 `student` 写进符号表；
2. 第二条语句 `SELECT ... FROM student ...` 的语义分析才能 `requireTable(student)` 成功。

这也是为什么 [Main.java](src/cli/Main.java) 要**逐条**做语义分析而不是整体做——语义依赖前面语句的副作用。
同时，正因为这里「先写符号表、执行阶段才可能失败」，才需要 4.1 里说的 `snapshot()/restore()` 回滚机制。

> 编译原理映射：语义分析 = **符号表的构造与查询 + 作用域解析 + 静态类型检查**。
> 它是「静态语义（static semantics）」的典型例子——一切都在不运行程序的情况下完成，
> 靠的是符号表里积累的声明信息。教材里「声明先于使用」「类型必须匹配」的原则，在这里逐条兑现。

**错误**：[SemanticError](src/utils/SemanticError.java) 格式 `[错误类型, 位置, 原因说明]`，
如 `TableNotFound`、`ColumnNotFound`、`AmbiguousColumn`、`TypeError`、`SemanticError`
（因 AST 节点暂不带行列号，位置统一用 `(0,0)` 占位）。

---

## 五、阶段四：计划生成（AST → 逻辑计划）

**入口**：[Planner.java](src/sql_compiler/Planner.java) 的 [plan()](src/sql_compiler/Planner.java#L38)。

语义分析通过后，AST 已经「语义合法」。但 AST 还是「用户怎么写就怎么摆」的树，离「机器怎么算」还差一步。
计划生成器（Planner）把 AST 翻译成**逻辑执行计划（logical plan）**——一棵由**关系代数算子**组成的树。
这棵算子树就是本编译器的**中间表示（IR, Intermediate Representation）**。

### 5.1 算子（operator）一览

每个算子是一个 [PlanNode](src/sql_compiler/plan/PlanNode.java) 子类，对应关系代数里的一个基本操作：

| 算子 | 关系代数 | 说明 |
|------|----------|------|
| `SeqScanPlan` | 表扫描 σ 前的基表 | 全表顺序扫描，输出整行 |
| `FilterPlan` | **σ（选择）** | 按 WHERE 条件保留满足条件的行 |
| `ProjectPlan` | **π（投影）** | 按 SELECT 列表只输出需要的列 |
| `SortPlan` | **τ（排序）** | 按 ORDER BY 键排序 |
| `AggregatePlan` | **γ（分组聚合）** | GROUP BY / 聚合函数 |
| `JoinPlan` | **⋈（连接）** | INNER / LEFT 连接 |

`PlanNode` 基类带 `children` 列表，并提供 [toTree()](src/sql_compiler/plan/PlanNode.java#L20) 输出 ASCII 树
（优化前后对比展示就靠它）。

### 5.2 SELECT 的生成规则：自底向上

[planSelect()](src/sql_compiler/Planner.java#L62) 从**叶节点往上**逐层包裹，完全对应 SQL 各子句的语义顺序：

```
SeqScan(student)                              // FROM  → 从哪张表读
   ↓ 有 WHERE
Filter(age > 20 - 2 AND true)                 // WHERE → 筛掉哪些行
   ↓ 无聚合、无 ORDER BY
Project[name, age]                            // SELECT → 输出哪些列
```

转换规则（[plan()](src/sql_compiler/Planner.java#L38)）：

| AST | 逻辑计划 |
|-----|----------|
| `CreateTableStmt` | `CreateTablePlan` |
| `InsertStmt` | `InsertPlan` |
| `SelectStmt` | `Project / Sort / Aggregate → [Filter] → [Join] → SeqScan` |
| `DeleteStmt` | `DeletePlan` |
| `UpdateStmt` | `UpdatePlan` |

两个值得注意的细节：

- **FROM 里的逗号 = 交叉连接**。`parseRelation()` 把 `A, B` 解析成 `JoinRelation(A, B, INNER, null)`，
  于是 `planRelation()` 生成一个 `condition = null` 的 `JoinPlan`（无 ON 的 INNER JOIN = 笛卡尔积）。
- **`SELECT *` 用哨兵 `Star` 表示**。`ProjectPlan` 的投影表达式是一个 `Star`，真正的列展开推迟到执行引擎
  结合表 schema 去做。这是「把未定信息留到最后一刻再决定」的常见技巧。

对示例，`planSelect` 产出的计划树（`toTree()` 输出）：

```
Project[name, age]
+- Filter[((age > (20 - 2)) AND true)]
   +- SeqScan[student]
```

> 编译原理映射：这一阶段对应教材「中间代码生成」的简化版。AST 是**语言结构**的树，
> 逻辑计划是**执行语义**的树；二者往往能一一对应，但计划是「可执行」的方向——
> 每个节点不再是语法成分，而是一个知道「怎么从子节点算到自己」的算子。

---

## 六、阶段五：规则优化（逻辑计划 → 等价计划）

**入口**：[Optimizer.java](src/sql_compiler/Optimizer.java) 的 [optimize()](src/sql_compiler/Optimizer.java#L38)。

逻辑计划「能算」，但未必「算得快」。优化器对计划做**等价变换**——保证结果不变的前提下让计划更高效。
本实现是「**规则式（rule-based）优化器**」：五条规则反复应用，直到计划不再变化。

### 6.1 不动点迭代

[optimize()](src/sql_compiler/Optimizer.java#L38) 的核心是一个「**迭代到不动点（fixed point）**」的循环：

```java
for (int pass = 0; pass < MAX_PASSES; pass++) {
    PlanNode next = applyAllRules(current);
    if (next.toTree().equals(current.toTree()))   // 结构不再变化 → 收敛
        return next;
    current = next;
}
```

每轮依次跑五条规则（[applyAllRules()](src/sql_compiler/Optimizer.java#L51)）：常量折叠 → 布尔化简 →
投影剪枝 → 谓词下推 → 冗余消除。`MAX_PASSES = 4` 是防死循环的兜底上限。
「用 `toTree()` 的字符串比较判断是否变化」是一个很直观的实现技巧——等价于比较两棵树的结构。

### 6.2 五条规则

| # | 规则 | 做什么 | 例 |
|---|------|--------|----|
| 1 | 常量折叠 `constantFold` | 纯常量的算术直接求值 | `age > 10 + 8` → `age > 18` |
| 2 | 布尔化简 `booleanSimplify` | 常量比较求值 + AND/OR/NOT 恒等式 | `1=1 AND X` → `X` |
| 3 | 投影剪枝 `projectPruning` | 删掉 `SELECT *` 的 `Project["*"]` | — |
| 4 | 谓词下推 `predicatePushdown` | Filter 移到 Project 之下（靠近扫描） | — |
| 5 | 冗余消除 `removeRedundant` | 去掉恒真 Filter | `Filter[true]` → 透传 |

**关键设计：所有规则都是「纯」的**（[mapChildren()](src/sql_compiler/Optimizer.java#L346) 不修改原节点，
而是重建新节点返回）。这带来一个硬性收益：**优化前、优化后的两棵树可以同时保留**，用于展示对比——
这是编译原理里「优化必须是安全等价变换」的体现：既然变换不破坏原树，就可以放心地保留旧树做对照。

### 6.3 追踪示例的优化过程

对上面的计划树，优化器实际发生了什么：

**第 1 轮 · 规则 1（常量折叠）**：`foldExpr` 自底向上遍历 Filter 条件，遇到
`BinaryExpr(MINUS, Literal(20), Literal(2))`——两侧都是数值字面量，`evalArithmetic` 直接算出 `18`，
替换成 `Literal(18, INT)`。条件变成 `((age > 18) AND true)`。

**第 1 轮 · 规则 2（布尔化简）**：`simplifyExpr` 套用恒等式 `X AND true → X`，把
`((age > 18) AND true)` 化简成 `(age > 18)`。

**规则 3/4/5**：本例不触发（没有 `SELECT *`、Filter 不在 Project 之上、条件不是恒真）。

于是第 1 轮结束，计划变成：

```
Project[name, age]
+- Filter[(age > 18)]
   +- SeqScan[student]
```

**第 2 轮**：再跑一遍五条规则，`18` 已是最简、条件已无恒等式可套，`toTree()` 与上一轮相同 → 收敛，返回。

**优化前后对比**：

```
优化前                                    优化后
Project[name, age]                       Project[name, age]
+- Filter[((age > (20 - 2)) AND true)]   +- Filter[(age > 18)]
   +- SeqScan[student]                      +- SeqScan[student]
```

对照原始 SQL `WHERE age > 20 - 2 AND true`，优化器等价地把它变成了 `WHERE age > 18`：
`20 - 2` 从「运行期每条记录都算一遍」变成「编译期算一次」，`AND true` 直接消除。这就是
**常量折叠**和**布尔化简**两个教科书优化在真实系统里的效果。

> 编译原理映射：优化器 = **多趟（multi-pass）的等价变换**。每一趟是「在树里找到某种可改写的模式
> （pattern），把它替换成等价但更优的形式」，反复应用直到没有可改写的模式为止（不动点）。
> 「纯函数式改写」保证了变换的可组合性与可回退性，这是编译器优化器设计的黄金法则。

---

## 7、终点：交给执行引擎

优化完成后的计划树，就是编译阶段的**最终产物**。它被交给 [Executor.java](src/engine/Executor.java) 的
[execute()](src/engine/Executor.java#L52)，按计划节点的类型逐个分派执行：

```
Executor.execute(计划根节点)
   ├─ CreateTablePlan → 建表
   ├─ InsertPlan      → 插入
   ├─ SeqScanPlan     → 顺序扫描，产出 QueryResult
   ├─ FilterPlan      → 对子算子输出的行按条件过滤
   ├─ ProjectPlan     → 投影出需要的列
   ├─ SortPlan        → 排序
   ├─ AggregatePlan   → 分组聚合
   ├─ JoinPlan        → 连接
   └─ ...             → 读写存储引擎
```

对示例，最终执行的是 `Project[name, age] → Filter[age > 18] → SeqScan[student]`，
由执行引擎自顶向下地「拉取」数据：Project 向 Filter 要一行，Filter 向 SeqScan 要一行，
SeqScan 读磁盘返回一行，Filter 判断 `age > 18` 决定留不留，Project 只保留 `name`、`age` 两列。
（这段已进入执行阶段，超出本文「仅编译阶段」的范围，故不展开。）

**到这里，一条 SQL 从字符串到可执行计划的编译之旅就走完了：**

```
SQL 字符串
   │  词法分析  Lexer         —— 字符串 → Token 流
   ▼
Token 流
   │  语法分析  Parser        —— Token 流 → AST（递归下降）
   ▼
AST
   │  语义分析  SemanticAnalyzer —— 名字绑定 + 类型检查 + 读写符号表 Catalog
   ▼
类型化的合法 AST（符号表已更新）
   │  计划生成  Planner       —— AST → 逻辑计划（算子树，IR）
   ▼
逻辑计划
   │  规则优化  Optimizer     —— 五条规则迭代到不动点
   ▼
优化后的计划  ──────────────►  Executor（执行阶段，本文止于此）
```

---

## 附录：编译原理术语 ↔ 本实现对照表

| 编译原理概念 | 本实现对应 | 位置 |
|--------------|-----------|------|
| 词法单元 / Token | `Token`（四元式 `[种别码, 词素, 行, 列]`） | [Token.java](src/sql_compiler/Token.java) |
| 词类 / 种别码 | `TokenType`（KEYWORD/IDENTIFIER/CONST/OPERATOR/DELIMITER） | [TokenType.java](src/utils/TokenType.java) |
| 保留字表 | `Lexer.KEYWORDS`（`HashSet<String>`） | [Lexer.java](src/sql_compiler/Lexer.java#L23) |
| 最长匹配 | `readOperator()` 先看 `peek(1)` 再决定单/双字符 | [Lexer.java](src/sql_compiler/Lexer.java#L187) |
| 递归下降 / LL(1) | `Parser`：每个非终结符一个方法，`peek()` 向前看一个 token | [Parser.java](src/sql_compiler/Parser.java) |
| 文法 | [grammar.md](grammar.md) | — |
| 运算符优先级 | `parseOr → parseAnd → parseNot → parseComparison → parseAdditive → parseMultiplicative → parsePrimary` 的调用链 | [Parser.java](src/sql_compiler/Parser.java#L315-L420) |
| 左结合 / 右结合 | 循环里 `left = new BinaryExpr(op, left, …)` / `NOT` 递归自身 | [Parser.java](src/sql_compiler/Parser.java#L315-L333) |
| 抽象语法树 AST | `src/sql_compiler/ast/`（21 个节点类） | [ast/](src/sql_compiler/ast/) |
| 符号表 | `Catalog`（表 → 列 → 列定义） | [Catalog.java](src/sql_compiler/Catalog.java) |
| 符号表条目 | `ColumnDef`（列名 + 类型 + 长度/精度） | [ColumnDef.java](src/utils/ColumnDef.java) |
| 作用域 / 名字解析 | `Scope` + `resolveColumnDef()`（别名翻译 + 歧义检查） | [SemanticAnalyzer.java](src/sql_compiler/SemanticAnalyzer.java#L305-L425) |
| 静态类型检查 | `inferType()` + `TypeSystem` 规则表 | [SemanticAnalyzer.java](src/sql_compiler/SemanticAnalyzer.java#L224) |
| 类型系统 | `TypeSystem`（算术/比较/逻辑/赋值/聚合的类型规则） | [TypeSystem.java](src/sql_compiler/TypeSystem.java) |
| 中间表示 IR | 逻辑计划算子树（`PlanNode` 及子类） | [plan/](src/sql_compiler/plan/) |
| 关系代数算子 | `SeqScan`(扫描) / `Filter`(σ) / `Project`(π) / `Sort`(τ) / `Aggregate`(γ) / `Join`(⋈) | [plan/](src/sql_compiler/plan/) |
| 优化遍 / 等价变换 | `Optimizer` 五条规则 + 不动点迭代 | [Optimizer.java](src/sql_compiler/Optimizer.java) |
| 常量折叠 | `constantFold()` / `evalArithmetic()` | [Optimizer.java](src/sql_compiler/Optimizer.java#L61) |
| 布尔化简 | `booleanSimplify()` / `simplifyExpr()` | [Optimizer.java](src/sql_compiler/Optimizer.java#L174) |
| 谓词下推 | `predicatePushdown()` | [Optimizer.java](src/sql_compiler/Optimizer.java#L320) |
| 编译器驱动程序 Driver | `Main.executeAndFormat()` 的流水线 | [Main.java](src/cli/Main.java#L95) |
| 错误恢复 / 回滚 | `Catalog.snapshot()/restore()` | [Catalog.java](src/sql_compiler/Catalog.java#L82) |
