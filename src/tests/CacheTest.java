package tests;

import engine.StorageEngine;
import storage.BufferPool;
import storage.FileManager;
import storage.Page;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Constants;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 缓存机制专项测试（实验验收用的预设测试程序）。
 *
 * <p>按下发的检查条目「使用固定页面访问序列检查缓存命中、淘汰、替换和回写过程是否符合 LRU 等指定策略」
 * 组织，用一个**固定的页面访问序列**驱动 {@link BufferPool}，并把每一步都跟一个教科书参考模型逐步比对：
 * <ol>
 *   <li>命中：序列里每一步是命中还是未命中（只比总命中数无法区分 LRU 与 FIFO，必须逐步比）</li>
 *   <li>淘汰：淘汰的页号序列、淘汰的时机（第几次访问）、容量是否被严格遵守</li>
 *   <li>替换：序列结束后缓存里驻留的是哪几页、以及它们的先后次序</li>
 *   <li>回写：脏页被淘汰时是否落盘、落盘的是不是它自己的内容、flushAll/flushPage 的边界</li>
 *   <li>策略：LRU / FIFO / AUTO 三种指定策略各自的语义是否被正确实现</li>
 * </ol>
 *
 * <p><b>为什么用参考模型而不是手写期望值：</b>期望的命中轨迹由 {@link RefCache}（十几行的教科书
 * LRU/FIFO）当场算出，测试只需断言「被测实现逐步等于模型」。这样固定序列可以任取多个、
 * 容量可以任取多种，覆盖不会因为手算期望值容易出错而缩水。模型本身极简，出错风险很低。
 *
 * <p><b>AUTO 为什么不用参考模型：</b>AUTO 会在序列中途换策略，单一模型无法刻画
 * （LRU 模型在切换那一刻就不再适用）。而且"模型和实现抄同一套状态机"是自证循环，
 * 抄错了也一起错。所以 AUTO 一律用**手工推演的固定序列期望值**断言：
 * 每条序列的最终策略、切换轨迹、淘汰页序列、命中/未命中数都写死在测试里，
 * 可以手工复算——这也正是本检查条目"用固定页面访问序列检查"的原意。
 *
 * <p>运行方式：{@code java -cp out tests.CacheTest}（已并入 {@link AllTests}）。
 * 测试数据建在系统临时目录，运行结束即清理，不会污染 {@code data/}。
 *
 * <p><b>可观测性：</b>淘汰顺序与策略切换现在有正式接口（{@code getEvictions()} /
 * {@code getSwitchHistory()} / {@code getEvictionCount()} / {@code getResidentPages()} /
 * {@code isResident()} / {@code getDirtyCount()}），断言以这些接口为准。
 * 日志文本（静态 {@code setLogSink}）另外单独校验一次，只为守住 GUI 的展示契约。
 * 测试在 finally 里 {@code setLogSink(null)} 还原默认出口，否则会吞掉 GUI 的缓存事件。
 */
public class CacheTest {

    /** 数据文件里建多少个数据页（要盖过所有序列里出现的页号与驻留探测用的页号）。 */
    private static final int FILE_PAGES = 12;

    /** 固定访问序列结束后，用于探测"驻留集合"的页号（升序逐个访问，与模型继续比对）。 */
    private static final int PROBE_PAGES = 6;

    /**
     * 固定访问序列集。每个序列针对性地压一个方面：
     * 循环扫描、热页、重复访问同一页、逆序、伪随机、纯顺序。
     */
    private static final int[][] SEQUENCES = {
            {1, 2, 3, 1, 4, 2, 5, 1, 2, 3, 4, 5},           // 循环：教科书上 LRU 表现最差的一例
            {1, 2, 3, 1, 1, 1, 4},                          // 热页：LRU 留住页 1，FIFO 会把页 1 淘汰
            {2, 2, 2, 1, 2},                                // 反复访问同一页
            {5, 4, 3, 2, 1, 5, 4, 3, 2, 1},                 // 逆序扫描
            {3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5},              // 伪随机
            {1, 2, 3, 4, 5, 6, 7, 8},                       // 纯顺序扫描
    };

    private static final String[] SEQUENCE_NAMES = {
            "循环", "热页", "重复", "逆序", "伪随机", "顺序",
    };

    public static int run() {
        Assert a = new Assert();
        sectionHitAndReplacement(a);
        sectionEviction(a);
        sectionStrategy(a);
        sectionWriteBack(a);
        sectionCoherence(a);
        sectionInvalidate(a);
        sectionEdge(a);
        return a.summary("CacheTest 缓存机制");
    }

    // ================= 一、命中与替换（固定序列逐步比对参考模型） =================

    /**
     * 核心：对每个固定序列、每种容量/策略，逐步断言命中与否，并在序列结束后探测驻留集合。
     * 逐步比对是关键——LRU 与 FIFO 在有些序列上总命中数相同（见"循环"序列），
     * 只有比对"哪一步命中"才能区分两者。
     */
    private static void sectionHitAndReplacement(Assert a) {
        File dir = TestFiles.tempDir("cache-seq");
        try {
            FileManager fm = fileWithPages(dir, "seq.dat", FILE_PAGES);
            LogSink sink = new LogSink();
            BufferPool.setLogSink(sink);
            try {
                for (int s = 0; s < SEQUENCES.length; s++) {
                    for (int capacity = 1; capacity <= 4; capacity++) {
                        checkSequence(a, fm, sink, SEQUENCES[s], SEQUENCE_NAMES[s],
                                BufferPool.Strategy.LRU, capacity);
                        checkSequence(a, fm, sink, SEQUENCES[s], SEQUENCE_NAMES[s],
                                BufferPool.Strategy.FIFO, capacity);
                    }
                }

                // 命中率接口本身
                BufferPool bp = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                bp.getPage(1);
                bp.getPage(1);
                a.checkEquals(1, bp.getHitCount(), "[命中] 两次访问同一页：命中 1 次");
                a.checkEquals(1, bp.getMissCount(), "[命中] 两次访问同一页：未命中 1 次");
                a.checkEquals(0.5, bp.hitRate(), "[命中] 命中率 = 1/2");
                a.checkEquals(BufferPool.Strategy.LRU, bp.getActiveStrategy(), "[策略] LRU 池报告 LRU");
                a.checkEquals(0.0, new BufferPool(2, BufferPool.Strategy.LRU, fm).hitRate(),
                        "[命中] 一次都没访问过时命中率为 0");
            } finally {
                BufferPool.setLogSink(null);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    /**
     * 跑一个固定序列，跟参考模型比对：逐步命中、总命中/未命中、淘汰序列、驻留集合。
     *
     * @param tagPrefix 断言标签前缀（体现序列名与配置，便于失败时定位）
     */
    private static void checkSequence(Assert a, FileManager fm, LogSink sink, int[] seq,
                                      String seqName, BufferPool.Strategy strategy, int capacity) {
        String tag = "[命中] " + seqName + "序列-" + strategy + "(容量" + capacity + ")";
        sink.clear();
        BufferPool bp = new BufferPool(capacity, strategy, fm);
        RefCache ref = new RefCache(capacity, strategy != BufferPool.Strategy.FIFO);

        // 逐步比对：每一次访问是命中还是未命中
        replay(a, tag, bp, ref, seq);

        a.checkEquals(ref.hits(), bp.getHitCount(), tag + " 总命中数");
        a.checkEquals(ref.misses(), bp.getMissCount(), tag + " 总未命中数");

        // 淘汰：顺序、时机、容量
        a.checkEquals(join(ref.evicted()), join(sink.evicted()), tag + " 淘汰页序列");
        a.checkEquals(ref.misses() - Math.min(capacity, distinct(seq)), ref.evicted().size(),
                tag + " 淘汰次数 = 未命中数 - 容量（容量被严格遵守）");

        // 替换：序列结束后驻留的是哪几页——用一次升序探测与模型继续比对
        String probeTag = "[替换] " + seqName + "序列-" + strategy + "(容量" + capacity + ")";
        int[] probe = new int[PROBE_PAGES];
        for (int i = 0; i < PROBE_PAGES; i++) {
            probe[i] = i + 1;
        }
        replay(a, probeTag, bp, ref, probe);
        a.checkTrue(ref.resident().size() <= capacity, probeTag + " 驻留页数不超过容量");
    }

    /**
     * 把固定序列喂给被测缓冲池与参考模型，逐步断言命中/未命中一致。
     * 命中与否用 hitCount 的增量判定（而不是比较 Page 对象），因为未命中恰好读到内容相同的页时
     * 两者无法区分——这正是必须用统计计数来观测的原因。
     */
    private static void replay(Assert a, String tag, BufferPool bp, RefCache ref, int[] seq) {
        for (int i = 0; i < seq.length; i++) {
            int pageId = seq[i];
            int hitsBefore = bp.getHitCount();
            Page p = bp.getPage(pageId);
            boolean hit = bp.getHitCount() > hitsBefore;
            boolean expectHit = ref.access(pageId);
            a.checkEquals(expectHit, hit, tag + " 第" + (i + 1) + "次访问页" + pageId);
            a.check(p != null, tag + " 第" + (i + 1) + "次访问页" + pageId + " 返回非空");
        }
    }

    // ================= 二、淘汰 =================

    /**
     * 淘汰的直接检查：淘汰的是不是"最该被淘汰的那一页"。
     * 用日志序列核对 LRU 与 FIFO 在同一固定序列上淘汰**不同的页**——
     * 这是"是否符合指定策略"最直接的证据。
     */
    private static void sectionEviction(Assert a) {
        File dir = TestFiles.tempDir("cache-evict");
        try {
            FileManager fm = fileWithPages(dir, "evict.dat", FILE_PAGES);
            LogSink sink = new LogSink();
            BufferPool.setLogSink(sink);
            try {
                // 热页序列：1 被反复访问。LRU 认为 1 最新，FIFO 认为 1 最旧。
                int[] hot = {1, 2, 3, 1, 1, 1, 4};
                sink.clear();
                BufferPool lru = new BufferPool(3, BufferPool.Strategy.LRU, fm);
                for (int id : hot) {
                    lru.getPage(id);
                }
                a.checkEquals("[2]", join(sink.evicted()),
                        "[淘汰] LRU 在热页序列上淘汰页 2（页 1 刚被访问过，不该淘汰）");
                a.checkFalse(sink.evicted().contains(1), "[淘汰] LRU 淘汰的不是最近访问的页 1");

                sink.clear();
                BufferPool fifo = new BufferPool(3, BufferPool.Strategy.FIFO, fm);
                for (int id : hot) {
                    fifo.getPage(id);
                }
                a.checkEquals("[1]", join(sink.evicted()),
                        "[淘汰] FIFO 在同一序列上淘汰页 1（先进先出，命中不改变次序）");

                // 同一固定序列下两种策略的淘汰结果必须不同，否则说明策略没生效
                a.checkFalse(join(lruEvicted(fm, hot)).equals(join(fifoEvicted(fm, hot))),
                        "[淘汰] 同一固定序列下 LRU 与 FIFO 的淘汰序列不同（策略确实生效）");

                // 淘汰的永远是队头，绝不会是刚插入的那一页
                sink.clear();
                BufferPool small = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                List<Integer> seen = new ArrayList<>();
                boolean evictedNewest = false;
                for (int id = 1; id <= 8; id++) {
                    int before = sink.evicted().size();
                    small.getPage(id);
                    List<Integer> now = sink.evicted();
                    if (now.size() > before) {
                        int victim = now.get(now.size() - 1);
                        if (victim == id) {
                            evictedNewest = true;      // 刚放进去就被淘汰 = 容量或淘汰点算错
                        }
                        seen.add(victim);
                    }
                }
                a.checkFalse(evictedNewest, "[淘汰] 刚插入的页不会被立刻淘汰（淘汰在插入之后、取队头）");
                a.checkEquals("[1, 2, 3, 4, 5, 6]", join(seen),
                        "[淘汰] 容量 2 下升序访问 8 页：严格按 LRU 次序淘汰 1..6");
                a.checkEquals(6, sink.evicted().size(),
                        "[淘汰] 访问 8 页容量 2：淘汰 8-2=6 次（容量被严格遵守，不会涨到 3）");
            } finally {
                BufferPool.setLogSink(null);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    /** 用给定序列跑一次 LRU，返回被淘汰的页号序列。 */
    private static List<Integer> lruEvicted(FileManager fm, int[] seq) {
        return evictedBy(fm, seq, BufferPool.Strategy.LRU, 3);
    }

    /** 用给定序列跑一次 FIFO，返回被淘汰的页号序列。 */
    private static List<Integer> fifoEvicted(FileManager fm, int[] seq) {
        return evictedBy(fm, seq, BufferPool.Strategy.FIFO, 3);
    }

    /** 用给定序列跑一次，返回被淘汰的页号序列（走 getEvictions() 接口，不借日志出口）。 */
    private static List<Integer> evictedBy(FileManager fm, int[] seq, BufferPool.Strategy s, int cap) {
        BufferPool bp = new BufferPool(cap, s, fm);
        for (int id : seq) {
            bp.getPage(id);
        }
        return bp.getEvictions();
    }

    // ================= 三、策略（LRU / FIFO / AUTO） =================

    /**
     * AUTO 的顺序扫描启发式：初始按 LRU，连续**同向**（升序或降序）跨过
     * {@code SCAN_ENTER = 4} 步就切 FIFO；之后必须连续 {@code HYST_EXIT = 3} 步**换向**
     * 才退回 LRU（滞回，防抖动）。
     *
     * <p>全部用手工可复算的固定序列 + 写死的期望值断言，分七组：
     * <ol>
     *   <li>阈值边界：哪一步触发、哪一步不触发</li>
     *   <li>方向识别：升序、降序都算顺序；随机不算</li>
     *   <li>重复访问是中性事件：既不累计也不清零</li>
     *   <li>滞回：反复循环扫描不抖动；锯齿够 3 步才退出</li>
     *   <li><b>按插入序重排</b>的判别序列（AUTO 与 LRU 淘汰不同的页）</li>
     *   <li>退化边界：容量 0/1、容量充足、不对外报告 AUTO</li>
     *   <li>已知局限：+2/-1 锯齿会停在 FIFO（记录在案，不是回归）</li>
     * </ol>
     */
    private static void sectionStrategy(Assert a) {
        File dir = TestFiles.tempDir("cache-auto");
        try {
            FileManager fm = fileWithPages(dir, "auto.dat", FILE_PAGES);
            LogSink sink = new LogSink();
            BufferPool.setLogSink(sink);
            try {
                List<BufferPool.Strategy> fifo = Arrays.asList(BufferPool.Strategy.FIFO);
                List<BufferPool.Strategy> lruThenFifo =
                        Arrays.asList(BufferPool.Strategy.FIFO, BufferPool.Strategy.LRU);

                // ---- 组 1：阈值边界。首次访问只登记方向，不计步 ----
                // S10a：1,2,3,4 只有 3 步同向，不够 4 步
                checkPolicy(a, fm, "阈值下（1,2,3,4 共 3 步同向）", 4, new int[]{1, 2, 3, 4},
                        BufferPool.Strategy.LRU, Collections.<BufferPool.Strategy>emptyList(),
                        new int[]{}, 0, 4);
                // S10b：多访问一页即触发，说明触发点精确落在第 5 个递增页号
                checkPolicy(a, fm, "阈值上（1,2,3,4,5 共 4 步同向）", 4, new int[]{1, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1}, 0, 5);
                // S14：页号不从 1 开始也一样——判定与"页号是否大于某个哨兵"无关
                checkPolicy(a, fm, "首访只登记（[4]）", 4, new int[]{4},
                        BufferPool.Strategy.LRU, Collections.<BufferPool.Strategy>emptyList(),
                        new int[]{}, 0, 1);
                checkPolicy(a, fm, "首访只登记（4,5,6,7 共 3 步）", 4, new int[]{4, 5, 6, 7},
                        BufferPool.Strategy.LRU, Collections.<BufferPool.Strategy>emptyList(),
                        new int[]{}, 0, 4);
                checkPolicy(a, fm, "首访只登记（4,5,6,7,8 共 4 步）", 4, new int[]{4, 5, 6, 7, 8},
                        BufferPool.Strategy.FIFO, fifo, new int[]{4}, 0, 5);

                // ---- 组 2：方向识别 ----
                checkPolicy(a, fm, "纯升序 1..8", 4, new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2, 3, 4}, 0, 8);
                // S02：降序同样算顺序扫描（旧实现只认升序，这条序列上 0 次切换）
                checkPolicy(a, fm, "纯降序 9..2", 4, new int[]{9, 8, 7, 6, 5, 4, 3, 2},
                        BufferPool.Strategy.FIFO, fifo, new int[]{9, 8, 7, 6}, 0, 8);
                // S05：随机访问凑不满 4 步同向，且 LRU 状态下不判定退出
                checkPolicy(a, fm, "伪随机（不触发）", 4, new int[]{3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9},
                        BufferPool.Strategy.LRU, Collections.<BufferPool.Strategy>emptyList(),
                        new int[]{3, 4, 1, 9, 2, 6, 3}, 4, 11);
                // 容量充足时前 8 页都不淘汰：切换时点与容量无关
                checkPolicy(a, fm, "容量充足的长升序", 8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2}, 0, 10);

                // ---- 组 3：重复访问是中性事件 ----
                // S08：反复读同一页，既不算顺序也不清零
                checkPolicy(a, fm, "单页反复访问（全中性）", 3, new int[]{7, 7, 7, 7, 7},
                        BufferPool.Strategy.LRU, Collections.<BufferPool.Strategy>emptyList(),
                        new int[]{}, 4, 1);
                // S03：升序中间重读一页不打断累计（旧实现把"相等"当"非升序"清零，切换被推迟）
                checkPolicy(a, fm, "升序中重读一页 1,2,3,3,4,5,6,7", 4, new int[]{1, 2, 3, 3, 4, 5, 6, 7},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2, 3}, 1, 7);
                // 同一条规则的判别式：1,2,3,3,4,5 只够在"重读不清零"时凑满 4 步。
                //   重读中性 -> 第 6 次访问页 5 时 runLen=4 -> FIFO
                //   重读清零 -> 页 5 只是第 2 步 -> 仍是 LRU
                checkPolicy(a, fm, "重读不影响累计（判别式）1,2,3,3,4,5", 4, new int[]{1, 2, 3, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1}, 1, 5);
                // S16：两次重读都是中性的，4 步照常累计
                checkPolicy(a, fm, "升序中夹两次重读 1,2,2,3,3,4,5,6", 4, new int[]{1, 2, 2, 3, 3, 4, 5, 6},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2}, 2, 6);

                // ---- 组 4：滞回（反复扫描不抖动）----
                // S04：循环扫描三遍。旧实现每圈切两次，三圈共 5 次切换
                checkPolicy(a, fm, "循环扫描三遍（滞回不抖动）", 4,
                        new int[]{1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo,
                        new int[]{1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1}, 0, 15);
                // S06：扫描后回到随机，连续 3 步换向才退出。这条是**退出侧重排**的判别序列。
                // 逐帧推演（容量 4，括号内是 lastUseSeq）：
                //   1..4 装入 [1,2,3,4]；第 5 次访问页 5 时同向满 4 步 -> 进 FIFO，
                //   随后的 5,6,8,3 把队头逐个顶掉，淘汰 1,2,3,4，链表 [5,6,8,3]。
                //   第 9 次访问页 8 命中（FIFO 不重排，但 lastUseSeq 刷新为 8）；第 10 次访问页 1
                //   使换向计数到 3 -> 退出回 LRU。此刻 lastUseSeq = 5(4) 6(5) 3(7) 8(8)：
                //     按最近使用序重排 -> [5,6,3,8]（页 3 比页 8 更久没用，应当排在前面）
                //     不重排          -> [5,6,8,3]（还是 FIFO 阶段的插入序，页 8 被当成更旧的）
                //   差别在第 12 次访问页 2 时体现：重排后队头是 3，淘汰 {3}；
                //   不重排队头是 8，淘汰 {8}。后面 8 又被命中，于是两条路径的淘汰总数也不同。
                checkPolicy(a, fm, "先扫描后随机（含退出侧重排）", 4,
                        new int[]{1, 2, 3, 4, 5, 6, 8, 3, 8, 1, 9, 2, 8, 4},
                        BufferPool.Strategy.LRU, lruThenFifo,
                        new int[]{1, 2, 3, 4, 5, 6, 3, 1}, 2, 12);
                // S11A/S11B：退出阈值的精确边界（只差最后一次访问）
                checkPolicy(a, fm, "锯齿 2 步（不够退出）", 4, new int[]{1, 2, 3, 4, 5, 5, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1}, 3, 5);
                checkPolicy(a, fm, "锯齿 3 步（恰好退出）", 4, new int[]{1, 2, 3, 4, 5, 5, 4, 5, 4},
                        BufferPool.Strategy.LRU, lruThenFifo, new int[]{1}, 4, 5);
                // S12：退出后继续锯齿，凑不满 4 步同向，不会重新进入
                checkPolicy(a, fm, "退出后继续锯齿（不再进入）", 4,
                        new int[]{1, 2, 3, 4, 5, 5, 4, 5, 4, 5, 4},
                        BufferPool.Strategy.LRU, lruThenFifo, new int[]{1}, 6, 5);
                // S09a/S09b：换方向本身不等于随机——回折一次不足以退出
                checkPolicy(a, fm, "V 形（降序后转升序）", 4, new int[]{9, 8, 7, 6, 5, 6, 7, 8, 9},
                        BufferPool.Strategy.FIFO, fifo, new int[]{9, 8}, 3, 6);
                checkPolicy(a, fm, "直角回折（降序后跳回升序）", 4, new int[]{9, 8, 7, 6, 5, 1, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{9, 8, 7, 6, 5, 1}, 0, 10);
                // S17：降序进入 FIFO，再接随机段退出
                checkPolicy(a, fm, "降序后接随机", 4, new int[]{9, 8, 7, 6, 5, 2, 7, 1, 8, 3},
                        BufferPool.Strategy.LRU, lruThenFifo, new int[]{9, 8, 7, 6, 5}, 1, 9);

                // ---- 组 5：切到 FIFO 时按插入序重排（唯一改变淘汰结果的一步）----
                // S13（容量 4）：序列 2,1,2,3,4,5
                //   第 3 次访问页 2 命中，LRU 把它移到队尾，链表次序 [1,2,3,4]（LRU 序），
                //   而插入序是 2,1,3,4。第 6 次访问页 5 触发切换：
                //     按插入序重排 -> 队头 2 -> 淘汰页 2（真正的 FIFO 语义）
                //     不重排      -> 队头 1 -> 淘汰页 1（与 LRU 完全相同，切换形同虚设）
                //   所以这条序列是"重排"与"没重排"的判别式：期望 [2]，而 LRU 给 [1]。
                checkPolicy(a, fm, "按插入序重排判据（容量 4）", 4, new int[]{2, 1, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{2}, 1, 5);
                a.checkEquals("[1]", join(evictedBy(fm, new int[]{2, 1, 2, 3, 4, 5},
                                BufferPool.Strategy.LRU, 4)),
                        "[策略] 同一序列上 LRU 淘汰页 1——与 AUTO 的 [2] 不同，"
                                + "证明 AUTO 确实按插入序重排了链表（不重排就会得到 [1]）");

                // S13b（容量 5）：更像真实扫描，且顺带暴露旧实现的三次抖动
                checkPolicy(a, fm, "按插入序重排判据（容量 5）", 5, new int[]{1, 2, 3, 4, 1, 5, 6, 7, 8},
                        BufferPool.Strategy.FIFO, fifo, new int[]{2, 3, 1}, 1, 8);
                a.checkEquals("[2, 3, 4]", join(evictedBy(fm, new int[]{1, 2, 3, 4, 1, 5, 6, 7, 8},
                                BufferPool.Strategy.LRU, 5)),
                        "[策略] 同一序列上 LRU 淘汰 [2, 3, 4]——AUTO 淘汰 [2, 3, 1]，"
                                + "第 10 次淘汰的差异（1 还是 4）来自重排");

                // 没有命中干扰时，重排后的淘汰次序应当与纯 FIFO 完全一致（重排确实是"插入序"）
                a.checkEquals(join(evictedBy(fm, new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                                BufferPool.Strategy.FIFO, 4)),
                        join(new int[]{1, 2, 3, 4}),
                        "[策略] 纯升序无命中时，纯 FIFO 淘汰 [1, 2, 3, 4]——与 AUTO 重排后一致");

                // ---- 组 6：退化边界 ----
                // 容量 0 + AUTO：链表恒空，重排必须不崩（对空表取 head 会抛越界）。
                // 这一条只走到**进入侧**的重排（rebuildByInsertOrder），退出侧的
                // rebuildByRecency 同样吃空表，但要在 FIFO 里再折返 3 步才触发——
                // 少了下面那条，把 rebuildByRecency 的空表守卫删掉整套用例仍然全绿
                // （变异实测），所以必须单独钉一条。
                checkPolicy(a, fm, "容量 0（进 FIFO 时对空表重排不崩）", 0, new int[]{1, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2, 3, 4, 5}, 0, 5);
                // 逐帧：1,2,3,4 累计 3 步同向（容量 0 每页装入后立即被淘汰，链表恒空），
                // 第 5 次访问凑满 4 步 -> 进 FIFO（空表重排）；随后 4,5,4 连续 3 步换向
                // -> 退出回 LRU（**又一次空表重排**，即被这条用例覆盖的那一步）。
                checkPolicy(a, fm, "容量 0（退 FIFO 时对空表重排不崩）", 0,
                        new int[]{1, 2, 3, 4, 5, 4, 5, 4},
                        BufferPool.Strategy.LRU, lruThenFifo,
                        new int[]{1, 2, 3, 4, 5, 4, 5, 4}, 0, 8);
                // 容量 1：重排是单节点空操作，状态机照常工作
                checkPolicy(a, fm, "容量 1", 1, new int[]{1, 2, 2, 3, 4, 5},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2, 3, 4}, 1, 5);

                // AUTO 池对外永远报告 LRU 或 FIFO，绝不报告 AUTO 自身
                List<BufferPool.Strategy> reported = new ArrayList<>();
                for (int cap = 1; cap <= 3; cap++) {
                    reported.add(new BufferPool(cap, BufferPool.Strategy.AUTO, fm).getActiveStrategy());
                }
                a.checkFalse(reported.contains(BufferPool.Strategy.AUTO),
                        "[策略] getActiveStrategy() 不返回 AUTO，只返回解析后的 LRU/FIFO");
                a.checkEquals(BufferPool.Strategy.LRU,
                        new BufferPool(2, BufferPool.Strategy.AUTO, fm).getActiveStrategy(),
                        "[策略] AUTO 池初始实际策略为 LRU");

                // ---- 组 7：已知局限 ----
                // 退出条件要连续 3 步换向，而同向延续会把换向计数清零。所以"单调但带回调"的
                // 负载（+2/-1 锯齿）换向计数峰值只有 2，一旦进了 FIFO 就退不出来。
                // 逐帧推演这条 17 次访问的锯齿：换向计数在 1,2 之间反复归零，始终够不到 3，
                // 于是第 5 步进来以后一直停在 FIFO。这里把实际行为写死记录下来，
                // 避免它被误当成回归；影响面限定在淘汰对象，且脏 victim 一律照常写回，
                // 没有正确性后果。
                checkPolicy(a, fm, "已知局限：+2/-1 锯齿停在 FIFO", 4,
                        new int[]{1, 2, 3, 4, 5, 2, 4, 6, 4, 6, 8, 6, 8, 10, 8, 10, 12},
                        BufferPool.Strategy.FIFO, fifo, new int[]{1, 2, 3, 4, 5}, 8, 9);
                a.checkEquals(Collections.<BufferPool.Strategy>emptyList(),
                        new BufferPool(4, BufferPool.Strategy.AUTO, fm).getSwitchHistory(),
                        "[策略] 新建池的切换历史为空，与上一条序列无关（每个池的状态独立）");

                // ---- 回归：访问不存在的页号不得影响顺序扫描判定 ----
                sink.clear();
                BufferPool ghost = new BufferPool(3, BufferPool.Strategy.AUTO, fm);
                ghost.getPage(1);
                ghost.getPage(2);
                ghost.getPage(3);
                a.checkEquals(null, ghost.getPage(9999), "[策略] 不存在的页号返回 null");
                a.checkEquals(0, ghost.getSwitchHistory().size(),
                        "[策略] 访问不存在的页号不会触发策略切换（顺序判定只看真实访问）");
                a.checkEquals(BufferPool.Strategy.LRU, ghost.getActiveStrategy(),
                        "[策略] 访问不存在的页号后仍是 LRU（回归：曾因这次空访问切到 FIFO）");
                // 空访问也不占同向步数。**判别序列必须让那条空访问刚好落成"第 4 步"**，
                // 否则变异体（把越界访问也喂进状态机）与正确实现在这条序列上殊途同归：
                //   1,2,3,(空) -> 正确实现 2 步；变异体也只到 3 步，都够不到阈值 4，无法区分。
                // 下面这两条才是真正的判别式，方向刚好相反：
                BufferPool ghost2 = new BufferPool(3, BufferPool.Strategy.AUTO, fm);
                for (int id : new int[]{1, 2, 3, 4, 9999}) {
                    ghost2.getPage(id);
                }
                // 正确实现：1,2,3,4 是 3 步真实同向 + 一次空访问，停在 LRU。
                // 变异体：  空访问的页号 9999 > 4 被当成第 4 步同向 -> 切到 FIFO。
                a.checkEquals(0, ghost2.getSwitchHistory().size(),
                        "[策略] 空访问不占同向步数：1,2,3,4,(空) 只有 3 步真实同向，不切换");
                a.checkEquals(BufferPool.Strategy.LRU, ghost2.getActiveStrategy(),
                        "[策略] 越界页号不得充当第 4 步同向访问（回归：曾被算作一次顺序扫描）");
                BufferPool ghost3 = new BufferPool(3, BufferPool.Strategy.AUTO, fm);
                for (int id : new int[]{1, 2, 3, 9999, 5, 6, 7}) {
                    ghost3.getPage(id);
                }
                // 正确实现：跳过空访问后 1,2,3,5,6,7 一路升序，第 6 次访问凑满 4 步 -> FIFO。
                // 变异体：  空访问把 lastPageId 污染成 9999，5 反而成了"降序"，换向计数把它拖住 -> LRU。
                a.checkEquals(BufferPool.Strategy.FIFO, ghost3.getActiveStrategy(),
                        "[策略] 空访问不得污染 lastPageId（否则其后真实的升序扫描被误判成换向）");
                // 对照组：1,2,3,4 共 3 步真实同向，不切换；补上第 5 次才切
                BufferPool ghost4 = new BufferPool(3, BufferPool.Strategy.AUTO, fm);
                for (int id : new int[]{1, 2, 3, 4}) {
                    ghost4.getPage(id);
                }
                a.checkEquals(BufferPool.Strategy.LRU, ghost4.getActiveStrategy(),
                        "[策略] 对照：1,2,3,4 恰好 3 步同向，未达阈值 4，仍是 LRU");
                ghost4.getPage(5);
                a.checkEquals(BufferPool.Strategy.FIFO, ghost4.getActiveStrategy(),
                        "[策略] 对照：再补第 4 步同向（页 5），切换到 FIFO");

                // ---- 日志契约：GUI 靠这两句文本展示淘汰与切换，格式不能变 ----
                sink.clear();
                BufferPool logged = new BufferPool(4, BufferPool.Strategy.AUTO, fm);
                for (int id : new int[]{1, 2, 3, 4, 5, 6}) {
                    logged.getPage(id);
                }
                a.checkEquals("[1, 2]", join(sink.evicted()),
                        "[策略] 日志文本仍以 '[BufferPool] 淘汰页 N' 报告淘汰（GUI 展示契约）");
                a.checkEquals(1, sink.switches().size(),
                        "[策略] 日志文本仍报告 1 次策略切换（GUI 展示契约）");
                a.checkEquals(join(logged.getEvictions()), join(sink.evicted()),
                        "[策略] 日志里的淘汰序列与 getEvictions() 完全一致");
            } finally {
                BufferPool.setLogSink(null);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    /**
     * 跑一条固定序列并断言其完整期望：最终策略、切换轨迹、淘汰序列、命中/未命中数。
     * 期望值全部是手工推演的（AUTO 不能用参考模型 —— 它会中途换策略）。
     *
     * @param name            序列名（进断言标签，失败时能直接定位到是哪条序列）
     * @param expectStrategy  序列结束时的实际生效策略
     * @param expectSwitches  发生过的策略切换轨迹（按顺序）
     * @param expectEvicted   被淘汰的页号（按顺序）
     * @param expectHits      命中数
     * @param expectMisses    未命中数
     */
    private static void checkPolicy(Assert a, FileManager fm, String name, int capacity, int[] seq,
                                    BufferPool.Strategy expectStrategy,
                                    List<BufferPool.Strategy> expectSwitches,
                                    int[] expectEvicted, int expectHits, int expectMisses) {
        String tag = "[策略] " + name + "(容量" + capacity + ")";
        BufferPool bp = new BufferPool(capacity, BufferPool.Strategy.AUTO, fm);
        for (int id : seq) {
            bp.getPage(id);
        }

        a.checkEquals(expectStrategy, bp.getActiveStrategy(), tag + " 最终策略");
        a.checkEquals(expectSwitches.toString(), bp.getSwitchHistory().toString(), tag + " 切换轨迹");
        // 注意 getEvictions() 是有界日志（只留最近 EVENT_LOG_LIMIT=64 条），而 getEvictionCount()
        // 是精确计数。本节所有序列的淘汰数都远小于 64，所以这条逐元素比较成立；将来若有人加了
        // 淘汰超过 64 次的长序列，这里会**响亮地失败**（拿截断后的列表比长列表），不会静默放过——
        // 下一条断言用的是精确计数，正好兜住。
        a.checkEquals(join(expectEvicted), join(bp.getEvictions()), tag + " 淘汰页序列");
        a.checkEquals(expectHits, bp.getHitCount(), tag + " 命中数");
        a.checkEquals(expectMisses, bp.getMissCount(), tag + " 未命中数");
        a.checkEquals((long) expectEvicted.length, bp.getEvictionCount(), tag + " 淘汰总次数");

        // 不变式：上报的策略必须与切换轨迹自洽（初始 LRU；最后一条切换是什么就是什么）
        BufferPool.Strategy fromHistory = expectSwitches.isEmpty()
                ? BufferPool.Strategy.LRU
                : expectSwitches.get(expectSwitches.size() - 1);
        a.checkEquals(fromHistory, bp.getActiveStrategy(),
                tag + " getActiveStrategy() 与 getSwitchHistory() 自洽");

        // 不变式：访问完之后，驻留的恰好是"访问过的不同页号"里最新的 min(容量, 个数) 个
        a.checkEquals(Math.min(capacity, distinct(seq)), bp.getResidentPages().size(),
                tag + " 驻留页数 = min(容量, 访问过的不同页数)");
        a.checkEquals((long) (bp.getMissCount() - bp.getResidentPages().size()),
                bp.getEvictionCount(),
                tag + " 淘汰总次数 = 未命中数 - 最终驻留页数（容量恒被遵守）");
        // 驻留集合里不能有重复，也不能有从未访问过的页号
        a.checkEquals(bp.getResidentPages().size(), new HashSet<>(bp.getResidentPages()).size(),
                tag + " 驻留页号无重复");
        for (int id : bp.getResidentPages()) {
            a.checkTrue(contains(seq, id), tag + " 驻留页 " + id + " 确实是访问过的页");
            a.checkTrue(bp.isResident(id), tag + " isResident(" + id + ") 与 getResidentPages() 一致");
        }
    }

    /** 序列里是否出现过该页号。 */
    private static boolean contains(int[] seq, int pageId) {
        for (int v : seq) {
            if (v == pageId) {
                return true;
            }
        }
        return false;
    }

    // ================= 四、回写 =================

    /**
     * 回写的四条边界：脏页淘汰时落盘、落盘的是它自己的内容、干净页不写、
     * flushAll/flushPage 的范围，以及"改了页却没标脏"这种调用方失误的后果。
     */
    private static void sectionWriteBack(Assert a) {
        File dir = TestFiles.tempDir("cache-writeback");
        try {
            FileManager fm = fileWithPages(dir, "wb.dat", 8);
            LogSink sink = new LogSink();
            BufferPool.setLogSink(sink);
            try {
                // （1）脏页被淘汰时必须落盘，且落盘的是这一页自己的内容
                BufferPool bp = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                Page p1 = bp.getPage(1);
                p1.writeRow(new byte[]{7, 7, 7, 7});
                bp.markDirty(1);
                bp.getPage(2);              // 装入页 2
                bp.getPage(3);              // 淘汰页 1（脏）→ 应写回

                a.checkEquals(1, sink.evicted().size(), "[回写] 容量 2 装入第 3 页淘汰 1 页");
                a.checkEquals(Integer.valueOf(1), sink.evicted().get(0), "[回写] 被淘汰的是最久未用的页 1");
                a.checkEquals(2, diskSlotCount(fm, 1),
                        "[回写] 脏页 1 被淘汰时已写回磁盘（槽数 2 = 建页时的标记行 + 新写的一行）");
                a.checkEquals(true, Arrays.equals(new byte[]{7, 7, 7, 7}, fm.readPage(1).readRow(1)),
                        "[回写] 写回的是页 1 自己的内容，不是别的页的（防写错 Victim）");
                a.checkEquals(true, Arrays.equals(marker(1), fm.readPage(1).readRow(0)),
                        "[回写] 写回后页 1 仍带着自己的标记行（标记与页号一致）");

                // 其它页不能被顺手写脏
                a.checkEquals(1, diskSlotCount(fm, 2), "[回写] 未修改的页 2 未被改动");
                a.checkEquals(1, diskSlotCount(fm, 3), "[回写] 未修改的页 3 未被改动");

                // （2）干净页被淘汰不产生写回：磁盘内容保持原样
                sink.clear();
                File dir2 = TestFiles.tempDir("cache-writeback-clean");
                try {
                    FileManager fm2 = fileWithPages(dir2, "wb2.dat", 4);
                    BufferPool clean = new BufferPool(1, BufferPool.Strategy.LRU, fm2);
                    clean.getPage(1);       // 只读，不标脏
                    clean.getPage(2);       // 淘汰干净的页 1
                    a.checkEquals(1, sink.evicted().size(), "[回写] 干净页被淘汰");
                    a.checkEquals(0, clean.getDirtyCount(), "[回写] 干净页淘汰后脏页集合为空");
                } finally {
                    TestFiles.deleteRecursively(dir2);
                }

                // （3）flushAll 写出所有驻留的脏页，且写完清空脏标记
                File dir3 = TestFiles.tempDir("cache-writeback-flush");
                try {
                    FileManager fm3 = fileWithPages(dir3, "wb3.dat", 6);
                    BufferPool fl = new BufferPool(6, BufferPool.Strategy.LRU, fm3);
                    for (int id = 1; id <= 3; id++) {
                        Page p = fl.getPage(id);
                        p.writeRow(new byte[]{(byte) id, (byte) id});
                        fl.markDirty(id);
                    }
                    a.checkEquals(3, fl.getDirtyCount(), "[回写] 3 页被标记为脏");
                    fl.flushAll();
                    a.checkEquals(0, fl.getDirtyCount(), "[回写] flushAll 后脏页集合清空");
                    boolean allWritten = true;
                    for (int id = 1; id <= 3; id++) {
                        Page disk = fm3.readPage(id);
                        if (disk.getSlotCount() != 2) {
                            allWritten = false;
                        }
                    }
                    a.checkTrue(allWritten, "[回写] flushAll 把 3 个脏页全部落盘（槽数各 2）");
                    a.checkEquals(1, diskSlotCount(fm3, 4), "[回写] flushAll 不碰未标脏的页 4");
                } finally {
                    TestFiles.deleteRecursively(dir3);
                }

                // （4）flushPage 只写指定的一页
                File dir4 = TestFiles.tempDir("cache-writeback-page");
                try {
                    FileManager fm4 = fileWithPages(dir4, "wb4.dat", 4);
                    BufferPool one = new BufferPool(4, BufferPool.Strategy.LRU, fm4);
                    for (int id = 1; id <= 2; id++) {
                        Page p = one.getPage(id);
                        p.writeRow(new byte[]{(byte) id});
                        one.markDirty(id);
                    }
                    one.flushPage(1);
                    a.checkEquals(1, one.getDirtyCount(), "[回写] flushPage 后只剩 1 页脏");
                    a.checkEquals(2, diskSlotCount(fm4, 1), "[回写] flushPage(1) 把页 1 落盘");
                    a.checkEquals(1, diskSlotCount(fm4, 2), "[回写] flushPage(1) 不写页 2");
                } finally {
                    TestFiles.deleteRecursively(dir4);
                }

                // （5）调用方失误的边界：页已被淘汰，调用方还拿着旧 Page 引用去改、再补标脏。
                //     旧行为是 markDirty 照单全收（脏标记记在一个按页号命名的集合里），
                //     而 flushAll 只写驻留的脏页——于是这次改动**无声无息地消失**：
                //     调用方以为标脏成功，实际永远不落盘，没有任何提示。
                //     现在脏标记挂在链表节点上，"脏但不在缓存里"结构上无法表示，
                //     markDirty 只能拒绝并记一条警告，把静默丢失变成可观测的异常。
                File dir5 = TestFiles.tempDir("cache-writeback-stale");
                try {
                    FileManager fm5 = fileWithPages(dir5, "wb5.dat", 4);
                    BufferPool st = new BufferPool(1, BufferPool.Strategy.LRU, fm5);
                    Page held = st.getPage(1);      // 拿到页 1 的引用
                    st.getPage(2);                  // 页 1 被淘汰（当时不脏，未写回）
                    a.checkFalse(st.isResident(1), "[回写] 页 1 已被淘汰");

                    sink.clear();
                    st.markDirty(1);                // 页不在缓存中：必须被拒绝，且报警告
                    a.checkEquals(0, st.getDirtyCount(), "[回写] 对非驻留页 markDirty 被忽略，脏页数仍为 0");
                    a.checkTrue(sink.anyContains("不在缓存中"),
                            "[回写] 对非驻留页 markDirty 会记一条警告（不再是静默丢弃）");

                    held.writeRow(new byte[]{9, 9}); // 改一个已经不在缓存里的页
                    st.flushAll();
                    a.checkEquals(1, diskSlotCount(fm5, 1),
                            "[回写] 已被淘汰的页不会被 flushAll 写盘（改它无效，但这次调用方收到了警告）");
                    a.checkEquals(0, st.getDirtyCount(), "[回写] flushAll 后脏页数归零");

                    // 页重新装入缓存后再标脏就正常了：拒绝只针对"不在缓存中"这一种情形
                    sink.clear();
                    a.checkFalse(st.isResident(1), "[回写] 页 1 仍未驻留");
                    st.getPage(1);                  // 容量 1：页 1 重新装载（换出页 2）
                    a.checkTrue(st.isResident(1), "[回写] 页 1 重新驻留");
                    sink.clear();                   // 上面这次换页会记一条"淘汰页 2"，与本条无关
                    st.markDirty(1);
                    a.checkEquals(1, st.getDirtyCount(), "[回写] 页回到缓存后 markDirty 正常生效");
                    a.checkEquals(0, sink.size(), "[回写] 正常路径下不产生任何日志（更没有警告）");
                } finally {
                    TestFiles.deleteRecursively(dir5);
                }

                // （6）整批脏页在淘汰与 flushAll 混合发生时，磁盘上每一页都必须还是"自己的"内容。
                //     每页建页时写的标记行就是该页的页号，因此这一条能一次性抓住
                //     "把 victim 的字节写到错误的页偏移"这类张冠李戴的写回错误。
                File dir6 = TestFiles.tempDir("cache-writeback-batch");
                try {
                    FileManager fm6 = fileWithPages(dir6, "wb6.dat", 8);
                    sink.clear();
                    BufferPool batch = new BufferPool(2, BufferPool.Strategy.LRU, fm6);
                    for (int id = 1; id <= 8; id++) {
                        Page p = batch.getPage(id);
                        p.writeRow(new byte[]{(byte) id});
                        batch.markDirty(id);
                    }
                    batch.flushAll();
                    a.checkEquals(6, sink.evicted().size(),
                            "[回写] 容量 2 逐个访问 8 页共淘汰 6 页（其余 2 页靠 flushAll 落盘）");
                    a.checkEquals(join(batch.getEvictions()), join(sink.evicted()),
                            "[回写] getEvictions() 与日志文本给出的淘汰序列一致");
                    a.checkEquals(6L, batch.getEvictionCount(), "[回写] getEvictionCount() 计数 6 次");
                    a.checkEquals("[7, 8]", join(batch.getResidentPages()),
                            "[回写] 淘汰 6 页后驻留的是最后两页，且队头 7 是下一个被淘汰的");
                    a.checkEquals(0, batch.getDirtyCount(), "[回写] flushAll 后无残留脏页");
                    StringBuilder bad = new StringBuilder();
                    for (int id = 1; id <= 8; id++) {
                        Page disk = fm6.readPage(id);
                        if (disk.getSlotCount() != 2 || !Arrays.equals(marker(id), disk.readRow(0))) {
                            bad.append(" 页").append(id).append(":槽数").append(disk.getSlotCount());
                        }
                    }
                    a.check(bad.length() == 0,
                            "[回写] 8 个脏页各自落回自己的位置，标记行与页号一致" + bad);
                } finally {
                    TestFiles.deleteRecursively(dir6);
                }
            } finally {
                BufferPool.setLogSink(null);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 五、回写与空闲页链的一致性 =================

    /**
     * 端到端一致性：真实引擎在"插入 -> 全删 -> 重插 -> 再全删 -> 重开文件"循环里，
     * 空闲页链表不得被缓冲池的回写作废。这是缓存机制里唯一会**永久损坏文件**的失效模式，
     * 所以用真实路径（{@link StorageEngine}）而不是裸 {@link BufferPool} 来压。
     *
     * <p>空闲页的页头 {@code nextPageId} 存的是空闲链的后继；而缓冲池里可能还留着该页**被释放之前**
     * 的陈旧镜像（当时的 {@code nextPageId} = -1）。若重写表的第一步把这些空闲页也 clear + markDirty，
     * 随后的 flushAll 就把 -1 写回磁盘，空闲链从该页起整段截断。
     *
     * <p>实测：13 个空闲页在一次 DELETE 之后只剩 2 个，其余 11 页永久泄漏——{@code freePage} 是幂等的，
     * 补不回来；而重启后它们不在链上，会被当成活页，再也不会被复用（文件随增删循环无上界增长）。
     */
    private static void sectionCoherence(Assert a) {
        File dir = TestFiles.tempDir("cache-coherence");
        try {
            final List<ColumnDef> schema = Arrays.asList(new ColumnDef("a", ColumnType.VARCHAR, 200));
            final String v = repeat('x', 185);
            // 行数刻意取到让数据页数**超过缓冲池默认容量 16**：只有超过，重写表的第一步
            // （升序 clear 每一页）才会真的触发淘汰，把"淘汰脏页"和"绕过缓存释放页"这两条
            // 通路同时压上。页数不够时这一步全走缓存命中，等于漏测。
            final int rows = 400;
            StorageEngine se = new StorageEngine(dir.getAbsolutePath());
            se.createTable("t", schema);
            for (int i = 0; i < rows; i++) {
                se.insertRow("t", Arrays.asList(v + i));
            }
            int dataPages = pageCountOf(dir, "t.dat") - 1;
            a.checkTrue(dataPages > Constants.DEFAULT_BUFFER_SIZE,
                    "[一致性] 数据页数 " + dataPages + " 超过缓冲池默认容量 "
                            + Constants.DEFAULT_BUFFER_SIZE + "（否则淘汰通路漏测）");

            // 引擎层验证 AUTO 对外报告的是**解析后的真实策略**（不是把 AUTO 原样漏出来）。
            // 注意别把这条读成"扫描触发了切换"：实测池在**插入阶段**（第 ~100 行、页号升序
            // 增长到填满池）就已经切到 FIFO 了，插入前是 LRU、插入后是 FIFO，全表扫描前后
            // 完全一样（[FIFO] -> 扫描 -> [FIFO]）。所以这里钉的是切换后的**终态**，
            // 与"引擎是否逐页经过缓冲池"无关——后者由 InterfaceTest 第四节的统计增量判别。
            a.checkEquals(rows, se.scanTable("t").size(), "[一致性] 全表扫描返回全部 " + rows + " 行");
            a.checkEquals(Arrays.asList(BufferPool.Strategy.FIFO), se.activeStrategies(),
                    "[一致性] 引擎内的 AUTO 池对外报告解析后的真实策略（此处为 FIFO）");

            // 第一次全删：所有数据页挂上空闲链
            a.checkEquals(rows, se.deleteRows("t", null), "[一致性] 第一次 DELETE 报告删除 " + rows + " 行");
            a.checkEquals(dataPages, freeChainLength(dir, "t.dat"),
                    "[一致性] 第一次 DELETE 后全部数据页都在空闲链上");

            // 只插 2 行：复用 1 页，其余空闲页仍留在链上（缓冲池里也还留着它们的陈旧镜像）
            for (int i = 0; i < 2; i++) {
                se.insertRow("t", Arrays.asList(v + i));
            }
            a.checkEquals(dataPages - 1, freeChainLength(dir, "t.dat"),
                    "[一致性] 只复用 1 页后，其余空闲页仍在链上");

            // 第二次全删：这一步曾把空闲链截断（13 页掉到 2 页）
            a.checkEquals(2, se.deleteRows("t", null), "[一致性] 第二次 DELETE 报告删除 2 行");
            a.checkEquals(dataPages, freeChainLength(dir, "t.dat"),
                    "[一致性] 带着既有空闲页再 DELETE 一次，空闲链不被截断（回归：曾从 13 页掉到 2 页）");
            a.checkEquals(0, se.scanTable("t").size(), "[一致性] 两次 DELETE 后表为空");

            // 空闲页不能丢：重开文件（从磁盘重建空闲链）后，插入同样多的行不应让文件变大。
            // 链被截断时这一步会露馅——只有 2 页可复用，其余十几页得往文件末尾追加。
            StorageEngine reopened = new StorageEngine(dir.getAbsolutePath());
            reopened.registerSchema("t", schema);
            for (int i = 0; i < rows; i++) {
                reopened.insertRow("t", Arrays.asList(v + i));
            }
            a.checkEquals(rows, reopened.scanTable("t").size(),
                    "[一致性] 重开后插入的 " + rows + " 行全部可扫出");
            a.checkEquals(dataPages + 1, pageCountOf(dir, "t.dat"),
                    "[一致性] 空闲页未泄漏：重开后插入同样多的行，文件页数不增长");
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 六、绕过缓存的页改写（作废副本） =================

    /**
     * {@code FileManager} 有几个操作**绕过页缓存直接改写磁盘**：释放页、复用空闲页、
     * 改写第 0 页的空闲链头。这些页在池里可能还留着陈旧副本，若被回写就会覆盖刚落盘的链指针。
     *
     * <p>实测过的后果：一次 DELETE 释放 13 个数据页，第二次 DELETE 时池把自己手里的
     * 陈旧副本（{@code nextPageId = -1}）写回磁盘，空闲链从该页起整段截断——
     * 13 个空闲页只剩 2 个，其余 11 页永久泄漏（{@code freePage} 幂等，补不回来）。
     *
     * <p>修法是 {@link FileManager} 在改写页之后发一条页变更通知，池把副本**丢弃**（不回写），
     * 因为此时磁盘才是权威。本节逐条检查这条通路。
     */
    private static void sectionInvalidate(Assert a) {
        LogSink sink = new LogSink();
        BufferPool.setLogSink(sink);
        try {
            // （1）页被释放后，池里的副本必须消失，再读拿到的是清空后的磁盘内容
            File dir1 = TestFiles.tempDir("cache-invalidate-free");
            try {
                FileManager fm = fileWithPages(dir1, "inv1.dat", 6);
                BufferPool bp = new BufferPool(8, BufferPool.Strategy.LRU, fm);
                a.checkTrue(bp.getPage(5) != null, "[作废] 页 5 可读");
                a.checkTrue(bp.isResident(5), "[作废] 页 5 已驻留");
                a.checkEquals(1, diskSlotCount(fm, 5), "[作废] 页 5 磁盘上有 1 行标记");

                fm.freePage(5);
                a.checkFalse(bp.isResident(5), "[作废] 页被释放后，池里的副本立即消失");
                a.checkEquals(0, bp.getDirtyCount(), "[作废] 被作废的副本不产生脏页");

                sink.clear();
                Page cleared = bp.getPage(5);
                a.checkTrue(cleared != null, "[作废] 释放后仍能按页号读到该页");
                a.checkEquals(0, cleared.getSlotCount(),
                        "[作废] 读到的是释放时清空后的内容，不是释放前的陈旧镜像");
                a.checkEquals(0, sink.evicted().size(), "[作废] 作废不是淘汰，不产生淘汰日志");
                a.checkEquals(0L, bp.getEvictionCount(), "[作废] 作废不增加淘汰次数（作废不是替换）");
            } finally {
                TestFiles.deleteRecursively(dir1);
            }

            // （2）空闲页被复用后，池必须重新读盘，而不是把释放前的旧对象原样还回去
            File dir2 = TestFiles.tempDir("cache-invalidate-reuse");
            try {
                FileManager fm = fileWithPages(dir2, "inv2.dat", 6);
                BufferPool bp = new BufferPool(8, BufferPool.Strategy.LRU, fm);
                Page stale = bp.getPage(6);
                stale.writeRow(new byte[]{1, 2, 3});
                a.checkEquals(2, stale.getSlotCount(),
                        "[作废] 页 6 在池里被写过一行（建页时还有 1 行标记，共 2 行）");

                fm.freePage(6);
                a.checkFalse(bp.isResident(6), "[作废] 页 6 释放后副本消失");
                byte[] asFree = fm.readPage(6).getRawData().clone();
                a.checkEquals(6, fm.allocatePage(), "[作废] 空闲页按 LIFO 复用，重新分配到页 6");
                // 复用只改写第 0 页的链头，**不碰被复用的那一页**。所以"复用后的磁盘内容"
                // 与"释放后的磁盘内容"逐字节相同，而释放时已经发过通知——
                // 池在任何时刻能持有的副本都不会比这更新，也就不可能出现陈旧镜像。
                // （allocatePage 里那条通知因此是纵深防御：它保证的不变式是"凡绕过缓存写过的页都通知"，
                //   而不是"这一刻非通知不可"。这条断言把这个前提钉住：若将来复用开始改写页内容，
                //   它会立刻失败，提醒那句通知从此变成必需的。）
                a.checkEquals(true, Arrays.equals(asFree, fm.readPage(6).getRawData()),
                        "[作废] 复用空闲页不改写被复用页的字节（故 freePage 的通知已足够）");

                Page fresh = bp.getPage(6);
                a.checkFalse(fresh == stale,
                        "[作废] 复用后读到的是重新读盘的新对象（没有作废就会把陈旧对象原样还回去）");
                a.checkEquals(0, fresh.getSlotCount(),
                        "[作废] 复用后拿到的是清空页，看不到释放前写的那一行");
            } finally {
                TestFiles.deleteRecursively(dir2);
            }

            // （3）第 0 页：空闲链头变了，池里缓存的副本必须刷新
            File dir3 = TestFiles.tempDir("cache-invalidate-meta");
            try {
                FileManager fm = fileWithPages(dir3, "inv3.dat", 6);
                BufferPool bp = new BufferPool(8, BufferPool.Strategy.LRU, fm);
                Page head0 = bp.getPage(0);
                a.checkEquals(-1, head0.getNextPageId(), "[作废] 初始空闲链为空");

                fm.freePage(4);         // 链头变成 4，第 0 页被绕过缓存改写
                a.checkFalse(bp.isResident(0), "[作废] 第 0 页被改写后副本立即消失");

                Page head1 = bp.getPage(0);
                a.checkFalse(head1 == head0, "[作废] 第 0 页重新读盘（不是同一个对象）");
                a.checkEquals(4, head1.getNextPageId(),
                        "[作废] 读到的是新的空闲链头 4，不是缓存的旧值 -1");
            } finally {
                TestFiles.deleteRecursively(dir3);
            }

            // （4）被作废的副本若是脏的：只丢弃、不回写，并且记一条警告
            File dir4 = TestFiles.tempDir("cache-invalidate-dirty");
            try {
                FileManager fm = fileWithPages(dir4, "inv4.dat", 6);
                BufferPool bp = new BufferPool(8, BufferPool.Strategy.LRU, fm);
                Page p3 = bp.getPage(3);
                p3.writeRow(new byte[]{7, 7, 7, 7});
                bp.markDirty(3);
                a.checkEquals(1, bp.getDirtyCount(), "[作废] 页 3 已标脏");

                sink.clear();
                fm.freePage(3);
                a.checkEquals(0, bp.getDirtyCount(), "[作废] 脏副本被作废，脏页数归零");
                a.checkEquals(0, diskSlotCount(fm, 3),
                        "[作废] 作废不回写：磁盘上是释放时清空的内容，不是缓存里的脏内容");
                a.checkTrue(sink.anyContains("丢弃未落盘的脏页副本"),
                        "[作废] 丢弃脏副本会记警告（说明调用方漏了 flushAll，不再静默）");
            } finally {
                TestFiles.deleteRecursively(dir4);
            }

            // （5）同一 FileManager 上的多个池都要收到通知，且池被回收后不影响后续通知
            File dir5 = TestFiles.tempDir("cache-invalidate-multi");
            try {
                FileManager fm = fileWithPages(dir5, "inv5.dat", 6);
                BufferPool p1 = new BufferPool(4, BufferPool.Strategy.LRU, fm);
                BufferPool p2 = new BufferPool(4, BufferPool.Strategy.FIFO, fm);
                p1.getPage(2);
                p2.getPage(2);
                a.checkTrue(p1.isResident(2) && p2.isResident(2), "[作废] 两个池都缓存了页 2");

                fm.freePage(2);
                a.checkFalse(p1.isResident(2), "[作废] 第 1 个池收到通知并作废副本");
                a.checkFalse(p2.isResident(2), "[作废] 第 2 个池同样收到通知");

                // 监听器只收弱引用：池被回收后通知循环不能出错
                BufferPool dropped = new BufferPool(4, BufferPool.Strategy.LRU, fm);
                dropped.getPage(3);
                dropped = null;
                System.gc();
                fm.freePage(3);
                a.checkTrue(p1.getPage(1) != null, "[作废] 有池被回收后，后续通知与访问仍正常");
            } finally {
                TestFiles.deleteRecursively(dir5);
            }

            // （6）对不在缓存中的页调用 invalidate 是空操作（第 0 页每次释放/复用都会被通知，
            //     而它通常并不驻留，所以这条通路必须能容忍"本来就没有副本"）
            File dir6 = TestFiles.tempDir("cache-invalidate-noop");
            try {
                FileManager fm = fileWithPages(dir6, "inv6.dat", 4);
                BufferPool bp = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                bp.getPage(1);
                sink.clear();
                bp.invalidate(9999);        // 越界页号
                bp.invalidate(-5);          // 负数页号
                bp.invalidate(3);           // 合法但未驻留
                a.checkEquals(0, sink.size(), "[作废] 对未驻留页作废是空操作，不产生任何日志");
                a.checkEquals(1, bp.getResidentPages().size(), "[作废] 空操作不影响已有驻留页");
                a.checkTrue(bp.getPage(1) != null && bp.getHitCount() == 1,
                        "[作废] 空操作后原有副本仍可命中");
            } finally {
                TestFiles.deleteRecursively(dir6);
            }
        } finally {
            BufferPool.setLogSink(null);
        }
    }

    // ================= 七、边界 =================

    /** 容量 1 / 0、越界页号、元数据页 0、以及同一页反复取用的一致性。 */
    private static void sectionEdge(Assert a) {
        File dir = TestFiles.tempDir("cache-edge");
        try {
            FileManager fm = fileWithPages(dir, "edge.dat", FILE_PAGES);
            LogSink sink = new LogSink();
            BufferPool.setLogSink(sink);
            try {
                // 容量 1：任何一次换页都淘汰上一页
                BufferPool one = new BufferPool(1, BufferPool.Strategy.LRU, fm);
                RefCache ref = new RefCache(1, true);
                int[] seq = {1, 1, 2, 2, 1, 3, 3};
                replay(a, "[边界] 容量1", one, ref, seq);
                a.checkEquals(join(ref.evicted()), join(sink.evicted()), "[边界] 容量 1 的淘汰序列");

                // 容量 0：什么都留不住，每次访问都是未命中（退化但不应崩溃/死循环）
                sink.clear();
                BufferPool zero = new BufferPool(0, BufferPool.Strategy.LRU, fm);
                boolean allMiss = true;
                for (int i = 0; i < 5; i++) {
                    int before = zero.getHitCount();
                    if (zero.getPage(1) == null || zero.getHitCount() > before) {
                        allMiss = false;
                    }
                }
                a.checkTrue(allMiss, "[边界] 容量 0 时每次访问都是未命中");
                a.checkEquals(0, zero.getHitCount(), "[边界] 容量 0 的命中数为 0");

                // 越界页号：返回 null、不缓存、不崩溃。
                // **不计入未命中**：什么都没从磁盘加载进来，计进去只会平白拉低命中率
                // （旧实现计入未命中，还能把 AUTO 的顺序判定带偏）。
                BufferPool oob = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                a.checkEquals(null, oob.getPage(FILE_PAGES + 5), "[边界] 越界页号返回 null");
                a.checkEquals(null, oob.getPage(-1), "[边界] 负数页号返回 null");
                a.checkEquals(0, oob.getMissCount(), "[边界] 越界页号不计入未命中（没从磁盘读进任何东西）");
                a.checkEquals(null, oob.getPage(FILE_PAGES + 5), "[边界] 越界页号不会在缓存里留下条目");
                a.checkEquals(0, oob.getHitCount(), "[边界] 越界页号不产生命中");
                a.checkEquals(0, oob.getResidentPages().size(), "[边界] 越界访问后缓存仍为空");
                a.checkEquals(0.0, oob.hitRate(), "[边界] 只有越界访问时命中率为 0（分母也是 0）");
                // 越界访问夹在正常访问之间，也不该影响命中/未命中的计数
                a.checkEquals(true, oob.getPage(1) != null, "[边界] 越界后正常页仍可访问");
                oob.getPage(9999);
                a.checkEquals(1, oob.getMissCount(), "[边界] 越界访问不改变未命中计数（仍为 1）");
                a.checkEquals(1, oob.getResidentPages().size(), "[边界] 越界访问不挤占缓存");

                // 元数据页 0：页号 0 是合法页，可以缓存，也可以命中
                BufferPool meta = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                Page m0 = meta.getPage(0);
                a.checkEquals(true, m0 != null, "[边界] 元数据页 0 可读");
                meta.getPage(0);
                a.checkEquals(1, meta.getHitCount(), "[边界] 元数据页 0 可被缓存并命中");
                a.checkEquals("[0]", join(meta.getResidentPages()), "[边界] 元数据页 0 与其他页一样驻留");

                // 反复取同一页：命中数线性增长，返回的应是同一个 Page 对象（缓存命中不重新读盘）
                BufferPool rep = new BufferPool(2, BufferPool.Strategy.LRU, fm);
                Page first = rep.getPage(3);
                Page again = rep.getPage(3);
                a.checkTrue(first == again, "[边界] 命中时返回缓存中的同一个 Page 实例");
                a.checkEquals(1, rep.getHitCount(), "[边界] 反复取同一页累计命中 1 次");
            } finally {
                BufferPool.setLogSink(null);
            }
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ================= 工具 =================

    /**
     * 参考模型：教科书 LRU / FIFO，用来给出固定序列的期望命中轨迹与期望驻留集合。
     *
     * <p>队首（下标 0）= 最久未使用（LRU）/ 最早插入（FIFO），队尾 = 最新。
     * LRU 命中后把该页移到队尾；FIFO 命中不改变次序。淘汰永远取队首。
     */
    private static final class RefCache {
        private final int capacity;
        private final boolean lru;
        private final List<Integer> order = new ArrayList<>();
        private final List<Integer> evicted = new ArrayList<>();
        private int hits;
        private int misses;

        RefCache(int capacity, boolean lru) {
            this.capacity = capacity;
            this.lru = lru;
        }

        /** 访问一页，返回是否命中；未命中时按需淘汰队首。 */
        boolean access(int pageId) {
            int i = order.indexOf(pageId);
            if (i >= 0) {
                hits++;
                if (lru) {
                    order.remove(i);
                    order.add(pageId);
                }
                return true;
            }
            misses++;
            order.add(pageId);
            if (order.size() > capacity) {
                evicted.add(order.remove(0));
            }
            return false;
        }

        int hits() {
            return hits;
        }

        int misses() {
            return misses;
        }

        List<Integer> evicted() {
            return evicted;
        }

        List<Integer> resident() {
            return new ArrayList<>(order);
        }
    }

    /**
     * 捕获 BufferPool 日志文本的出口。
     *
     * <p>淘汰与策略切换有正式接口（{@code getEvictions()} / {@code getSwitchHistory()}），
     * 断言以接口为准；这里解析日志文本只为了守住**界面展示契约**——GUI 直接显示这两句，
     * 文本格式变了界面就瞎了。另外"警告"类事件（markDirty 被忽略、脏副本被作废）没有查询接口，
     * 只能从这里观测。
     */
    private static final class LogSink implements Consumer<String> {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void accept(String message) {
            lines.add(message);
        }

        void clear() {
            lines.clear();
        }

        /** 已淘汰的页号（按发生顺序）。 */
        List<Integer> evicted() {
            List<Integer> ids = new ArrayList<>();
            for (String s : lines) {
                if (s.startsWith(EVICT_PREFIX)) {
                    ids.add(Integer.valueOf(s.substring(EVICT_PREFIX.length()).trim()));
                }
            }
            return ids;
        }

        /** 策略切换的目标（按发生顺序），如 "FIFO" / "LRU"。 */
        List<String> switches() {
            List<String> out = new ArrayList<>();
            for (String s : lines) {
                int i = s.indexOf(SWITCH_PREFIX);
                if (i >= 0) {
                    out.add(s.substring(i + SWITCH_PREFIX.length()).trim());
                }
            }
            return out;
        }

        /** 是否有日志行包含给定片段（用于校验警告类事件）。 */
        boolean anyContains(String fragment) {
            for (String s : lines) {
                if (s.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        /** 现有的日志行数。 */
        int size() {
            return lines.size();
        }
    }

    private static final String EVICT_PREFIX = "[BufferPool] 淘汰页 ";
    private static final String SWITCH_PREFIX = "切换策略 -> ";

    /** 建一个含 n 个数据页的数据文件，每页写入 1 行 4 字节标记（标记值 = 页号）。 */
    private static FileManager fileWithPages(File dir, String name, int n) {
        FileManager fm = new FileManager(dir.getAbsolutePath(), name);
        fm.init();
        for (int i = 0; i < n; i++) {
            int id = fm.allocatePage();
            Page p = fm.readPage(id);
            p.writeRow(marker(id));
            fm.writePage(p);
        }
        return fm;
    }

    /** 页标记：页号的大端 4 字节，用于核对"写回的是不是这一页自己的内容"。 */
    private static byte[] marker(int pageId) {
        return new byte[]{(byte) (pageId >>> 24), (byte) (pageId >>> 16), (byte) (pageId >>> 8), (byte) pageId};
    }

    /** 直接从磁盘读某页的槽数（绕过缓冲池）。 */
    private static int diskSlotCount(FileManager fm, int pageId) {
        Page p = fm.readPage(pageId);
        return p == null ? -1 : p.getSlotCount();
    }

    /** 数据文件当前的页数（按文件长度推算，绕过 FileManager 的内存快照）。 */
    private static int pageCountOf(File dir, String fileName) {
        return (int) (new File(dir, fileName).length() / Constants.PAGE_SIZE);
    }

    /**
     * 磁盘上空闲页链的长度（直接读文件，绕过缓冲池与 FileManager 的内存状态）。
     * 负数表示链有环、重复或越界（返回 -已走过的页数），-1 表示读失败。
     */
    private static int freeChainLength(File dir, String fileName) {
        File f = new File(dir, fileName);
        if (!f.exists()) {
            return -1;
        }
        int pages = (int) (f.length() / Constants.PAGE_SIZE);
        byte[] buf = new byte[Constants.PAGE_SIZE];
        Set<Integer> visited = new HashSet<>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(buf);
            int p = readInt(buf, 16);       // 第 0 页 nextPageId = 空闲链头
            while (p != -1) {
                if (p <= 0 || p >= pages || !visited.add(p)) {
                    return -visited.size();
                }
                raf.seek((long) p * Constants.PAGE_SIZE);
                raf.readFully(buf);
                p = readInt(buf, 16);
            }
        } catch (Exception e) {
            return -1;
        }
        return visited.size();
    }

    /** 读大端 4 字节整数。 */
    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 生成 n 个字符 c 组成的字符串。 */
    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** 序列里出现过多少个不同的页号（用于推算期望淘汰次数）。 */
    private static int distinct(int[] seq) {
        List<Integer> seen = new ArrayList<>();
        for (int v : seq) {
            if (!seen.contains(v)) {
                seen.add(v);
            }
        }
        return seen.size();
    }

    /** 把页号列表渲染成 "[1, 2, 3]"，便于断言失败时直接读出期望与实际。 */
    private static String join(List<Integer> ids) {
        return ids.toString();
    }

    /** 页号数组的同款渲染，供写死期望值的断言使用。 */
    private static String join(int[] ids) {
        return Arrays.toString(ids);
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
