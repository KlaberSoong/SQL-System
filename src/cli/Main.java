package cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 命令行入口：交互式 REPL。
 * 当前为框架占位：进入 REPL，echo 输入，输入 quit / exit 退出。
 */
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("MiniDB 框架已启动（Java 版）。");
        System.out.println("输入 SQL 语句，或输入 quit 退出。");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("MiniDB > ");
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                System.out.println("Bye.");
                break;
            }
            // TODO: 接入 Lexer -> Parser -> Semantic -> Planner -> Optimizer -> Executor 全链路
            System.out.println("[框架占位] 收到: " + line);
        }
    }
}
