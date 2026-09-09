package sql_compiler.plan;

import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Star;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影算子：对子算子输出的记录按 SELECT 列表投影。
 *
 * <p>保存的是「投影表达式」列表（{@link Expr}）：列引用 / 常量 / 算术 / 比较 / 逻辑等。
 * 单个 {@link Star} 表示 {@code SELECT *}（输出全部列），由执行引擎结合表 schema 展开；
 * 其余元素由执行引擎对每一行求值后作为投影结果输出。
 *
 * <p>{@link #getColumns()} 返回用于展示的输出列名（列引用取列名、{@code *} 取 {@code "*"}、
 * 其余取表达式文本），供计划树打印、优化器判断与结果集表头使用。
 */
public class ProjectPlan extends PlanNode {
    private final List<Expr> expressions;

    public ProjectPlan(List<Expr> expressions, PlanNode child) {
        this.expressions = expressions;
        if (child != null) {
            children.add(child);
        }
    }

    public List<Expr> getExpressions() {
        return expressions;
    }

    /** 输出列名列表（仅展示用：Star → "*"、ColumnRef → 列名、其余 → 表达式文本）。 */
    public List<String> getColumns() {
        List<String> names = new ArrayList<>(expressions.size());
        for (Expr e : expressions) {
            if (e instanceof Star) {
                names.add("*");
            } else if (e instanceof ColumnRef) {
                names.add(((ColumnRef) e).getColumn());
            } else {
                names.add(e.toString());
            }
        }
        return names;
    }

    @Override
    public String nodeName() {
        return "Project[" + getColumns() + "]";
    }
}
