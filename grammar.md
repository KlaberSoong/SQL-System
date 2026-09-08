# SQL 子集文法（grammar.md）

本文件是 SQL 编译器支持的语言子集的形式化文法。**代码实现（`Parser.java`）必须与本文法严格一致。**

## 一、词法（Token）约定

| 种别码 | 说明 | 示例 |
|--------|------|------|
| KEYWORD | 关键字（大小写不敏感） | SELECT FROM WHERE CREATE TABLE INSERT INTO VALUES DELETE INT VARCHAR FLOAT BOOL AND OR NOT |
| IDENTIFIER | 标识符（字母/下划线开头，后跟字母数字下划线） | student、age、user_name |
| CONST | 常量（INT_CONST / FLOAT_CONST / STRING_CONST） | 20、3.14、'Alice' |
| OPERATOR | 运算符（多字符优先匹配） | = != < <= > >= + - * / |
| DELIMITER | 分隔符 | ( ) , ; |

Token 四元式输出格式：`[种别码, 词素值, 行号, 列号]`。

## 二、文法（EBNF）

```
program     := statement (';' statement)* ';'?
statement   := create_stmt | insert_stmt | select_stmt | delete_stmt

create_stmt := CREATE TABLE identifier '(' column_def (',' column_def)* ')'
column_def  := identifier data_type
data_type   := INT | VARCHAR '(' INT_CONST ')' | FLOAT | BOOL

insert_stmt := INSERT INTO identifier ('(' column_list ')')? VALUES '(' value_list ')'
column_list := identifier (',' identifier)*
value_list  := expr (',' expr)*

select_stmt := SELECT select_list FROM identifier where_clause?
select_list := '*' | column_ref (',' column_ref)*
column_ref  := identifier ('.' identifier)?
where_clause:= WHERE or_expr

delete_stmt := DELETE FROM identifier where_clause?

/* 表达式优先级（低 -> 高） */
or_expr     := and_expr (OR and_expr)*
and_expr    := not_expr (AND not_expr)*
not_expr    := NOT not_expr | comparison
comparison  := add_expr (('='|'!='|'<>'|'>'|'>='|'<'|'<=') add_expr)?
add_expr    := mul_expr (('+'|'-') mul_expr)*
mul_expr    := primary (('*'|'/') primary)*
primary     := column_ref | value | '(' or_expr ')'
value       := INT_CONST | FLOAT_CONST | STRING_CONST
```

## 三、优先级与结合性

优先级从低到高：**OR < AND < NOT < 比较 < 加减 < 乘除 < 主表达式**。

- `a = 1 OR b = 2 AND c = 3` → 根节点是 OR，其右子树是 AND（AND 优先于 OR）。
- `age > 10 + 8` → 先算 `10 + 8`，再比较；树中加法节点在比较节点下方。

## 四、左递归

表达式文法必须先消除**直接左递归**才能用递归下降 / LL(1) 实现。上文中的 `x := y (...)*` 形式即已消除左递归的写法；`Parser.java` 中 `parseOr/parseAnd/.../parsePrimary` 与之一一对应。

## 五、错误处理约定

- 词法错误：`[LexError] at (行,列): 原因`（非法字符、未闭合字符串、非法数字）
- 语法错误：`[SyntaxError] at (行,列): unexpected '实际符号', expected [期望符号集]`
- 语义错误：`[错误类型, 位置, 原因说明]`
