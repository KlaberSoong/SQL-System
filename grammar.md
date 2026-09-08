# SQL 子集文法（grammar.md）

本文件是 SQL 编译器支持的语言子集形式化文法，代码实现（`Parser.java`）必须与本文法一致。

## 一、文法

```
statement     -> create_stmt | insert_stmt | select_stmt | delete_stmt ;

select_stmt   -> SELECT select_list FROM IDENTIFIER where_opt ';' ;
select_list   -> '*' | IDENTIFIER (',' IDENTIFIER) ;
where_opt     -> WHERE expression | ε ;

delete_stmt   -> DELETE FROM IDENTIFIER where_opt ';' ;

create_stmt   -> CREATE TABLE IDENTIFIER '(' column_def
                 (',' column_def)* ')' ';' ;
column_def    -> IDENTIFIER type ;
type          -> INT | VARCHAR ;

insert_stmt   -> INSERT INTO IDENTIFIER '(' id_list ')'
                 VALUES '(' value_list ')' ';' ;
```

> 说明：
>
> - 文法中引用的 `expression`、`id_list`、`value_list` 幻灯片未展开，其具体定义见下文「实现层补充」（仅为实现连贯性，不改变上述文法表达）。
> - `type` 仅 `INT | VARCHAR`；`VARCHAR` **不带**长度参数。因此 `VARCHAR(20)`、`FLOAT`、`BOOL` 均**不属于**本子集。
> - 每个语句以 `';'` 结尾；顶层入口为 `statement`。

## 二、实现层补充

```
expression    := comparison
comparison    := primary  (('='|'!='|'<'|'<='|'>'|'>=') primary)?
primary       := IDENTIFIER | CONST | '(' expression ')'
id_list       := IDENTIFIER (',' IDENTIFIER)*
value_list    := CONST (',' CONST)*
CONST         := INT_CONST | FLOAT_CONST | STRING_CONST | BOOL_CONST
```

> 注意：`expression` 中的比较运算符集合（含 `!=`、`<>` 等）由 `Lexer.java` 实际支持为准。

## 三、Token 约定

| 种别码     | 说明                                          | 示例                                                                 |
| ---------- | --------------------------------------------- | -------------------------------------------------------------------- |
| KEYWORD    | 关键字（大小写不敏感）                        | SELECT FROM WHERE CREATE TABLE INSERT INTO VALUES DELETE INT VARCHAR |
| IDENTIFIER | 标识符（字母/下划线开头，后跟字母数字下划线） | student、age、user_name                                              |
| CONST      | 常量（INT_CONST / FLOAT_CONST / STRING_CONST / BOOL_CONST） | 20、3.14、'Alice'、true                                             |
| OPERATOR   | 运算符（多字符优先匹配）                      | = != < <= > >= + - * /                                               |
| DELIMITER  | 分隔符                                        | ( ) , ;                                                              |

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
- 语义错误：`[错误类型, 位置, 原因说明]`
