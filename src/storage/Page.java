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
 *
 * 页体布局（自页头之后，两条边界相向增长，中间为空闲空间）：
 *   [24, freeSpaceOffset)                 行数据（自前向后追加，行间连续）
 *   [freeSpaceOffset, 槽目录起点)          空闲空间
 *   [PAGE_SIZE-4*slotCount, PAGE_SIZE)     槽目录（自页尾向前增长）
 * 槽目录第 i 项（0 起）位于 [PAGE_SIZE-4*(i+1), PAGE_SIZE-4*i)，每槽 = [偏移 2B][长度 2B]。
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

    // ---- 行读写（行数据自前向后 + 槽目录自后向前） ----

    /**
     * 槽目录表项长度：每个槽 = [偏移 2B][长度 2B]（均按大端无符号短整数存储）。
     * PAGE_SIZE=4096，偏移最大 4095、行最大 4072，均可用无符号短整数（0~65535）表示。
     */
    private static final int SLOT_SIZE = 4;

    /** 槽目录第 slotIndex 项（0 起）在页内的偏移（自页尾向前）。 */
    private int slotOffset(int slotIndex) {
        return Constants.PAGE_SIZE - SLOT_SIZE * (slotIndex + 1);
    }

    /** 判断追加一行（含其槽目录项）是否有足够空闲空间。 */
    public boolean hasSpace(int rowLength) {
        // 新行写在 freeSpaceOffset，新槽写在页尾更靠前处；两者不得越过彼此
        return getFreeSpaceOffset() + rowLength
                <= Constants.PAGE_SIZE - SLOT_SIZE * (getSlotCount() + 1);
    }

    /**
     * 按槽下标读取一行字节。
     *
     * <p>槽目录第 slotIndex 项位于 {@code [PAGE_SIZE-4*(slotIndex+1), PAGE_SIZE-4*slotIndex)}，
     * 依次为行偏移（2B）与行长（2B）。据此把对应区间的字节拷贝出来返回。
     */
    public byte[] readRow(int slotIndex) {
        int off = slotOffset(slotIndex);
        int rowOffset = getShort(off) & 0xFFFF;
        int rowLength = getShort(off + 2) & 0xFFFF;
        byte[] row = new byte[rowLength];
        System.arraycopy(data, rowOffset, row, 0, rowLength);
        return row;
    }

    /**
     * 在空闲空间追加一行字节并登记槽；空间不足返回 false。
     *
     * <p>步骤：先在 {@code freeSpaceOffset} 处写入行字节，再在槽目录（页尾更靠前处）登记
     * [偏移, 长度]，最后前移 {@code freeSpaceOffset}、递增 {@code slotCount}。
     */
    public boolean writeRow(byte[] rowData) {
        if (!hasSpace(rowData.length)) {
            return false;
        }
        int rowOffset = getFreeSpaceOffset();
        // 1. 写入行数据（自前向后追加）
        System.arraycopy(rowData, 0, data, rowOffset, rowData.length);
        // 2. 在槽目录（页尾向前）登记 [偏移, 长度]
        int off = slotOffset(getSlotCount());
        putShort(off, rowOffset);
        putShort(off + 2, rowData.length);
        // 3. 前移空闲空间起始偏移、递增槽数
        setFreeSpaceOffset(rowOffset + rowData.length);
        setSlotCount(getSlotCount() + 1);
        return true;
    }

    /** 清空本页所有行（仅复位槽数与空闲空间，保留页头其余字段）。 */
    public void clear() {
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
    }

    // ---- 内部字节读写（大端） ----

    /** 读取 2 字节无符号短整数（大端），返回范围为 0~65535 的有符号 int。 */
    private int getShort(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /** 写入 2 字节（大端），取 value 的低 16 位。 */
    private void putShort(int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

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
