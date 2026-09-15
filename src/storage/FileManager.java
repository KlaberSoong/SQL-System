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

/**
 * 文件管理：管理单个表（或系统目录）对应的磁盘数据文件与空闲页链表。
 * 文件按页组织，第 pageId 页位于文件偏移 pageId*PAGE_SIZE 处；第 0 页为元数据页，
 * 其 nextPageId 存放空闲页链表头，空闲页之间用各自页头 nextPageId 链接。
 *
 * <p>释放是幂等的：对同一页重复调用 {@link #freePage(int)} 只会生效一次，
 * 因此空闲链不会出现重复项或自环（重复释放会让页被分配两次，或在重开文件时死循环）。
 *
 * <p><b>页变更通知：</b>本类有几个操作会**绕过上层页缓存直接改写磁盘**——{@link #freePage(int)}
 * 清空被释放页并改写第 0 页的链头、{@link #allocatePage()} 复用页时改写链头、
 * {@link #init()} 建立元数据页。此时缓冲池里可能还留着这些页的**陈旧副本**，
 * 若被回写就会覆盖掉刚落盘的链指针（实测：13 个空闲页在一次 DELETE 后只剩 2 个）。
 * 因此这些操作会通过 {@link PageChangeListener} 通知注册过的缓冲池丢弃副本。
 * 监听器只收弱引用，池被回收后自动失效，无需显式注销。
 *
 * <p><b>覆盖边界：</b>通知只在**同一个 FileManager 实例**内生效。若两个
 * {@code StorageEngine} 实例各自打开同一个 .dat（两套 FileManager + 两套池），
 * 它们之间不会互相通知——跨实例一致性只能靠"同一时刻只让一个实例持有该目录"的约定。
 */
public class FileManager {

    /**
     * 页变更监听器：某一页被本类**绕过缓存**改写后收到通知，应当丢弃自己的缓存副本。
     * 接口声明在这里（而不是引用 BufferPool），使 FileManager 不反向依赖缓存层。
     */
    public interface PageChangeListener {
        /** 页 {@code pageId} 的磁盘内容已被本类改写，缓存副本已失效。 */
        void pageChanged(int pageId);
    }

    private final String filePath;
    /** 空闲页栈（栈顶最近释放，复用顺序为 LIFO）。 */
    private final Deque<Integer> freePages = new ArrayDeque<>();
    /** 与 freePages 同步的集合，供 isFreePage 做 O(1) 判定并拦截重复释放。 */
    private final Set<Integer> freeSet = new HashSet<>();
    /** 注册的页变更监听器（弱引用；同一 FileManager 上可以并存多个缓冲池）。 */
    private final List<WeakReference<PageChangeListener>> listeners = new ArrayList<>();
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
            notifyPageChanged(0);
        }
    }

    // 注册页变更监听器。只保留弱引用：池不再被引用时监听器自动失效，不需要注销。
    public void addPageChangeListener(PageChangeListener listener) {
        listeners.add(new WeakReference<>(listener));
    }

    // 通知所有监听器"这一页的磁盘内容已变，缓存副本作废"，顺便清理已被回收的弱引用。
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
        if (pageCount == 0) {
            init();     // 空文件先建立第 0 页元数据页，避免把元数据页当数据页分配出去
        }
        while (!freePages.isEmpty()) {
            int id = freePages.pop();
            if (!freeSet.remove(id)) {
                continue;   // 陈旧条目（历史遗留的重复项），跳过
            }
            Page popped = readPage(id);
            int next = popped == null ? -1 : popped.getNextPageId();
            setFreeHead(next);          // 改写第 0 页链头（setFreeHead 内已通知）
            // 这一页在释放时被清空并写盘；池里可能还留着它释放前的旧内容，
            // 必须作废，否则调用方会拿到陈旧镜像（旧实现下 GET 到的就是它）。
            //
            // 这是**纵深防御**，不是当前唯一防线：本方法只改写第 0 页，不碰被复用页的字节，
            // 而释放时已经通知过一次（见 freePage），所以池能持有的副本本就等于当前磁盘内容。
            // 变异测试（把这一句删掉）全量用例仍全绿，正是这个原因。保留它是为了让
            // "凡绕过缓存写过的页都发通知"成为一条无条件的不变式——将来复用若开始改写页内容，
            // 少这一句就会变成静默的数据损坏，而 CacheTest 里有断言钉住这个前提。
            notifyPageChanged(id);
            return id;
        }
        int id = pageCount;
        writePage(new Page(id));
        notifyPageChanged(id);
        return id;
    }

    // 释放页：清空内容并链入空闲页链表。重复释放同一页会被忽略（幂等）。
    public void freePage(int pageId) {
        // 第 0 页是元数据页，不属于数据页；越界页号本就不存在：均不释放。
        if (pageId <= 0 || pageId >= pageCount) {
            return;
        }
        if (!freeSet.add(pageId)) {
            return;   // 已在空闲链中：直接忽略，避免链上出现重复项或自环
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
        // 被释放页的磁盘内容已经变成"清空 + 链指针"，池里那份旧内容必须作废。
        notifyPageChanged(pageId);
    }

    // 判断页号是否处于空闲页链表中。
    public boolean isFreePage(int pageId) {
        return freeSet.contains(pageId);
    }

    // 从磁盘第 0 页读取空闲页链头，并重建内存空闲页栈。
    // 对损坏的链（自环、重复、越界页号）做检测并截断，避免重开文件时无限循环。
    private void loadFreeList() {
        freePages.clear();
        freeSet.clear();
        Page head = readPage(0);
        int p = head == null ? -1 : head.getNextPageId();
        int lastValid = -1;
        boolean broken = false;
        while (p != -1) {
            if (p <= 0 || p >= pageCount || !freeSet.add(p)) {
                broken = true;      // 越界页号、重复页或自环
                break;
            }
            // 顺序入队（而非 push 到队首），使"队首 == 磁盘链头"这一不变式与
            // freePage/allocatePage 保持一致：否则重开后复用的顺序会与链头相反，
            // 分配时会把已分配的页重新挂回空闲链（重启后被重复发放，覆盖数据）。
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
            // 把最后一个有效页的 nextPageId 置 -1（链为空则清空链头），截断坏链。
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
            notifyPageChanged(0);
        }
    }
}
