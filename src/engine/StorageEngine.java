package engine;

import sql_compiler.ast.Expr;
import storage.BufferPool;
import storage.FileManager;
import storage.Page;
import utils.ColumnDef;
import utils.ColumnType;
import utils.Constants;
import utils.DbException;
import utils.Serializer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储引擎：实现记录（Row）与页（Page）之间的映射、序列化，管理空闲页与表扩展。
 *
 * <p>每个表对应一个 {@code data/<table>.dat} 文件；表结构（列定义）保存在内存
 * {@link #schemas} 中，由 {@link CatalogManager} 在启动时从系统目录（pg_catalog）装载、
 * 在 {@link #createTable} 时登记。序列化复用 {@link Serializer}（INT 4B / FLOAT 4B /
 * BOOL 1B / VARCHAR [4B长度][UTF-8]）。
 */
public class StorageEngine {
    private final String dataDir;
    /** 表名 -> 列定义（运行期表结构缓存，启动时由 CatalogManager 装载）。 */
    private final Map<String, List<ColumnDef>> schemas = new LinkedHashMap<>();
    private final Map<String, FileManager> fileManagers = new HashMap<>();
    private final Map<String, BufferPool> bufferPools = new HashMap<>();

    public StorageEngine(String dataDir) {
        this.dataDir = dataDir;
        new File(dataDir).mkdirs();
    }

    /** 表对应的数据文件是否已存在。 */
    public boolean tableExists(String name) {
        return new File(filePath(name)).exists();
    }

    /** 登记表结构（不创建文件，供 CatalogManager 装载目录时填充）。 */
    public void registerSchema(String name, List<ColumnDef> columns) {
        schemas.put(name, columns);
    }

    /** 按表名取表结构；表不存在返回 null。 */
    public List<ColumnDef> getSchema(String name) {
        return schemas.get(name);
    }

    /** 建表：创建数据文件（含第 0 页元数据页）并登记表结构。 */
    public void createTable(String name, List<ColumnDef> columns) {
        if (tableExists(name)) {
            throw new DbException("table '" + name + "' already exists");
        }
        schemas.put(name, columns);
        FileManager fm = new FileManager(dataDir, fileName(name));
        fm.init();
        fileManagers.put(name, fm);
        bufferPools.put(name, new BufferPool(BufferPool.Strategy.AUTO, fm));
    }

    /** 插入一行（Row -> 字节 -> 页）。 */
    public void insertRow(String table, List<Object> values) {
        List<ColumnDef> schema = requireSchema(table);
        if (values.size() != schema.size()) {
            throw new DbException("INSERT into '" + table + "' expects " + schema.size()
                    + " values, but got " + values.size());
        }
        byte[] bytes = Serializer.encodeRow(values, toTypes(schema));
        FileManager fm = fileManager(table);
        BufferPool bp = bufferPool(table);

        Page page = findPageWithSpace(bp, fm, bytes.length);
        if (page == null) {
            page = bp.getPage(fm.allocatePage());
        }
        if (page == null || !page.writeRow(bytes)) {
            throw new DbException("row too large to fit in a page");
        }
        bp.markDirty(page.getPageId());
        bp.flushAll();
    }

    /** 全表扫描，返回所有行（每行是值列表）。 */
    public List<List<Object>> scanTable(String table) {
        List<ColumnDef> schema = requireSchema(table);
        List<ColumnType> types = toTypes(schema);
        FileManager fm = fileManager(table);
        BufferPool bp = bufferPool(table);

        List<List<Object>> rows = new ArrayList<>();
        for (int id = 1; id < fm.pageCount(); id++) {
            if (fm.isFreePage(id)) {
                continue;
            }
            Page p = bp.getPage(id);
            if (p == null) {
                continue;
            }
            for (int s = 0; s < p.getSlotCount(); s++) {
                rows.add(Serializer.decodeRow(p.readRow(s), types));
            }
        }
        return rows;
    }

    /** 按条件删除行（条件为 null 表示删除全表），返回删除行数。 */
    public int deleteRows(String table, Expr condition) {
        List<ColumnDef> schema = requireSchema(table);
        List<List<Object>> all = scanTable(table);
        List<List<Object>> survivors = new ArrayList<>();
        if (condition == null) {
            // 无条件：全部删除
        } else {
            Map<String, Integer> idx = ExpressionEvaluator.indexMap(columnNames(schema));
            for (List<Object> row : all) {
                if (!ExpressionEvaluator.evalCondition(condition, idx, row)) {
                    survivors.add(row);
                }
            }
        }
        int removed = all.size() - survivors.size();
        rewriteTable(table, survivors);
        return removed;
    }

    /** 全量重写表：清空所有数据页后重新打包写入，并回收尾部空页。 */
    private void rewriteTable(String table, List<List<Object>> rows) {
        FileManager fm = fileManager(table);
        BufferPool bp = bufferPool(table);
        List<ColumnType> types = toTypes(requireSchema(table));

        // 1. 清空所有数据页
        for (int id = 1; id < fm.pageCount(); id++) {
            Page p = bp.getPage(id);
            if (p != null) {
                p.clear();
                bp.markDirty(id);
            }
        }

        // 2. 重新打包写入存活行，记录最后用到（写满）的页号
        int lastUsed = 0;
        for (List<Object> row : rows) {
            byte[] bytes = Serializer.encodeRow(row, types);
            Page page = findPageWithSpace(bp, fm, bytes.length);
            if (page == null) {
                page = bp.getPage(fm.allocatePage());
            }
            if (page == null || !page.writeRow(bytes)) {
                throw new DbException("row too large to fit in a page");
            }
            bp.markDirty(page.getPageId());
            lastUsed = Math.max(lastUsed, page.getPageId());
        }

        // 3. 先落盘已清空/重写的内容，再回收尾部空页（空闲页链表由 FileManager 直接读写磁盘）
        bp.flushAll();
        for (int id = fm.pageCount() - 1; id > lastUsed; id--) {
            fm.freePage(id);
        }
    }

    /** 在已分配的数据页中找第一个能放下 rowLen 字节（含槽目录项）的页。 */
    private Page findPageWithSpace(BufferPool bp, FileManager fm, int rowLen) {
        for (int id = 1; id < fm.pageCount(); id++) {
            if (fm.isFreePage(id)) {
                continue;
            }
            Page p = bp.getPage(id);
            if (p != null && p.hasSpace(rowLen)) {
                return p;
            }
        }
        return null;
    }

    private List<ColumnDef> requireSchema(String table) {
        List<ColumnDef> schema = schemas.get(table);
        if (schema == null) {
            throw new DbException("table '" + table + "' does not exist");
        }
        return schema;
    }

    /** 取（或惰性创建）表对应的 FileManager。 */
    private FileManager fileManager(String table) {
        FileManager fm = fileManagers.get(table);
        if (fm == null) {
            fm = new FileManager(dataDir, fileName(table));
            fileManagers.put(table, fm);
            bufferPools.put(table, new BufferPool(BufferPool.Strategy.AUTO, fm));
        }
        return fm;
    }

    private BufferPool bufferPool(String table) {
        fileManager(table);
        return bufferPools.get(table);
    }

    /** 汇总所有表缓冲池的命中/未命中次数，返回 [hit, miss]。 */
    public int[] bufferPoolStats() {
        int hit = 0;
        int miss = 0;
        for (BufferPool bp : bufferPools.values()) {
            hit += bp.getHitCount();
            miss += bp.getMissCount();
        }
        return new int[]{hit, miss};
    }

    /** 返回所有表缓冲池当前实际生效的策略（去重）。 */
    public List<BufferPool.Strategy> activeStrategies() {
        List<BufferPool.Strategy> list = new ArrayList<>();
        for (BufferPool bp : bufferPools.values()) {
            BufferPool.Strategy s = bp.getActiveStrategy();
            if (!list.contains(s)) {
                list.add(s);
            }
        }
        return list;
    }

    private String filePath(String name) {
        return dataDir + File.separator + fileName(name);
    }

    private String fileName(String name) {
        return name + Constants.DATA_FILE_SUFFIX;
    }

    private static List<ColumnType> toTypes(List<ColumnDef> schema) {
        List<ColumnType> types = new ArrayList<>(schema.size());
        for (ColumnDef c : schema) {
            types.add(c.getType());
        }
        return types;
    }

    private static List<String> columnNames(List<ColumnDef> schema) {
        List<String> names = new ArrayList<>(schema.size());
        for (ColumnDef c : schema) {
            names.add(c.getName());
        }
        return names;
    }
}
