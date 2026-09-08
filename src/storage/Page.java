package storage;

import utils.Constants;
import utils.PageType;

/**
 * 页：页式存储的最小 I/O 单位，固定 PAGE_SIZE(4KB) 字节。
 *
 * 布局（共 4096 字节，页头 HEADER_SIZE=24 字节，均按大端存储）：
 *   [0,4)    pageId（int）
 *   [4,8)    pageType（int：0=DATA, 1=CATALOG）
 *   [8,12)   freeSpaceOffset（int，空闲空间起始偏移）
 *   [12,16)  slotCount（int，行数）
 *   [16,20)  nextPageId（int，链表下一页，-1 表示无）
 *   [20,24)  保留
 *   [24,...) 槽目录 + 行数据 + 空闲空间
 */
public class Page {
    private final int pageId;
    private final byte[] data;

    /** 新建一页并初始化页头。 */
    public Page(int pageId) {
        this(pageId, new byte[Constants.PAGE_SIZE]);
        setPageId(pageId);
        setPageType(PageType.DATA);
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
        setNextPageId(-1);
    }

    /** 用已有字节构造（从磁盘读入时使用）。 */
    public Page(int pageId, byte[] data) {
        this.pageId = pageId;
        this.data = data;
    }

    // ---- 页头字段读写（大端） ----

    public int getPageId() {
        return getInt(0);
    }

    public void setPageId(int v) {
        putInt(0, v);
    }

    public PageType getPageType() {
        return getInt(4) == 1 ? PageType.CATALOG : PageType.DATA;
    }

    public void setPageType(PageType t) {
        putInt(4, t == PageType.CATALOG ? 1 : 0);
    }

    public int getFreeSpaceOffset() {
        return getInt(8);
    }

    public void setFreeSpaceOffset(int v) {
        putInt(8, v);
    }

    public int getSlotCount() {
        return getInt(12);
    }

    public void setSlotCount(int v) {
        putInt(12, v);
    }

    public int getNextPageId() {
        return getInt(16);
    }

    public void setNextPageId(int v) {
        putInt(16, v);
    }

    /** 返回整页原始字节（写入磁盘用）。 */
    public byte[] getRawData() {
        return data;
    }

    // ---- 行读写（槽目录 + 行数据 + 空闲空间，待实现） ----

    /** 按槽下标读取一行字节。 */
    public byte[] readRow(int slotIndex) {
        throw new UnsupportedOperationException("TODO: 按槽读取一行字节");
    }

    /** 在空闲空间追加一行字节并登记槽；空间不足返回 false。 */
    public boolean writeRow(byte[] rowData) {
        throw new UnsupportedOperationException("TODO: 在空闲空间追加一行，并登记槽");
    }

    // ---- 内部字节读写（大端） ----

    private int getInt(int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private void putInt(int offset, int v) {
        data[offset] = (byte) (v >>> 24);
        data[offset + 1] = (byte) (v >>> 16);
        data[offset + 2] = (byte) (v >>> 8);
        data[offset + 3] = (byte) v;
    }
}
