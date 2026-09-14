# SQL 子集文法（grammar.md）

本文件是 SQL 编译器支持的语言子集形式化文法，代码实现（`Parser.java`）必须与本文法一致。

## 一、文法

```
statement     -> create_stmt | insert_stmt | select_stmt | delete_stmt | update_stmt ;

create_stmt   -> CREATE TABLE identifier '(' column_def (',' column_def)* ')' ';' ;
column_def    -> identifier type ;
type          -> INT | FLOAT | BOOL
               | VARCHAR '(' int_const ')'
               | CHAR '(' int_const ')'
               | DECIMAL '(' int_const ',' int_const ')'
               | DATE | TEXT ;

insert_stmt   -> INSERT INTO identifier ( '(' id_list ')' )?
                 VALUES '(' value_list ')' ';' ;

select_stmt   -> SELECT select_list FROM relation where_opt group_opt order_opt ';' ;
select_list   -> '*' | expression (',' expression)* ;
relation      -> table_ref ( (',' table_ref) | join_clause )* ;
join_clause   -> (INNER JOIN | LEFT JOIN | JOIN) table_ref ON expression ;
table_ref     -> identifier ( AS identifier | identifier )? ;
where_opt     -> WHERE expression | ε ;
group_opt     -> GROUP BY expression (',' expression)* | ε ;
order_opt     -> ORDER BY order_item (',' order_item)* | ε ;
order_item    -> expression ( ASC | DESC )? ;

delete_stmt   -> DELETE FROM identifier where_opt ';' ;

update_stmt   -> UPDATE identifier SET assignment (',' assignment)* where_opt ';' ;
assignment    -> identifier '=' expression ;
```

> 说明：
>
> - 每个语句以 `';'` 结尾；顶层入口为 `statement`。
> - `type` 为 `INT | FLOAT | BOOL | VARCHAR(n) | CHAR(n) | DECIMAL(p,s) | DATE | TEXT`；
>   `VARCHAR(n)`/`CHAR(n)` 必须带括号长度参数，`DECIMAL(p,s)` 带精度与标度两个参数，`DATE`/`TEXT` 无参数。
> - `relation` 中的 `','` 是交叉连接（等价于 `INNER JOIN` 且无 `ON`）；`JOIN` 简写等价于 `INNER JOIN`。
> - `table_ref` 的别名可写 `AS alias` 或省略 `AS`（紧跟的标识符即别名）；列引用支持 `alias.col` / `table.col`。

## 二、实现层补充

```
expression     := comparison
comparison     := additive ( ('='|'!='|'<>'|'<'|'<='|'>'|'>=') additive )?
                | additive IS [NOT] NULL
additive       := multiplicative ( ('+'|'-') multiplicative )*
multiplicative := primary ( ('*'|'/') primary )*
primary        := identifier ('.' identifier)? | CONST | '(' expression ')' | aggregate_call
                | NULL | DATE STRING_CONST | DECIMAL STRING_CONST
aggregate_call := (COUNT | SUM | AVG | MIN | MAX) '(' ( '*' | expression ) ')'
id_list        := identifier (',' identifier)*
value_list     := expression (',' expression)*
CONST          := INT_CONST | FLOAT_CONST | STRING_CONST | BOOL_CONST | NULL_CONST
```

> 注意：
>
> - `expression` 中比较运算符集合（含 `!=`、`<>`、`==`）以 `Lexer.java` 实际支持为准。
> - `IS [NOT] NULL` 是后置一元谓词，恒返回确定 `BOOL`；普通比较 `x = NULL` 结果恒为 UNKNOWN（三值逻辑）。
> - `NULL` 是词法层 NULL_CONST 常量（非关键字），对应内部 `null`；`DATE '...'` / `DECIMAL '...'` 是类型化字面量，
>   分别按 ISO 日期（`yyyy-MM-dd`）与定点小数解析，非法格式抛语法错误。
> - 聚合函数名（COUNT/SUM/AVG/MIN/MAX）**不是**保留关键字，仅当标识符后紧跟 `(` 时才按聚合调用解析；`COUNT(*)` 表示计数整行（参数为 `*`），其余聚合需数值/可比较参数。
> - 逻辑/算术优先级由低到高：`OR < AND < NOT < 比较 < 加减 < 乘除 < 原子`。

## 三、Token 约定

| 种别码     | 说明                                          | 示例                                                                 |
| ---------- | --------------------------------------------- | -------------------------------------------------------------------- |
| KEYWORD    | 关键字（大小写不敏感）                        | SELECT FROM WHERE CREATE TABLE INSERT INTO VALUES DELETE UPDATE SET INT VARCHAR FLOAT BOOL DATE DECIMAL CHAR TEXT AND OR NOT ORDER BY GROUP JOIN INNER LEFT ON AS ASC DESC IS |
| IDENTIFIER | 标识符（字母/下划线开头，后跟字母数字下划线） | student、age、user_name、count（非关键字）                          |
| CONST      | 常量（INT_CONST / FLOAT_CONST / STRING_CONST / BOOL_CONST / NULL_CONST） | 20、3.14、'Alice'、true、NULL             |
| OPERATOR   | 运算符（多字符优先匹配）                      | = == != <> < <= > >= + - * /                                       |
| DELIMITER  | 分隔符                                        | ( ) , ; .                                                          |

Token 四元式输出格式：`[种别码, 词素值, 行号, 列号]`。

## 四、错误处理约定

- 词法错误：`[LexError] at (行,列): 原因`（非法字符、未闭合字符串、非法数字）
- 语法错误（三段式，可定位、可解释）：

  ```
  SyntaxError at line 3, column 19
  unexpected token: ';'
  expected: IDENTIFIER | CONST | '(' | NOT
  ```

  三段分别为：位置（line + column 定位到错误 token）、实际符号（unexpected token）、期望集合（expected tokens，给出下一步修复线索）。
- 语义错误：`[错误类型, 位置, 原因说明]`（如 `TableNotFound`、`ColumnNotFound`、`AmbiguousColumn`、`TypeError`、`SemanticError`）
