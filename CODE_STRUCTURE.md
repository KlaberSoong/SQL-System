# 3.1–3.6 代码结构说明（SQL 编译器模块）

> 本文档对应 `plan.md` 第三章「各模块详细设计」中的 3.1–3.6，即 **SQL 编译器**部分
> （词法 → 语法 → 语义 → 类型系统 → 计划生成 → 规则式优化）。
> 存储系统（第四章）与数据库引擎（第五章）不在此范围内。

---

## 目录

- [一、总体数据流](#一总体数据流)
- [二、包结构与职责划分](#二包结构与职责划分)
- [三、各节代码结构详解](#三各节代码结构详解)
  - [3.1 词法分析器](#31-词法分析器)
  - [3.2 语法分析器](#32-语法分析器)
  - [3.3 语义分析器](#33-语义分析器)
  - [3.4 类型系统](#34-类型系统)
  - [3.5 执行计划生成器](#35-执行计划生成器)
  - [3.6 规则式优化器](#36-规则式优化器)
- [四、异常体系](#四异常体系)
- [五、关键类型（utils）](#五关键类型utils)

---

## 一、总体数据流

```
SQL 文本
   │  3.1 Lexer     词法分析
   ▼
Token 流   [TokenType, lexeme, line, col]
   │  3.2 Parser    语法分析（递归下降）
   ▼
AST   （Statement / Expr 树）
   │  3.3 SemanticAnalyzer + Catalog   语义分析（名字绑定 + 类型检查）
   ▼
Typed AST（语义合法，CREATE TABLE 已注册进 Catalog）
   │  3.5 Planner   计划生成
   ▼
Logical Plan   （PlanNode 算子树）
   │  3.6 Optimizer 规则式优化（5 条规则）
   ▼
Optimized Plan   （可保留优化前后两棵树用于对比展示）
```

类型规则（3.4）作为被 3.3 与执行引擎共用的独立模块 `TypeSystem`，贯穿于语义检查与常量折叠的求值过程。

---

## 二、包结构与职责划分

| 包 / 目录 | 职责 | 对应章节 |
|-----------|------|----------|
| `src/sql_compiler/` | 编译器主类（Lexer/Parser/Semantic/Catalog/TypeSystem/Planner/Optimizer） | 3.1–3.6 |
| `src/sql_compiler/ast/` | AST 节点（语句 + 表达式） | 3.2 |
| `src/sql_compiler/plan/` | 逻辑计划节点（算子） | 3.5 |
| `src/utils/` | 枚举 / 列定义 / 异常（被编译链路共用） | 3.1–3.4 |

各模块只依赖「接口契约」：`utils/*` 的枚举与异常、`ast/*` 与 `plan/*` 的节点字段，
使得前端（词法/语法）与后端（语义/计划/优化）可并行开发、独立替换方法体。

---

## 三、各节代码结构详解

### 3.1 词法分析器

**相关文件**：[Lexer.java](src/sql_compiler/Lexer.java)、[Token.java](src/sql_compiler/Token.java)、
[TokenType.java](src/utils/TokenType.java)、[ConstSubtype.java](src/utils/ConstSubtype.java)、[LexError.java](src/utils/LexError.java)

**Token 结构**（四元式 `[种别码, 词素值, 行号, 列号]`）：
- `TokenType` 种别码：`KEYWORD / IDENTIFIER / CONST / OPERATOR / DELIMITER`。
- `Token` 携带 `type / lexeme / line / col`；`CONST` 额外携带 `constSubtype` 与解析后的 `constValue`。
- `ConstSubtype` 常量子类型：`INT_CONST / FLOAT_CONST / STRING_CONST / BOOL_CONST / NULL_CONST`。

**Lexer 工作方式**：持有源字符串 `source` 与游标 `pos`，`peek()` 预读、`advance()` 消费并同步维护 `line/col`。

| 方法 | 功能 |
|------|------|
| [tokenize()](src/sql_compiler/Lexer.java#L44) | 主入口：跳过空白/注释后按首字符分派，产出 Token 流 |
| [skipWhitespaceAndComments()](src/sql_compiler/Lexer.java#L70) | 跳过空白、`--` 行注释、`/* */` 块注释 |
| [readIdentifierOrKeyword()](src/sql_compiler/Lexer.java#L104) | 读标识符/关键字/布尔字面量，按大小写不敏感查关键字表 |
| [readNumber()](src/sql_compiler/Lexer.java#L127) | 读整数/浮点常量，非法数字（如 `1.2.3`）报错 |
| [readString()](src/sql_compiler/Lexer.java#L156) | 读单引号字符串，`''` 转义为单引号，未闭合报错 |
| [readOperator()](src/sql_compiler/Lexer.java#L187) | 读运算符，多字符（`>= <= != <> ==`）优先 |
| [readDelimiter()](src/sql_compiler/Lexer.java#L204) | 读单字符分隔符（`( ) , ; .`） |

**错误处理**：非法字符、未闭合字符串、非法数字、未闭合块注释统一抛 `LexError`（含行号列号与原因），不崩溃。

---

### 3.2 语法分析器

**相关文件**：[Parser.java](src/sql_compiler/Parser.java)、[ast/](src/sql_compiler/ast/)（21 个）、[SyntaxError.java](src/utils/SyntaxError.java)、[Operator.java](src/utils/Operator.java)、[JoinType.java](src/utils/JoinType.java)

**AST 节点**（`ast/*`）：

| 节点 | 类型 | 说明 |
|------|------|------|
| [Statement](src/sql_compiler/ast/Statement.java) | 接口 | 五类语句的公共父类型 |
| [CreateTableStmt](src/sql_compiler/ast/CreateTableStmt.java) | 语句 | 表名 + 列定义列表 |
| [InsertStmt](src/sql_compiler/ast/InsertStmt.java) | 语句 | 表名 + 列名列表（可 null）+ 值表达式列表 |
| [SelectStmt](src/sql_compiler/ast/SelectStmt.java) | 语句 | 投影列表 + FROM 关系 + WHERE/groupBy/orderBy（均可 null） |
| [DeleteStmt](src/sql_compiler/ast/DeleteStmt.java) | 语句 | 表名 + WHERE（可 null） |
| [UpdateStmt](src/sql_compiler/ast/UpdateStmt.java) | 语句 | 表名 + 赋值列表 + WHERE（可 null） |
| [Relation](src/sql_compiler/ast/Relation.java) | 抽象类 | FROM 关系的公共父类型 |
| [TableRelation](src/sql_compiler/ast/TableRelation.java) | 关系 | 表名 + 别名（可 null） |
| [JoinRelation](src/sql_compiler/ast/JoinRelation.java) | 关系 | 左/右关系 + 连接类型 + ON（可 null） |
| [Expr](src/sql_compiler/ast/Expr.java) | 抽象类 | 表达式的公共父类型 |
| [ColumnRef](src/sql_compiler/ast/ColumnRef.java) | 表达式 | 列引用，`table` 可为 null（支持 `table.col`） |
| [Literal](src/sql_compiler/ast/Literal.java) | 表达式 | 字面量（值 + 类型） |
| [NullLiteral](src/sql_compiler/ast/NullLiteral.java) | 表达式 | SQL NULL 字面量（无类型） |
| [IsNullExpr](src/sql_compiler/ast/IsNullExpr.java) | 表达式 | `operand IS [NOT] NULL`，恒返回 BOOL |
| [Comparison](src/sql_compiler/ast/Comparison.java) | 表达式 | `left op right`，op ∈ 比较运算符 |
| [BinaryExpr](src/sql_compiler/ast/BinaryExpr.java) | 表达式 | `left op right`，op 为逻辑或算术运算符 |
| [UnaryExpr](src/sql_compiler/ast/UnaryExpr.java) | 表达式 | `NOT operand` |
| [Star](src/sql_compiler/ast/Star.java) | 表达式 | `SELECT *` 的星号 |
| [AggregateCall](src/sql_compiler/ast/AggregateCall.java) | 表达式 | 聚合调用 `func(arg)`，`arg` 为 null 表示 `COUNT(*)` |
| [OrderByItem](src/sql_compiler/ast/OrderByItem.java) | 排序键 | 表达式 + 升降序标志 |
| [Assignment](src/sql_compiler/ast/Assignment.java) | 赋值项 | UPDATE 的 `column = expr` |

**Parser 递归下降方法**（按优先级由低到高，逐层调用）：

| 方法 | 文法 | 优先级 |
|------|------|--------|
| [parseProgram()](src/sql_compiler/Parser.java#L67) | `statement (';' statement)* ';'?` | 顶层 |
| [parseStatement()](src/sql_compiler/Parser.java#L83) | 按关键字分派 CREATE/INSERT/SELECT/DELETE/UPDATE | — |
| [parseOr()](src/sql_compiler/Parser.java#L315) | `and_expr (OR and_expr)*` | 最低 |
| [parseAnd()](src/sql_compiler/Parser.java#L324) | `not_expr (AND not_expr)*` | |
| [parseNot()](src/sql_compiler/Parser.java#L333) | `NOT not_expr \| comparison` | |
| [parseComparison()](src/sql_compiler/Parser.java#L344) | `additive (比较运算符 additive)? \| additive IS [NOT] NULL` | |
| [parseAdditive()](src/sql_compiler/Parser.java#L375) | `multiplicative (('+'\|'-') multiplicative)*` | |
| [parseMultiplicative()](src/sql_compiler/Parser.java#L393) | `primary (('*'\|'/') primary)*` | |
| [parsePrimary()](src/sql_compiler/Parser.java#L420) | `identifier \| constant \| NULL \| DATE/DECIMAL 类型化字面量 \| '(' or_expr ')' \| 聚合调用` | 最高 |

**语句/子句级方法**：[parseCreateTable()](src/sql_compiler/Parser.java#L104)（建表）、[parseInsert()](src/sql_compiler/Parser.java#L148)、
[parseSelect()](src/sql_compiler/Parser.java#L174)（SELECT，GROUP BY 内联解析）、[parseDelete()](src/sql_compiler/Parser.java#L210)、[parseUpdate()](src/sql_compiler/Parser.java#L219)（UPDATE … SET）、
[parseAssignment()](src/sql_compiler/Parser.java#L233)（列 = 表达式）、[parseRelation()](src/sql_compiler/Parser.java#L244)（FROM 关系：逗号交叉连接 / `[INNER|LEFT] JOIN … ON`，左结合）、
[parseTableRef()](src/sql_compiler/Parser.java#L273)（表 + 可选别名）、[parseWhereClause()](src/sql_compiler/Parser.java#L307)、
[parseOrderBy()](src/sql_compiler/Parser.java#L285)/[parseOrderByItem()](src/sql_compiler/Parser.java#L295)、[parseAggregateCall()](src/sql_compiler/Parser.java#L448)（`COUNT(*)` 或 `func(expr)`）。
聚合函数名（COUNT/SUM/AVG/MIN/MAX）不是保留关键字，仅在标识符后紧跟 `(` 时按聚合调用解析。

**语法错误**：三段式 `SyntaxError`（位置 + 实际符号 + 期望集合），由 `error()` 统一构造。

---

### 3.3 语义分析器

**相关文件**：[SemanticAnalyzer.java](src/sql_compiler/SemanticAnalyzer.java)、[Catalog.java](src/sql_compiler/Catalog.java)、
[SemanticError.java](src/utils/SemanticError.java)、[ColumnDef.java](src/utils/ColumnDef.java)、[ColumnType.java](src/utils/ColumnType.java)

**Catalog（符号表）**：`table_name -> (column_name -> ColumnDef)`，用 `LinkedHashMap` 保持列顺序。
接口：[createTable()](src/sql_compiler/Catalog.java#L17) / [containsTable()](src/sql_compiler/Catalog.java#L30) / [containsColumn()](src/sql_compiler/Catalog.java#L35) / [getColumn()](src/sql_compiler/Catalog.java#L41) / [getColumns()](src/sql_compiler/Catalog.java#L47) / [getTableNames()](src/sql_compiler/Catalog.java#L56)。

**SemanticAnalyzer 检查项**（`analyze()` 主入口按语句类型分发）：

| 方法 | 功能 |
|------|------|
| [analyze()](src/sql_compiler/SemanticAnalyzer.java#L48) | 主入口：逐条语句分析 |
| [analyzeCreate()](src/sql_compiler/SemanticAnalyzer.java#L72) | 重复建表检查 + 列名重复检查 + 注册进 Catalog |
| [analyzeInsert()](src/sql_compiler/SemanticAnalyzer.java#L87) | 表存在性、目标列存在性、列数一致、值类型与列类型兼容 |
| [analyzeSelect()](src/sql_compiler/SemanticAnalyzer.java#L139) | FROM 各表存在性 + 投影/分组/排序列类型 + 聚合与分组合法性 |
| [analyzeDelete()](src/sql_compiler/SemanticAnalyzer.java#L183) | 表存在性 + WHERE 必须为 BOOL |
| [analyzeUpdate()](src/sql_compiler/SemanticAnalyzer.java#L190) | 表存在性 + SET 列存在性 + 值类型可赋值 + WHERE 为 BOOL |
| [checkVarcharLength()](src/sql_compiler/SemanticAnalyzer.java#L128) | VARCHAR 字面量长度上限检查 |
| [checkWhere()](src/sql_compiler/SemanticAnalyzer.java#L213) | WHERE 条件类型必须为 BOOL |
| [inferType()](src/sql_compiler/SemanticAnalyzer.java#L224) | 核心：递归推导表达式类型，同时完成列名绑定与类型检查 |
| [resolveColumn()](src/sql_compiler/SemanticAnalyzer.java#L300) / [resolveColumnDef()](src/sql_compiler/SemanticAnalyzer.java#L305) | 名字绑定：ColumnRef → Catalog 中的列定义（含别名/表限定） |
| [requireTable()](src/sql_compiler/SemanticAnalyzer.java#L341) | 取表结构，表不存在报错 |

**作用域（Scope）**：`FROM` 关系被折叠成 `Scope`（别名→表名映射 + 参与表列表）。带表限定 `t.col` 先查别名映射；
无限定列在单表直接解析、多表时唯一命中可用、多命中报 `AmbiguousColumn`。
`inferType` 对 `AggregateCall` 调用 `TypeSystem.checkAggregate` 推导结果类型。

**聚合校验**：有 `GROUP BY` 或聚合时，SELECT 项必须是聚合调用或等于某个分组键，否则报 `SemanticError`（`*` 亦不允许）。

**错误输出**：`SemanticError` 格式 `[错误类型, 位置, 原因说明]`；因 AST 节点暂不带行列号，位置统一用 `(0,0)` 占位。

---

### 3.4 类型系统

**相关文件**：[TypeSystem.java](src/sql_compiler/TypeSystem.java)（依赖 [ColumnType.java](src/utils/ColumnType.java)、[Operator.java](src/utils/Operator.java)）

**设计目标**：把「运算符作用在某两类上产生什么结果 / 是否合法」的全部规则集中到一处，避免散落在
`SemanticAnalyzer` / `Executor` 的 if/else 里；`SemanticAnalyzer` 只「查询」本类。

**类型规则**：

| 表达式 | 结果 |
|--------|------|
| INT + INT | INT |
| INT / INT | FLOAT |
| 含 FLOAT 的算术运算 | FLOAT |
| 含 DECIMAL 的算术运算（含除法） | DECIMAL |
| 数值比较（INT/FLOAT/DECIMAL 混比） | BOOL |
| DATE 全序比较（< <= > >= = !=） | BOOL |
| 字符串族（VARCHAR/CHAR/TEXT）仅 = / != | BOOL |
| VARCHAR/CHAR/TEXT/BOOL 的大小比较（< <= > >=） | 报错 |
| BOOL AND BOOL / NOT BOOL | BOOL |
| INT + VARCHAR | 报错 |
| 数值族（INT/FLOAT/DECIMAL）互赋、字符串族互赋 | 合法 |
| DATE ← VARCHAR、DECIMAL ← 数值/VARCHAR | 合法（运行时 coerce 解析） |

**对外方法**：

| 方法 | 功能 |
|------|------|
| [isNumeric()](src/sql_compiler/TypeSystem.java#L17) | 判断是否为 INT/FLOAT/DECIMAL |
| [isStringFamily()](src/sql_compiler/TypeSystem.java#L22) | 判断是否为 VARCHAR/CHAR/TEXT |
| [arithmetic()](src/sql_compiler/TypeSystem.java#L28) | 算术运算（+ - * /）的结果类型 |
| [checkComparison()](src/sql_compiler/TypeSystem.java#L47) | 比较运算的类型合法性检查 |
| [checkLogical()](src/sql_compiler/TypeSystem.java#L73) | AND/OR 的类型合法性检查 |
| [checkUnaryLogical()](src/sql_compiler/TypeSystem.java#L82) | NOT 的类型合法性检查 |
| [checkAssignable()](src/sql_compiler/TypeSystem.java#L90) | INSERT/UPDATE 赋值兼容性检查 |
| [checkAggregate()](src/sql_compiler/TypeSystem.java#L111) | 聚合函数结果类型：COUNT→INT、SUM→argType(数值)、AVG→FLOAT(数值)、MIN/MAX→argType |

类型不匹配时抛 `IllegalArgumentException`，由调用方捕获后包装成带位置的 `SemanticError`。

---

### 3.5 执行计划生成器

**相关文件**：[Planner.java](src/sql_compiler/Planner.java)、[plan/](src/sql_compiler/plan/)（11 个）

**算子（PlanNode 子类）**：

| 算子 | 字段 | 说明 |
|------|------|------|
| [CreateTablePlan](src/sql_compiler/plan/CreateTablePlan.java) | table, columns | 建表 |
| [InsertPlan](src/sql_compiler/plan/InsertPlan.java) | table, columns（可 null）, values | 插入 |
| [SeqScanPlan](src/sql_compiler/plan/SeqScanPlan.java) | table, alias（可 null） | 全表顺序扫描；alias 非空时输出 `alias.col` 列名 |
| [FilterPlan](src/sql_compiler/plan/FilterPlan.java) | condition | 按 WHERE 过滤 |
| [ProjectPlan](src/sql_compiler/plan/ProjectPlan.java) | expressions（`List<Expr>`） | 按 SELECT 列表投影 |
| [SortPlan](src/sql_compiler/plan/SortPlan.java) | keys（`List<OrderByItem>`） | 按 ORDER BY 键排序 |
| [AggregatePlan](src/sql_compiler/plan/AggregatePlan.java) | selectItems, groupBy（可 null） | 分组聚合，直接产出最终行 |
| [JoinPlan](src/sql_compiler/plan/JoinPlan.java) | left, right, type, condition（可 null） | INNER/LEFT 连接 |
| [DeletePlan](src/sql_compiler/plan/DeletePlan.java) | table, condition（可 null） | 删除 |
| [UpdatePlan](src/sql_compiler/plan/UpdatePlan.java) | table, assignments, condition（可 null） | 更新 |

`PlanNode` 抽象基类带 `children` 列表，提供 `toTree()` ASCII 树形输出（供优化前后对比展示）。

**转换规则**（[plan()](src/sql_compiler/Planner.java#L38) 主入口）：

| AST | Logical Plan |
|-----|--------------|
| [CreateTableStmt](src/sql_compiler/ast/CreateTableStmt.java) | [CreateTablePlan](src/sql_compiler/plan/CreateTablePlan.java) |
| [InsertStmt](src/sql_compiler/ast/InsertStmt.java) | [InsertPlan](src/sql_compiler/plan/InsertPlan.java) |
| [SelectStmt](src/sql_compiler/ast/SelectStmt.java) | `Project/Sort/Aggregate → [Filter] → [Join] → SeqScan` |
| [DeleteStmt](src/sql_compiler/ast/DeleteStmt.java) | [DeletePlan](src/sql_compiler/plan/DeletePlan.java) |
| [UpdateStmt](src/sql_compiler/ast/UpdateStmt.java) | [UpdatePlan](src/sql_compiler/plan/UpdatePlan.java) |

**SELECT 计划**（[planSelect()](src/sql_compiler/Planner.java#L62)，自底向上）：[planRelation(from)](src/sql_compiler/Planner.java#L88) 生成 SeqScan/Join 子树 → 有 WHERE 包 `Filter` →
有聚合或 GROUP BY 包 `AggregatePlan`、否则包 `ProjectPlan` → 有 ORDER BY 包 `SortPlan`。
非聚合时 `SortPlan` 位于 `ProjectPlan` 之下（ORDER BY 可引用任意 FROM 列）；聚合时位于 `AggregatePlan` 之上。

**SELECT \* 约定**：`SELECT *` 生成的 `ProjectPlan` 投影表达式为单个 `Star` 哨兵，执行引擎遇到它时结合表 schema 展开为全部列。

---

### 3.6 规则式优化器

**相关文件**：[Optimizer.java](src/sql_compiler/Optimizer.java)

**主入口**：[optimize()](src/sql_compiler/Optimizer.java#L38) 迭代应用五条规则直到计划结构不再变化（不动点，`MAX_PASSES = 4` 兜底）。

**五条规则**（[applyAllRules()](src/sql_compiler/Optimizer.java#L51) 依次应用）：

| 规则 | 方法 | 效果 | 示例 |
|------|------|------|------|
| 1 常量折叠 | [constantFold()](src/sql_compiler/Optimizer.java#L61) | 算术常量直接求值 | `age > 10 + 8` → `age > 18` |
| 2 布尔化简 | [booleanSimplify()](src/sql_compiler/Optimizer.java#L174) | 常量比较求值 + AND/OR/NOT 恒等式 | `1=1 AND X` → `X` |
| 3 投影剪枝 | [projectPruning()](src/sql_compiler/Optimizer.java#L311) | 剪掉 `SELECT *` 产生的 `Project["*"]` | — |
| 4 谓词下推 | [predicatePushdown()](src/sql_compiler/Optimizer.java#L320) | Filter 下移到 Project 之下（靠近 SeqScan） | — |
| 5 冗余消除 | [removeRedundant()](src/sql_compiler/Optimizer.java#L337) | 去掉恒真 Filter | `Filter[true]` → 透传 |

**关键设计**：所有规则都是「纯」的——`mapChildren()` 不修改原节点，而是重建新节点返回，
因此可同时保留优化前、优化后两棵树用于展示结构对比（硬性验收点）。

**辅助方法**：[foldExpr()](src/sql_compiler/Optimizer.java#L113)/[simplifyExpr()](src/sql_compiler/Optimizer.java#L226)（单表达式）、[evalArithmetic()](src/sql_compiler/Optimizer.java#L150)/[evalComparison()](src/sql_compiler/Optimizer.java#L279)（常量求值）、
[referencedColumns()](src/sql_compiler/Optimizer.java#L450)/[collectColumns()](src/sql_compiler/Optimizer.java#L457)（收集列引用）、[withChildren()](src/sql_compiler/Optimizer.java#L364)（重建节点，支持 Sort/Aggregate 单子、Join 双子）、
[foldAssignments()](src/sql_compiler/Optimizer.java#L418)/[simplifyAssignments()](src/sql_compiler/Optimizer.java#L434)（UPDATE 赋值列表）。
常量折叠与布尔化简同样作用于 `UpdatePlan`（SET 值 + WHERE）与 `JoinPlan`（ON 条件）。

---

## 四、异常体系

| 异常 | 基类 | 输出格式 | 对应章节 |
|------|------|----------|----------|
| [DbException](src/utils/DbException.java) | `RuntimeException` | — | 基类 |
| [LexError](src/utils/LexError.java) | `DbException` | `[LexError] at (行,列): 原因` | 3.1 |
| [SyntaxError](src/utils/SyntaxError.java) | `DbException` | 三段式：位置 / 实际符号 / 期望集合 | 3.2 |
| [SemanticError](src/utils/SemanticError.java) | `DbException` | `[错误类型, 位置, 原因说明]` | 3.3 |

---

## 五、关键类型（utils）

| 类型 | 说明 |
|------|------|
| [ColumnType](src/utils/ColumnType.java) | INT / FLOAT / VARCHAR / BOOL / DATE / DECIMAL / CHAR / TEXT，含关键字与 `fromKeyword` 解析 |
| [ColumnDef](src/utils/ColumnDef.java) | 列名 + 类型 + 长度（VARCHAR/CHAR 长度、DECIMAL 精度）+ 标度（DECIMAL） |
| [Operator](src/utils/Operator.java) | 比较/逻辑/算术运算符枚举，含 `isComparison/isLogical/isArithmetic/isUnaryLogical` 分类 |
