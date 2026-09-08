# MiniDB —— 简化数据库系统（Java 版）

《大型平台软件设计实习》课程项目：分三阶段构建一个简化数据库系统，贯通编译原理、操作系统与数据库三门课。

- **SQL 编译器**：词法分析 → 语法分析 → 语义分析 → 执行计划生成（+ 规则式优化）
- **页式存储系统**：页分配/释放/读写 + LRU/FIFO 缓存
- **数据库引擎**：执行引擎 + 存储引擎 + 系统目录（持久化）

> 本仓库当前为**代码框架**：接口契约（枚举、Token、AST、Plan、Page、序列化、异常、Catalog）已完整实现，算法类已留好方法签名与 TODO，可一次 `javac` 编译通过，作为三人并行开发的起点。

## 一、目录结构

```
database_system/
├── src/
│   ├── utils/            # 枚举 / 常量 / 序列化 / 异常
│   ├── sql_compiler/     # SQL 编译器（Token/Lexer/Parser/Semantic/Planner/Optimizer/Catalog）
│   │   ├── ast/          # AST 节点
│   │   └── plan/         # 逻辑计划节点
│   ├── storage/          # Page / BufferPool / FileManager
│   ├── engine/           # Executor / StorageEngine / CatalogManager
│   ├── cli/              # Main（REPL 入口）
│   └── tests/            # SmokeTest（接口契约冒烟测试）
├── grammar.md            # SQL 子集文法
└── README.md
```

## 二、编译与运行（需 JDK 8+）

**Windows（双击或命令行）**

```bat
build.bat      :: 编译全部源码到 out\
test.bat       :: 运行接口契约冒烟测试 SmokeTest
run.bat        :: 启动命令行 REPL（当前为框架占位）
```

**命令行（跨平台）**

```bash
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -cp out tests.SmokeTest   # 冒烟测试
java -cp out cli.Main          # REPL
```

## 三、三人分工（并行开发）

| 成员 | 负责 | 关键文件 |
|------|------|----------|
| 人 A | 编译器前端（词法 + 语法） | `Lexer.java`、`Parser.java`、`ast/*`、`Token.java` |
| 人 B | 存储系统 | `storage/Page.java`、`BufferPool.java`、`FileManager.java` |
| 人 C | 编译器后端（语义 + 计划 + 优化） | `SemanticAnalyzer.java`、`Planner.java`、`Optimizer.java`、`Catalog.java`、`plan/*` |
| 合流 | 引擎 + CLI + 测试 | `engine/*`、`cli/Main.java`、`tests/*` |

**同步方式**：接口契约（`utils/*`、`ast/*`、`plan/*`、`Serializer`、`Page` 页头布局）已在本仓库定死；各自开发时只改自己模块的方法体，接口变更需三方确认。

## 四、接口契约要点

- **Token**：`{TokenType type, String lexeme, int line, int col, ConstSubtype constSubtype, Object constValue}`；`toString()` 输出四元式。
- **AST 节点**：见 `src/sql_compiler/ast/`（字段即契约）。
- **Plan 节点**：见 `src/sql_compiler/plan/`，`PlanNode.toTree()` 输出树形结构。
- **Serializer**：INT 4B、FLOAT 4B、BOOL 1B、VARCHAR `[4B长度][UTF-8]`，`encodeRow/decodeRow` 往返一致。
- **Page**：4KB，页头 24B（pageId/pageType/freeSpaceOffset/slotCount/nextPageId/保留），大端存储。
- **异常**：`LexError` / `SyntaxError` / `SemanticError` 均继承 `DbException`，按各自格式拼消息。

## 五、实现状态

| 类别 | 状态 |
|------|------|
| `utils/*`（枚举/常量/序列化/异常） | ✅ 完整 |
| `ast/*`、`plan/*`、`Token`、`Catalog` | ✅ 完整 |
| `Lexer` / `Parser` / `SemanticAnalyzer` / `Planner` / `Optimizer` | ⬜ 骨架 + TODO |
| `Page`（页头读写） | ✅ 完整；行/槽读写 TODO |
| `BufferPool` / `FileManager` / `engine/*` / `cli/Main` | ⬜ 骨架 + TODO |
