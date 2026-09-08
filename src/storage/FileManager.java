package storage;

/**
 * 文件管理：管理磁盘数据文件、空闲页链表、表 -> 页集合的映射。
 */
public class FileManager {
    private final String dataDir;

    public FileManager(String dataDir) {
        this.dataDir = dataDir;
    }

    /** 按页号从文件读出一页（文件偏移 = pageId * PAGE_SIZE）。 */
    public Page readPage(int pageId) {
        throw new UnsupportedOperationException("TODO: 实现 readPage()");
    }

    /** 把一页写回文件（文件偏移 = pageId * PAGE_SIZE）。 */
    public void writePage(Page page) {
        throw new UnsupportedOperationException("TODO: 实现 writePage()");
    }

    /** 分配一个新页，返回页号。 */
    public int allocatePage() {
        throw new UnsupportedOperationException("TODO: 实现 allocatePage()（维护空闲页链表）");
    }

    /** 释放一个页，加入空闲页链表。 */
    public void freePage(int pageId) {
        throw new UnsupportedOperationException("TODO: 实现 freePage()");
    }
}
