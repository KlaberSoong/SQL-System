package storage;

import utils.Constants;
import utils.DbException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 文件管理：管理单个表（或系统目录）对应的磁盘数据文件、空闲页链表。
 *
 * <p>文件按页组织：第 {@code pageId} 页位于文件偏移 {@code pageId * PAGE_SIZE} 处。
 * <p>第 0 页为元数据页，其 {@code nextPageId} 字段存放空闲页链表头（-1 表示无空闲页）；
 * 空闲页之间同样用各自页头的 {@code nextPageId} 链接成链。数据页从第 1 页开始。
 * 这样空闲页链表随页一并落盘，重启后仍可恢复。
 *
 * <p>本类方法直接读写磁盘（不经缓冲池）；缓存层由 {@link BufferPool} 在其上提供。
 */
public class FileManager {
    /** 单文件路径（如 data/student.dat）。 */
    private final String filePath;
    /** 内存空闲页栈（与磁盘链表头保持一致，供快速分配）。 */
    private final Deque<Integer> freePages = new ArrayDeque<>();
    /** 文件当前页数（0 表示文件尚不存在）。 */
    private int pageCount;

    public FileManager(String dataDir, String fileName) {
        this.filePath = dataDir + File.separator + fileName;
        File f = new File(filePath);
        this.pageCount = f.exists() ? (int) (f.length() / Constants.PAGE_SIZE) : 0;
        loadFreeList();
    }

    /** 初始化文件：若不存在则创建并写入第 0 页（元数据页）。 */
    public void init() {
        if (pageCount == 0) {
            writePage(new Page(0));
        }
    }

    /** 返回文件当前页数（含第 0 页元数据页）。 */
    public int pageCount() {
        return pageCount;
    }

    /** 按页号从文件读出一页（文件偏移 = pageId * PAGE_SIZE）；越界返回 null。 */
    public Page readPage(int pageId) {
        if (pageId < 0 || pageId >= pageCount) {
            return null;
        }
        byte[] buf = new byte[Constants.PAGE_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek((long) pageId * Constants.PAGE_SIZE);
            raf.readFully(buf);
        } catch (IOException e) {
            throw new DbException("readPage(" + pageId + ") failed: " + e.getMessage(), e);
        }
        return new Page(pageId, buf);
    }

    /** 把一页写回文件（文件偏移 = pageId * PAGE_SIZE）；必要时扩展文件。 */
    public void writePage(Page page) {
        int id = page.getPageId();
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw")) {
            raf.seek((long) id * Constants.PAGE_SIZE);
            raf.write(page.getRawData());
        } catch (IOException e) {
            throw new DbException("writePage(" + id + ") failed: " + e.getMessage(), e);
        }
        if (id >= pageCount) {
            pageCount = id + 1;
        }
    }

    /** 分配一个新页：优先复用空闲页链表，否则在文件末尾追加一页；返回页号。 */
    public int allocatePage() {
        if (!freePages.isEmpty()) {
            int id = freePages.pop();
            // 空闲页链头前移：指向刚弹出页所指向的下一页
            Page popped = readPage(id);
            int next = popped == null ? -1 : popped.getNextPageId();
            setFreeHead(next);
            return id;
        }
        int id = pageCount;
        writePage(new Page(id)); // 追加新页并增长文件
        return id;
    }

    /** 释放一个页：清空其内容并链入空闲页链表。 */
    public void freePage(int pageId) {
        Page p = readPage(pageId);
        if (p == null) {
            return;
        }
        p.clear();
        p.setNextPageId(getFreeHead());
        writePage(p);
        setFreeHead(pageId);
        freePages.push(pageId);
    }

    /** 该页号当前是否处于空闲页链表中（供上层扫描时跳过）。 */
    public boolean isFreePage(int pageId) {
        return freePages.contains(pageId);
    }

    /** 从磁盘第 0 页读取空闲页链头，并重建内存空闲页栈。 */
    private void loadFreeList() {
        freePages.clear();
        Page head = readPage(0);
        int p = head == null ? -1 : head.getNextPageId();
        while (p != -1) {
            freePages.push(p);
            Page fp = readPage(p);
            p = fp == null ? -1 : fp.getNextPageId();
        }
    }

    /** 读取空闲页链头（第 0 页的 nextPageId）。 */
    private int getFreeHead() {
        Page head = readPage(0);
        return head == null ? -1 : head.getNextPageId();
    }

    /** 写回空闲页链头（第 0 页的 nextPageId）。 */
    private void setFreeHead(int pageId) {
        Page head = readPage(0);
        if (head != null) {
            head.setNextPageId(pageId);
            writePage(head);
        }
    }
}
