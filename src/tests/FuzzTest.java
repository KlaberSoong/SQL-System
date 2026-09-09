package tests;

import engine.CatalogManager;
import engine.Executor;
import engine.StorageEngine;
import sql_compiler.Catalog;
import sql_compiler.Lexer;
import sql_compiler.Optimizer;
import sql_compiler.Parser;
import sql_compiler.Planner;
import sql_compiler.SemanticAnalyzer;
import sql_compiler.Token;
import sql_compiler.ast.Statement;
import sql_compiler.plan.PlanNode;
import utils.ColumnType;
import utils.DbException;
import utils.LexError;
import utils.SyntaxError;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Fuzz 测试（对应 plan.md 第八节）：随机生成合法 SQL（应通过全链路）+ 随机变异非法 SQL（应拒绝且不崩溃）。
 *
 * 关注指标：Crash（抛出非 DbException）/ WrongAccept（变异后仍被接受）/ WrongReject（合法 SQL 被拒绝）/
 * ErrorLocation（词法/语法错误是否含位置）。固定随机种子保证可复现。
 */
public class FuzzTest {

    public static int run() {
        Assert a = new Assert();
        Random rnd = new Random(20240909L); // 固定种子，可复现

        File dir = TestFiles.tempDir("fuzz");
        try {
            StorageEngine storage = new StorageEngine(dir.getAbsolutePath());
            CatalogManager cm = new CatalogManager(storage);
            Catalog catalog = cm.loadCatalog();

            List<String> tableNames = new ArrayList<>();
            List<List<Col>> tables = new ArrayList<>();

            int wrongReject = 0, wrongAccept = 0, crash = 0, badLocation = 0;

            // —— 阶段一：随机生成合法 SQL，应全部通过全链路 ——
            int rounds = 120;
            for (int i = 0; i < rounds; i++) {
                if (tableNames.isEmpty() || rnd.nextInt(4) == 0) {
                    String name = "t" + tableNames.size();
                    List<Col> cols = genColumns(rnd);
                    try {
                        executeOne(storage, cm, catalog, genCreate(name, cols));
                        tableNames.add(name);
                        tables.add(cols);
                    } catch (DbException e) {
                        wrongReject++;
                    } catch (Throwable t) {
                        crash++;
                    }
                    continue;
                }
                int ti = rnd.nextInt(tableNames.size());
                String tn = tableNames.get(ti);
                List<Col> cols = tables.get(ti);
                String sql;
                switch (rnd.nextInt(3)) {
                    case 0: sql = genInsert(tn, cols, rnd); break;
                    case 1: sql = genSelect(tn, cols, rnd); break;
                    default: sql = genDelete(tn, cols, rnd); break;
                }
                try {
                    executeOne(storage, cm, catalog, sql);
                } catch (DbException e) {
                    wrongReject++;
                } catch (Throwable t) {
                    crash++;
                }
            }
            a.checkEquals(0, wrongReject, "合法 SQL 零拒绝（WrongReject）");
            a.checkEquals(0, crash, "合法 SQL 无崩溃");

            // —— 阶段二：随机变异合法 SQL，应拒绝（DbException）且不崩溃 ——
            List<String> samples = new ArrayList<>();
            for (int i = 0; i < 40 && !tableNames.isEmpty(); i++) {
                int ti = rnd.nextInt(tableNames.size());
                samples.add(genSelect(tableNames.get(ti), tables.get(ti), rnd));
            }
            for (String base : samples) {
                String mutated = mutate(base, rnd);
                try {
                    executeOne(storage, cm, catalog, mutated);
                    wrongAccept++;
                } catch (LexError le) {
                    if (le.getLine() < 1 || le.getCol() < 1) badLocation++;
                } catch (SyntaxError se) {
                    if (se.getLine() < 1 || se.getCol() < 1) badLocation++;
                } catch (DbException ignored) {
                    // 正常拒绝
                } catch (Throwable t) {
                    crash++;
                }
            }
            a.checkEquals(0, crash, "变异无崩溃");
            a.checkEquals(0, badLocation, "词法/语法错误均含位置");

            System.out.println("   [Fuzz] 变异 " + samples.size() + " 条：WrongAccept=" + wrongAccept
                    + "（变异后仍合法，仅供参考，不计失败）");
        } finally {
            TestFiles.deleteRecursively(dir);
        }
        return a.summary("FuzzTest 模糊测试");
    }

    /** 单表一列的最小结构描述（仅测试用）。 */
    private static final class Col {
        final String name;
        final ColumnType type;
        final int varcharLen;

        Col(String name, ColumnType type, int varcharLen) {
            this.name = name;
            this.type = type;
            this.varcharLen = varcharLen;
        }
    }

    /** 完整「词法→语法→语义→计划→优化→执行」流水线；失败抛 DbException（崩溃则抛其它 Throwable）。 */
    private static void executeOne(StorageEngine storage, CatalogManager cm, Catalog catalog, String sql) {
        List<Token> tokens = new Lexer(sql).tokenize();
        if (tokens.isEmpty()) {
            return;
        }
        List<Statement> stmts = new Parser(tokens).parseProgram();
        for (Statement stmt : stmts) {
            new SemanticAnalyzer(catalog).analyze(Collections.singletonList(stmt));
            PlanNode plan = new Planner().plan(stmt);
            plan = new Optimizer().optimize(plan);
            new Executor(storage, cm).execute(plan);
        }
    }

    // —— 生成器 ——

    private static List<Col> genColumns(Random rnd) {
        int n = 2 + rnd.nextInt(4); // 2..5 列
        List<Col> cols = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ColumnType[] types = {ColumnType.INT, ColumnType.FLOAT, ColumnType.VARCHAR, ColumnType.BOOL};
            ColumnType t = types[rnd.nextInt(types.length)];
            int len = (t == ColumnType.VARCHAR) ? 4 + rnd.nextInt(12) : 0;
            cols.add(new Col("c" + i, t, len));
        }
        return cols;
    }

    private static String genCreate(String name, List<Col> cols) {
        StringBuilder sb = new StringBuilder("CREATE TABLE " + name + "(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(cols.get(i).name).append(" ").append(typeKeyword(cols.get(i)));
        }
        return sb.append(");").toString();
    }

    private static String typeKeyword(Col c) {
        switch (c.type) {
            case INT: return "INT";
            case FLOAT: return "FLOAT";
            case BOOL: return "BOOL";
            default: return "VARCHAR(" + c.varcharLen + ")";
        }
    }

    private static String genLiteral(Random rnd, Col c) {
        switch (c.type) {
            case INT: return String.valueOf(rnd.nextInt(1000));
            case FLOAT: return rnd.nextInt(100) + "." + (1 + rnd.nextInt(9));
            case BOOL: return rnd.nextBoolean() ? "true" : "false";
            default: {
                int len = 1 + rnd.nextInt(Math.max(1, c.varcharLen));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(26)));
                return "'" + sb + "'";
            }
        }
    }

    private static String genInsert(String table, List<Col> cols, Random rnd) {
        StringBuilder sb = new StringBuilder("INSERT INTO " + table + " VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(genLiteral(rnd, cols.get(i)));
        }
        return sb.append(");").toString();
    }

    private static String genSelect(String table, List<Col> cols, Random rnd) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (rnd.nextBoolean()) {
            sb.append("*");
        } else {
            int k = 1 + rnd.nextInt(cols.size());
            List<Integer> idxs = shuffled(rnd, cols.size());
            for (int i = 0; i < k; i++) {
                if (i > 0) sb.append(",");
                sb.append(cols.get(idxs.get(i)).name);
            }
        }
        sb.append(" FROM ").append(table);
        if (rnd.nextBoolean()) {
            sb.append(" WHERE ").append(genCondition(rnd, cols));
        }
        return sb.append(";").toString();
    }

    private static String genDelete(String table, List<Col> cols, Random rnd) {
        StringBuilder sb = new StringBuilder("DELETE FROM " + table);
        if (rnd.nextBoolean()) {
            sb.append(" WHERE ").append(genCondition(rnd, cols));
        }
        return sb.append(";").toString();
    }

    private static String genCondition(Random rnd, List<Col> cols) {
        Col c = cols.get(rnd.nextInt(cols.size()));
        String op;
        switch (c.type) {
            case INT:
            case FLOAT:
                op = new String[]{"=", "!=", "<", "<=", ">", ">="}[rnd.nextInt(6)];
                break;
            default:
                op = rnd.nextBoolean() ? "=" : "!=";
        }
        String cond = c.name + " " + op + " " + genLiteral(rnd, c);
        if (rnd.nextInt(10) < 3) {
            cond = "NOT " + cond;
        } else if (rnd.nextInt(10) < 3) {
            cond = cond + " " + (rnd.nextBoolean() ? "AND" : "OR") + " " + genCondition(rnd, cols);
        }
        return cond;
    }

    private static List<Integer> shuffled(Random rnd, int n) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        Collections.shuffle(idx, rnd);
        return idx;
    }

    private static String mutate(String s, Random rnd) {
        StringBuilder sb = new StringBuilder(s);
        int ops = 1 + rnd.nextInt(3);
        for (int i = 0; i < ops && sb.length() > 0; i++) {
            int p = rnd.nextInt(sb.length());
            switch (rnd.nextInt(4)) {
                case 0: sb.deleteCharAt(p); break;
                case 1: sb.setCharAt(p, (char) (32 + rnd.nextInt(95))); break;
                case 2: sb.insert(p, (char) (32 + rnd.nextInt(95))); break;
                default:
                    if (p + 1 < sb.length()) {
                        char t = sb.charAt(p);
                        sb.setCharAt(p, sb.charAt(p + 1));
                        sb.setCharAt(p + 1, t);
                    }
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }
}
