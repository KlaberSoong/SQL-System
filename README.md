# MiniDB —— 简化数据库系统（Java 版）

《大型平台软件设计实习》课程项目：分三阶段构建一个简化数据库系统，贯通编译原理、操作系统与数据库三门课。

- **SQL 编译器**：词法分析 → 语法分析 → 语义分析 → 执行计划生成（+ 规则式优化）
- **页式存储系统**：页分配/释放/读写 + LRU/FIFO 缓存
- **数据库引擎**：执行引擎 + 存储引擎 + 系统目录（持久化）

> 三个阶段均已实现：`javac` 一次编译通过，`java -cp out tests.AllTests` → **2860 条断言全部通过**（逐模块计数见第五节）。
> 本文除设计取舍外，还附**各阶段验收用的预设测试程序**，以及开发中实际发生过的缺陷、抓它的回归断言和变异反查结果。

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
│   └── tests/            # AllTests + 各阶段专项测试（PagingTest / CacheTest / InterfaceTest 验收程序）
├── grammar.md            # SQL 子集文法
└── README.md
```

## 二、编译与运行（需 JDK 8+）

**Windows（双击或命令行）**

```bat
build.bat      :: 编译全部源码到 out\
test.bat       :: 运行测试全集 AllTests
run.vbs        :: 双击启动 cmd 风格图形窗口（无黑色控制台）
```

**命令行（跨平台）**

```bash
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -cp out tests.AllTests    # 测试全集
java -cp out cli.Main          # REPL
```

**页式存储管理预设测试程序**

实验验收用的专项测试是 [`src/tests/PagingTest.java`](src/tests/PagingTest.java)，按实验要求的五项能力逐项检查页面**分配、释放、读取、写入、数据恢复**是否正确：

```bash
java -cp out tests.PagingTest   # 或 test.bat（已并入 AllTests）
```

测试数据建在系统临时目录、结束即清理，不会污染 `data/`。重开文件的检查带 5 秒看门狗：
若空闲页链退化成环（历史上曾因重复释放出现），`FileManager.loadFreeList` 会死循环，
此时判为**失败**而不是把测试挂住。

除常规用例外，测试还固化了这几条回归断言（都曾在开发中真实发生过）：

- 未 init 的空文件不会把第 0 页元数据页当数据页发出去
- 重复释放同一页不会让该页被分配两次，空闲链不会出现自环
- 释放状态跨重启保持，且重开后**复用顺序与磁盘空闲链一致**——否则会把已分配的
  活页重新挂回空闲链，再次重启时按空闲跳过（实测 1050 行 → 全删 → 重启插 1000 行 → 再重启只扫出 491 行）
- 注定写不进一页的行在**分配页之前**就被拒绝，失败的插入不会让文件无上界地增长
- 匹配 0 行的 `DELETE` 不会物理擦除任何存活页

**缓存机制预设测试程序**

对应"使用固定页面访问序列检查缓存命中、淘汰、替换和回写过程是否符合 LRU 等指定策略"这一项，
专项测试是 [`src/tests/CacheTest.java`](src/tests/CacheTest.java)（2259 条断言）：

```bash
java -cp out tests.CacheTest   # 或 test.bat（已并入 AllTests）
```

做法是**固定访问序列 + 参考模型对拍**：6 条写死的页访问序列（循环、热页、重复、逆序、伪随机、顺序扫描）
在 1/2/3/4 四种容量下重放，每一步的命中与否、淘汰页号、淘汰总数都与一个约 15 行的教科书级
`RefCache`（LRU/FIFO 两种）逐条比对。

**AUTO 不跟模型对拍**，因为它会在序列中途换策略，单一模型无法刻画；而且"模型和实现抄同一套状态机"
是自证循环，抄错了会一起错。AUTO 一律用**26 条手工推演的固定序列 + 写死的期望值**断言
（最终策略、切换轨迹、淘汰页序列、命中/未命中数），每条都能手工复算——这也正是检查条目
"用固定页面访问序列检查"的原意。这类期望值的风险是"把实现行为当成规范固化下来"，
所以每条都逐帧推演核对过（推演过程写在测试的注释里），并另外用变异测试反查（见下）。

AUTO 的实际语义：初始按 LRU；**连续 4 步同向**（升序或降序都算，重复访问同一页是中性事件、
不计步也不清零）切到 FIFO；此后必须**连续 3 步换向**才退回 LRU。两个计数器互斥，所以不会出现
同一步既进又出的抖动——这是结构上的保证，不靠调参。

**两个切换方向都必须重排链表**，只做一个方向等于没切：进 FIFO 要按插入序重排（`rebuildByInsertOrder`），
退 LRU 要按最近使用序重排（`rebuildByRecency`）。不重排的话，"FIFO 阶段"的实际淘汰次序是上一阶段的
LRU 序、而"LRU 阶段"的实际淘汰次序是上一阶段的插入序，切换在这一项上就是空操作。退 LRU 少这一步时，
只有被真实访问到的节点靠"命中移到队尾"逐个纠正，收敛不了其余的——随机负载实测 1.5% 的序列淘汰出
不同的页。所以节点上同时记 `insertSeq` 与 `lastUseSeq`（每次真实访问刷新，全局单调递增，**永不重排**，
重排会破坏 LRU 语义）。

覆盖的性质：命中/未命中计数与命中率、LRU 命中后重排、FIFO 命中不重排、两者在热页序列上淘汰出
不同页（LRU 淘汰 `2`、FIFO 淘汰 `1`）、刚插入的页不可能是本次淘汰对象、脏页淘汰触发回写且落到
原偏移、干净页淘汰不产生写、`flushAll`/`flushPage` 的写入范围、多脏页批量回写完整性、
容量 0/1、越界页号、第 0 页可缓存等边界；以及"绕过缓存的页改写"这条通路——
`FileManager` 释放页 / 复用空闲页 / 改写空闲链头时会通知缓冲池作废副本（只丢弃、不回写，
磁盘才是权威），脏副本被作废时记警告。

淘汰与策略切换有正式观测接口（`getEvictions()` / `getSwitchHistory()` / `getEvictionCount()` /
`getResidentPages()` / `isResident()` / `getDirtyCount()`），断言以接口为准；日志文本
（`淘汰页 N`、`切换策略 -> X`）另外单独校验一次，只为守住 GUI 的展示契约。

用这套接口时要注意一个不对称：`getEvictions()` / `getSwitchHistory()` 是**有界日志**
（只保留最近 64 条，给 GUI 用的），`getEvictionCount()` 是**精确计数**。所以想拿淘汰序列跟
期望值逐条比对，序列必须短于 64；要比长序列就用精确计数或自己数日志文本。测试里两者都用了，
长序列断言一律走精确计数。

除常规用例外，还固化了这几条回归断言（都曾在开发中真实发生过）：

- `getPage(越界页号)` 返回 `null`、**不计入未命中**、且**不参与顺序扫描判定**——否则一次"什么都没
  发生"的空访问会累加同向计数、把策略从 LRU 切到 FIFO，并用无效页号污染 `lastPageId`。
  判别序列是 `1,2,3,4,(空)`（空访问若被算作第 4 步同向就会误切）与 `1,2,3,(空),5,6,7`（空访问若
  污染 `lastPageId`，真实的升序就被误判成换向、反而退不出 LRU）；两条方向相反，各自钉住一种变异
- 对**不在缓存中**的页 `markDirty` 会被拒绝并记警告——旧实现照单全收，而 `flushAll` 只写驻留的
  脏页，于是这次改动无声无息地消失
- 带着既有空闲页再 `DELETE` 一次，空闲链不会被截断——否则缓冲池里被释放页的陈旧镜像
  （`nextPageId = -1`）会被 `flushAll` 写回磁盘，把空闲链从该页起整段截断（实测 13 个空闲页掉到 2 个）
- 引擎内的 AUTO 池对外报告的是**解析后的真实策略**（`StorageEngine.activeStrategies()` 只给 LRU/FIFO，
  不会把 AUTO 原样漏出来）。别把这条读成"扫描触发了切换"：实测池在**插入阶段**（第 ~100 行、
  页号升序增长到填满池）就已经切到 FIFO，插入前 LRU、插入后 FIFO，全表扫描前后完全一样。
  它也因此**不能**当作"引擎逐页经过缓冲池"的判据——那条判据在 InterfaceTest 第四节，用统计增量写
- 表重写路径在数据页数**超过缓冲池容量 16** 时仍然正确（页数不够时清页全走缓存命中，淘汰通路漏测）

实现与测试都用**变异测试**反查过：把每个已修缺陷逐个"变回去"（删掉两个方向的切换重排、删掉重排
的空表守卫、删掉重排时的 `tail.next = null`、命中时不刷新最近使用序号、去掉滞回、把降序算成非
顺序、越界计入未命中、空访问喂进状态机、淘汰后按页号回查脏标记、作废时改为回写、摘掉各处的页
变更通知 …），**24 个变异里 23 个被测试抓住**（断言失败、异常，或链表成环导致的挂起——驱动脚本
带 180s 超时把"挂起"单独归一类，不然它看起来像脚本卡住）。唯一存活的是 `allocatePage` 里的那条
通知——它确实与 `freePage` 的通知冗余（复用不改写被复用页的字节，且释放时已经通知过），保留是为了
让"凡绕过缓存写过的页都发通知"成为无条件不变式，测试里有一条断言钉住这个前提。

退出侧重排的可观测性另外用一个**带对照组的探针**量过（`ExitDev`：把 AUTO 状态机逐字照抄成模型，
唯一开关是"退出时是否重排"）：开关打开时该模型在 40000 条随机序列上与实现**零差异**（证明实现现在
严格按最近使用序退出）；关掉时 594 条（1.5%）淘汰序列不同。对照组存在的意义是防止探针本身写错——
修复前这两组的数值正好相反（0 / 594），所以它不是"改完才凑出来的数"。

**接口与集成预设测试程序**

对应"使用统一接口测试 `get_page()`、`write_page()` 等基本功能，并检查其与上层数据库模块的衔接情况"这一项，
专项测试是 [`src/tests/InterfaceTest.java`](src/tests/InterfaceTest.java)（134 条断言）：

```bash
java -cp out tests.InterfaceTest   # 或 test.bat（已并入 AllTests）
```

分两部分。第一～三节只用**公开接口**调用页级 I/O 与缓存
（`FileManager.readPage/writePage/allocatePage/freePage`、`BufferPool.getPage/markDirty/flushPage/flushAll/invalidate`），
逐条钉住它们的契约；第四～六节检查**层与层之间的接缝**（`StorageEngine` ↔ 文件与缓存、
`CatalogManager`/`Catalog`、`Executor`/`cli.Main` 全链路）。

贯穿全篇的一条原则是**不用被测的接口自证**：凡是"到底写进磁盘没有"这类问题，一律另开
`RandomAccessFile` 直接读原始字节、手工按大端解析页头与槽目录（`readRaw` / `slotCountOnDisk` /
`pageIdOnDisk`），而不是读回来看看对不对。否则接口和它的验证是同一段代码，抄错了会一起错。
"这件事有没有发生"则尽量用**统计增量**观测（命中/未命中数、脏页数、淘汰数），因为很多情况下
两条路径的**内容完全相同**（例如复用空闲页写出的就是一张全新空白页），只有计数能分辨。

第一～三节覆盖：`writePage` 的落点就是 `pageId * PAGE_SIZE`、写下的字节与 `getRawData()` 逐字节一致、
手工解析槽目录能把行原样取回、改写中间页不影响左右邻页、越界/负页号读返回 `null` 且不改页数；
**命中返回的是池里那个活对象**（所以不打脏标记的改动在池里看得见、在磁盘上看不见）、
`markDirty` + `flushAll` 才落盘、`flushPage` 只写点名的那一页、对非驻留页 `markDirty` 被拒绝**且留警告**、
越界页号**既不算命中也不算未命中**；分配路径**不留空洞页**（每页页头的页号 = 它在文件中的页号）、
同一个 `FileManager` 上两个池都会被通知作废、复用空闲页拿到的是清空后的新页而不是陈旧镜像、
作废只丢弃不回写、复用空闲页也发通知（内容一样，只能靠命中/未命中增量观测）。

第四～六节里最关键的是**"引擎真的经过缓冲池"这条判据的写法**。判据是**小表**那两条：
页数 ≤ 缓冲池容量 16 的表，首次全表扫描的未命中数 == 数据页数、第二次的**命中数** == 数据页数
（整表常驻）——引擎若绕过缓冲池直接 `FileManager.readPage`，池的计数会一直停在 0，两条都不成立。
而 > 16 页的大表第二次扫描仍然每页都未命中、0 次命中（抖动），**这一组不能单独当判据**：
"每次都未命中、命中 0"是抖动与"绕过缓冲池直接读盘"共有的读数，光看它无法判断池在不在路径上。
所以大表那组钉的是另一半——容量上限是真的、没被悄悄放宽，装不下整表时第二次扫描**不许**命中，
即 GUI 上那个"命中 0 / 命中率 0.0%"是真实读数而不是统计漏记。

另有：每张表一个池，所以 b 表的抖动不会淘汰 a 表的页；
`DELETE` 全表后文件长度不变、再插入复用第 1 页；`pg_catalog` 一列一行、字段序为
表名/列名/类型序号/VARCHAR 长度/列位置，重启后表结构能重建；`Executor` 的结果与直接查
`StorageEngine` 互相印证；失败的 `INSERT` 不留行、不涨文件，之后引擎仍可用。

> 之所以能把上面这些写死成断言，是因为它们都是用**共享 `Assert`** 断言的。
> 用它时要注意一个坑：`checkEquals(Object,Object)` 走 `.equals`，所以 `Integer` 与 `Long` 永远不等
> （失败信息长成 `expected <16384> but got <16384>` 这种自相矛盾的样子）；`ColumnDef` 没有实现
> `equals`，直接比较两个 `List<ColumnDef>` 会退化成引用比较。测试里分别用 `(long)` 转换和
> `sameColumns` 逐字段比较绕开，两处都写了注释。

除常规用例外，还固化了这两条回归断言（都是这一轮接口/集成测试发现并修掉的；前一条在正常操作中
就可触发——两次写入之间崩溃即可复现，后一条需要某页的页头先被改坏）：

- **失败的 `CREATE TABLE` 不得污染编译期符号表**（`cli.Main` + `Catalog` 的修复）。
  `SemanticAnalyzer` 在**分析阶段**就把表注册进 `Catalog`（符号表的教科书做法），而真正建表到执行阶段
  才可能失败（例如 `.dat` 文件已存在但目录里没有这张表——两次写入之间崩溃、或从别处拷来一个数据文件
  都会造成这个状态）。失败后不回滚的话，符号表里就留下一个"编译期认为存在、引擎里并不存在"的表，
  于是同一会话内重建它会**误报 DuplicateTable**，而 `INSERT`/`SELECT` 又从引擎层报
  `table does not exist`——两个错误互相矛盾，且这张表**整个会话内再也建不出来**。
  修复是给 `Catalog` 加 `snapshot()`/`restore()`，`cli.Main.executeAndFormat` 在每条语句执行前取快照、
  失败时回滚（见 `Main.java` 的"语句级原子性"）。测试里同时钉住了反面：**表名→文件名的映射
  与符号表无关**，绕过 `CatalogManager.registerTable` 建出来的表重启后就是查不到的孤儿表
  （引擎按目录判断，不按文件判断），这条断言解释了上一条为什么必须回滚而不是"让引擎去认文件"
- **页号只有一个权威**（`Page` 的修复）。`getPageId()` 原本读的是**页头里的那个副本**，
  而 `writePage` 按 `getPageId()` 寻址——于是一页坏掉的页头足以让写操作落到完全不相干的偏移上，
  极端情况覆盖第 0 页元数据页、抹掉空闲链头，之后 `loadFreeList` 会顺着被污染的链把数据页截断。
  现在身份是构造时定下的 `final int pageId`（`getPageId()` 返回它），页头偏移 0 处的副本退回
  它本来的职责——磁盘格式的自我描述、落盘后自查，不参与寻址。测试里手工把某一页的页头页号篡改成
  别的值，验证寻址仍跟随身份、写回落在自己的偏移上、文件不被凭空拉长，**且那个坏掉的页头副本
  被原样保留**（不悄悄改好它：磁盘损坏不该被掩盖）

实现同样用**变异测试**反查过（把每个已修缺陷逐个"变回去"）：失败的语句不回滚符号表、
`restore` 只合并不清空、`scanTable` 绕过缓冲池直接读盘、插入不标脏、建表不登记目录、建表不查重名、
`freePage` 不发通知、`invalidate` 改成回写、命中返回副本、`markDirty` 静默接受非驻留页、
越界计入未命中、`setPageId` 变空操作、`allocatePage` 复用后不发通知、`getPageId` 改回读页头，
**14 个变异里 14 个被测试抓住**（13 个断言失败、1 个异常）。

这套矩阵也顺手暴露了一件事：**有两个变异 InterfaceTest 一开始抓不到**（`markDirty` 静默接受非驻留页、
`allocatePage` 复用后不发通知），因为它们只改变"有没有留下痕迹"，不改变最终内容。补上的办法正是
上面说的两条观测面——前者补一条**事件出口**断言（`setEventSink` 收到的消息里必须有那条警告），
后者补一条**命中/未命中增量**断言（复用后第一次取该页必须是未命中）。这也是 GUI 和测试
共用同一个观测面的价值：能被测的，才谈得上能被显示。

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

`java -cp out tests.AllTests` 当前 **2860 条断言全部通过（exit 0）**，逐模块计数：

| 类别 | 状态 | 断言数 |
|------|------|-------|
| `utils/*`（枚举/常量/序列化/异常） | ✅ 完整 | — |
| `ast/*`、`plan/*`、`Token`、`Catalog` | ✅ 完整 | — |
| `Lexer` | ✅ 完整 | 65 |
| `Parser` | ✅ 完整 | 89 |
| `SemanticAnalyzer` | ✅ 完整 | 48 |
| `Planner` / `Optimizer` | ✅ 完整 | 35 |
| `StorageEngine` / `Executor` | ✅ 完整 | 23 |
| `Page`（页头 + 行/槽读写） | ✅ 完整 | 89 |
| `BufferPool` / `FileManager` | ✅ 完整 | 2259 |
| 接口与集成（统一接口 ↔ 上层模块） | ✅ 完整 | 134 |
| 高级 SQL（UPDATE / ORDER BY / GROUP BY / JOIN） | ✅ 完整 | 36 |
| NULL 与新增类型（DATE / DECIMAL / CHAR / TEXT） | ✅ 完整 | 43 |
| 引擎 / 端到端 / 模糊 | ✅ 完整 | 20 / 15 / 4 |
| `cli/Main`（REPL + GUI） | ✅ 完整 | — |
