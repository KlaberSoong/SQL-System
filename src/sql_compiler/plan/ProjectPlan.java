package sql_compiler.plan;

import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Star;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影算子：对子算子输出的记录按 SELECT 表达式列表投影。
 * 保存的是投影表达式（列引用/常量/算术等）；单个 Star 表示 SELECT *，由执行引擎结合 schema 展开。
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

    // 输出列名列表（仅展示用：Star→"*"、ColumnRef→列名、其余→表达式文本）
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
