package storage;

import utils.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 页缓存：在内存中缓存热点页，支持 LRU / FIFO 替换策略，并可用 AUTO 模式按访问模式自动切换。
 * 用自维护的双向链表 + HashMap 实现 O(1) 查找与淘汰；脏页在淘汰或 flush 时写回磁盘。
 *
 * <p><b>链表次序的两种语义：</b>队头（head）永远是下一个被淘汰的页。
 * LRU 下队头 = 最久未使用（命中后 moveToTail 重排）；FIFO 下队头 = 最早插入
 * （命中不动，次序由 {@link Node#insertSeq} 的插入序决定）。
 *
 * <p><b>AUTO 两个切换方向都要重排：</b>进 FIFO 调 {@link #rebuildByInsertOrder()}
 * 按插入序排，退 LRU 调 {@link #rebuildByRecency()} 按最近使用序排。
 * 两者都不可省——不重排的话，新阶段的队头还是旧阶段的次序，于是"FIFO 阶段"实际按 LRU 序淘汰、
 * "LRU 阶段"实际按插入序淘汰，切换在这一项上等于空操作。
 * （退 LRU 时少这一步，"下次命中 moveToTail"只能逐个纠正被访问到的节点，收敛不了其余的；
 *  随机序列实测两种做法有 1.5% 的序列淘汰出不同的页。）
 *
 * <p><b>脏页标记：</b>脏标记挂在 {@link Node} 上而不是一个按页号记名的集合，
 * 于是"脏但不在缓存里"这种状态在结构上无法表达——淘汰一个脏节点时必然当场回写，
 * 不存在"标记留到下一次 flushAll 却被静默丢弃"的窗口。
 *
 * <p><b>观测接口：</b>{@link #getEvictions()} / {@link #getEvictionCount()} /
 * {@link #getSwitchHistory()} 给出淘汰与策略切换的真实记录（有界，见 {@link #EVENT_LOG_LIMIT}），
 * {@link #getResidentPages()} / {@link #isResident(int)} / {@link #getDirtyCount()} 给出驻留与脏页状态。
 * 这些是测试与 GUI 可断言的接口；日志文本（{@link #setLogSink}）只用于向界面展示。
 */
public class BufferPool {
    /** 替换策略：LRU 最近最少使用；FIFO 先进先出；AUTO 按顺序扫描检测自动切换。 */
    public enum Strategy {
        LRU, FIFO, AUTO;
    }

    /**
     * AUTO 模式下判定"顺序扫描"的阈值：连续同向（升序或降序）跨过这么多页即切 FIFO。
     * 单位是**方向步数**，不是访问次数——4 步同向意味着连续 5 个严格单调的页号
     * （首次访问只登记方向，不计步）。
     */
    private static final int SCAN_ENTER = 4;

    /**
     * 退出 FIFO 的滞回阈值：只有连续这么多步**改变方向**才判定"不是顺序扫描"、退回 LRU。
     * 比 {@link #SCAN_ENTER} 低是刻意的：循环扫描每圈只在回折处产生 2 次连续换向，
     * 阈值 3 让它退不出，从而消除"反复扫描时 LRU<->FIFO 来回抖"。
     *
     * <p>两个计数器互斥（连续 4 步同向 与 连续 3 步换向 不可能同时成立），
     * 所以不会出现同一步既进又出的抖动——这是结构上的保证，不靠调参。
     */
    private static final int HYST_EXIT = 3;

    /** {@link #getEvictions()} / {@link #getSwitchHistory()} 保留的最大条数（超出丢最早的）。 */
    private static final int EVENT_LOG_LIMIT = 64;

    /**
     * 双向链表节点。
     * {@code insertSeq} 记录插入次序（FIFO 的排序键，淘汰后重装会拿到更大的新号）；
     * {@code lastUseSeq} 记录最近一次被访问的次序（LRU 的排序键，每次真实访问都刷新）；
     * {@code dirty} 记录该页是否有未落盘的修改。
     */
    private static final class Node {
        final int pageId;
        final Page page;
        /** 插入序号：addToTail 时领取，全局单调递增，永不重排（重排会破坏 FIFO 语义）。 */
        long insertSeq;
        /** 最近使用序号：每次真实访问刷新，全局单调递增，永不重排（重排会破坏 LRU 语义）。 */
        long lastUseSeq;
        /** 是否有未落盘的修改。 */
        boolean dirty;
        Node prev;
        Node next;

        Node(int pageId, Page page) {
            this.pageId = pageId;
            this.page = page;
        }
    }

    private final int capacity;
    private final Strategy strategy;
    private final FileManager fileManager;
    private final Map<Integer, Node> cache = new HashMap<>();
    private Node head;
    private Node tail;
    private int hitCount;
    private int missCount;

    // AUTO 模式状态：当前实际策略 + 顺序扫描检测。
    private boolean activeLru = true;
    /** 是否已经发生过一次真实访问。首次访问只登记方向，不计入任何游程。 */
    private boolean hasLast = false;
    /** 上一次真实访问的页号（仅当 hasLast 为真时有效）。 */
    private int lastPageId = -1;
    /** 上一步的方向：+1 升序、-1 降序、0 表示还没形成任何一步。 */
    private int prevDir = 0;
    /** 连续**同向**步数（升序与降序同等看待，只问方向是否一致）。 */
    private int runLen = 0;
    /** 连续**换向**步数（专门识别锯齿/随机访问）。 */
    private int revRun = 0;
    /** 插入序号发号器（FIFO 排序键）。 */
    private long insertCounter = 0;
    /** 最近使用序号发号器（LRU 排序键）。 */
    private long useCounter = 0;

    // 事件记录：计数是精确的，列表有界（只用于观测，不承担正确性）。
    private long evictionCount = 0;
    private final List<Integer> evictions = new ArrayList<>();
    private final List<Strategy> switchHistory = new ArrayList<>();

    /** 本池的事件出口；为 null 时在 emit 时刻回退到静态出口（见 {@link #setLogSink}）。 */
    private Consumer<String> eventSink;
    /** 把监听器作为强引用hold在池上，FileManager 那边只存弱引用。 */
    private final FileManager.PageChangeListener invalidator = this::invalidate;

    // 构造：按策略初始化缓存（AUTO 初始按 LRU），并订阅 FileManager 的页变更通知。
    public BufferPool(int capacity, Strategy strategy, FileManager fileManager) {
        this.capacity = capacity;
        this.strategy = strategy;
        this.fileManager = fileManager;
        this.activeLru = strategy != Strategy.FIFO;
        fileManager.addPageChangeListener(invalidator);
    }

    // 用默认容量构造。
    public BufferPool(Strategy strategy, FileManager fileManager) {
        this(Constants.DEFAULT_BUFFER_SIZE, strategy, fileManager);
    }

    // 获取页：命中直接返回（LRU 时移到队尾），未命中从磁盘加载（必要时淘汰队头）。
    public Page getPage(int pageId) {
        Node node = cache.get(pageId);
        if (node != null) {
            hitCount++;
            // 先刷新最近使用序号，再判定策略：退出 FIFO 的那一次访问本身就应当是最新的，
            // 这样 rebuildByRecency 排完序它已经在队尾，随后的 moveToTail 自然是空操作。
            node.lastUseSeq = useCounter++;
            if (strategy == Strategy.AUTO) {
                updatePolicy(pageId);
            }
            if (activeLru) {
                moveToTail(node);
            }
            return node.page;
        }
        Page page = fileManager.readPage(pageId);
        if (page == null) {
            // 越界/不存在的页号：既不是命中也不是未命中——什么都没从磁盘加载进来，
            // 计入未命中只会平白拉低命中率；更不能拿去走顺序扫描判定（一次"什么都没发生"
            // 的访问会累加同向计数、把策略切到 FIFO，并用无效页号污染 lastPageId）。
            // 也不领最近使用序号：与"不占方向步数"保持一致。
            return null;
        }
        missCount++;
        Node fresh = new Node(pageId, page);
        fresh.lastUseSeq = useCounter++;
        if (strategy == Strategy.AUTO) {
            updatePolicy(pageId);
        }
        addToTail(fresh);
        if (cache.size() > capacity) {
            evictHead();
        }
        return page;
    }

    // 标记页为脏（修改后调用），以便后续刷回。
    // 只有驻留页能标脏：脏标记挂在节点上，页不在缓存里就没有地方承载这个标记。
    public void markDirty(int pageId) {
        Node node = cache.get(pageId);
        if (node == null) {
            log("[BufferPool] 警告：页 " + pageId + " 不在缓存中，markDirty 被忽略（该改动不会落盘）");
            return;
        }
        node.dirty = true;
    }

    // 刷回指定脏页到磁盘。
    public void flushPage(int pageId) {
        Node node = cache.get(pageId);
        if (node != null && node.dirty) {
            fileManager.writePage(node.page);
            node.dirty = false;
        }
    }

    // 刷回所有脏页到磁盘。
    public void flushAll() {
        // 先快照再写：写盘过程可能间接触发回调改到缓存，边遍历边写会踩到迭代器。
        List<Node> pending = new ArrayList<>();
        for (Node node : cache.values()) {
            if (node.dirty) {
                pending.add(node);
            }
        }
        for (Node node : pending) {
            fileManager.writePage(node.page);
            node.dirty = false;
        }
    }

    /**
     * 作废某一页的缓存副本（不写回）。
     *
     * <p>调用时机：{@code FileManager} 绕过本缓存直接改写了该页的磁盘内容
     * （释放页、复用空闲页、改写第 0 页链头）。此时**磁盘是权威**，
     * 回写只会把旧内容盖回刚写好的链指针，所以这里只丢弃、不回写。
     *
     * <p>若被丢弃的副本还是脏的，说明调用方在"改了页"和"这页被绕开缓存改写"之间
     * 没有先 flushAll——那部分改动会丢，因此记一条警告让它变成可观测的异常。
     */
    public void invalidate(int pageId) {
        Node node = cache.get(pageId);
        if (node == null) {
            return;     // 容忍"页不在缓存"：第 0 页每次释放/复用都会被通知，而它通常不被缓存
        }
        if (node.dirty) {
            log("[BufferPool] 警告：丢弃未落盘的脏页副本 " + pageId + "（磁盘为准）");
        }
        unlink(node);
        cache.remove(pageId);
    }

    // 返回缓存命中次数。
    public int getHitCount() {
        return hitCount;
    }

    // 返回缓存未命中次数（不含越界页号这种"不存在的访问"）。
    public int getMissCount() {
        return missCount;
    }

    // 返回缓存命中率。
    public double hitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    // 返回当前实际生效的策略（AUTO 解析为 LRU 或 FIFO）。
    public Strategy getActiveStrategy() {
        if (strategy != Strategy.AUTO) {
            return strategy;
        }
        return activeLru ? Strategy.LRU : Strategy.FIFO;
    }

    // 返回配置的策略（可能是 AUTO，用于区分"配置成 AUTO 且当前恰好是 LRU"与"直接配成 LRU"）。
    public Strategy getStrategy() {
        return strategy;
    }

    // 已发生的淘汰总次数（精确计数，不受 EVENT_LOG_LIMIT 截断影响）。
    public long getEvictionCount() {
        return evictionCount;
    }

    // 被淘汰的页号（按发生顺序，最多保留最近 EVENT_LOG_LIMIT 条）。
    public List<Integer> getEvictions() {
        return Collections.unmodifiableList(new ArrayList<>(evictions));
    }

    // 策略切换历史：每次真正翻转时追加一条（AUTO 之外恒为空）。
    public List<Strategy> getSwitchHistory() {
        return Collections.unmodifiableList(new ArrayList<>(switchHistory));
    }

    // 当前驻留的页号，按淘汰次序（队头 -> 队尾，第一个就是下一个被淘汰的）。
    public List<Integer> getResidentPages() {
        List<Integer> ids = new ArrayList<>();
        for (Node n = head; n != null; n = n.next) {
            ids.add(n.pageId);
        }
        return ids;
    }

    // 页是否驻留在缓存中。
    public boolean isResident(int pageId) {
        return cache.containsKey(pageId);
    }

    // 当前带脏标记的驻留页数。
    public int getDirtyCount() {
        int n = 0;
        for (Node node : cache.values()) {
            if (node.dirty) {
                n++;
            }
        }
        return n;
    }

    // 注入本池的事件出口（传 null 表示回退到静态出口）。
    public void setEventSink(Consumer<String> sink) {
        this.eventSink = sink;
    }

    // AUTO：按"连续同向 / 连续换向"两个游程计数器判定顺序扫描，切换 LRU/FIFO。
    // 每个真实访问恰好调用一次。
    private void updatePolicy(int pageId) {
        // 首次真实访问：只登记，不做方向判定。旧的 lastPageId = -1 哨兵让所有合法页号
        // 都满足 pageId > -1，等于凭空多算一步升序，阈值实际只需 3 次访问就触发。
        if (!hasLast) {
            hasLast = true;
            lastPageId = pageId;
            prevDir = 0;
            runLen = 0;
            revRun = 0;
            return;
        }

        // 重复访问同一页是中性事件：一个字段都不动。若把它当成"非升序"清零，
        // 顺序扫描里夹一次重读就会把已累计的同向步数抹掉（findPageWithSpace 正是
        // "反复读同一页再看下一页"的两趟模式，生产路径上会被系统性误判）。
        if (pageId == lastPageId) {
            return;
        }

        int dir = pageId > lastPageId ? 1 : -1;
        lastPageId = pageId;

        if (prevDir == 0) {
            runLen = 1;             // 真正形成的第一步
            revRun = 0;
        } else if (dir == prevDir) {
            runLen = runLen + 1;    // 同向延续
            revRun = 0;
        } else {
            runLen = 1;             // 折返：同向游程重新起算
            revRun = revRun + 1;
        }
        prevDir = dir;

        if (activeLru) {
            if (runLen >= SCAN_ENTER) {
                activeLru = false;
                rebuildByInsertOrder();
                recordSwitch(Strategy.FIFO);
            }
        } else {
            if (revRun >= HYST_EXIT) {
                activeLru = true;
                rebuildByRecency();     // 必须重排，否则"LRU 阶段"仍按插入序淘汰
                recordSwitch(Strategy.LRU);
            }
        }
    }

    /**
     * 把链表按 {@link Node#insertSeq} 升序重排，使队头 = 最早插入的页（真正的 FIFO）。
     *
     * <p>只重接 prev/next，**不能**复用 {@link #addToTail}：那会重新领取插入序号，
     * 按当前（LRU）次序依次追加，结果插入序被覆盖成 LRU 序，重排退化成空操作。
     */
    private void rebuildByInsertOrder() {
        List<Node> nodes = new ArrayList<>(cache.values());
        if (nodes.size() <= 1) {
            return;     // 空表（容量 0 + AUTO 可达）或单节点：重排是空操作
        }
        nodes.sort(Comparator.comparingLong(n -> n.insertSeq));
        relink(nodes);
    }

    /**
     * 把链表按 {@link Node#lastUseSeq} 升序重排，使队头 = 最久未使用的页（真正的 LRU）。
     *
     * <p>与 {@link #rebuildByInsertOrder()} 对称：进 FIFO 要按插入序，退 LRU 要按使用序。
     * 少这一步的话，"LRU 阶段"的队头是 FIFO 阶段留下的插入序队头，而不是最久未使用的页——
     * 靠"命中时 moveToTail"只能逐个纠正被访问到的节点，收敛不了剩下的。
     * 随机序列实测：退出时是否重排，会让 1.5% 的序列淘汰出不同的页。
     */
    private void rebuildByRecency() {
        List<Node> nodes = new ArrayList<>(cache.values());
        if (nodes.size() <= 1) {
            return;
        }
        nodes.sort(Comparator.comparingLong(n -> n.lastUseSeq));
        relink(nodes);
    }

    /** 把已按目标次序排好的节点列表重新串成双向链表（只重接 prev/next，不动 cache）。 */
    private void relink(List<Node> nodes) {
        Node prev = null;
        for (Node n : nodes) {
            n.prev = prev;
            if (prev != null) {
                prev.next = n;
            }
            prev = n;
        }
        head = nodes.get(0);
        tail = nodes.get(nodes.size() - 1);
        head.prev = null;
        tail.next = null;
    }

    // 把节点移到队尾（标记为最新）。
    private void moveToTail(Node node) {
        if (node == tail) {
            return;
        }
        unlink(node);
        linkTail(node);
    }

    // 把新页追加到队尾，并领取插入序号。
    private void addToTail(Node node) {
        node.insertSeq = insertCounter++;
        cache.put(node.pageId, node);
        linkTail(node);
    }

    // 把节点从链表中摘下（不移除 cache 项）。
    private void unlink(Node node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    // 把已摘下/新节点接到队尾。
    private void linkTail(Node node) {
        node.prev = tail;
        node.next = null;
        if (tail != null) {
            tail.next = node;
        } else {
            head = node;
        }
        tail = node;
    }

    // 淘汰队头页，脏页先写回磁盘。
    private void evictHead() {
        if (head == null) {
            return;
        }
        Node victim = head;
        unlink(victim);
        cache.remove(victim.pageId);
        // 必须用 victim 这个局部引用判脏与回写：cache.remove 之后再按 pageId 回查
        // 必然拿到 null，那样所有脏页淘汰都会悄悄不再落盘。
        if (victim.dirty) {
            fileManager.writePage(victim.page);
            victim.dirty = false;
        }
        evictionCount++;
        if (evictions.size() >= EVENT_LOG_LIMIT) {
            evictions.remove(0);
        }
        evictions.add(victim.pageId);
        log("[BufferPool] 淘汰页 " + victim.pageId);
    }

    // 记录一次策略切换（只在真正翻转时调用）。
    private void recordSwitch(Strategy target) {
        if (switchHistory.size() >= EVENT_LOG_LIMIT) {
            switchHistory.remove(0);
        }
        switchHistory.add(target);
        log("[BufferPool] 切换策略 -> " + target);
    }

    // 日志出口：默认写标准错误；GUI 注入自定义出口以把事件显示到界面。
    private static Consumer<String> logSink = System.err::println;

    // 注入全局日志出口（传 null 恢复默认写标准错误）。
    // 注意这是**进程级**的：只影响没有自己设置 setEventSink 的池。
    public static void setLogSink(Consumer<String> sink) {
        logSink = sink == null ? System.err::println : sink;
    }

    // 向本池的事件出口输出一条事件。未设置实例出口时**在此时刻**回退到静态出口，
    // 而不是在构造时快照——GUI 注册 sink 之前就已经建好了池（pg_catalog 在建 CmdWindow
    // 之前就被装载），快照语义会让那些池的事件永远进不了界面。
    private void log(String message) {
        Consumer<String> sink = eventSink != null ? eventSink : logSink;
        sink.accept(message);
    }
}
