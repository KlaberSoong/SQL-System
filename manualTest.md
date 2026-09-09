# MiniDB 手动测试方案（manualTest.md）

> **目的**：在**不依赖自动化测试框架**的前提下，通过命令行交互**大致**验证系统功能是否正确实现。
> 本方案不追求穷举，只覆盖 plan.md 第八节（测试体系）与第十节（验证方式）的四类：正常、错误、边界、持久化，并单独说明优化器如何验证。
>
> **前置条件**：已执行 `build.bat` 编译通过（`out\` 下有 class）。JDK 8+。

---

## 0. 启动方式

| 方式 | 命令 | 说明 |
|------|------|------|
| 交互 REPL | `java -Dfile.encoding=UTF-8 -cp out cli.Main` | 逐行输入 SQL，`quit`/`exit` 退出 |
| 图形窗口 | 双击 `run.bat` 或 `run.vbs` | cmd 风格窗口（推荐，中文无乱码） |
| 执行 SQL 文件 | `java -cp out cli.Main 文件.sql` | 一次性执行整个文件 |

**注意事项**：

1. 数据默认落在 `./data/` 目录（含 `pg_catalog` 目录表）。做「持久化」测试前想清空，直接删除 `data/` 目录即可。
2. REPL 是**逐行执行**的：一条 SQL 要写在同一行；多行/多条语句请用「SQL 文件」模式。
3. 在 Git Bash / 旧 cmd 里中文可能显示乱码，执行 `chcp 65001` 或用图形窗口可解决。乱码只是显示问题，不影响功能。
4. **文件模式会在第一个词法/语法错误处中止**（后续语句不再执行）；错误用例建议在**交互 REPL** 里逐条输入。

---

## 1. 正常功能（四条核心语句，端到端）

在 REPL 里依次输入：

```sql
CREATE TABLE student(id INT, name VARCHAR(20), age INT);
INSERT INTO student(id,name,age) VALUES (1,'Alice',20);
INSERT INTO student(id,name,age) VALUES (2,'Bob',17);
SELECT id,name FROM student WHERE age > 18;
DELETE FROM student WHERE id = 1;
SELECT * FROM student;
```

**预期输出**（逐条）：

| 输入 | 预期关键输出 |
|------|-------------|
| CREATE | `CREATE TABLE 'student' (3 columns)` |
| INSERT（第 1 条） | `INSERT 1` |
| INSERT（第 2 条） | `INSERT 1` |
| SELECT ... WHERE age>18 | 表头 `id \| name`，一行 `1 \| 'Alice'`，`(1 row)`；**不含** Bob |
| DELETE ... id=1 | `DELETE 1` |
| SELECT * | 表头 `id \| name \| age`，仅剩 `2 \| 'Bob' \| 17`，`(1 row)` |

> 这就是 plan.md 第十节给出的端到端脚本，逐条结果与上面一致即「大致正确」。

**补充：其余类型（FLOAT / BOOL / VARCHAR(n)）**

```sql
CREATE TABLE t(f FLOAT, b BOOL, s VARCHAR(5));
INSERT INTO t VALUES (3.5, true, 'ok');
SELECT * FROM t;
```

预期：表头 `f | b | s`，一行 `3.5 | true | 'ok'`，`(1 row)`。
（说明：实际实现支持 `INT / FLOAT / BOOL / VARCHAR(n)` 四种类型，`true/false` 是 BOOL 字面量。）

**补充：投影算术（计算列）**

```sql
CREATE TABLE nums(a INT, b INT);
INSERT INTO nums VALUES (3, 4);
SELECT a * b, a + b, a / b FROM nums;
```

预期：表头 `(a * b) | (a + b) | (a / b)`，一行 `12 | 7 | 0.75`，`(1 row)`。
（说明：SELECT 列表支持 `+ - * /` 算术表达式；`INT * INT`、`INT + INT` 结果为 **INT**（`12`、`7`），`INT / INT` 结果为 **FLOAT**（`0.75`）。这与 TypeSystem 规则一致，也是验证投影求值类型正确性的关键点。）

---

## 2. 错误处理（应报对应错误，且**报错后系统不崩溃、可继续使用**）

在 REPL 里**逐条**输入下列语句，观察错误信息，并在每条之后输入一条合法语句（如 `SELECT * FROM student;`）确认系统仍在工作。

| # | 输入 | 类别 | 预期看到的关键内容 |
|---|------|------|-------------------|
| 1 | `SELECT @x FROM student;` | 词法 | `[LexError]` + 行/列 + `非法字符 '@'` |
| 2 | `'abc` | 词法 | `[LexError]` + `未闭合的字符串` |
| 3 | `SELECT 1.2.3;` | 词法 | `[LexError]` + `非法数字` |
| 4 | `SELECT id name FROM student;` | 语法 | `SyntaxError at line 1, column ...` 三段式（缺逗号） |
| 5 | `SELECT * FROM student WHERE (age > 18;` | 语法 | `SyntaxError`（括号不匹配，期望 `')'`） |
| 6 | `SELECT score FROM student;` | 语义 | `[ColumnNotFound, ...] column 'score' does not exist` |
| 7 | `SELECT * FROM nosuch;` | 语义 | `[TableNotFound, ...] table 'nosuch' does not exist` |
| 8 | `CREATE TABLE student(x INT);` | 语义 | `[DuplicateTable, ...] already exists` |
| 9 | `INSERT INTO student VALUES (1,2,3,4);` | 语义 | `[ColumnCountMismatch, ...] expects 3 values, but got 4` |
| 10 | `INSERT INTO student VALUES ('a','b','c');` | 语义 | `[TypeError, ...] cannot assign ... VARCHAR ... INT` |
| 11 | `INSERT INTO t VALUES (3.5,true,'toolong');` | 语义 | `[ValueTooLong, ...] exceeds VARCHAR(5)` |
| 12 | `SELECT * FROM student WHERE age;` | 语义 | `[TypeError, ...] WHERE condition must be BOOL` |

**三个验证点**：

1. 每条错误都带「错误类型 + 位置 + 原因」三元信息（词法/语法带真实行列号）。
2. **语义错误的位置恒为 `(0, 0)`** —— 这是已知限制（AST 节点暂未携带行列号），**不是 bug**。
3. 报错后继续输入 `SELECT * FROM student;` 仍能正常返回结果 → 证明「非法输入不崩溃」。

---

## 3. 边界情况

| # | 输入 | 预期 |
|---|------|------|
| 1 | （直接回车，空行） | 无输出、不报错、提示符继续 |
| 2 | `select * from student;` 或 `SeLeCt * FrOm student;` | 正常执行（关键字大小写不敏感） |
| 3 | `SELECT * FROM student; -- 这是注释` | 注释被忽略，正常执行 |
| 4 | `SELECT * FROM student; SELECT id FROM student;`（一行两条） | 两条结果依次输出 |
| 5 | 很长的表名/列名（如 30+ 字符标识符）建表再查询 | 正常，不崩溃 |

---

## 4. 优化器验证

优化器的效果可通过两种方式验证：

**（a）功能等价性（CLI 可直接验证）**：

```sql
SELECT name FROM student WHERE 1=1 AND age > 10 + 8;  -- 常量折叠后等价于 age > 18，返回 Alice
SELECT name FROM student WHERE 1=0 OR age > 18;        -- 布尔化简，返回 Alice
SELECT name FROM student WHERE age + 1 > 20;           -- 非常量算术运行时求值，返回 Alice
```

（Parser 已支持 `+ - * /` 算术，优先级：比较 < 加减 < 乘除；`10 + 8` 会被优化器的常量折叠规则化简为 `18`。投影（SELECT 列表）同样支持算术表达式，如 `SELECT a*b, a+b, a/b FROM nums`，见 §1 补充；纯常量投影如 `SELECT 2*3 FROM t` 也会被常量折叠为 `6`。）

**（b）优化前后结构对比（自动化测试）**：

CLI 仍**不打印**优化前后计划（`Main.executeAndFormat` 只返回执行结果）。结构对比由 `PlannerOptimizerTest` 断言（`Filter[(age > 18)]`，运行 `test.bat` 验证）。若要手动看到计划树，见「附录 A」。

---

## 5. 持久化（重启不丢数据）

1. 建表 + 插数据（复用第 1 节脚本）。
2. 输入 `quit` 退出。
3. **重新**运行 `java -cp out cli.Main`，输入 `SELECT * FROM student;`。
4. 预期：之前建的表和数据**仍在**（数据落在 `./data/`）。

---

## 6. 通过标准（勾选清单）

- [ ] §1 四条核心语句端到端结果逐条正确，FLOAT/BOOL/VARCHAR 类型正常显示
- [ ] §2 的 12 类错误均报对应「类型 + 位置 + 原因」，且报错后系统仍可继续使用
- [ ] §3 边界用例行为符合预期
- [ ] §4 布尔化简结果正确；优化前后结构对比由 `test.bat` 的 `PlannerOptimizerTest` 通过
- [ ] §5 重启后数据不丢
- [ ] 全程无崩溃、无死循环、无异常退出（退出码非 0 仅出现在主动 `quit` 之外的情况）

---

## 附录 A：手动查看优化前后计划

在项目根目录新建临时文件 `ShowPlan.java`：

```java
import sql_compiler.*;
import sql_compiler.ast.*;
import sql_compiler.plan.*;
import utils.*;
import java.util.*;

public class ShowPlan {
    public static void main(String[] args) {
        // 直接构造含算术的 AST（等价于 plan.md 的验收示例）
        Expr where = new BinaryExpr(Operator.AND,
            new Comparison(Operator.EQ, new Literal(1, ColumnType.INT), new Literal(1, ColumnType.INT)),
            new Comparison(Operator.GT, new ColumnRef(null, "age"),
                new BinaryExpr(Operator.PLUS, new Literal(10, ColumnType.INT), new Literal(8, ColumnType.INT))));
        SelectStmt stmt = new SelectStmt(
            Collections.singletonList(new ColumnRef(null, "name")), "student", where);
        PlanNode before = new Planner().plan(stmt);
        PlanNode after  = new Optimizer().optimize(before);
        System.out.println("优化前：\n" + before.toTree());
        System.out.println("优化后：\n" + after.toTree());
    }
}
```

编译运行：

```bat
javac -encoding UTF-8 -cp out -d out ShowPlan.java
java -cp out ShowPlan
```

预期「优化后」形如 `Project[[name]] -> Filter[(age > 18)] -> SeqScan[student]`，且**不再含** `1 = 1` 与 `10 + 8`（常量折叠 + 布尔化简 + 冗余消除生效）。

---

## 已知限制 / 注意事项汇总

1. **语义错误位置恒为 `(0, 0)`**：AST 节点未携带行列号，属已知限制，非 bug。
2. **CLI 不打印优化前后计划**：结构对比靠自动化测试（见 §4 / 附录 A）。
3. **文档过时**：`README.md` 的「实现状态」表仍写「骨架 + TODO」；`grammar.md` 第一节 `type -> INT | VARCHAR`（无长度、无 FLOAT/BOOL）与实际实现不符（实际支持 `INT / FLOAT / BOOL / VARCHAR(n)`）。手动测试以**实际运行结果**为准。
4. REPL 逐行执行、文件模式遇语法错误中止（见 §0）。
