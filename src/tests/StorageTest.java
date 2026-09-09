package tests;

import storage.BufferPool;
import storage.FileManager;
import storage.Page;
import utils.ColumnType;
import utils.Constants;
import utils.PageType;
import utils.Serializer;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * 存储系统测试（对应 plan.md 第四章：Page / BufferPool / FileManager + Serializer）。
 *
 * 覆盖：页头读写、行槽读写往返、序列化往返（含中文/负整数）、缓冲池命中统计与刷盘、页分配/释放/复用。
 */
public class StorageTest {

    public static int run() {
        Assert a = new Assert();

        // —— Page 页头 ——
        Page p = new Page(7);
        a.checkEquals(7, p.getPageId(), "页号");
        a.checkEquals(PageType.DATA, p.getPageType(), "默认页类型 DATA");
        a.checkEquals(Constants.HEADER_SIZE, p.getFreeSpaceOffset(), "初始空闲偏移");
        a.checkEquals(0, p.getSlotCount(), "初始槽数 0");
        a.checkEquals(-1, p.getNextPageId(), "初始下一页 -1");
        p.setPageType(PageType.CATALOG);
        a.checkEquals(PageType.CATALOG, p.getPageType(), "页类型 CATALOG 读写");

        // —— Page 行读写 ——
        byte[] row = {10, 20, 30, 40};
        a.checkEquals(true, p.writeRow(row), "写行成功");
        a.checkEquals(1, p.getSlotCount(), "写行后槽数 1");
        a.checkEquals(true, Arrays.equals(row, p.readRow(0)), "行读写往返一致");
        a.checkEquals(false, p.hasSpace(Constants.PAGE_SIZE), "超大行无空间");
        p.clear();
        a.checkEquals(0, p.getSlotCount(), "clear 复位槽数");
        a.checkEquals(Constants.HEADER_SIZE, p.getFreeSpaceOffset(), "clear 复位空闲偏移");

        // —— Serializer 往返 ——
        List<Object> rowVals = Arrays.asList(-123, 3.5f, true, "你好, MiniDB!");
        List<ColumnType> types = Arrays.asList(ColumnType.INT, ColumnType.FLOAT, ColumnType.BOOL, ColumnType.VARCHAR);
        byte[] bytes = Serializer.encodeRow(rowVals, types);
        List<Object> decoded = Serializer.decodeRow(bytes, types);
        a.checkEquals(rowVals, decoded, "Serializer 全类型往返一致");

        // —— FileManager + BufferPool ——
        File dir = TestFiles.tempDir("storage");
        try {
            FileManager fm = new FileManager(dir.getAbsolutePath(), "t.dat");
            fm.init();
            a.checkEquals(1, fm.pageCount(), "init 后含第 0 页");
            int id = fm.allocatePage();
            a.checkEquals(1, id, "分配第一个数据页为 1");

            BufferPool bp = new BufferPool(BufferPool.Strategy.LRU, fm);
            Page page = bp.getPage(id);
            a.checkEquals(true, page != null, "缓冲池取页");
            page.writeRow(new byte[]{1, 2, 3});
            bp.markDirty(id);
            bp.flushAll();
            Page disk = fm.readPage(id);
            a.checkEquals(1, disk.getSlotCount(), "刷盘后行落盘");
            a.checkEquals(true, Arrays.equals(new byte[]{1, 2, 3}, disk.readRow(0)), "刷盘后行内容一致");

            // 命中 / 未命中统计
            BufferPool bp2 = new BufferPool(BufferPool.Strategy.FIFO, fm);
            bp2.getPage(id);
            bp2.getPage(id);
            a.checkEquals(1, bp2.getHitCount(), "命中次数");
            a.checkEquals(1, bp2.getMissCount(), "未命中次数");
            a.checkEquals(0.5, bp2.hitRate(), "命中率 0.5");

            // 页释放 -> 空闲页复用
            fm.freePage(id);
            a.checkEquals(true, fm.isFreePage(id), "释放后为空闲页");
            int reused = fm.allocatePage();
            a.checkEquals(id, reused, "复用空闲页");
        } finally {
            TestFiles.deleteRecursively(dir);
        }

        return a.summary("StorageTest 存储系统");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
