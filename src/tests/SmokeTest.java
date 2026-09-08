package tests;

import sql_compiler.Token;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import storage.Page;
import utils.ColumnType;
import utils.Operator;
import utils.Serializer;
import utils.TokenType;

import java.util.Arrays;
import java.util.List;

/**
 * 冒烟测试：验证接口契约（枚举 / Token / AST / Plan / Serializer / Page）可编译、可正常使用。
 */
public class SmokeTest {
    public static void main(String[] args) {
        // 1. Token 与四元式输出
        Token t = new Token(TokenType.KEYWORD, "SELECT", 1, 1);
        System.out.println("Token: " + t);

        // 2. AST 构造
        SelectStmt sel = new SelectStmt(
                Arrays.asList(new ColumnRef(null, "id"), new ColumnRef(null, "name")),
                "student",
                new Comparison(Operator.GT, new ColumnRef(null, "age"), new Literal(18, ColumnType.INT)));
        System.out.println("AST: " + sel);

        // 3. 计划树构造（Project -> Filter -> SeqScan）
        ProjectPlan plan = new ProjectPlan(
                Arrays.asList("id", "name"),
                new FilterPlan(sel.getWhere(), new SeqScanPlan("student")));
        System.out.println("Plan:\n" + plan.toTree());

        // 4. 序列化往返
        List<Object> row = Arrays.asList(1, "Alice", 20);
        List<ColumnType> types = Arrays.asList(ColumnType.INT, ColumnType.VARCHAR, ColumnType.INT);
        byte[] bytes = Serializer.encodeRow(row, types);
        List<Object> decoded = Serializer.decodeRow(bytes, types);
        System.out.println("Serializer round-trip: " + decoded + " (size=" + bytes.length + "B)");

        // 5. 页头读写
        Page page = new Page(7);
        page.setFreeSpaceOffset(100);
        page.setSlotCount(3);
        System.out.println("Page: id=" + page.getPageId()
                + ", freeSpaceOffset=" + page.getFreeSpaceOffset()
                + ", slotCount=" + page.getSlotCount()
                + ", nextPageId=" + page.getNextPageId());

        System.out.println("\nSmokeTest PASSED: interface contracts are consistent.");
    }
}
