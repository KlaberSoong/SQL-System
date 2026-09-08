package sql_compiler;

import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行计划生成器（对应 plan.md 3.5）：把 AST 转换为逻辑执行计划（算子树）。
 *
 * 转换规则（与 plan.md 3.5 严格对应）：
 *   CreateTableStmt -> CreateTablePlan(table, columns)
 *   InsertStmt      -> InsertPlan(table, columns, values)
 *   SelectStmt      -> Project(选择列) -> [Filter(WHERE)] -> SeqScan(table)
 *   DeleteStmt      -> DeletePlan(table, condition)
 *
 * 关于 SELECT * 的约定（重要，执行引擎需要遵守）：
 *   本类不持有 Catalog，无法把 * 展开为具体列名，因此 SELECT * 生成的 Project 的
 *   列列表为 ["*"]（单元素、内容为 "*" 的哨兵值）。执行引擎（Executor）遇到 ["*"]
 *   时表示「输出全部列」，需结合表的 schema（来自 StorageEngine/CatalogManager）展开。
 *   显式列（如 SELECT id, name）则直接生成 ["id", "name"]。
 */
public class Planner {

    /** 单条语句 -> 逻辑计划根节点。 */
    public PlanNode plan(Statement stmt) {
        if (stmt instanceof CreateTableStmt) {
            CreateTableStmt c = (CreateTableStmt) stmt;
            return new CreateTablePlan(c.getTable(), c.getColumns());
        }
        if (stmt instanceof InsertStmt) {
            InsertStmt i = (InsertStmt) stmt;
            return new InsertPlan(i.getTable(), i.getColumns(), i.getValues());
        }
        if (stmt instanceof SelectStmt) {
            return planSelect((SelectStmt) stmt);
        }
        if (stmt instanceof DeleteStmt) {
            DeleteStmt d = (DeleteStmt) stmt;
            return new DeletePlan(d.getTable(), d.getWhere());
        }
        throw new IllegalArgumentException("cannot plan unknown statement: " + stmt.getClass().getSimpleName());
    }

    /**
     * SELECT 计划：自底向上构造 SeqScan -> (Filter) -> Project。
     * 这样 Filter 天然紧贴 SeqScan（谓词在最底层过滤），是后续优化的起点，
     * 也是 plan.md 3.6 谓词下推规则所期望的规范形状。
     */
    private PlanNode planSelect(SelectStmt sel) {
        // 1. 最底层：全表扫描
        PlanNode node = new SeqScanPlan(sel.getTable());

        // 2. 中间层：有 WHERE 时加 Filter
        if (sel.getWhere() != null) {
            node = new FilterPlan(sel.getWhere(), node);
        }

        // 3. 最上层：Project（SELECT 列）
        node = new ProjectPlan(projectColumns(sel), node);
        return node;
    }

    /** 把 SELECT 列表（ColumnRef / Star）映射为 Project 的列名列表。 */
    private List<String> projectColumns(SelectStmt sel) {
        List<String> cols = new ArrayList<>();
        for (Expr item : sel.getSelectItems()) {
            if (item instanceof Star) {
                cols.add("*"); // SELECT * 的哨兵值（见类顶部注释）
            } else if (item instanceof ColumnRef) {
                // 单表查询：忽略可能的表前缀，仅取列名即可
                cols.add(((ColumnRef) item).getColumn());
            } else {
                // grammar 中 select_list 只允许 * 与 column_ref，正常不会走到这里
                cols.add(item.toString());
            }
        }
        return cols;
    }
}
