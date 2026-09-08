package storage;

import utils.Constants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 页缓存：在内存中缓存热点页，支持 LRU / FIFO 替换策略，统计命中并输出替换日志。
 *
 * <p>LRU 用 {@link LinkedHashMap}(accessOrder=true) 实现「按访问序淘汰最久未用」；
 * FIFO 用 accessOrder=false 实现「按插入序淘汰最先进」；两者统一通过覆写
 * {@link LinkedHashMap#removeEldestEntry(Map.Entry)} 在超出容量时淘汰，并顺手把
 * 脏页写回磁盘。未命中时从 {@link FileManager} 加载。
 */
public class BufferPool {
    /** 替换策略。 */
    public enum Strategy {
        LRU, FIFO;
    }

    private final int capacity;
    private final Strategy strategy;
    private final FileManager fileManager;
    private final LinkedHashMap<Integer, Page> cache;
    private final Set<Integer> dirty = new HashSet<>();
    private int hitCount;
    private int missCount;

    public BufferPool(int capacity, Strategy strategy, FileManager fileManager) {
        this.capacity = capacity;
        this.strategy = strategy;
        this.fileManager = fileManager;
        // LRU：accessOrder=true，按访问序重排；FIFO：accessOrder=false，按插入序。
        this.cache = new LinkedHashMap<Integer, Page>(capacity, 0.75f, strategy == Strategy.LRU) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Page> eldest) {
                if (size() <= capacity) {
                    return false;
                }
                int evictId = eldest.getKey();
                if (dirty.contains(evictId)) {
                    fileManager.writePage(eldest.getValue()); // 脏页先写回
                    dirty.remove(evictId);
                }
                System.err.println("[BufferPool] 淘汰页 " + evictId + " (" + strategy + ")");
                return true;
            }
        };
    }

    public BufferPool(Strategy strategy, FileManager fileManager) {
        this(Constants.DEFAULT_BUFFER_SIZE, strategy, fileManager);
    }

    /**
     * 获取页；缓存命中直接返回，未命中则从磁盘加载（必要时按策略淘汰页并记录替换日志）。
     * 页不存在（越界）时返回 null。
     */
    public Page getPage(int pageId) {
        Page p = cache.get(pageId);
        if (p != null) {
            hitCount++;
            return p;
        }
        missCount++;
        p = fileManager.readPage(pageId);
        if (p != null) {
            cache.put(pageId, p);
        }
        return p;
    }

    /** 标记页为脏（调用方修改页后调用），以便后续刷回。 */
    public void markDirty(int pageId) {
        dirty.add(pageId);
    }

    /** 刷回指定页（脏页写回磁盘）。 */
    public void flushPage(int pageId) {
        Page p = cache.get(pageId);
        if (p != null && dirty.contains(pageId)) {
            fileManager.writePage(p);
            dirty.remove(pageId);
        }
    }

    /** 刷回所有脏页。 */
    public void flushAll() {
        for (int id : dirty) {
            Page p = cache.get(id);
            if (p != null) {
                fileManager.writePage(p);
            }
        }
        dirty.clear();
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
