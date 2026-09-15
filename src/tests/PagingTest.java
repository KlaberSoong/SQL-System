package tests;

import engine.StorageEngine;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Literal;
import storage.BufferPool;
import storage.FileManager;
import storage.Page;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Constants;
import utils.DbException;
import utils.Operator;
import utils.PageType;
import utils.Serializer;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 页式存储管理专项测试（实验验收用的预设测试程序）。
 *
 * <p>按实验要求的五项能力逐项检查，每项都给出可断言的期望值：
 * <ol>
 *   <li>页面分配：页头初值、文件增长、页号唯一</li>
 *   <li>页面释放：状态标记、内容清空、空闲页复用、幂等性、元数据页不可释放</li>
 *   <li>页面读取：越界页号、页头往返、槽行按序读回、缓冲池命中统计</li>
 *   <li>页面写入：行往返、页写满边界、超大行、脏页落盘与淘汰写回</li>
 *   <li>数据恢复：跨"重启"（重开 FileManager）后页数/行数据/空闲链完整，以及反复增删不破坏空闲链</li>
 * </ol>
 *
 * <p>运行方式：{@code java -cp out tests.PagingTest}（已并入 {@link AllTests}）。
 * 测试数据建在系统临时目录，运行结束即清理，不会污染 {@code data/}。
 *
 * <p>重开文件的检查带看门狗超时：若空闲页链退化成环，{@code loadFreeList} 会死循环，
 * 此时判为失败而不是把整个测试挂住。
 */
public class PagingTest {

    /** 看门狗超时（毫秒）：超时说明空闲页链有环，重开数据文件时死循环。 */
    private static final long WATCHDOG_TIMEOUT_MS = 5000;

    /** 单列 INT 的行类型表。 */
    private static final List<ColumnType> INT_TYPES = Arrays.asList(ColumnType.INT);

    public static int run() {
        Assert a = new Assert();
        sectionAllocate(a);
        sectionFree(a);
        sectionRead(a);
        sectionWrite(a);
        sectionRecovery(a);
        return a.summary("PagingTest 页式存储管理");
    }

    // ================= 一、页面分配 =================

    private static void sectionAllocate(Assert a) {
        // 新建页的页头初值
        Page p = new Page(7);
        a.checkEquals(7, p.getPageId(), "[分配] 新页页号");
        a.checkEquals(PageType.DATA, p.getPageType(), "[分配] 新页类型为数据页");
        a.checkEquals(Constants.HEADER_SIZE, p.getFreeSpaceOffset(), "[分配] 新页空闲偏移=页头长度");
        a.checkEquals(0, p.getSlotCount(), "[分配] 新页行数为 0");
        a.checkEquals(-1, p.getNextPageId(), "[分配] 新页无后继页");

        File dir = TestFiles.tempDir("paging-alloc");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "alloc.dat");
            a.checkEquals(0, fm.pageCount(), "[分配] 新文件页数为 0");

            // 未 init 的空文件直接分配：不能把第 0 页元数据页当数据页发出去
            FileManager blank = new FileManager(dir.getAbsolutePath(), "blank.dat");
            a.checkFalse(blank.allocatePage() == 0, "[分配] 未 init 的空文件不会发出元数据页 0");
            a.checkEquals(true, blank.readPage(0) != null, "[分配] 空文件首次分配时先建立第 0 页元数据页");

            fm.init();
            a.checkEquals(1, fm.pageCount(), "[分配] init 后只有第 0 页元数据页");
            a.check(fm.readPage(0) != null, "[分配] 第 0 页可读");

            // 连续分配：页号递增、唯一、可读回
            Set<Integer> ids = new HashSet<>();
            boolean ok = true;
            for (int i = 1; i <= 8; i++) {
                int id = fm.allocatePage();
                if (id != i || !ids.add(id) || fm.readPage(id) == null) {
                    ok = false;
                }
            }
            a.check(ok, "[分配] 连续分配 8 页：页号递增、互不相同、均可读回");
            a.checkEquals(9, fm.pageCount(), "[分配] 分配 8 页后文件共 9 页");
            a.checkFalse(ids.contains(0), "[分配] 元数据页 0 不会被分配出去");

            // 回归：注定写不进一页的行，必须在“分配页之前”就被拒绝。
            // 曾因先 allocatePage 再 writeRow 失败抛异常、已分配的页不回收，
            // 每次失败的插入都让文件长一页且无上界（实测连续 3 次失败 1->2->3->4 页）。
            File sub = TestFiles.tempDir("paging-alloc-overflow");
            try {
                StorageEngine se = new StorageEngine(sub.getAbsolutePath());
                se.createTable("t", Arrays.asList(new ColumnDef("a", ColumnType.VARCHAR, 5000)));
                int before = pageCountOf(sub, "t.dat");
                String huge = repeat('y', Page.maxRowBytes() + 1);
                boolean threw = false;
                for (int i = 0; i < 3; i++) {
                    try {
                        se.insertRow("t", Arrays.asList(huge));
                    } catch (DbException e) {
                        threw = true;
                    }
                }
                a.check(threw, "[分配] 超过一页的行插入失败并报错");
                a.checkEquals(before, pageCountOf(sub, "t.dat"),
                        "[分配] 插入失败不会让文件增长（回归：曾每次失败泄漏一页）");
                a.checkEquals(0, se.scanTable("t").size(), "[分配] 失败的插入不留下任何行");
            } finally {
                TestFiles.deleteRecursively(sub);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 二、页面释放 =================

    private static void sectionFree(Assert a) {
        File dir = TestFiles.tempDir("paging-free");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "free.dat");
            fm.init();
            int p1 = fm.allocatePage();
            int p2 = fm.allocatePage();
            int p3 = fm.allocatePage();

            // 释放前先往 p2 写一行，用于验证释放会清空内容
            Page before = fm.readPage(p2);
            before.writeRow(encodeInt(7788));
            fm.writePage(before);
            a.checkEquals(1, fm.readPage(p2).getSlotCount(), "[释放] 释放前该页有 1 行");

            fm.freePage(p2);
            a.checkEquals(true, fm.isFreePage(p2), "[释放] 释放后标记为空闲页");
            a.checkEquals(0, fm.readPage(p2).getSlotCount(), "[释放] 释放后页内行被清空");
            a.checkEquals(false, fm.isFreePage(p1), "[释放] 未释放的页不受影响");
            a.checkEquals(4, fm.pageCount(), "[释放] 释放不改变文件页数");

            // 空闲页被重新分配时优先复用
            a.checkEquals(p2, fm.allocatePage(), "[释放] 释放的页被优先复用");
            a.checkEquals(false, fm.isFreePage(p2), "[释放] 复用后不再是空闲页");

            // 多页释放按 LIFO 复用
            fm.freePage(p1);
            fm.freePage(p3);
            a.checkEquals(p3, fm.allocatePage(), "[释放] 多页释放按 LIFO 复用（后释放的先复用）");
            a.checkEquals(p1, fm.allocatePage(), "[释放] 多页释放按 LIFO 复用（先释放的后复用）");

            // 元数据页与不存在的页号不可释放
            fm.freePage(0);
            a.checkEquals(false, fm.isFreePage(0), "[释放] 元数据页 0 不可释放");
            fm.freePage(-1);
            fm.freePage(9999);
            a.checkEquals(0, freePagesOf(fm).size(), "[释放] 负页号与越界页号被拒绝，空闲页数不变");
            a.checkFalse(fm.allocatePage() == 0, "[释放] 元数据页 0 未被发放出去");

            // 重复释放幂等（回归：曾导致空闲页链自环 + 同一页被分配两次）
            fm.freePage(p2);
            fm.freePage(p2);
            a.checkEquals(1, freePagesOf(fm).size(), "[释放] 重复释放同一页只生效一次（幂等）");
            int first = fm.allocatePage();
            int second = fm.allocatePage();
            a.checkEquals(p2, first, "[释放] 重复释放后复用得回该页");
            a.checkFalse(first == second, "[释放] 重复释放后不会把同一页分配两次");
            a.check(chainIsHealthy(dir, "free.dat"), "[释放] 磁盘空闲页链无环、无重复、无越界");

            // 重开文件（回归：环会让 loadFreeList 死循环）
            FileManager reopened = reopenWithin(a, dir, "free.dat", "[释放] 重复释放后仍能正常重开文件");
            if (reopened != null) {
                a.checkEquals(fm.pageCount(), reopened.pageCount(), "[释放] 重开后页数一致");
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 三、页面读取 =================

    private static void sectionRead(Assert a) {
        File dir = TestFiles.tempDir("paging-read");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "read.dat");
            fm.init();
            int id = fm.allocatePage();

            // 越界页号
            a.checkEquals(null, fm.readPage(-1), "[读取] 负页号返回 null");
            a.checkEquals(null, fm.readPage(fm.pageCount()), "[读取] 页号等于页数返回 null");
            a.checkEquals(null, fm.readPage(9999), "[读取] 远越界页号返回 null");
            a.check(fm.readPage(0) != null, "[读取] 合法页号（含元数据页 0）可读");

            // 页头字段写盘后读回
            Page p = fm.readPage(id);
            p.setPageType(PageType.CATALOG);
            p.setNextPageId(-1);
            fm.writePage(p);
            Page head = fm.readPage(id);
            a.checkEquals(id, head.getPageId(), "[读取] 页号写盘后读回一致");
            a.checkEquals(PageType.CATALOG, head.getPageType(), "[读取] 页类型写盘后读回一致");
            a.checkEquals(Constants.HEADER_SIZE, head.getFreeSpaceOffset(), "[读取] 空闲偏移读回一致");
            a.checkEquals(-1, head.getNextPageId(), "[读取] 后继页号读回一致");

            // 槽行按序读回
            Page rows = fm.readPage(id);
            rows.clear();
            for (int i = 0; i < 5; i++) {
                rows.writeRow(encodeInt(i));
            }
            fm.writePage(rows);
            Page back = fm.readPage(id);
            a.checkEquals(5, back.getSlotCount(), "[读取] 写盘后行数读回一致");
            boolean same = true;
            for (int i = 0; i < 5; i++) {
                if (!Arrays.equals(encodeInt(i), back.readRow(i))) {
                    same = false;
                }
            }
            a.check(same, "[读取] 各槽行按序读回，内容一致");

            // 缓冲池命中统计
            BufferPool bp = new BufferPool(4, BufferPool.Strategy.LRU, fm);
            bp.getPage(id);
            bp.getPage(id);
            bp.getPage(id);
            a.checkEquals(1, bp.getMissCount(), "[读取] 缓冲池未命中次数");
            a.checkEquals(2, bp.getHitCount(), "[读取] 缓冲池命中次数");
            a.check(Math.abs(bp.hitRate() - 2.0 / 3.0) < 1e-9, "[读取] 缓冲池命中率 = 命中/(命中+未命中)");
            a.checkEquals(null, bp.getPage(9999), "[读取] 缓冲池读越界页返回 null");
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 四、页面写入 =================

    private static void sectionWrite(Assert a) {
        // 行写往返
        Page p = new Page(1);
        byte[] row = encodeInt(12345);
        a.checkEquals(true, p.writeRow(row), "[写入] 行写入成功");
        a.checkEquals(true, Arrays.equals(row, p.readRow(0)), "[写入] 行写入后读回一致");
        a.checkEquals(1, p.getSlotCount(), "[写入] 写入后行数 +1");
        a.check(p.getFreeSpaceOffset() > Constants.HEADER_SIZE, "[写入] 写入后空闲偏移前移");

        // 页写满边界：每行占 4 字节数据 + 4 字节槽目录
        Page full = new Page(2);
        int expected = (Constants.PAGE_SIZE - Constants.HEADER_SIZE) / 8;
        int written = 0;
        while (full.writeRow(row)) {
            written++;
        }
        a.checkEquals(expected, written, "[写入] 页最多容纳的行数");
        a.checkFalse(full.hasSpace(row.length), "[写入] 写满后 hasSpace 为 false");
        a.checkEquals(false, full.writeRow(row), "[写入] 写满后再写返回 false");
        a.checkEquals(expected, full.getSlotCount(), "[写入] 写满后行数不再变化");

        // 超大行
        Page big = new Page(3);
        a.checkEquals(false, big.writeRow(new byte[Constants.PAGE_SIZE]), "[写入] 超过一页的超大行写不进");
        a.checkEquals(0, big.getSlotCount(), "[写入] 超大行失败后行数不变");

        // clear 复位
        full.clear();
        a.checkEquals(0, full.getSlotCount(), "[写入] clear 后行数归零");
        a.checkEquals(Constants.HEADER_SIZE, full.getFreeSpaceOffset(), "[写入] clear 后空闲偏移复位");

        // 脏页落盘与淘汰写回
        File dir = TestFiles.tempDir("paging-write");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "write.dat");
            fm.init();
            int id1 = fm.allocatePage();
            BufferPool bp = new BufferPool(1, BufferPool.Strategy.LRU, fm);
            bp.getPage(id1).writeRow(row);
            bp.markDirty(id1);
            bp.flushAll();
            a.checkEquals(1, fm.readPage(id1).getSlotCount(), "[写入] flushAll 后脏页落盘");

            int id2 = fm.allocatePage();
            BufferPool bp2 = new BufferPool(1, BufferPool.Strategy.LRU, fm);
            bp2.getPage(id2).writeRow(row);
            bp2.markDirty(id2);
            bp2.getPage(id1);   // 容量为 1，id2 被淘汰，脏页应在淘汰时写回
            a.checkEquals(1, fm.readPage(id2).getSlotCount(), "[写入] 页面被淘汰时脏页自动写回磁盘");
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 五、数据恢复 =================

    private static void sectionRecovery(Assert a) {
        // （1）页级：跨"重启"重开文件后，页数、行数据、空闲链状态完整
        File dir = TestFiles.tempDir("paging-recover");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "rec.dat");
            fm.init();
            int p1 = fm.allocatePage();
            int p2 = fm.allocatePage();
            int p3 = fm.allocatePage();

            // 缓冲池容量小于页数，强制发生淘汰，验证淘汰过的页也能恢复
            BufferPool bp = new BufferPool(2, BufferPool.Strategy.LRU, fm);
            int[] ids = {p1, p2, p3};
            for (int i = 0; i < ids.length; i++) {
                Page pg = bp.getPage(ids[i]);
                pg.writeRow(encodeInt(1000 + i));
                bp.markDirty(ids[i]);
            }
            bp.flushAll();

            FileManager re = reopenWithin(a, dir, "rec.dat", "[恢复] 重开数据文件不会卡死");
            if (re != null) {
                a.checkEquals(4, re.pageCount(), "[恢复] 重开后页数与关闭前一致");
                boolean allThere = true;
                for (int i = 0; i < ids.length; i++) {
                    Page pg = re.readPage(ids[i]);
                    if (pg == null || pg.getSlotCount() != 1
                            || !Arrays.equals(encodeInt(1000 + i), pg.readRow(0))) {
                        allThere = false;
                    }
                }
                a.check(allThere, "[恢复] 重开后三页行数据（含被淘汰过的页）完整读回");

                // 释放状态跨重启保持
                re.freePage(p3);
                FileManager re2 = reopenWithin(a, dir, "rec.dat", "[恢复] 释放后重开仍正常");
                if (re2 != null) {
                    a.checkEquals(true, re2.isFreePage(p3), "[恢复] 释放状态跨重启保持");
                    a.checkEquals(p3, re2.allocatePage(), "[恢复] 重开后复用得回该空闲页");
                    a.checkFalse(p3 == re2.allocatePage(), "[恢复] 重开后复用不会与在用页冲突");
                }

                // 释放多页 -> 重开 -> 连续分配：重开后复用顺序必须与磁盘空闲链一致，
                // 否则会把刚分配的页重新挂回空闲链，再次重启时被重复发放、覆盖数据。
                FileManager mf = new FileManager(dir.getAbsolutePath(), "order.dat");
                mf.init();
                int q1 = mf.allocatePage();
                int q2 = mf.allocatePage();
                int q3 = mf.allocatePage();
                mf.freePage(q1);
                mf.freePage(q2);
                mf.freePage(q3);
                FileManager mo = reopenWithin(a, dir, "order.dat", "[恢复] 释放多页后重开仍正常");
                if (mo != null) {
                    final Set<Integer> reused = new HashSet<>();
                    boolean distinct = true;
                    for (int i = 0; i < 3; i++) {
                        int id = mo.allocatePage();
                        if (id != q1 && id != q2 && id != q3) {
                            distinct = false;   // 分配到了非空闲页
                        }
                        if (!reused.add(id)) {
                            distinct = false;   // 同一页被分配两次
                        }
                    }
                    a.check(distinct, "[恢复] 重开后连续分配：复用页互不相同且都来自空闲链");

                    FileManager mo2 = reopenWithin(a, dir, "order.dat", "[恢复] 分配后再次重开仍正常");
                    if (mo2 != null) {
                        boolean noneFreeAgain = true;
                        for (int id : reused) {
                            if (mo2.isFreePage(id)) {
                                noneFreeAgain = false;
                            }
                        }
                        a.check(noneFreeAgain, "[恢复] 已分配的页不会在重启后又被认作空闲（回归：曾被重复发放）");
                        a.check(chainIsHealthy(dir, "order.dat"), "[恢复] 分配后磁盘空闲链无环、无越界");
                    }
                }
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }

        // （2）表级：反复增删后空闲链不被破坏，重启后数据完整
        //     回归用例——曾因"重复释放"让空闲链成环，重启后访问该表直接死循环。
        File sub = TestFiles.tempDir("paging-recover-table");
        try {
            final List<ColumnDef> schema = Arrays.asList(new ColumnDef("a", ColumnType.VARCHAR, 200));
            final String v = repeat('x', 185);

            StorageEngine se = new StorageEngine(sub.getAbsolutePath());
            se.createTable("t", schema);
            for (int i = 0; i < 70; i++) {
                se.insertRow("t", Arrays.asList(v + i));
            }
            a.checkEquals(70, se.scanTable("t").size(), "[恢复] 插入 70 行后可全部扫出");

            se.deleteRows("t", null);
            a.checkEquals(0, se.scanTable("t").size(), "[恢复] 全表删除后扫描为空");

            for (int i = 0; i < 15; i++) {
                se.insertRow("t", Arrays.asList(v + i));
            }
            se.deleteRows("t", null);
            a.check(chainIsHealthy(sub, "t.dat"), "[恢复] 反复增删后空闲页链仍无环（回归：曾导致重启死循环）");

            // 重启：新建 StorageEngine 读同一目录。用看门狗兜底，链路成环时判失败而不是把测试挂住。
            final int[] afterRestart = {-1};
            boolean restarted = runWithin(a, "[恢复] 重启后扫描该表不会死循环", () -> {
                StorageEngine se2 = new StorageEngine(sub.getAbsolutePath());
                se2.registerSchema("t", schema);
                afterRestart[0] = se2.scanTable("t").size();
            });
            if (restarted) {
                a.checkEquals(0, afterRestart[0], "[恢复] 重启后全表删除已持久化");
            }

            final int[] afterRefill = {-1};
            boolean refilled = runWithin(a, "[恢复] 重启后重新插入并再次重启读取", () -> {
                StorageEngine se3 = new StorageEngine(sub.getAbsolutePath());
                se3.registerSchema("t", schema);
                for (int i = 0; i < 30; i++) {
                    se3.insertRow("t", Arrays.asList(v + i));
                }
                StorageEngine se4 = new StorageEngine(sub.getAbsolutePath());
                se4.registerSchema("t", schema);
                afterRefill[0] = se4.scanTable("t").size();
            });
            if (refilled) {
                a.checkEquals(30, afterRefill[0], "[恢复] 重启后重新插入的 30 行完整可读");
            }
        } finally {
            TestFiles.deleteRecursively(sub);
        }

        // （3）页分配 + 数据恢复：重启后经“空闲页复用”重建的表，行数不得丢失。
        //     回归——重建空闲页栈的顺序与磁盘链头相反时，重启后分配页会把已分配的
        //     活页重新挂回空闲链，再次重启即把活页当空闲跳过：
        //     实测 1050 行 -> 全删 -> 重启插 1000 行 -> 再重启只扫出 491 行。
        File reuse = TestFiles.tempDir("paging-recover-reuse");
        try {
            final List<ColumnDef> intSchema = Arrays.asList(new ColumnDef("a", ColumnType.INT));
            StorageEngine s1 = new StorageEngine(reuse.getAbsolutePath());
            s1.createTable("t", intSchema);
            for (int i = 0; i < 1050; i++) {
                s1.insertRow("t", Arrays.asList(i));
            }
            a.checkEquals(1050, s1.scanTable("t").size(), "[恢复] 跨多个数据页写入 1050 行可全部扫出");

            s1.deleteRows("t", null);
            a.check(chainIsHealthy(reuse, "t.dat"), "[恢复] 全表删除后空闲页链无环、无越界");

            final int[] afterReuse = {-1};
            boolean reused = runWithin(a, "[恢复] 复用空闲页后重启读取不会死循环", () -> {
                StorageEngine s2 = new StorageEngine(reuse.getAbsolutePath());
                s2.registerSchema("t", intSchema);
                for (int i = 0; i < 1000; i++) {
                    s2.insertRow("t", Arrays.asList(i));
                }
                StorageEngine s3 = new StorageEngine(reuse.getAbsolutePath());
                s3.registerSchema("t", intSchema);
                afterReuse[0] = s3.scanTable("t").size();
            });
            if (reused) {
                a.checkEquals(1000, afterReuse[0],
                        "[恢复] 重启后复用空闲页写入的 1000 行不丢失（回归：曾只扫出 491 行）");
            }
            a.check(chainIsHealthy(reuse, "t.dat"), "[恢复] 复用空闲页后链仍无环、无重复、无越界");
        } finally {
            TestFiles.deleteRecursively(reuse);
        }

        // （4）数据恢复：匹配 0 行的 DELETE 不得物理擦除任何存活页。
        //     回归——曾被空闲链错误放大成不可恢复的擦除：DELETE 报告 removed=0，
        //     却把整页 slotCount 清零落盘（被跳过的行原本物理完好、可恢复）。
        File intact = TestFiles.tempDir("paging-recover-intact");
        try {
            final List<ColumnDef> schema = Arrays.asList(new ColumnDef("a", ColumnType.VARCHAR, 200));
            final String v = repeat('x', 185);
            StorageEngine se = new StorageEngine(intact.getAbsolutePath());
            se.createTable("t", schema);
            for (int i = 0; i < 70; i++) {
                se.insertRow("t", Arrays.asList(v + i));
            }
            int pages = pageCountOf(intact, "t.dat");
            int[] slotsBefore = new int[pages];
            for (int id = 1; id < pages; id++) {
                slotsBefore[id] = slotCountOnDisk(intact, "t.dat", id);
            }

            // "xxxx…" > "zzzzzzzz" 恒为 false：匹配 0 行
            Comparison zeroMatch = new Comparison(Operator.GT,
                    new ColumnRef(null, "a"), new Literal(repeat('z', 8), ColumnType.VARCHAR));
            a.checkEquals(0, se.deleteRows("t", zeroMatch), "[恢复] 匹配 0 行的 DELETE 报告 removed=0");
            a.checkEquals(70, se.scanTable("t").size(), "[恢复] 匹配 0 行的 DELETE 后 70 行原样保留");

            StringBuilder wiped = new StringBuilder();
            for (int id = 1; id < pages; id++) {
                int now = slotCountOnDisk(intact, "t.dat", id);
                if (slotsBefore[id] > 0 && now == 0) {
                    wiped.append(" 页").append(id).append(":").append(slotsBefore[id]).append("->0");
                }
            }
            a.check(wiped.length() == 0, "[恢复] 匹配 0 行的 DELETE 未清空任何存活页" + wiped);
        } finally {
            TestFiles.deleteRecursively(intact);
        }
    }

    // ================= 工具 =================

    /** 把整数编码成一行的字节（INT 列）。 */
    private static byte[] encodeInt(int v) {
        return Serializer.encodeRow(Arrays.asList(v), INT_TYPES);
    }

    /** 列出当前被标记为空闲的页号（不含元数据页 0）。 */
    private static List<Integer> freePagesOf(FileManager fm) {
        List<Integer> list = new ArrayList<>();
        for (int id = 1; id < fm.pageCount(); id++) {
            if (fm.isFreePage(id)) {
                list.add(id);
            }
        }
        return list;
    }

    /** 数据文件当前的页数（按文件长度推算，绕过 FileManager 的内存快照）。 */
    private static int pageCountOf(File dir, String fileName) {
        return (int) (new File(dir, fileName).length() / Constants.PAGE_SIZE);
    }

    /**
     * 直接读磁盘上某页的槽数（绕过缓冲池与空闲链，用于核对真正落盘的内容）。
     * 读不到返回 -1，便于与"被清空(0)"区分开。
     */
    private static int slotCountOnDisk(File dir, String fileName, int pageId) {
        byte[] buf = new byte[Constants.PAGE_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, fileName), "r")) {
            raf.seek((long) pageId * Constants.PAGE_SIZE);
            raf.readFully(buf);
        } catch (Exception e) {
            return -1;
        }
        return new Page(pageId, buf).getSlotCount();
    }

    /**
     * 从第 0 页的链头出发遍历磁盘上的空闲页链，检查它是否健康：
     * 必须能在有限步内走到 -1，且途中不出现重复页号（环）或越界页号。
     */
    private static boolean chainIsHealthy(File dir, String fileName) {
        File f = new File(dir, fileName);
        if (!f.exists()) {
            return false;
        }
        int pageCount = (int) (f.length() / Constants.PAGE_SIZE);
        Set<Integer> visited = new HashSet<>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[Constants.PAGE_SIZE];
            raf.readFully(buf);
            int p = readInt(buf, 16);
            while (p != -1) {
                if (p <= 0 || p >= pageCount || !visited.add(p)) {
                    return false;   // 越界、重复或成环
                }
                if (visited.size() > pageCount) {
                    return false;
                }
                raf.seek((long) p * Constants.PAGE_SIZE);
                raf.readFully(buf);
                p = readInt(buf, 16);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在守护线程里重开数据文件，最多等 {@link #WATCHDOG_TIMEOUT_MS}；
     * 超时说明空闲页链有环、{@code loadFreeList} 陷入死循环，记为失败并返回 null。
     */
    private static FileManager reopenWithin(Assert a, File dir, String fileName, String name) {
        final FileManager[] box = new FileManager[1];
        boolean finished = runWithin(a, name, () -> {
            box[0] = new FileManager(dir.getAbsolutePath(), fileName);
        });
        return finished ? box[0] : null;
    }

    /**
     * 在守护线程里跑一段代码，看它能否在 {@link #WATCHDOG_TIMEOUT_MS} 内返回；
     * 用于把"空闲页链成环导致重启死循环"这种挂起型缺陷转成一条失败断言，而不是把测试卡住。
     * 代码内部抛出的异常按"已返回"处理（不让它影响超时判定）。
     */
    private static boolean runWithin(Assert a, String name, Runnable body) {
        final boolean[] done = {false};
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable ignored) {
                // 是否抛异常交给具体断言判断，这里只关心有没有卡住
            }
            done[0] = true;
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(WATCHDOG_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        a.check(done[0], name + "（" + WATCHDOG_TIMEOUT_MS + "ms 内应返回，超时=空闲链成环死循环）");
        return done[0];
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static String repeat(char c, int n) {
        char[] cs = new char[n];
        Arrays.fill(cs, c);
        return new String(cs);
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
