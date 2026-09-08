package storage;

import utils.Constants;

/**
 * 页缓存：内存中缓存热点页，支持 LRU / FIFO 替换策略，统计命中并输出替换日志。
 */
public class BufferPool {
    /** 替换策略。 */
    public enum Strategy {
        LRU, FIFO;
    }

    private final int capacity;
    private final Strategy strategy;
    private int hitCount;
    private int missCount;

    public BufferPool(int capacity, Strategy strategy) {
        this.capacity = capacity;
        this.strategy = strategy;
        this.hitCount = 0;
        this.missCount = 0;
    }

    public BufferPool(Strategy strategy) {
        this(Constants.DEFAULT_BUFFER_SIZE, strategy);
    }

    /**
     * 获取页；缓存命中直接返回，未命中则从磁盘加载（必要时按策略淘汰页并记录替换日志）。
     * TODO: LRU 用 LinkedHashMap(accessOrder=true)，FIFO 用队列。
     */
    public Page getPage(int pageId) {
        throw new UnsupportedOperationException("TODO: 实现 getPage()");
    }

    /** 刷回指定页（脏页写回磁盘）。 */
    public void flushPage(int pageId) {
        throw new UnsupportedOperationException("TODO: 实现 flushPage()");
    }

    /** 刷回所有脏页。 */
    public void flushAll() {
        throw new UnsupportedOperationException("TODO: 实现 flushAll()");
    }

    public int getHitCount() {
        return hitCount;
    }

    public int getMissCount() {
        return missCount;
    }

    /** 缓存命中率。 */
    public double hitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }
}
