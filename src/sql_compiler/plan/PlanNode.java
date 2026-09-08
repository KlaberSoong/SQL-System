package sql_compiler.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑执行计划节点抽象基类。带 children 列表，提供树形输出（供计划生成阶段打印）。
 */
public abstract class PlanNode {
    protected final List<PlanNode> children = new ArrayList<>();

    public List<PlanNode> getChildren() {
        return children;
    }

    /** 节点自身的单行描述（不含子节点），如 SeqScan[student]。 */
    public abstract String nodeName();

    /** 树形结构输出（ASCII 缩进）。 */
    public String toTree() {
        StringBuilder sb = new StringBuilder();
        buildTree(sb, "", "");
        return sb.toString();
    }

    private void buildTree(StringBuilder sb, String prefix, String childPrefix) {
        sb.append(prefix).append(nodeName());
        for (int i = 0; i < children.size(); i++) {
            boolean last = (i == children.size() - 1);
            sb.append('\n');
            children.get(i).buildTree(
                    sb,
                    childPrefix + (last ? "+- " : "|- "),
                    childPrefix + (last ? "   " : "|  "));
        }
    }

    @Override
    public String toString() {
        return nodeName();
    }
}
