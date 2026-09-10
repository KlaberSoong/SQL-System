package storage;

import utils.Constants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 页缓存：在内存中缓存热点页，支持 LRU / FIFO 替换策略，并可用 AUTO 模式按访问模式自动切换。
 * 用自维护的双向链表 + HashMap 实现 O(1) 查找与淘汰；脏页在淘汰或 flush 时写回磁盘。
 */
public class BufferPool {
    /** 替换策略：LRU 最近最少使用；FIFO 先进先出；AUTO 按顺序扫描检测自动切换。 */
    public enum Strategy {
        LRU, FIFO, AUTO;
    }

    /** AUTO 模式下，连续递增访问多少次即判定为顺序扫描并切到 FIFO。 */
    private static final int SCAN_THRESHOLD = 4;

    /** 双向链表节点：LRU 按访问序、FIFO 按插入序维护。 */
    private static final class Node {
        final int pageId;
        final Page page;
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
    private final Set<Integer> dirty = new HashSet<>();
    private int hitCount;
    private int missCount;

    // AUTO 模式状态：当前实际策略 + 顺序扫描检测。
    private boolean activeLru = true;
    private int lastPageId = -1;
    private int ascendingStreak = 0;

    // 构造：按策略初始化缓存（AUTO 初始按 LRU）。
    public BufferPool(int capacity, Strategy strategy, FileManager fileManager) {
        this.capacity = capacity;
        this.strategy = strategy;
        this.fileManager = fileManager;
        this.activeLru = strategy != Strategy.FIFO;
    }

    // 用默认容量构造。
    public BufferPool(Strategy strategy, FileManager fileManager) {
        this(Constants.DEFAULT_BUFFER_SIZE, strategy, fileManager);
    }

    // 获取页：命中直接返回（LRU 时移到队尾），未命中从磁盘加载（必要时淘汰队头）。
    public Page getPage(int pageId) {
        if (strategy == Strategy.AUTO) {
            updatePolicy(pageId);
        }
        Node node = cache.get(pageId);
        if (node != null) {
            hitCount++;
            if (activeLru) {
                moveToTail(node);
            }
            return node.page;
        }
        missCount++;
        Page page = fileManager.readPage(pageId);
        if (page != null) {
            addToTail(new Node(pageId, page));
            if (cache.size() > capacity) {
                evictHead();
            }
        }
        return page;
    }

    // 标记页为脏（修改后调用），以便后续刷回。
    public void markDirty(int pageId) {
        dirty.add(pageId);
    }

    // 刷回指定脏页到磁盘。
    public void flushPage(int pageId) {
        Node node = cache.get(pageId);
        if (node != null && dirty.contains(pageId)) {
            fileManager.writePage(node.page);
            dirty.remove(pageId);
        }
    }

    // 刷回所有脏页到磁盘。
    public void flushAll() {
        for (int id : dirty) {
            Node node = cache.get(id);
            if (node != null) {
                fileManager.writePage(node.page);
            }
        }
        dirty.clear();
    }

    // 返回缓存命中次数。
    public int getHitCount() {
        return hitCount;
    }

    // 返回缓存未命中次数。
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

    // AUTO：根据页号是否连续上升判定顺序扫描，切换 LRU/FIFO。
    private void updatePolicy(int pageId) {
        ascendingStreak = (pageId > lastPageId) ? ascendingStreak + 1 : 0;
        lastPageId = pageId;
        boolean nowLru = ascendingStreak < SCAN_THRESHOLD;
        if (nowLru != activeLru) {
            activeLru = nowLru;
            log("[BufferPool] 切换策略 -> " + (activeLru ? "LRU" : "FIFO"));
        }
    }

    // 把节点移到队尾（标记为最新）。
    private void moveToTail(Node node) {
        if (node == tail) {
            return;
        }
        unlink(node);
        linkTail(node);
    }

    // 把新页追加到队尾。
    private void addToTail(Node node) {
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
        if (dirty.contains(victim.pageId)) {
            fileManager.writePage(victim.page);
            dirty.remove(victim.pageId);
        }
        log("[BufferPool] 淘汰页 " + victim.pageId);
    }

    // 日志出口：默认写标准错误；GUI 注入自定义出口以把事件显示到界面。
    private static Consumer<String> logSink = System.err::println;

    // 注入日志出口（传 null 恢复默认写标准错误）。
    public static void setLogSink(Consumer<String> sink) {
        logSink = sink == null ? System.err::println : sink;
    }

    // 向日志出口输出一条缓冲池事件。
    private static void log(String message) {
        logSink.accept(message);
    }
}
