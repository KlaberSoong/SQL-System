package storage;

import utils.Constants;
import utils.PageType;


public class Page {
    
    private final int pageId;
    private final byte[] data;

    // 新建一页并初始化页头
    public Page(int pageId) {
        this(pageId, new byte[Constants.PAGE_SIZE]);
        setPageId(pageId);
        setPageType(PageType.DATA);
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
        setNextPageId(-1);
        setFormatVersion(Constants.FILE_FORMAT_VERSION);
    }

    // 从磁盘读入时用已有字节构造一页
    public Page(int pageId, byte[] data) {
        this.pageId = pageId;
        this.data = data;
    }

   // 返回页号
    public int getPageId() {
        return pageId;
    }

   // 设置页号
    public void setPageId(int v) {
        putInt(0, v);
    }

    // 读取页类型
    public PageType getPageType() {
        return getInt(4) == 1 ? PageType.CATALOG : PageType.DATA;
    }

    // 设置页类型
    public void setPageType(PageType t) {
        putInt(4, t == PageType.CATALOG ? 1 : 0);
    }

    // 读取空闲空间起始偏移
    public int getFreeSpaceOffset() {
        return getInt(8);
    }

    // 设置空闲空间起始偏移
    public void setFreeSpaceOffset(int v) {
        putInt(8, v);
    }

    // 读取行数
    public int getSlotCount() {
        return getInt(12);
    }

    // 设置行数
    public void setSlotCount(int v) {
        putInt(12, v);
    }

    // 读取下一页号
    public int getNextPageId() {
        return getInt(16);
    }

    // 设置下一页号
    public void setNextPageId(int v) {
        putInt(16, v);
    }

    // 读取格式版本号  
    public int getFormatVersion() {
        return getInt(20);
    }

    // 写入格式版本号
    public void setFormatVersion(int v) {
        putInt(20, v);
    }

    // 返回整页原始字节
    public byte[] getRawData() {
        return data;
    }

    private static final int SLOT_SIZE = 4;

    // 计算槽目录第 slotIndex 项在页内的偏移
    private int slotOffset(int slotIndex) {
        return Constants.PAGE_SIZE - SLOT_SIZE * (slotIndex + 1);
    }

    // 判断是否还有空间追加一行
    public boolean hasSpace(int rowLength) {
        return getFreeSpaceOffset() + rowLength
                <= Constants.PAGE_SIZE - SLOT_SIZE * (getSlotCount() + 1);
    }

    // 返回本页可存储的最大行字节数
    public static int maxRowBytes() {
        return Constants.PAGE_SIZE - Constants.HEADER_SIZE - SLOT_SIZE;
    }

    // 按槽下标读取一行字节
    public byte[] readRow(int slotIndex) {
        int off = slotOffset(slotIndex);
        int rowOffset = getShort(off) & 0xFFFF;
        int rowLength = getShort(off + 2) & 0xFFFF;
        byte[] row = new byte[rowLength];
        System.arraycopy(data, rowOffset, row, 0, rowLength);
        return row;
    }

    // 追加一行字节并登记槽，空间不足返回 false
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

    // 清空本页所有行
    public void clear() {
        setFreeSpaceOffset(Constants.HEADER_SIZE);
        setSlotCount(0);
    }

    // 读 2 字节大端整数
    private int getShort(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // 写 2 字节大端整数，取 value 低 16 位。
    private void putShort(int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    // 读 4 字节大端整数
    private int getInt(int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    // 写 4 字节大端整数
    private void putInt(int offset, int v) {
        data[offset] = (byte) (v >>> 24);
        data[offset + 1] = (byte) (v >>> 16);
        data[offset + 2] = (byte) (v >>> 8);
        data[offset + 3] = (byte) v;
    }
}
