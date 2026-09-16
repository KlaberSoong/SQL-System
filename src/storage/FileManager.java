package storage;

import utils.Constants;
import utils.DbException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class FileManager {

    // 监听器接口：当某页的磁盘内容被改写时，通知缓存池作废该页的副本
    public interface PageChangeListener {
        void pageChanged(int pageId);
    }

    private final String filePath;
    //有序，查询顺序，时间复杂度高
    private final Deque<Integer> freePages = new ArrayDeque<>();
    //随机，查询存在，时间复杂度低
    private final Set<Integer> freeSet = new HashSet<>();
    private final List<WeakReference<PageChangeListener>> listeners = new ArrayList<>();
    private int pageCount;

    // 构造器：拼出文件的完整路径，计算页数并读出哪些页是空的
    public FileManager(String dataDir, String fileName) {
        this.filePath = dataDir + File.separator + fileName;
        File f = new File(filePath);
        long fileLength = f.exists() ? f.length() : 0;
        this.pageCount = (int) (fileLength / Constants.PAGE_SIZE);
        checkFormatVersion(fileLength);
        loadFreeList();
    }

    // 初始化文件：不存在则创建并写入第 0 页元数据页
    public void init() {
        if (pageCount == 0) {
            writePage(new Page(0));
            notifyPageChanged(0);
        }
    }

    // 注册监听器：当某页的磁盘内容被改写时，通知缓存池作废该页的副本
    public void addPageChangeListener(PageChangeListener listener) {
        listeners.add(new WeakReference<>(listener));
    }

    // 通知所有监听器：某页的磁盘内容被改写，缓存池应作废该页的副本
    private void notifyPageChanged(int pageId) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            PageChangeListener l = listeners.get(i).get();
            if (l == null) {
                listeners.remove(i);
            } else {
                l.pageChanged(pageId);
            }
        }
    }

    // 返回文件当前页数
    public int pageCount() {
        return pageCount;
    }

    // 按页号从文件读出一页，越界返回 null
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

    // 把一页写回文件，必要时扩展文件
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

    // 分配一页：先从空闲页链表取，取不到就追加新页，返回页号
    public int allocatePage() {
        if (pageCount == 0) {
            init();    
        }
        while (!freePages.isEmpty()) {
            int id = freePages.pop();
            if (!freeSet.remove(id)) {
                continue;   
            }
            Page popped = readPage(id);
            int next = popped == null ? -1 : popped.getNextPageId();
            setFreeHead(next);          
            notifyPageChanged(id);
            return id;
        }
        int id = pageCount;
        writePage(new Page(id));
        notifyPageChanged(id);
        return id;
    }

    // 释放一页：清空页内容并挂回空闲页链表，重复释放或越界页号无效
    public void freePage(int pageId) {
        if (pageId <= 0 || pageId >= pageCount) {
            return;
        }
        if (!freeSet.add(pageId)) {
            return;   
        }
        Page p = readPage(pageId);
        if (p == null) {
            freeSet.remove(pageId);
            return;
        }
        p.clear();
        p.setNextPageId(getFreeHead());
        writePage(p);
        setFreeHead(pageId);
        freePages.push(pageId);
        notifyPageChanged(pageId);
    }

    // 判断页号是否处于空闲页链表中
    public boolean isFreePage(int pageId) {
        return freeSet.contains(pageId);
    }

    // 检查文件格式版本
    private void checkFormatVersion(long fileLength) {
        if (fileLength == 0) {
            return;     
        }
        if (fileLength % Constants.PAGE_SIZE != 0) {
            throw new DbException(truncatedFileMessage(fileLength));
        }
        Page meta = readPage(0);
        if (meta == null) {
            throw new DbException("数据文件 " + filePath + " 的第 0 页不存在");
        }
        int found = meta.getFormatVersion();
        if (found == Constants.FILE_FORMAT_VERSION) {
            return;
        }
        throw new DbException(versionMismatchMessage(found));
    }

    private String truncatedFileMessage(long fileLength) {
        return "数据文件 " + filePath + " 长度为 " + fileLength + " 字节，不是页大小 "
                + Constants.PAGE_SIZE + " 的整数倍，已拒绝打开。"
                + "处理办法：先从备份恢复该文件；确认内容已不可用，再删除它让程序重建空表。";
    }

    private String versionMismatchMessage(int found) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据文件 ").append(filePath).append(" 的格式版本为 ").append(found)
                .append("，本程序需要 ").append(Constants.FILE_FORMAT_VERSION).append("，已拒绝打开。");
        if (found == 0) {
            sb.append("版本 0 表示该文件写于本程序引入格式版本号之前。");
        }
        sb.append("处理办法：备份后删除该文件，程序会重建一张空表；")
                .append("确需保留其中的数据，请用写下该文件的程序版本导出后重新导入。");
        return sb.toString();
    }

    // 读入空闲页链表：从第 0 页开始，按 nextPageId 依次读出空闲页号，遇到越界或重复页号就截断链表
    private void loadFreeList() {
        freePages.clear();
        freeSet.clear();
        Page head = readPage(0);
        int p = head == null ? -1 : head.getNextPageId();
        int lastValid = -1;
        boolean broken = false;
        while (p != -1) {
            if (p <= 0 || p >= pageCount || !freeSet.add(p)) {
                broken = true;      
                break;
            }
            freePages.addLast(p);
            Page fp = readPage(p);
            if (fp == null) {
                broken = true;
                break;
            }
            lastValid = p;
            p = fp.getNextPageId();
        }
        if (broken) {
            if (lastValid == -1) {
                setFreeHead(-1);
            } else {
                Page tail = readPage(lastValid);
                if (tail != null) {
                    tail.setNextPageId(-1);
                    writePage(tail);
                    notifyPageChanged(lastValid);
                }
            }
        }
    }

    // 读取空闲页链头
    private int getFreeHead() {
        Page head = readPage(0);
        return head == null ? -1 : head.getNextPageId();
    }

    // 写回空闲页链头
    private void setFreeHead(int pageId) {
        Page head = readPage(0);
        if (head != null) {
            head.setNextPageId(pageId);
            writePage(head);
            notifyPageChanged(0);
        }
    }
}