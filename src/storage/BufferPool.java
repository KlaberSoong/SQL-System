package storage;

import utils.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


public class BufferPool {
   
    // 缓存策略枚举：LRU、FIFO、AUT
    public enum Strategy {
        LRU, FIFO, AUTO;
    }

    private static final int SCAN_ENTER = 4;
    private static final int HYST_EXIT = 3;
    private static final int EVENT_LOG_LIMIT = 64;

   // 链表节点：承载页号、页对象、脏标记、插入序号、最近使用序号，以及双向链表指针
    private static final class Node {
        final int pageId;
        final Page page;
        long insertSeq;
        long lastUseSeq;
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
    private boolean activeLru = true;
    private boolean hasLast = false;
    private int lastPageId = -1;
    private int prevDir = 0;
    private int runLen = 0;
    private int revRun = 0;
    private long insertCounter = 0;
    private long useCounter = 0;
    private long evictionCount = 0;
    private final List<Integer> evictions = new ArrayList<>();
    private final List<Strategy> switchHistory = new ArrayList<>();
    private Consumer<String> eventSink;
    private final FileManager.PageChangeListener invalidator = this::invalidate;

    // 构造器：指定容量、策略和文件管理器
    public BufferPool(int capacity, Strategy strategy, FileManager fileManager) {
        this.capacity = capacity;
        this.strategy = strategy;
        this.fileManager = fileManager;
        this.activeLru = strategy != Strategy.FIFO;
        fileManager.addPageChangeListener(invalidator);
    }

    // 用默认容量构造
    public BufferPool(Strategy strategy, FileManager fileManager) {
        this(Constants.DEFAULT_BUFFER_SIZE, strategy, fileManager);
    }

   // 用默认容量和策略构造
    public Page getPage(int pageId) {
        Node node = cache.get(pageId);
        if (node != null) {
            hitCount++;
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

    // 标记某页为脏页
    public void markDirty(int pageId) {
        Node node = cache.get(pageId);
        if (node == null) {
            log("[BufferPool] 警告：页 " + pageId + " 不在缓存中，markDirty 被忽略（该改动不会落盘）");
            return;
        }
        node.dirty = true;
    }

    // 刷脏页到磁盘
    public void flushPage(int pageId) {
        Node node = cache.get(pageId);
        if (node != null && node.dirty) {
            fileManager.writePage(node.page);
            node.dirty = false;
        }
    }

    // 刷回所有脏页到磁盘
    public void flushAll() {
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

    // 作废某页的缓存副本
    public void invalidate(int pageId) {
        Node node = cache.get(pageId);
        if (node == null) {
            return;     
        }
        if (node.dirty) {
            log("[BufferPool] 警告：丢弃未落盘的脏页副本 " + pageId + "（磁盘为准）");
        }
        unlink(node);
        cache.remove(pageId);
    }

    // 返回缓存命中次数
    public int getHitCount() {
        return hitCount;
    }

    // 返回缓存未命中次数
    public int getMissCount() {
        return missCount;
    }

    // 返回缓存命中率
    public double hitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    // 返回当前实际生效的策略
    public Strategy getActiveStrategy() {
        if (strategy != Strategy.AUTO) {
            return strategy;
        }
        return activeLru ? Strategy.LRU : Strategy.FIFO;
    }

    // 返回配置的策略
    public Strategy getStrategy() {
        return strategy;
    }

    // 已发生的淘汰总次数
    public long getEvictionCount() {
        return evictionCount;
    }

    // 被淘汰的页号
    public List<Integer> getEvictions() {
        return Collections.unmodifiableList(new ArrayList<>(evictions));
    }

    // 策略切换历史日志
    public List<Strategy> getSwitchHistory() {
        return Collections.unmodifiableList(new ArrayList<>(switchHistory));
    }

    // 当前驻留的页号，按淘汰次序（队头 -> 队尾，第一个就是下一个被淘汰的）
    public List<Integer> getResidentPages() {
        List<Integer> ids = new ArrayList<>();
        for (Node n = head; n != null; n = n.next) {
            ids.add(n.pageId);
        }
        return ids;
    }

    // 页是否驻留在缓存中
    public boolean isResident(int pageId) {
        return cache.containsKey(pageId);
    }

    // 当前带脏标记的驻留页数
    public int getDirtyCount() {
        int n = 0;
        for (Node node : cache.values()) {
            if (node.dirty) {
                n++;
            }
        }
        return n;
    }

    // 注入本池的事件出口
    public void setEventSink(Consumer<String> sink) {
        this.eventSink = sink;
    }

    // 注入本池的事件出口
    private void updatePolicy(int pageId) {
        if (!hasLast) {
            hasLast = true;
            lastPageId = pageId;
            prevDir = 0;
            runLen = 0;
            revRun = 0;
            return;
        }
        if (pageId == lastPageId) {
            return;
        }
        int dir = pageId > lastPageId ? 1 : -1;
        lastPageId = pageId;
        if (prevDir == 0) {
            runLen = 1;             
            revRun = 0;
        } else if (dir == prevDir) {
            runLen = runLen + 1;    
            revRun = 0;
        } else {
            runLen = 1;             
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
                rebuildByRecency();     
                recordSwitch(Strategy.LRU);
            }
        }
    }

   // FIFO
    private void rebuildByInsertOrder() {
        List<Node> nodes = new ArrayList<>(cache.values());
        if (nodes.size() <= 1) {
            return;     // 空表（容量 0 + AUTO 可达）或单节点：重排是空操作
        }
        nodes.sort(Comparator.comparingLong(n -> n.insertSeq));
        relink(nodes);
    }

   // LRU
    private void rebuildByRecency() {
        List<Node> nodes = new ArrayList<>(cache.values());
        if (nodes.size() <= 1) {
            return;
        }
        nodes.sort(Comparator.comparingLong(n -> n.lastUseSeq));
        relink(nodes);
    }

   // 重新链接链表：按 nodes 顺序把 prev/next 指针连起来，并更新 head/tail
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

    // 把节点移到队尾（标记为最新）
    private void moveToTail(Node node) {
        if (node == tail) {
            return;
        }
        unlink(node);
        linkTail(node);
    }

    // 把新页追加到队尾，并领取插入序号
    private void addToTail(Node node) {
        node.insertSeq = insertCounter++;
        cache.put(node.pageId, node);
        linkTail(node);
    }

    // 把节点从链表中摘下
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

    // 把已摘下/新节点接到队尾
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

    // 淘汰队头页，脏页先写回磁盘
    private void evictHead() {
        if (head == null) {
            return;
        }
        Node victim = head;
        unlink(victim);
        cache.remove(victim.pageId);
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

    // 记录一次策略切换
    private void recordSwitch(Strategy target) {
        if (switchHistory.size() >= EVENT_LOG_LIMIT) {
            switchHistory.remove(0);
        }
        switchHistory.add(target);
        log("[BufferPool] 切换策略 -> " + target);
    }

    private static Consumer<String> logSink = System.err::println;

    // 设置静态事件出口
    public static void setLogSink(Consumer<String> sink) {
        logSink = sink == null ? System.err::println : sink;
    }

    // 注入本池的事件出口
    private void log(String message) {
        Consumer<String> sink = eventSink != null ? eventSink : logSink;
        sink.accept(message);
    }
}

