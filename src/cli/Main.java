package cli;

import engine.CatalogManager;
import engine.Executor;
import engine.QueryResult;
import engine.StorageEngine;
import sql_compiler.Catalog;
import sql_compiler.Lexer;
import sql_compiler.Optimizer;
import sql_compiler.Parser;
import sql_compiler.Planner;
import sql_compiler.SemanticAnalyzer;
import sql_compiler.ast.Statement;
import sql_compiler.plan.PlanNode;
import sql_compiler.Token;
import utils.Constants;
import utils.DbException;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

/**
 * 命令行入口：交互式 REPL（{@code MiniDB > }），逐条执行 SQL；返回结果集或错误信息；
 * {@code quit} / {@code exit} 退出。也支持从 SQL 文件（首个命令行参数）或标准输入
 * 读入多条语句；传入 {@code --gui} / {@code -g} 则启动 cmd 风格图形窗口（见
 * {@link CmdWindow}）。
 */
public class Main {
    public static void main(String[] args) throws Exception {
        StorageEngine storage = new StorageEngine(Constants.DEFAULT_DATA_DIR);
        CatalogManager catalogManager = new CatalogManager(storage);
        Catalog catalog = catalogManager.loadCatalog();

        if (args.length > 0 && ("--gui".equals(args[0]) || "-g".equals(args[0]))) {
            SwingUtilities.invokeLater(() -> new CmdWindow(storage, catalogManager, catalog).show());
            return;
        }

        if (args.length > 0) {
            // 从 SQL 文件读入多条语句
            runFile(args[0], storage, catalogManager, catalog);
            return;
        }

        // 交互式 REPL
        System.out.println("MiniDB 已启动（Java 版）。输入 SQL 语句，或输入 quit 退出。");
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
            System.out.println(executeAndFormat(line, storage, catalogManager, catalog));
        }
    }

    /** 逐条执行文件中的 SQL（每条语句以分号结尾）。 */
    private static void runFile(String path, StorageEngine storage,
                                CatalogManager catalogManager, Catalog catalog) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            StringBuilder sql = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sql.append(line).append('\n');
            }
            System.out.println(executeAndFormat(sql.toString(), storage, catalogManager, catalog));
        }
    }

    /**
     * 编译并执行一段 SQL（可能含多条以分号分隔的语句），返回格式化后的文本结果。
     *
     * <p>供终端 REPL 与 {@link CmdWindow} 共用：词法 → 语法 → 语义 → 计划 → 优化 → 执行，
     * 逐条语句执行并累积结果；任何 {@link DbException} 被捕获后把错误消息追加到结果末尾。
     *
     * @return 结果字符串（多条语句结果之间以换行分隔；源为空时返回空串）
     */
    public static String executeAndFormat(String sql, StorageEngine storage,
                                          CatalogManager catalogManager, Catalog catalog) {
        StringBuilder out = new StringBuilder();
        try {
            List<Token> tokens = new Lexer(sql).tokenize();
            if (tokens.isEmpty()) {
                return "";
            }
            List<Statement> statements = new Parser(tokens).parseProgram();
            for (Statement stmt : statements) {
                new SemanticAnalyzer(catalog).analyze(Collections.singletonList(stmt));
                PlanNode plan = new Planner().plan(stmt);
                plan = new Optimizer().optimize(plan);
                Object result = new Executor(storage, catalogManager).execute(plan);
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(formatResult(result));
            }
        } catch (DbException e) {
            out.append(e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：非 DbException 的运行时异常（如类型转换失败）也要显示出来，
            // 避免在 javaw 无控制台的 GUI 下静默无响应。
            out.append("[internal error] ").append(e.toString());
        }
        return out.toString();
    }

    /** 把执行结果格式化为文本：查询结果集按表格输出，其余按字符串提示输出。 */
    private static String formatResult(Object result) {
        if (result instanceof QueryResult) {
            QueryResult q = (QueryResult) result;
            if (q.getColumns().isEmpty()) {
                return "(empty result)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.join(" | ", q.getColumns())).append('\n');
            sb.append(repeat("-", Math.max(1, q.getColumns().size() * 10))).append('\n');
            for (List<Object> row : q.getRows()) {
                StringBuilder rowSb = new StringBuilder();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        rowSb.append(" | ");
                    }
                    rowSb.append(formatValue(row.get(i)));
                }
                sb.append(rowSb).append('\n');
            }
            sb.append('(').append(q.getRows().size()).append(" row")
                    .append(q.getRows().size() == 1 ? "" : "s").append(')');
            return sb.toString();
        } else {
            return String.valueOf(result);
        }
    }

    private static String formatValue(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof String) {
            return "'" + v + "'";
        }
        return String.valueOf(v);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
