package tests;

/**
 * 汇总运行全部测试（对应 plan.md 第八节「运行 tests/ 全套」）。
 *
 * 运行方式：{@code java -cp out tests.AllTests}（或 {@code test.bat}）。
 * 任一测试失败则返回非零退出码，便于集成到构建流程。
 */
public class AllTests {
    public static void main(String[] args) {
        int failed = 0;
        failed += LexerTest.run();
        failed += ParserTest.run();
        failed += SemanticTest.run();
        failed += PlannerOptimizerTest.run();
        failed += StorageTest.run();
        failed += EngineTest.run();
        failed += EndToEndTest.run();
        failed += FuzzTest.run();

        System.out.println();
        if (failed == 0) {
            System.out.println("AllTests: ALL PASSED");
        } else {
            System.out.println("AllTests: " + failed + " FAILED");
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
