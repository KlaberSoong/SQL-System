package storage;

import utils.Constants;
import utils.DbException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 文件管理：管理单个表（或系统目录）对应的磁盘数据文件与空闲页链表。
 * 文件按页组织，第 pageId 页位于文件偏移 pageId*PAGE_SIZE 处；第 0 页为元数据页，
 * 其 nextPageId 存放空闲页链表头，空闲页之间用各自页头 nextPageId 链接。
 */
public class FileManager {
    private final String filePath;
    private final Deque<Integer> freePages = new ArrayDeque<>();
    private int pageCount;

    // 构造：按文件长度计算页数并加载空闲页链表。
    public FileManager(String dataDir, String fileName) {
        this.filePath = dataDir + File.separator + fileName;
        File f = new File(filePath);
        this.pageCount = f.exists() ? (int) (f.length() / Constants.PAGE_SIZE) : 0;
        loadFreeList();
    }

    // 初始化文件：不存在则创建并写入第 0 页元数据页。
    public void init() {
        if (pageCount == 0) {
            writePage(new Page(0));
        }
    }

    // 返回文件当前页数（含第 0 页元数据页）。
    public int pageCount() {
        return pageCount;
    }

    // 按页号从文件读出一页，越界返回 null。
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

    // 把一页写回文件，必要时扩展文件。
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

    // 分配新页：优先复用空闲页链表，否则在文件末尾追加，返回页号。
    public int allocatePage() {
        if (!freePages.isEmpty()) {
            int id = freePages.pop();
            Page popped = readPage(id);
            int next = popped == null ? -1 : popped.getNextPageId();
            setFreeHead(next);
            return id;
        }
        int id = pageCount;
        writePage(new Page(id));
        return id;
    }

    // 释放页：清空内容并链入空闲页链表。
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

    // 判断页号是否处于空闲页链表中。
    public boolean isFreePage(int pageId) {
        return freePages.contains(pageId);
    }

    // 从磁盘第 0 页读取空闲页链头，并重建内存空闲页栈。
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

    // 读取空闲页链头（第 0 页的 nextPageId）。
    private int getFreeHead() {
        Page head = readPage(0);
        return head == null ? -1 : head.getNextPageId();
    }

    // 写回空闲页链头（第 0 页的 nextPageId）。
    private void setFreeHead(int pageId) {
        Page head = readPage(0);
        if (head != null) {
            head.setNextPageId(pageId);
            writePage(head);
        }
    }
}
