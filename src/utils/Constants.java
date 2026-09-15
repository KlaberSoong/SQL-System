package utils;

/**
 * 全局共享常量。
 */
public final class Constants {
    private Constants() {
    }

    /** 页大小：固定 4KB。 */
    public static final int PAGE_SIZE = 4096;

    /** 页头固定字节数（pageId/pageType/freeSpaceOffset/slotCount/nextPageId/formatVersion）。 */
    public static final int HEADER_SIZE = 24;

    /**
     * 数据文件的格式版本号，写在每页页头的最后一个字段（偏移 20，大端 4 字节）。
     *
     * <p><b>改动下面任一项时必须把它 +1：</b>
     * <ul>
     *   <li>{@link Serializer} 的行编码——字段宽度、字节序、增删标志位</li>
     *   <li>页头或槽目录的布局</li>
     * </ul>
     *
     * <p>起因是一次真实的静默失配：{@code Serializer} 给每个值加了 1 字节 NULL 标志后，
     * INT 行由 4 字节变成 5 字节，而旧的 {@code .dat} 仍被当作"格式合法"打开，
     * 一直到某一行解码时才抛 {@code decodeRow failed}——错误点离病因很远，
     * 看起来像数据损坏而不是格式不兼容。有了版本号，打开文件的瞬间就能报出
     * "文件是版本 N，程序要版本 M"，并且顺带挡住了"按旧布局去解链指针"这类更危险的读法。
     *
     * <p><b>0 不是合法版本</b>：它表示"本程序引入版本号之前写下的文件"。这类文件无法判断
     * 其行编码是否与当前版本一致，因此同样被拒绝装载（见 {@code storage.FileManager}）。
     */
    public static final int FILE_FORMAT_VERSION = 1;

    /** 缓冲池默认可容纳的页数。 */
    public static final int DEFAULT_BUFFER_SIZE = 16;

    /** 表数据文件后缀。 */
    public static final String DATA_FILE_SUFFIX = ".dat";

    /** 系统目录特殊表名。 */
    public static final String CATALOG_FILE = "pg_catalog";

    /** 默认数据目录。 */
    public static final String DEFAULT_DATA_DIR = "data";
}
