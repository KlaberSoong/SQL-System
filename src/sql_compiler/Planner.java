package sql_compiler;

import sql_compiler.ast.AggregateCall;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.JoinRelation;
import sql_compiler.ast.Relation;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Statement;
import sql_compiler.ast.TableRelation;
import sql_compiler.ast.UnaryExpr;
import sql_compiler.ast.UpdateStmt;
import sql_compiler.plan.AggregatePlan;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.JoinPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import sql_compiler.plan.SortPlan;
import sql_compiler.plan.UpdatePlan;

/**
 * 执行计划生成器（对应 plan.md 3.5）：把 AST 转换为逻辑执行计划（算子树）。
 * 转换规则：CreateTableStmt→CreateTablePlan、InsertStmt→InsertPlan、
 * SelectStmt→Project/Sort/Aggregate→[Filter]→[Join]→SeqScan、DeleteStmt→DeletePlan、UpdateStmt→UpdatePlan。
 * SELECT * 的 Project 投影表达式为单个 Star，由执行引擎结合 schema 展开。
 */
public class Planner {

    // 单条语句 -> 逻辑计划根节点
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
        if (stmt instanceof UpdateStmt) {
            UpdateStmt u = (UpdateStmt) stmt;
            return new UpdatePlan(u.getTable(), u.getAssignments(), u.getWhere());
        }
        throw new IllegalArgumentException("cannot plan unknown statement: " + stmt.getClass().getSimpleName());
    }

    // SELECT 计划：自底向上构造 SeqScan/Join -> (Filter) -> (Aggregate|Project) -> (Sort)
    private PlanNode planSelect(SelectStmt sel) {
        PlanNode node = planRelation(sel.getFrom(), false);
        if (sel.getWhere() != null) {
            node = new FilterPlan(sel.getWhere(), node);
        }

        boolean hasGroupBy = sel.getGroupBy() != null && !sel.getGroupBy().isEmpty();
        boolean hasAggregate = hasAggregate(sel.getSelectItems());
        boolean hasOrderBy = sel.getOrderBy() != null && !sel.getOrderBy().isEmpty();
        if (hasGroupBy || hasAggregate) {
            node = new AggregatePlan(sel.getSelectItems(), sel.getGroupBy(), node);
            // 聚合查询：Sort 位于 Aggregate 之上，ORDER BY 键只能引用输出列/分组键
            if (hasOrderBy) {
                node = new SortPlan(sel.getOrderBy(), node);
            }
        } else {
            // 非聚合查询：Sort 位于 Project 之下，ORDER BY 可引用任意 FROM 列
            if (hasOrderBy) {
                node = new SortPlan(sel.getOrderBy(), node);
            }
            node = new ProjectPlan(sel.getSelectItems(), node);
        }
        return node;
    }

    // FROM 关系 -> 扫描/连接子树；qualified 表示是否需用别名限定列名（连接内参与表需消歧）
    private PlanNode planRelation(Relation r, boolean qualified) {
        if (r instanceof TableRelation) {
            TableRelation t = (TableRelation) r;
            String alias = qualified ? (t.getAlias() != null ? t.getAlias() : t.getTable()) : null;
            return new SeqScanPlan(t.getTable(), alias);
        }
        if (r instanceof JoinRelation) {
            JoinRelation j = (JoinRelation) r;
            return new JoinPlan(planRelation(j.getLeft(), true), planRelation(j.getRight(), true),
                    j.getType(), j.getOn());
        }
        throw new IllegalArgumentException("cannot plan unknown relation: " + r.getClass().getSimpleName());
    }

    // 判断表达式列表是否含聚合调用
    private boolean hasAggregate(java.util.List<Expr> exprs) {
        for (Expr e : exprs) {
            if (containsAggregate(e)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAggregate(Expr e) {
        if (e instanceof AggregateCall) {
            return true;
        }
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            return containsAggregate(c.getLeft()) || containsAggregate(c.getRight());
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            return containsAggregate(b.getLeft()) || containsAggregate(b.getRight());
        }
        if (e instanceof UnaryExpr) {
            return containsAggregate(((UnaryExpr) e).getOperand());
        }
        return false;
    }
}
