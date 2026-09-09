package tests;

import java.io.File;

/** 测试用临时目录与递归清理工具（隔离测试数据，避免污染工作区）。 */
public final class TestFiles {
    private TestFiles() {
    }

    /** 在系统临时目录下创建一个唯一子目录。 */
    public static File tempDir(String prefix) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "minidb-" + prefix + "-" + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    /** 递归删除目录及其内容（测试收尾用，尽力而为）。 */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}
