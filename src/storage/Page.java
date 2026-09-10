package storage;

import utils.Constants;
import utils.PageType;

/**
 * 页：页式存储的最小 I/O 单位，固定 PAGE_SIZE(4KB) 字节。
 * 布局：24 字节页头（pageId/pageType/freeSpaceOffset/slotCount/nextPageId，大端）
 * + 行数据（自前向后追加）+ 槽目录（自页尾向前增长）。
 */
public class Page {
    private final int pageId;
    private final byte[] data;

    // 新建一页并初始化页头。
    public Page(int pageId) {
        this(pageId, new byte[Constants.PAGE_SIZE]);
        setPageId(pageId);
        setPageType(PageType.DATA);
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
        setNextPageId(-1);
    }

    // 用已有字节构造一页（从磁盘读入时使用）。
    public Page(int pageId, byte[] data) {
        this.pageId = pageId;
        this.data = data;
    }

    // 读取页号。
    public int getPageId() {
        return getInt(0);
    }

    // 设置页号。
    public void setPageId(int v) {
        putInt(0, v);
    }

    // 读取页类型。
    public PageType getPageType() {
        return getInt(4) == 1 ? PageType.CATALOG : PageType.DATA;
    }

    // 设置页类型。
    public void setPageType(PageType t) {
        putInt(4, t == PageType.CATALOG ? 1 : 0);
    }

    // 读取空闲空间起始偏移。
    public int getFreeSpaceOffset() {
        return getInt(8);
    }

    // 设置空闲空间起始偏移。
    public void setFreeSpaceOffset(int v) {
        putInt(8, v);
    }

    // 读取行数。
    public int getSlotCount() {
        return getInt(12);
    }

    // 设置行数。
    public void setSlotCount(int v) {
        putInt(12, v);
    }

    // 读取下一页号。
    public int getNextPageId() {
        return getInt(16);
    }

    // 设置下一页号。
    public void setNextPageId(int v) {
        putInt(16, v);
    }

    // 返回整页原始字节（写盘用）。
    public byte[] getRawData() {
        return data;
    }

    private static final int SLOT_SIZE = 4;

    // 计算槽目录第 slotIndex 项在页内的偏移（自页尾向前）。
    private int slotOffset(int slotIndex) {
        return Constants.PAGE_SIZE - SLOT_SIZE * (slotIndex + 1);
    }

    // 判断是否还有空间追加一行（行数据与槽目录不得越过彼此）。
    public boolean hasSpace(int rowLength) {
        return getFreeSpaceOffset() + rowLength
                <= Constants.PAGE_SIZE - SLOT_SIZE * (getSlotCount() + 1);
    }

    // 按槽下标读取一行字节。
    public byte[] readRow(int slotIndex) {
        int off = slotOffset(slotIndex);
        int rowOffset = getShort(off) & 0xFFFF;
        int rowLength = getShort(off + 2) & 0xFFFF;
        byte[] row = new byte[rowLength];
        System.arraycopy(data, rowOffset, row, 0, rowLength);
        return row;
    }

    // 追加一行字节并登记槽，空间不足返回 false。
    public boolean writeRow(byte[] rowData) {
        if (!hasSpace(rowData.length)) {
            return false;
        }
        int rowOffset = getFreeSpaceOffset();
        System.arraycopy(rowData, 0, data, rowOffset, rowData.length);
        int off = slotOffset(getSlotCount());
        putShort(off, rowOffset);
        putShort(off + 2, rowData.length);
        setFreeSpaceOffset(rowOffset + rowData.length);
        setSlotCount(getSlotCount() + 1);
        return true;
    }

    // 清空本页所有行（仅复位槽数与空闲空间）。
    public void clear() {
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
    }

    // 读 2 字节大端无符号短整数。
    private int getShort(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // 写 2 字节（大端），取 value 低 16 位。
    private void putShort(int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    // 读 4 字节大端整数。
    private int getInt(int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    // 写 4 字节大端整数。
    private void putInt(int offset, int v) {
        data[offset] = (byte) (v >>> 24);
        data[offset + 1] = (byte) (v >>> 16);
        data[offset + 2] = (byte) (v >>> 8);
        data[offset + 3] = (byte) v;
    }
}
