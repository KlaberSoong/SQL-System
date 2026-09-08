package utils;

/**
 * 全局共享常量。
 */
public final class Constants {
    private Constants() {
    }

    /** 页大小：固定 4KB。 */
    public static final int PAGE_SIZE = 4096;

    /** 页头固定字节数（pageId/pageType/freeSpaceOffset/slotCount/nextPageId/保留）。 */
    public static final int HEADER_SIZE = 24;

    /** 缓冲池默认可容纳的页数。 */
    public static final int DEFAULT_BUFFER_SIZE = 16;

    /** 表数据文件后缀。 */
    public static final String DATA_FILE_SUFFIX = ".dat";

    /** 系统目录特殊表名。 */
    public static final String CATALOG_FILE = "pg_catalog";

    /** 默认数据目录。 */
    public static final String DEFAULT_DATA_DIR = "data";
}
