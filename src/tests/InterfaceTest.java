package tests;

import cli.Main;
import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;
import storage.BufferPool;
import storage.FileManager;
import storage.Page;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Constants;
import utils.DbException;
import utils.Serializer;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 接口与集成专项测试（实验验收用的预设测试程序）。
 *
 * <p>对应检查条目「使用统一接口测试 get_page()、write_page() 等基本功能，并检查其与上层数据库模块的衔接情况」，
 * 分两部分：
 * <ol>
 *   <li><b>统一接口的基本功能</b>（第一～三节）：只通过公开接口调用页级 I/O
 *       （{@code FileManager.readPage/writePage/allocatePage/freePage}）与缓存
 *       （{@code BufferPool.getPage/markDirty/flushPage/flushAll/invalidate}），
 *       逐条钉住它们的契约：越界返回什么、写盘落在哪个偏移、改动什么时候真正落盘、
 *       命中拿到的是不是同一个对象、绕过缓存的改写如何作废副本。
 *       凡是"写进去了没有"这类问题，一律用**独立手段**验证——直接开
 *       {@link RandomAccessFile} 手工解析页头与槽目录，不经过被测的接口自证。</li>
 *   <li><b>与上层模块的衔接</b>（第四～六节）：{@code StorageEngine} ↔ {@code FileManager}/{@code BufferPool}、
 *       {@code CatalogManager}/{@code Catalog}（系统目录与编译期符号表）、{@code Executor} / {@code cli.Main}
 *       （全链路）。重点是**层与层之间的接缝**：引擎是否真的经过缓冲池（而不是绕过它直接读盘）、
 *       目录与数据文件必须同时成立、语句失败后各层的状态是否还一致。</li>
 * </ol>
 *
 * <p>运行方式：{@code java -cp out tests.InterfaceTest}（已并入 {@link AllTests}）。
 * 测试数据建在系统临时目录，运行结束即清理，不会污染 {@code data/}。
 */
public class InterfaceTest {

    /** 单列 INT 的表结构。 */
    private static final List<ColumnDef> INT_SCHEMA = Arrays.asList(new ColumnDef("a", ColumnType.INT));

    /** 单列 VARCHAR(200) 的表结构（每页约 17 行，便于精确控制页数）。 */
    private static final List<ColumnDef> WIDE_SCHEMA = Arrays.asList(new ColumnDef("a", ColumnType.VARCHAR, 200));

    /** VARCHAR 列的实际值长度：加上 3 位行号后仍是 188 字节，一页约 17 行。 */
    private static final String WIDE_VALUE = repeat('x', 185);

    public static int run() {
        Assert a = new Assert();
        File root = TestFiles.tempDir("iface");
        try {
            sectionPageIo(a, root);
            sectionPoolContract(a, root);
            sectionBypass(a, root);
            sectionStorageEngine(a, root);
            sectionCatalog(a, root);
            sectionExecutor(a, root);
            sectionBoundary(a, root);
        } finally {
            TestFiles.deleteRecursively(root);
        }
        return a.summary("InterfaceTest 接口与集成");
    }

    // ================= 一、统一接口：页级读写 =================

    private static void sectionPageIo(Assert a, File root) {
        File dir = sub(root, "pageio");
        FileManager fm = new FileManager(dir.getAbsolutePath(), "t.dat");
        fm.init();
        a.checkEquals(1, fm.pageCount(), "[接口] init 之后只有第 0 页元数据页");

        int p1 = fm.allocatePage();
        int p2 = fm.allocatePage();
        int p3 = fm.allocatePage();

        // writePage 的落点：第 pageId 页在文件偏移 pageId*PAGE_SIZE 处（类注释的约定）
        Page page = fm.readPage(p2);
        page.writeRow(encodeInt(4242));
        page.setNextPageId(777);            // 用页头字段当路标，便于独立读盘时辨认
        fm.writePage(page);

        byte[] raw = readRaw(dir, "t.dat", p2);
        a.check(Arrays.equals(page.getRawData(), raw),
                "[接口] writePage 写下的字节与 Page.getRawData() 逐字节一致");
        a.checkEquals(p2, readIntAt(raw, 0), "[接口] 写盘内容里页头第 0..3 字节确实是该页页号");
        a.checkEquals(777, readIntAt(raw, 16), "[接口] 写盘内容里页头的 nextPageId 字段正确");
        a.checkEquals((long) (4 * Constants.PAGE_SIZE), fileLength(dir, "t.dat"),
                "[接口] 分配 3 页后文件长度 = 4 页（含元数据页）");

        // 完全独立地手工解析槽目录，把行取回来（不经过 Page 的任何方法）
        int slotOff = Constants.PAGE_SIZE - 4;
        int rowOff = readShortAt(raw, slotOff);
        int rowLen = readShortAt(raw, slotOff + 2);
        byte[] handRead = Arrays.copyOfRange(raw, rowOff, rowOff + rowLen);
        a.check(Arrays.equals(encodeInt(4242), handRead),
                "[接口] 手工解析页尾槽目录取回的行，与写进去的字节完全一致");
        a.checkEquals(1, readIntAt(raw, 12), "[接口] 页头行数字段为 1");
        a.checkEquals(Constants.HEADER_SIZE + rowLen, readIntAt(raw, 8),
                "[接口] 页头空闲偏移 = 页头长度 + 行长度");

        // 改写中间一页不影响左邻右舍
        Page mid = fm.readPage(p2);
        mid.clear();
        mid.writeRow(encodeInt(9999));
        fm.writePage(mid);
        a.checkEquals(0, slotCountOnDisk(dir, "t.dat", p1), "[接口] 改写第 2 页没有把第 1 页写坏");
        a.checkEquals(1, slotCountOnDisk(dir, "t.dat", p2), "[接口] 改写后的第 2 页仍是 1 行");
        a.checkEquals(0, slotCountOnDisk(dir, "t.dat", p3), "[接口] 改写第 2 页没有把第 3 页写坏");
        a.checkEquals(9999, readIntAt(readRaw(dir, "t.dat", p2), Constants.HEADER_SIZE),
                "[接口] 改写后的行内容落盘正确");

        // 越界与负页号：读返回 null，且不得改动页数
        a.checkEquals(null, fm.readPage(-1), "[接口] readPage(负页号) 返回 null");
        a.checkEquals(null, fm.readPage(fm.pageCount()), "[接口] readPage(页数) 返回 null");
        a.checkEquals(null, fm.readPage(9999), "[接口] readPage(远越界) 返回 null");
        a.checkEquals(4, fm.pageCount(), "[接口] 越界读取不改变页数");

        // 页号的两条来源：身份（构造时定下）与页头副本（磁盘格式的自我描述）。
        // 正常文件里两者一致，所以这里手工把某一页的页头副本改坏，看寻址跟谁走。
        // 历史缺陷：getPageId() 读的是页头，于是 writePage 按页头里的页号寻址——
        // 一页坏掉的页头足以让写操作落到完全不相干的偏移上（极端情况覆盖第 0 页元数据页，
        // 把空闲链头抹掉，之后 loadFreeList 会顺着被污染的链把数据页截断）。
        File dirM = sub(root, "pageid");
        FileManager fmM = new FileManager(dirM.getAbsolutePath(), "t.dat");
        fmM.init();
        int target = fmM.allocatePage();
        fmM.allocatePage();                 // 再要一页，确保 target 不是文件里最后一页
        pokeDisk(dirM, "t.dat", target, 7); // 把第 target 页的页头页号篡改成 7

        Page mismatched = fmM.readPage(target);
        a.checkEquals(target, mismatched.getPageId(),
                "[接口] 页头页号与所在位置不一致时，getPageId 仍返回该页的身份（寻址不跟页头走）");
        mismatched.writeRow(encodeInt(31337));
        fmM.writePage(mismatched);
        a.checkEquals(1, slotCountOnDisk(dirM, "t.dat", target),
                "[接口] 写回落在它自己的偏移上，不按页头里那个坏页号乱跑");
        a.checkEquals((long) (3 * Constants.PAGE_SIZE), fileLength(dirM, "t.dat"),
                "[接口] 写回没有在偏移 7 处凭空拉长文件");
        a.checkEquals(7, pageIdOnDisk(dirM, "t.dat", target),
                "[接口] 坏掉的页头副本被原样保留（不悄悄改好它，磁盘损坏不该被掩盖）");
    }

    /** 篡改磁盘上某页页头第 0..3 字节（页号的自我描述副本）。 */
    private static void pokeDisk(File dir, String fileName, int pageId, int value) {
        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, fileName), "rw")) {
            raf.seek((long) pageId * Constants.PAGE_SIZE);
            raf.writeInt(value);
        } catch (Exception e) {
            throw new DbException("poke page " + pageId + " failed: " + e.getMessage(), e);
        }
    }

    // ================= 二、统一接口：缓冲池的读写与回写契约 =================

    private static void sectionPoolContract(Assert a, File root) {
        File dir = sub(root, "pool");
        FileManager fm = new FileManager(dir.getAbsolutePath(), "t.dat");
        fm.init();
        int id = fm.allocatePage();
        int other = fm.allocatePage();
        BufferPool bp = new BufferPool(4, BufferPool.Strategy.LRU, fm);

        // 命中返回同一个对象：池交给上层的是活对象，不是每次拷贝一份
        Page first = bp.getPage(id);
        a.check(first == bp.getPage(id), "[接口] 命中时 getPage 返回同一个 Page 对象（池内是活对象）");
        a.checkFalse(fm.readPage(id) == fm.readPage(id),
                "[接口] 对照：FileManager.readPage 每次返回新对象（两层语义不同，不能混用）");

        // 契约：不 markDirty 的改动在池内可见、但不会落盘
        first.writeRow(encodeInt(111));
        a.checkEquals(1, bp.getPage(id).getSlotCount(), "[接口] 未 markDirty 的改动在池内立即可见");
        a.checkEquals(0, bp.getDirtyCount(), "[接口] 未 markDirty 时脏页数为 0");
        bp.flushAll();
        a.checkEquals(0, slotCountOnDisk(dir, "t.dat", id),
                "[接口] 未 markDirty 的改动不会落盘（flushAll 也不写它）");

        // 契约：markDirty + flushAll 才落盘
        bp.markDirty(id);
        a.checkEquals(1, bp.getDirtyCount(), "[接口] markDirty 后脏页数为 1");
        bp.flushAll();
        a.checkEquals(1, slotCountOnDisk(dir, "t.dat", id), "[接口] markDirty 后 flushAll 把该页落盘");
        a.checkEquals(0, bp.getDirtyCount(), "[接口] flushAll 之后脏页数归零");

        // flushPage 只写指定页
        bp.getPage(other).writeRow(encodeInt(222));
        bp.markDirty(other);
        bp.flushPage(id);
        a.checkEquals(0, slotCountOnDisk(dir, "t.dat", other), "[接口] flushPage(另一页) 不会顺手把本页刷下去");
        bp.flushPage(other);
        a.checkEquals(1, slotCountOnDisk(dir, "t.dat", other), "[接口] flushPage 把指定页落盘");

        // 对非驻留页 markDirty：拒绝（只警告），改动不会落盘。
        // 这条被拒绝之后**必须留下痕迹**——静默丢弃会让上层以为改动已经交出去了，
        // 所以除了统计不变之外，还要校验事件出口上确实出现了一条警告（GUI 就是靠它显示的）。
        int third = fm.allocatePage();
        List<String> events = new ArrayList<>();
        bp.setEventSink(events::add);
        bp.markDirty(third);
        a.checkFalse(bp.isResident(third), "[接口] 对非驻留页 markDirty 不会把它塞进缓存");
        a.checkEquals(0, bp.getDirtyCount(), "[接口] 对非驻留页 markDirty 被拒绝，脏页数不变");
        a.check(hasEvent(events, "警告", "markDirty"),
                "[接口] 对非驻留页 markDirty 会在事件出口记一条警告，而不是静默丢弃");

        // 淘汰与作废同样要留下可观测的事件（事件出口是 GUI 与测试共用的观测面）
        events.clear();
        BufferPool small = new BufferPool(1, BufferPool.Strategy.LRU, fm);
        small.setEventSink(events::add);
        small.getPage(id);
        small.getPage(other);       // 容量 1 -> id 被淘汰
        a.check(hasEvent(events, "淘汰页"),
                "[接口] 淘汰会在事件出口上报（淘汰页 N）");

        events.clear();
        File dirEv = sub(root, "pool-events");
        FileManager fmEv = new FileManager(dirEv.getAbsolutePath(), "t.dat");
        fmEv.init();
        int evictPage = fmEv.allocatePage();
        BufferPool bpEv = new BufferPool(4, BufferPool.Strategy.LRU, fmEv);
        bpEv.setEventSink(events::add);
        bpEv.getPage(evictPage);
        bpEv.markDirty(evictPage);
        fmEv.freePage(evictPage);   // 绕过缓存改写 -> 作废脏副本
        a.check(hasEvent(events, "警告", "脏页副本"),
                "[接口] 作废一个还没落盘的脏副本会记警告（数据可能丢，必须可观测）");

        // 越界页号：返回 null、不计未命中（不是一次"失败的缓存查找"）
        int[] before = new int[]{bp.getHitCount(), bp.getMissCount()};
        a.checkEquals(null, bp.getPage(9999), "[接口] getPage(越界) 返回 null");
        a.checkEquals(null, bp.getPage(-1), "[接口] getPage(负页号) 返回 null");
        a.checkEquals(before[0], bp.getHitCount(), "[接口] 越界访问不计命中");
        a.checkEquals(before[1], bp.getMissCount(), "[接口] 越界访问不计未命中");
    }

    // ================= 三、跨层：绕过缓存的写与作废副本 =================

    private static void sectionBypass(Assert a, File root) {
        File dir = sub(root, "bypass");
        FileManager fm = new FileManager(dir.getAbsolutePath(), "t.dat");
        fm.init();
        int id = fm.allocatePage();

        // 分配路径不留空洞页：每一页的页头页号必须等于它在文件中的页号。
        // 这是"readPage 用参数定位、writePage 用页头定位"两条路径不会分叉的前提。
        int last = id;
        for (int i = 0; i < 5; i++) {
            last = fm.allocatePage();
        }
        boolean idsMatch = true;
        for (int p = 1; p <= last; p++) {
            if (pageIdOnDisk(dir, "t.dat", p) != p) {
                idsMatch = false;
            }
        }
        a.check(idsMatch, "[作废] 分配出来的每一页，页头里的页号都等于它在文件中的页号（不留空洞页）");

        // 同一个 FileManager 上挂两个池：绕过缓存的改写必须让两个池都作废副本
        BufferPool p1 = new BufferPool(4, BufferPool.Strategy.LRU, fm);
        BufferPool p2 = new BufferPool(4, BufferPool.Strategy.FIFO, fm);
        p1.getPage(id).writeRow(encodeInt(555));
        p2.getPage(id).writeRow(encodeInt(555));
        a.check(p1.isResident(id) && p2.isResident(id), "[作废] 两个池都缓存了该页");

        fm.freePage(id);
        a.checkFalse(p1.isResident(id), "[作废] 释放页后第一个池丢弃了副本");
        a.checkFalse(p2.isResident(id), "[作废] 释放页后第二个池也丢弃了副本（监听器是列表，不是单个）");

        // 复用该页：池里绝不能还端出释放前的陈旧镜像
        //（历史缺陷：DELETE 后空闲链被池里的陈旧副本盖回去，13 个空闲页掉到 2 个）
        int reused = fm.allocatePage();
        a.checkEquals(id, reused, "[作废] 释放的页被优先复用");
        Page again = p1.getPage(reused);
        a.checkEquals(0, again.getSlotCount(), "[作废] 复用后经池读到的页是清空后的新内容，不是释放前的旧镜像");

        // 作废只丢弃、不回写：池里那份没落盘的改动随作废一起消失（磁盘为准）
        File dir2 = sub(root, "bypass2");
        FileManager fm2 = new FileManager(dir2.getAbsolutePath(), "t.dat");
        fm2.init();
        int x = fm2.allocatePage();
        BufferPool bp = new BufferPool(4, BufferPool.Strategy.LRU, fm2);
        bp.getPage(x).writeRow(encodeInt(777));
        bp.markDirty(x);
        fm2.freePage(x);            // 通知 -> invalidate
        a.checkEquals(0, slotCountOnDisk(dir2, "t.dat", x),
                "[作废] 作废脏副本时只丢弃、不回写（磁盘是权威，回写会盖掉刚写好的链指针）");

        // 复用空闲页同样要发通知：复用写出的是全新空白页，内容与池里那份空闲页**恰好一样**，
        // 所以从内容上永远看不出区别——只能从统计上观测（池里那份副本到底还在不在）。
        File dir3 = sub(root, "bypass3");
        FileManager fm3 = new FileManager(dir3.getAbsolutePath(), "t.dat");
        fm3.init();
        int q = fm3.allocatePage();
        BufferPool bp3 = new BufferPool(4, BufferPool.Strategy.LRU, fm3);
        fm3.freePage(q);                    // 释放（此刻池里还没有副本）
        bp3.getPage(q);                     // 把空闲页读进池：池里现在有这个"空闲页副本"
        int hits0 = bp3.getHitCount();
        int misses0 = bp3.getMissCount();
        int againQ = fm3.allocatePage();    // 复用该页 -> 应当通知 -> 作废副本
        a.checkEquals(q, againQ, "[作废] 复用的就是空闲链顶端那一页");
        bp3.getPage(againQ);
        a.checkEquals(0, bp3.getHitCount() - hits0,
                "[作废] 复用空闲页后，池里那份副本已作废（再取该页不是命中）");
        a.checkEquals(1, bp3.getMissCount() - misses0,
                "[作废] 复用空闲页后第一次取该页是未命中（复用路径确实发了变更通知）");
    }

    // ================= 四、衔接：StorageEngine ↔ 文件与缓存 =================

    private static void sectionStorageEngine(Assert a, File root) {
        // （1）建表：表名 <-> 文件名，第 0 页元数据页
        File dir = sub(root, "engine");
        StorageEngine se = new StorageEngine(dir.getAbsolutePath());
        a.checkFalse(se.tableExists("t"), "[衔接] 建表前表不存在");
        se.createTable("t", INT_SCHEMA);
        a.check(se.tableExists("t"), "[衔接] 建表后 tableExists 为真");
        a.check(new File(dir, "t" + Constants.DATA_FILE_SUFFIX).exists(),
                "[衔接] 建表生成 <表名>.dat（表名到文件名的映射）");
        a.checkEquals(1, pageCountOf(dir, "t.dat"), "[衔接] 建表后文件里有且只有第 0 页元数据页");
        a.checkEquals(0, se.scanTable("t").size(), "[衔接] 新表扫描为 0 行");

        // （2）插入：行真的落在数据页上（独立读盘验证，且在 markDirty 之外还经过 flushAll）
        se.insertRow("t", Arrays.asList(20240915));
        a.checkEquals(2, pageCountOf(dir, "t.dat"), "[衔接] 插入一行后文件增长到 2 页");
        byte[] raw = readRaw(dir, "t.dat", 1);
        int rowOff = readShortAt(raw, Constants.PAGE_SIZE - 4);
        int rowLen = readShortAt(raw, Constants.PAGE_SIZE - 2);
        a.check(Arrays.equals(Serializer.encodeRow(Arrays.asList(20240915),
                        Arrays.asList(ColumnType.INT)),
                Arrays.copyOfRange(raw, rowOff, rowOff + rowLen)),
                "[衔接] 插入的行按 Serializer 编码落在数据页上（独立读盘解析）");
        a.checkEquals(1, se.scanTable("t").size(), "[衔接] 扫描读回该行");

        // （3）重启：换一套 FileManager/BufferPool，直接读同一批文件
        StorageEngine reopened = reopen(dir, "t", INT_SCHEMA);
        a.checkEquals(1, reopened.scanTable("t").size(), "[衔接] 重启后数据仍在（池与页缓存都不是权威，磁盘才是）");

        // （4）引擎确实逐页经过缓冲池：用统计增量判别
        //     小表（页数 <= 池容量）：首次全表扫描全部未命中，第二次全部命中。
        //     这两条（未命中数 == 页数、命中数 == 页数）就是判据：引擎若绕过缓冲池直接
        //     FileManager.readPage，池的计数会一直停在 0，两条都不成立。
        File small = sub(root, "engine-small");
        StorageEngine s1 = new StorageEngine(small.getAbsolutePath());
        s1.createTable("t", WIDE_SCHEMA);
        for (int i = 0; i < 100; i++) {
            s1.insertRow("t", Arrays.asList(WIDE_VALUE + i));
        }
        int smallPages = pageCountOf(small, "t.dat") - 1;
        a.check(smallPages > 4 && smallPages <= Constants.DEFAULT_BUFFER_SIZE,
                "[衔接] 前提：本表数据页数在 5.." + Constants.DEFAULT_BUFFER_SIZE + " 之间（池装得下整表），实测 "
                        + smallPages + " 页");
        StorageEngine freshSmall = reopen(small, "t", WIDE_SCHEMA);
        int[] b0 = freshSmall.bufferPoolStats();
        freshSmall.scanTable("t");
        int[] b1 = freshSmall.bufferPoolStats();
        freshSmall.scanTable("t");
        int[] b2 = freshSmall.bufferPoolStats();
        a.checkEquals(smallPages, b1[1] - b0[1],
                "[衔接] 首次全表扫描的未命中数 == 数据页数（引擎逐页经过缓冲池，而不是绕过它直接读盘）");
        a.checkEquals(0, b1[0] - b0[0], "[衔接] 首次扫描没有任何命中");
        a.checkEquals(0, b2[1] - b1[1],
                "[衔接] 页数不超过池容量的表，第二次全表扫描零未命中（整表驻留）");
        a.checkEquals(smallPages, b2[0] - b1[0], "[衔接] 第二次扫描的命中数 == 数据页数");

        //     大表（页数 > 池容量）：顺序扫描抖动，第二次仍然一页都命不中。
        //     注意这一条**不能**单独当判据："每次都未命中、命中 0"是抖动和"绕过缓冲池
        //     直接读盘"共有的读数，光看它无法判断池到底在不在路径上，所以判据是小表那两条。
        //     这里要钉的是另一半——容量上限是真的、没被悄悄放宽：装不下整表时第二次扫描
        //     **不许**命中，即 GUI 上那个"命中 0 / 命中率 0.0%"是真实读数而不是统计漏记。
        File big = sub(root, "engine-big");
        StorageEngine s2 = new StorageEngine(big.getAbsolutePath());
        s2.createTable("t", WIDE_SCHEMA);
        for (int i = 0; i < 400; i++) {
            s2.insertRow("t", Arrays.asList(WIDE_VALUE + i));
        }
        int bigPages = pageCountOf(big, "t.dat") - 1;
        a.check(bigPages > Constants.DEFAULT_BUFFER_SIZE,
                "[衔接] 前提：本表数据页数超过池容量 " + Constants.DEFAULT_BUFFER_SIZE + "，实测 " + bigPages + " 页");
        StorageEngine freshBig = reopen(big, "t", WIDE_SCHEMA);
        int[] c0 = freshBig.bufferPoolStats();
        freshBig.scanTable("t");
        int[] c1 = freshBig.bufferPoolStats();
        freshBig.scanTable("t");
        int[] c2 = freshBig.bufferPoolStats();
        a.checkEquals(bigPages, c1[1] - c0[1], "[衔接] 超过容量的表首次扫描逐页未命中");
        a.checkEquals(bigPages, c2[1] - c1[1],
                "[衔接] 超过容量的表第二次扫描仍然逐页未命中（容量装不下整表，顺序扫描抖动）");
        a.checkEquals(0, c2[0] - c1[0], "[衔接] 抖动时命中数为 0");
        for (BufferPool.Strategy s : freshBig.activeStrategies()) {
            a.check(s == BufferPool.Strategy.LRU || s == BufferPool.Strategy.FIFO,
                    "[衔接] activeStrategies 只暴露解析后的 LRU/FIFO，不会把 AUTO 原样漏出来");
        }

        // （5）删除：页被回收进空闲链供复用，文件不缩短
        File del = sub(root, "engine-delete");
        StorageEngine s3 = new StorageEngine(del.getAbsolutePath());
        s3.createTable("t", WIDE_SCHEMA);
        for (int i = 0; i < 100; i++) {
            s3.insertRow("t", Arrays.asList(WIDE_VALUE + i));
        }
        int pagesBefore = pageCountOf(del, "t.dat");
        a.checkEquals(100, s3.deleteRows("t", null), "[衔接] 全表删除报告删除 100 行");
        a.checkEquals(0, s3.scanTable("t").size(), "[衔接] 全表删除后扫描为 0 行");
        a.checkEquals(pagesBefore, pageCountOf(del, "t.dat"),
                "[衔接] 删除不缩短文件（页回收进空闲链，由后续插入复用）");
        s3.insertRow("t", Arrays.asList(WIDE_VALUE + "again"));
        a.checkEquals(1, s3.scanTable("t").size(), "[衔接] 删除后重新插入可读回");
        a.checkEquals(pagesBefore, pageCountOf(del, "t.dat"), "[衔接] 重新插入复用了空闲页，文件没有继续增长");
        a.checkEquals(1, slotCountOnDisk(del, "t.dat", 1), "[衔接] 复用顺序与空闲链一致：行落在第 1 页");

        // （6）跨表隔离：每张表各有自己的文件与缓冲池，别的表的抖动挤不掉这张表的页
        File two = sub(root, "engine-two");
        StorageEngine s4 = new StorageEngine(two.getAbsolutePath());
        s4.createTable("a", INT_SCHEMA);
        s4.createTable("b", WIDE_SCHEMA);
        s4.insertRow("a", Arrays.asList(1));
        for (int i = 0; i < 400; i++) {
            s4.insertRow("b", Arrays.asList(WIDE_VALUE + i));
        }
        a.check(pageCountOf(two, "b.dat") - 1 > Constants.DEFAULT_BUFFER_SIZE,
                "[衔接] 前提：表 b 的数据页数超过池容量（页数 " + (pageCountOf(two, "b.dat") - 1) + "）");
        a.checkEquals(Arrays.asList(Arrays.asList(1)), s4.scanTable("a"), "[衔接] 表 a 只看到自己的行");

        int[] m0 = s4.bufferPoolStats();
        s4.scanTable("a");
        int[] m1 = s4.bufferPoolStats();
        a.checkEquals(1, m1[0] - m0[0], "[衔接] 扫描 a 全部命中（a 的页一直留在自己的池里）");
        a.checkEquals(0, m1[1] - m0[1],
                "[衔接] 扫描 a 零未命中：表 b 那 20 多页的抖动挤不掉表 a 的页（每张表一个池）");
    }

    // ================= 五、衔接：系统目录与编译期符号表 =================

    private static void sectionCatalog(Assert a, File root) {
        File dir = sub(root, "catalog");
        StorageEngine se = new StorageEngine(dir.getAbsolutePath());
        CatalogManager cm = new CatalogManager(se);
        Catalog catalog = cm.loadCatalog();
        a.check(se.tableExists("pg_catalog"), "[目录] 空目录启动时自动建立 pg_catalog 表");
        a.checkEquals(0, se.scanTable("pg_catalog").size(), "[目录] 新库的目录表是空的");
        a.check(catalog.getTableNames().isEmpty(), "[目录] 新库的编译期符号表也是空的");

        // 目录表本身也是一张普通表，同样走存储引擎读写：每列一行
        List<ColumnDef> student = Arrays.asList(
                new ColumnDef("id", ColumnType.INT),
                new ColumnDef("name", ColumnType.VARCHAR, 20),
                new ColumnDef("age", ColumnType.INT));
        se.createTable("student", student);
        cm.registerTable("student", student);
        a.checkEquals(3, se.scanTable("pg_catalog").size(), "[目录] 登记 3 列的表写入 3 行目录");
        List<List<Object>> rows = se.scanTable("pg_catalog");
        a.checkEquals(Arrays.asList("student", "id", ColumnType.INT.ordinal(), 0, 0), rows.get(0),
                "[目录] 目录行的字段顺序为 表名/列名/类型序号/VARCHAR长度/列位置");
        a.checkEquals(Arrays.asList("student", "name", ColumnType.VARCHAR.ordinal(), 20, 1), rows.get(1),
                "[目录] VARCHAR 列的长度也持久化在目录里");

        // 重启：表结构由目录重建（含列顺序与 VARCHAR 长度）
        StorageEngine se2 = new StorageEngine(dir.getAbsolutePath());
        CatalogManager cm2 = new CatalogManager(se2);
        Catalog catalog2 = cm2.loadCatalog();
        a.check(catalog2.containsTable("student"), "[目录] 重启后编译期符号表含 student");
        a.check(sameColumns(student, catalog2.getColumns("student")),
                "[目录] 重启后列定义（名/类型/长度/顺序）与建表时一致");
        a.check(sameColumns(student, se2.getSchema("student")), "[目录] 重启后存储引擎的表结构也重建了");
        a.checkEquals(3, se2.scanTable("pg_catalog").size(), "[目录] 重启不会重复写入目录行");

        // 衔接契约：建表必须同时登记目录，否则重启后文件还在、引擎却不认识它
        //（回归：只调 StorageEngine.createTable 而不登记目录时，重启后该表变成孤儿文件）
        File orphan = sub(root, "catalog-orphan");
        StorageEngine o1 = new StorageEngine(orphan.getAbsolutePath());
        CatalogManager ocm1 = new CatalogManager(o1);
        ocm1.loadCatalog();
        o1.createTable("ghost", INT_SCHEMA);
        o1.insertRow("ghost", Arrays.asList(1));
        a.checkEquals(1, o1.scanTable("ghost").size(), "[目录] 未登记目录的表在本次运行内可用");
        StorageEngine o2 = new StorageEngine(orphan.getAbsolutePath());
        CatalogManager ocm2 = new CatalogManager(o2);
        Catalog oc2 = ocm2.loadCatalog();
        a.check(o2.tableExists("ghost"), "[目录] 孤儿表的数据文件仍在磁盘上");
        a.checkFalse(oc2.containsTable("ghost"), "[目录] 但重启后它不在符号表里（目录是表结构的唯一持久来源）");
        final StorageEngine o2f = o2;
        a.checkThrows(DbException.class, () -> o2f.scanTable("ghost"),
                "[目录] 于是重启后访问该表报错——建表必须成对调用 createTable 与 registerTable");

        // 衔接契约：一条语句失败后，它在语义分析阶段对符号表的副作用必须被回滚。
        //（回归：失败的 CREATE TABLE 曾把表注册进符号表却不撤销，于是同一会话里
        //  重建该表被误报 DuplicateTable、INSERT/SELECT 又从引擎层报"表不存在"，
        //  两个错误互相矛盾，且这张表在整个会话内再也建不出来。）
        File clash = sub(root, "catalog-clash");
        // 造一个"文件在、目录里没有"的表：用一个临时引擎只建文件（不登记目录），再重启
        StorageEngine maker = new StorageEngine(clash.getAbsolutePath());
        maker.createTable("t", INT_SCHEMA);
        StorageEngine c2 = new StorageEngine(clash.getAbsolutePath());
        CatalogManager ccm2 = new CatalogManager(c2);
        Catalog ccat2 = ccm2.loadCatalog();
        a.check(c2.tableExists("t") && !ccat2.containsTable("t"), "[目录] 前提：t 的文件已存在但目录里没有它");

        String first = Main.executeAndFormat("CREATE TABLE t(a INT);", c2, ccm2, ccat2);
        a.checkContains(first, "already exists", "[目录] 建表被引擎拒绝（数据文件已存在）");
        a.checkFalse(ccat2.containsTable("t"),
                "[目录] 失败的 CREATE 不得把表留在编译期符号表里（回归：曾留下一个引擎里不存在的表）");
        String second = Main.executeAndFormat("CREATE TABLE t(a INT);", c2, ccm2, ccat2);
        a.checkEquals(first, second,
                "[目录] 重试得到同一条真实错误，而不是谎报 DuplicateTable");
        String ins = Main.executeAndFormat("INSERT INTO t VALUES (1);", c2, ccm2, ccat2);
        a.checkContains(ins, "TableNotFound",
                "[目录] 对这张建不出来的表做 INSERT，报错来自拥有符号表的那一层（两侧口径一致）");
        a.checkFalse(ccat2.containsTable("t"), "[目录] 失败语句之后符号表依然是干净的");
        a.checkContains(Main.executeAndFormat("CREATE TABLE ok(a INT);", c2, ccm2, ccat2), "CREATE TABLE",
                "[目录] 同一会话里建别的表不受影响");
        a.check(c2.tableExists("ok"), "[目录] 且这次真的建成了");
    }

    // ================= 六、衔接：Executor / cli.Main 全链路 =================

    private static void sectionExecutor(Assert a, File root) {
        File dir = sub(root, "exec");
        StorageEngine se = new StorageEngine(dir.getAbsolutePath());
        CatalogManager cm = new CatalogManager(se);
        Catalog catalog = cm.loadCatalog();

        a.checkContains(run(se, cm, catalog, "CREATE TABLE student(id INT, name VARCHAR(20), age INT);"),
                "CREATE TABLE", "[全链路] 建表");
        a.check(se.tableExists("student"), "[全链路] 建表真的建了数据文件");
        a.checkEquals(3, se.scanTable("pg_catalog").size(), "[全链路] 建表同时登记了 3 行目录");
        a.checkContains(run(se, cm, catalog, "INSERT INTO student VALUES (1,'Alice',20);"), "INSERT 1",
                "[全链路] 插入");
        a.checkEquals(1, se.scanTable("student").size(), "[全链路] 执行引擎写入的行存储层能扫到");
        List<List<Object>> stored = se.scanTable("student");
        a.checkEquals(Arrays.asList(1, "Alice", 20), stored.get(0), "[全链路] 行内容与值列表一致（含类型）");

        a.checkContains(run(se, cm, catalog, "SELECT * FROM student;"), "'Alice'", "[全链路] 查询");
        a.checkContains(run(se, cm, catalog, "DELETE FROM student WHERE id = 1;"), "DELETE 1", "[全链路] 删除");
        a.checkEquals(0, se.scanTable("student").size(), "[全链路] 删除后存储层确实没有行了");

        // 失败语句之后引擎仍然可用：错误不得让状态机卡住，也不得写坏数据
        run(se, cm, catalog, "INSERT INTO student VALUES (2,'Bob',17);");
        int pagesBefore = pageCountOf(dir, "student.dat");
        String bad = run(se, cm, catalog, "INSERT INTO student VALUES (3,4,5,6);");
        a.checkContains(bad, "ColumnCountMismatch", "[全链路] 列数不符被语义分析拦下");
        a.checkEquals(1, se.scanTable("student").size(), "[全链路] 失败的插入没有留下半行");
        a.checkEquals(pagesBefore, pageCountOf(dir, "student.dat"), "[全链路] 失败的插入没有让文件增长");
        a.checkContains(run(se, cm, catalog, "SELECT * FROM student;"), "'Bob'",
                "[全链路] 失败之后引擎仍能正常查询");

        // 多语句脚本：后面的语句看得到前面语句在符号表里的效果
        String script = run(se, cm, catalog, "CREATE TABLE t2(a INT); INSERT INTO t2 VALUES (7); SELECT * FROM t2;");
        a.checkContains(script, "CREATE TABLE", "[全链路] 多语句脚本：建表");
        a.checkContains(script, "INSERT 1", "[全链路] 多语句脚本：插入（上一条语句的符号表登记必须已生效）");
        a.checkContains(script, "\n7\n", "[全链路] 多语句脚本：查得到刚插入的值");

        // 表不存在：报错来自语义层，且不留下任何副作用
        String noTable = run(se, cm, catalog, "SELECT * FROM nosuch;");
        a.checkContains(noTable, "TableNotFound", "[全链路] 表不存在");
        a.checkFalse(catalog.containsTable("nosuch"), "[全链路] 失败的查询不会往符号表里塞东西");

        // 重启之后，通过执行引擎建的每一个表都还在（文件 + 目录两条线都对上了）
        StorageEngine se2 = new StorageEngine(dir.getAbsolutePath());
        CatalogManager cm2 = new CatalogManager(se2);
        Catalog catalog2 = cm2.loadCatalog();
        a.check(catalog2.containsTable("student") && catalog2.containsTable("t2"),
                "[全链路] 重启后两张表都在符号表里");
        a.checkContains(run(se2, cm2, catalog2, "SELECT * FROM student;"), "'Bob'", "[全链路] 重启后数据仍在");
        a.checkContains(run(se2, cm2, catalog2, "SELECT * FROM t2;"), "\n7\n", "[全链路] 重启后 t2 的数据仍在");
    }

    // ================= 七、接口边界（退化输入） =================

    private static void sectionBoundary(Assert a, File root) {
        File dir = sub(root, "boundary");
        FileManager fm = new FileManager(dir.getAbsolutePath(), "t.dat");
        fm.init();
        int id = fm.allocatePage();
        BufferPool bp = new BufferPool(2, BufferPool.Strategy.AUTO, fm);
        bp.getPage(id);

        // 退化输入全部是空操作，不许抛异常、不许改变可观测状态
        int[] before = new int[]{bp.getHitCount(), bp.getMissCount(), bp.getDirtyCount()};
        bp.markDirty(-1);
        bp.markDirty(9999);
        bp.flushPage(-1);
        bp.flushPage(9999);
        bp.invalidate(-1);
        bp.invalidate(9999);
        a.checkEquals(before[0], bp.getHitCount(), "[边界] 退化输入不改变命中数");
        a.checkEquals(before[1], bp.getMissCount(), "[边界] 退化输入不改变未命中数");
        a.checkEquals(before[2], bp.getDirtyCount(), "[边界] 退化输入不改变脏页数");
        a.check(bp.isResident(id), "[边界] 退化输入不会把驻留页踢出去");

        // 容量 0：读得到页，但什么都留不住
        BufferPool zero = new BufferPool(0, BufferPool.Strategy.LRU, fm);
        Page p = zero.getPage(id);
        a.check(p != null, "[边界] 容量 0 时仍能读到页");
        a.checkFalse(zero.isResident(id), "[边界] 容量 0 时页留不住（驻留集合恒空）");
        a.check(zero.getResidentPages().isEmpty(), "[边界] 容量 0 时驻留页列表为空");

        // 空文件的元数据页：第 0 页可读、可缓存，但不会被当作数据页发出去
        a.check(fm.readPage(0) != null, "[边界] 第 0 页元数据页可读");
        a.checkEquals(0, bp.getPage(0).getPageId(), "[边界] 第 0 页可经缓冲池读取");
        int allocated = fm.allocatePage();
        a.checkFalse(allocated == 0, "[边界] 元数据页不会被分配出去");

        // 空表：文件只有元数据页时扫描为空，且不报错
        File empty = sub(root, "boundary-empty");
        StorageEngine se = new StorageEngine(empty.getAbsolutePath());
        se.createTable("t", INT_SCHEMA);
        a.checkEquals(0, se.scanTable("t").size(), "[边界] 空表扫描为 0 行");
        a.checkEquals(0, se.deleteRows("t", null), "[边界] 空表删除报告 0 行");
        a.checkEquals(1, pageCountOf(empty, "t.dat"), "[边界] 空表删除不会让文件增长或缩短");
    }

    // ================= 工具 =================

    private static String run(StorageEngine se, CatalogManager cm, Catalog catalog, String sql) {
        return Main.executeAndFormat(sql, se, cm, catalog);
    }

    /** 在同一个目录上开一个"重启后"的引擎：只登记表结构（文件本来就在）。 */
    private static StorageEngine reopen(File dir, String table, List<ColumnDef> schema) {
        StorageEngine se = new StorageEngine(dir.getAbsolutePath());
        se.registerSchema(table, schema);
        return se;
    }

    /**
     * 逐字段比较两份列定义。
     * 注意 {@link ColumnDef} 没有实现 equals（它是个可变语义上的值对象，但只提供 getter），
     * 直接写 {@code checkEquals(list1, list2)} 会退化成引用比较、永远不等——
     * 列表里每个元素的 toString 看起来一模一样，失败信息会是一条自相矛盾的 expected &lt;X&gt; but got &lt;X&gt;。
     */
    private static boolean sameColumns(List<ColumnDef> x, List<ColumnDef> y) {
        if (x == null || y == null || x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            ColumnDef p = x.get(i);
            ColumnDef q = y.get(i);
            if (!p.getName().equals(q.getName()) || p.getType() != q.getType()
                    || p.getVarcharLength() != q.getVarcharLength()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 事件出口里是否出现过一条同时包含全部关键词的消息。
     * 事件文本是中文，这里只做子串匹配（比较的是 Java 字符串，与控制台编码无关）。
     */
    private static boolean hasEvent(List<String> events, String... keywords) {
        for (String e : events) {
            boolean all = true;
            for (String k : keywords) {
                if (e.indexOf(k) < 0) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static File sub(File root, String name) {
        File dir = new File(root, name);
        dir.mkdirs();
        return dir;
    }

    private static byte[] encodeInt(int v) {
        return Serializer.encodeRow(Arrays.asList(v), Arrays.asList(ColumnType.INT));
    }

    /** 数据文件当前的页数（按文件长度推算，绕过 FileManager 的内存快照）。 */
    private static int pageCountOf(File dir, String fileName) {
        return (int) (fileLength(dir, fileName) / Constants.PAGE_SIZE);
    }

    private static long fileLength(File dir, String fileName) {
        return new File(dir, fileName).length();
    }

    /** 绕过所有接口，直接从磁盘取一页的原始字节。 */
    private static byte[] readRaw(File dir, String fileName, int pageId) {
        byte[] buf = new byte[Constants.PAGE_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, fileName), "r")) {
            raf.seek((long) pageId * Constants.PAGE_SIZE);
            raf.readFully(buf);
        } catch (Exception e) {
            throw new DbException("raw read of page " + pageId + " failed: " + e.getMessage(), e);
        }
        return buf;
    }

    /** 磁盘上某页的槽数（绕过缓冲池与空闲链）。 */
    private static int slotCountOnDisk(File dir, String fileName, int pageId) {
        return readIntAt(readRaw(dir, fileName, pageId), 12);
    }

    /** 磁盘上某页页头里记录的页号（绕过 Page 对象）。 */
    private static int pageIdOnDisk(File dir, String fileName, int pageId) {
        return readIntAt(readRaw(dir, fileName, pageId), 0);
    }

    private static int readIntAt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static int readShortAt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
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
