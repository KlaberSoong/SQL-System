package tests;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简测试断言工具（不依赖 JUnit）：收集失败项，由各测试类的 main 汇总输出。
 *
 * <p>用法：
 * <pre>
 *   Assert a = new Assert();
 *   a.check(condition, "描述");
 *   a.checkEquals(expected, actual, "描述");
 *   a.checkThrows(LexError.class, () -&gt; { ... }, "描述");
 *   a.checkContains(haystack, needle, "描述");
 *   int failed = a.summary("LexerTest");   // 0 表示全部通过
 * </pre>
 */
public final class Assert {

    private int passed = 0;
    private final List<String> failures = new ArrayList<>();

    /** 可抛异常的运行片段，用于 {@link #checkThrows}。 */
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public void check(boolean condition, String name) {
        if (condition) {
            passed++;
        } else {
            failures.add("FAIL: " + name);
        }
    }

    public void checkTrue(boolean condition, String name) {
        check(condition, name);
    }

    public void checkFalse(boolean condition, String name) {
        check(!condition, name);
    }

    public void checkEquals(Object expected, Object actual, String name) {
        boolean eq = (expected == null) ? (actual == null) : expected.equals(actual);
        if (eq) {
            passed++;
        } else {
            failures.add("FAIL: " + name + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    public void checkContains(String haystack, String needle, String name) {
        if (haystack != null && haystack.contains(needle)) {
            passed++;
        } else {
            failures.add("FAIL: " + name + " — expected to contain <" + needle + "> in <" + haystack + ">");
        }
    }

    public void checkNotContains(String haystack, String needle, String name) {
        if (haystack != null && !haystack.contains(needle)) {
            passed++;
        } else {
            failures.add("FAIL: " + name + " — expected NOT to contain <" + needle + "> in <" + haystack + ">");
        }
    }

    /**
     * 期望 {@code run} 抛出指定类型异常；返回捕获到的异常（未抛出或类型不符则记录失败并返回 null）。
     */
    public <T extends Throwable> T checkThrows(Class<T> type, ThrowingRunnable run, String name) {
        try {
            run.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                return type.cast(t);
            }
            failures.add("FAIL: " + name + " — expected " + type.getSimpleName()
                    + " but got " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
        failures.add("FAIL: " + name + " — expected " + type.getSimpleName() + " but no exception thrown");
        return null;
    }

    /** 打印该测试类的汇总并返回失败数。 */
    public int summary(String title) {
        System.out.println("== " + title + " ==");
        for (String f : failures) {
            System.out.println("   " + f);
        }
        int total = passed + failures.size();
        System.out.println("   " + passed + "/" + total + " passed"
                + (failures.isEmpty() ? "" : "   *** " + failures.size() + " FAILED ***"));
        return failures.size();
    }
}
