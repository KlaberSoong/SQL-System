package sql_compiler;

import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Literal;
import sql_compiler.ast.UnaryExpr;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import utils.ColumnType;
import utils.Operator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 规则式优化器（对应 plan.md 3.6）：对逻辑计划做等价的规则重写。
 *
 * 五条规则（依次应用，并迭代到不动点）：
 *   1. constantFold      常量折叠：   age > 10 + 8   ->  age > 18
 *   2. booleanSimplify   布尔化简：   1=1 AND X      ->  X；  1=0 OR X -> X
 *   3. projectPruning    投影剪枝：   剪掉 SELECT * 产生的冗余 Project["*"]
 *   4. predicatePushdown 谓词下推：   Filter 尽量下移到靠近 SeqScan
 *   5. removeRedundant   冗余消除：   去掉恒真（true）的 Filter 等无意义节点
 *
 * 说明：本系统的 Planner 默认就生成 Project -> Filter -> SeqScan 的规范形状，
 *       因此第 3、4 条规则对默认计划通常不触发；它们作为通用规则保留，供更复杂的
 *       计划形状使用，也满足「五条必做规则」的要求。
 *       真正可见的优化效果来自 1 / 2 / 5，例如（plan.md 验收示例）：
 *         SELECT name FROM student WHERE 1=1 AND age > 10+8
 *         优化前：Project[name] -> Filter[(1 = 1) AND (age > (10 + 8))] -> SeqScan[student]
 *         优化后：Project[name] -> Filter[(age > 18)]                 -> SeqScan[student]
 *
 * 设计要点（重要）：所有规则都是「纯」的——不修改传入的节点，而是返回重建后的新节点。
 * 因此调用方可以同时保留优化前、优化后的两棵树，用于展示前后结构对比（硬性验收点）。
 */
public class Optimizer {
    /** 最大优化轮数：防止异常情况下死循环；正常计划一两轮即达不动点。 */
    private static final int MAX_PASSES = 4;

    /** 优化主入口：迭代应用五条规则，直到计划结构不再变化（达不动点）。 */
    public PlanNode optimize(PlanNode plan) {
        PlanNode current = plan;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            PlanNode next = applyAllRules(current);
            // 树形文本相等即视为结构等价（本项目计划树很小，开销可忽略）
            if (next.toTree().equals(current.toTree())) {
                return next; // 已达不动点，提前结束
            }
            current = next;
        }
        return current;
    }

    /** 依次应用五条规则（注意顺序：先折叠/化简表达式，再做结构级改写）。 */
    private PlanNode applyAllRules(PlanNode n) {
        n = constantFold(n);
        n = booleanSimplify(n);
        n = projectPruning(n);
        n = predicatePushdown(n);
        n = removeRedundant(n);
        return n;
    }

    // =====================================================================
    // 规则 1：常量折叠（constantFold）
    // =====================================================================

    /** 自底向上折叠所有表达式位置上的算术常量。返回折叠后的计划（未变化则返回原节点）。 */
    private PlanNode constantFold(PlanNode n) {
        // 先递归折叠子节点（mapChildren 不修改原树，返回重建后的节点）
        PlanNode r = mapChildren(n, this::constantFold);

        // 再折叠本节点自身的表达式
        if (r instanceof FilterPlan) {
            FilterPlan f = (FilterPlan) r;
            Expr folded = foldExpr(f.getCondition());
            if (folded != f.getCondition()) {
                return new FilterPlan(folded, onlyChild(f));
            }
        } else if (r instanceof DeletePlan) {
            DeletePlan d = (DeletePlan) r;
            if (d.getCondition() != null) {
                Expr folded = foldExpr(d.getCondition());
                if (folded != d.getCondition()) {
                    return new DeletePlan(d.getTable(), folded);
                }
            }
        } else if (r instanceof InsertPlan) {
            InsertPlan ip = (InsertPlan) r;
            List<Expr> old = ip.getValues();
            List<Expr> folded = foldExprList(old);
            if (folded != old) {
                return new InsertPlan(ip.getTable(), ip.getColumns(), folded);
            }
        }
        return r;
    }

    /** 折叠一个表达式：返回折叠后的表达式（无法折叠则返回原对象）。 */
    private Expr foldExpr(Expr e) {
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            Expr l = foldExpr(c.getLeft());
            Expr r = foldExpr(c.getRight());
            if (l != c.getLeft() || r != c.getRight()) {
                return new Comparison(c.getOp(), l, r);
            }
            return c;
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            Expr l = foldExpr(b.getLeft());
            Expr r = foldExpr(b.getRight());
            // 两侧都折叠成字面量且是算术运算 -> 直接算出结果（如 10 + 8 -> 18）
            if (b.getOp().isArithmetic() && l instanceof Literal && r instanceof Literal) {
                Literal result = evalArithmetic(b.getOp(), (Literal) l, (Literal) r);
                if (result != null) {
                    return result;
                }
            }
            if (l != b.getLeft() || r != b.getRight()) {
                return new BinaryExpr(b.getOp(), l, r);
            }
            return b;
        }
        if (e instanceof UnaryExpr) {
            UnaryExpr u = (UnaryExpr) e;
            Expr operand = foldExpr(u.getOperand());
            if (operand != u.getOperand()) {
                return new UnaryExpr(u.getOp(), operand);
            }
            return u;
        }
        // Literal / ColumnRef / Star 无需折叠
        return e;
    }

    /** 计算两个数值字面量的算术结果；非数值或非算术运算返回 null（表示无法折叠）。 */
    private Literal evalArithmetic(Operator op, Literal l, Literal r) {
        if (!(l.getValue() instanceof Number) || !(r.getValue() instanceof Number)) {
            return null; // 语义分析阶段应已拦截非数值算术，这里仅防御
        }
        double a = ((Number) l.getValue()).doubleValue();
        double b = ((Number) r.getValue()).doubleValue();
        double result;
        switch (op) {
            case PLUS:  result = a + b; break;
            case MINUS: result = a - b; break;
            case MUL:   result = a * b; break;
            case DIV:   result = a / b; break; // 除零得到 Inf/NaN，不抛异常
            default:    return null;           // 非算术运算
        }
        // 除法或任一操作数为 FLOAT -> 结果为 FLOAT；否则为 INT（与 TypeSystem 规则一致）
        boolean isFloat = op == Operator.DIV
                || l.getType() == ColumnType.FLOAT
                || r.getType() == ColumnType.FLOAT;
        if (isFloat) {
            return new Literal((float) result, ColumnType.FLOAT);
        }
        return new Literal((int) result, ColumnType.INT);
    }

    // =====================================================================
    // 规则 2：布尔化简（booleanSimplify）
    // =====================================================================

    /** 化简布尔表达式：先求值常量比较，再套用 AND/OR/NOT 恒等式。 */
    private PlanNode booleanSimplify(PlanNode n) {
        PlanNode r = mapChildren(n, this::booleanSimplify);

        if (r instanceof FilterPlan) {
            FilterPlan f = (FilterPlan) r;
            Expr s = simplifyExpr(f.getCondition());
            if (s != f.getCondition()) {
                return new FilterPlan(s, onlyChild(f));
            }
        } else if (r instanceof DeletePlan) {
            DeletePlan d = (DeletePlan) r;
            if (d.getCondition() != null) {
                Expr s = simplifyExpr(d.getCondition());
                if (s != d.getCondition()) {
                    return new DeletePlan(d.getTable(), s);
                }
            }
        } else if (r instanceof InsertPlan) {
            InsertPlan ip = (InsertPlan) r;
            List<Expr> old = ip.getValues();
            List<Expr> s = simplifyExprList(old);
            if (s != old) {
                return new InsertPlan(ip.getTable(), ip.getColumns(), s);
            }
        }
        return r;
    }

    /** 化简单个表达式（布尔域：常量比较求值 + AND/OR/NOT 恒等式）。 */
    private Expr simplifyExpr(Expr e) {
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            Expr l = simplifyExpr(c.getLeft());
            Expr r = simplifyExpr(c.getRight());
            Comparison rebuilt = (l != c.getLeft() || r != c.getRight())
                    ? new Comparison(c.getOp(), l, r) : c;
            // 两侧都是字面量 -> 直接求值成布尔常量（如 1=1 -> true）
            if (rebuilt.getLeft() instanceof Literal && rebuilt.getRight() instanceof Literal) {
                Literal b = evalComparison(rebuilt);
                if (b != null) {
                    return b;
                }
            }
            return rebuilt;
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            Expr l = simplifyExpr(b.getLeft());
            Expr r = simplifyExpr(b.getRight());
            if (b.getOp() == Operator.AND) {
                // true AND X -> X；false AND X -> false
                if (isBoolLiteral(l, true))  return r;
                if (isBoolLiteral(r, true))  return l;
                if (isBoolLiteral(l, false) || isBoolLiteral(r, false)) {
                    return new Literal(false, ColumnType.BOOL);
                }
            } else if (b.getOp() == Operator.OR) {
                // false OR X -> X；true OR X -> true
                if (isBoolLiteral(l, false)) return r;
                if (isBoolLiteral(r, false)) return l;
                if (isBoolLiteral(l, true) || isBoolLiteral(r, true)) {
                    return new Literal(true, ColumnType.BOOL);
                }
            }
            if (l != b.getLeft() || r != b.getRight()) {
                return new BinaryExpr(b.getOp(), l, r);
            }
            return b;
        }
        if (e instanceof UnaryExpr) {
            UnaryExpr u = (UnaryExpr) e;
            Expr operand = simplifyExpr(u.getOperand());
            if (u.getOp() == Operator.NOT && operand instanceof Literal) {
                if (isBoolLiteral(operand, true))  return new Literal(false, ColumnType.BOOL);
                if (isBoolLiteral(operand, false)) return new Literal(true, ColumnType.BOOL);
            }
            if (operand != u.getOperand()) {
                return new UnaryExpr(u.getOp(), operand);
            }
            return u;
        }
        return e;
    }

    /** 求值常量比较（两侧都是字面量）为布尔字面量；不可求值返回 null。 */
    private Literal evalComparison(Comparison c) {
        Expr l = c.getLeft();
        Expr r = c.getRight();
        if (!(l instanceof Literal) || !(r instanceof Literal)) {
            return null;
        }
        Object lv = ((Literal) l).getValue();
        Object rv = ((Literal) r).getValue();
        int cmp;
        if (lv instanceof Number && rv instanceof Number) {
            // 数值：按 double 比较（INT 与 FLOAT 混比也成立）
            cmp = Double.compare(((Number) lv).doubleValue(), ((Number) rv).doubleValue());
        } else if (lv instanceof String && rv instanceof String) {
            cmp = ((String) lv).compareTo((String) rv); // VARCHAR：字典序
        } else if (lv instanceof Boolean && rv instanceof Boolean) {
            cmp = ((Boolean) lv).compareTo((Boolean) rv); // BOOL：false < true
        } else {
            return null; // 类型不可比较（语义分析阶段应已拦截）
        }
        boolean result;
        switch (c.getOp()) {
            case EQ:  result = cmp == 0; break;
            case NE:  result = cmp != 0; break;
            case LT:  result = cmp < 0;  break;
            case LE:  result = cmp <= 0; break;
            case GT:  result = cmp > 0;  break;
            case GE:  result = cmp >= 0; break;
            default:  return null;       // 非比较运算符
        }
        return new Literal(result, ColumnType.BOOL);
    }

    // =====================================================================
    // 规则 3：投影剪枝（projectPruning）
    // =====================================================================

    /**
     * 投影剪枝：剪掉「投影全部列」的 Project（SELECT * 产生的 Project["*"]）。
     * 本单表设计中 SeqScan 本身输出整行，Project["*"] 不产生任何裁剪收益，
     * 等价于直接输出 SeqScan 的结果，因此可剪掉该 Project 节点。
     * 若 Project 是显式列（如 [name]），这些列就是查询真正需要的输出列，予以保留。
     */
    private PlanNode projectPruning(PlanNode n) {
        PlanNode r = mapChildren(n, this::projectPruning);
        if (r instanceof ProjectPlan && isSelectAll(((ProjectPlan) r).getColumns())) {
            return onlyChild(r); // 剪掉 Project["*"]
        }
        return r;
    }

    // =====================================================================
    // 规则 4：谓词下推（predicatePushdown）
    // =====================================================================

    /**
     * 谓词下推：把 Filter 尽量往下（靠近 SeqScan）移动。
     * 当计划形状为 Filter[c] -> Project[cols] -> child 时，把 Filter 移到 Project 之下：
     *   Filter[c] -> Project[cols] -> child   ==>   Project[cols] -> Filter[c] -> child
     * 安全性：Filter 引用的列必须都能在 Project 的输出中取到，即
     *   cols == ["*"]（保留全部列），或 cols 包含 Filter 引用的全部列；
     * 否则下推后 Filter 会访问到被投影掉的列，不能下推。
     * 本系统的 Planner 默认就生成 Project -> Filter -> SeqScan，因此本规则对默认计划
     * 通常无操作，作为通用规则保留。
     */
    private PlanNode predicatePushdown(PlanNode n) {
        PlanNode r = mapChildren(n, this::predicatePushdown);

        if (r instanceof FilterPlan) {
            PlanNode child = onlyChild(r);
            if (child instanceof ProjectPlan) {
                ProjectPlan proj = (ProjectPlan) child;
                Set<String> used = referencedColumns(((FilterPlan) r).getCondition());
                if (isSelectAll(proj.getColumns()) || proj.getColumns().containsAll(used)) {
                    PlanNode newFilter = new FilterPlan(((FilterPlan) r).getCondition(), onlyChild(proj));
                    return new ProjectPlan(proj.getColumns(), newFilter);
                }
            }
        }
        return r;
    }

    // =====================================================================
    // 规则 5：冗余节点消除（removeRedundant）
    // =====================================================================

    /** 冗余消除：去掉恒真（true）的 Filter 等无意义节点。 */
    private PlanNode removeRedundant(PlanNode n) {
        PlanNode r = mapChildren(n, this::removeRedundant);
        // Filter[true] 恒真，直接透传子节点
        if (r instanceof FilterPlan && isBoolLiteral(((FilterPlan) r).getCondition(), true)) {
            return onlyChild(r);
        }
        return r;
    }

    // =====================================================================
    // 通用辅助
    // =====================================================================

    /**
     * 对 n 的每个子节点递归应用 rule，返回一个「子节点已被替换」的新节点。
     * 若所有子节点都未变化，返回 n 本身（保持 identity，供上层判断是否发生变化）。
     * 本方法不修改原节点，保证优化是「纯」的：输入计划树在优化前后保持不变，
     * 从而可以展示优化前后的结构对比（plan.md 的硬性验收点）。
     */
    private PlanNode mapChildren(PlanNode n, Function<PlanNode, PlanNode> rule) {
        List<PlanNode> kids = n.getChildren();
        if (kids.isEmpty()) {
            return n;
        }
        List<PlanNode> newKids = new ArrayList<>(kids.size());
        boolean changed = false;
        for (PlanNode child : kids) {
            PlanNode newChild = rule.apply(child);
            newKids.add(newChild);
            if (newChild != child) {
                changed = true;
            }
        }
        return changed ? withChildren(n, newKids) : n;
    }

    /** 用新的子节点列表重建一个与 n 同类型的节点（仅 Project / Filter 有子节点）。 */
    private PlanNode withChildren(PlanNode n, List<PlanNode> newKids) {
        if (n instanceof ProjectPlan) {
            return new ProjectPlan(((ProjectPlan) n).getColumns(), newKids.get(0));
        }
        if (n instanceof FilterPlan) {
            return new FilterPlan(((FilterPlan) n).getCondition(), newKids.get(0));
        }
        // 其余节点类型（SeqScan/CreateTable/Insert/Delete）没有子节点，正常不会走到这里
        throw new IllegalStateException("unexpected node with children: " + n.getClass().getSimpleName());
    }

    /** 折叠表达式列表；任一元素变化则返回新列表，否则返回原列表（供 identity 判断）。 */
    private List<Expr> foldExprList(List<Expr> exprs) {
        List<Expr> result = null;
        for (int i = 0; i < exprs.size(); i++) {
            Expr old = exprs.get(i);
            Expr folded = foldExpr(old);
            if (folded != old && result == null) {
                result = new ArrayList<>(exprs); // 惰性复制：第一次变化时才复制
            }
            if (result != null) {
                result.set(i, folded);
            }
        }
        return result == null ? exprs : result;
    }

    /** 化简表达式列表（语义同 foldExprList，套用布尔化简）。 */
    private List<Expr> simplifyExprList(List<Expr> exprs) {
        List<Expr> result = null;
        for (int i = 0; i < exprs.size(); i++) {
            Expr old = exprs.get(i);
            Expr s = simplifyExpr(old);
            if (s != old && result == null) {
                result = new ArrayList<>(exprs);
            }
            if (result != null) {
                result.set(i, s);
            }
        }
        return result == null ? exprs : result;
    }

    /** 收集表达式里引用的所有列名（用于谓词下推的安全性判断）。 */
    private Set<String> referencedColumns(Expr e) {
        Set<String> cols = new HashSet<>();
        collectColumns(e, cols);
        return cols;
    }

    private void collectColumns(Expr e, Set<String> out) {
        if (e instanceof ColumnRef) {
            out.add(((ColumnRef) e).getColumn());
        } else if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            collectColumns(c.getLeft(), out);
            collectColumns(c.getRight(), out);
        } else if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            collectColumns(b.getLeft(), out);
            collectColumns(b.getRight(), out);
        } else if (e instanceof UnaryExpr) {
            collectColumns(((UnaryExpr) e).getOperand(), out);
        }
        // Literal / Star 无列引用
    }

    /** 判断某表达式是否为指定真值的布尔字面量（如 true / false）。 */
    private boolean isBoolLiteral(Expr e, boolean target) {
        if (!(e instanceof Literal)) {
            return false;
        }
        Literal l = (Literal) e;
        return l.getType() == ColumnType.BOOL
                && l.getValue() instanceof Boolean
                && ((Boolean) l.getValue()) == target;
    }

    /** 判断 Project 的列列表是否为 SELECT *（哨兵值 ["*"]）。 */
    private boolean isSelectAll(List<String> columns) {
        return columns.size() == 1 && "*".equals(columns.get(0));
    }

    /** 取节点的唯一子节点；无子节点返回 null。 */
    private PlanNode onlyChild(PlanNode n) {
        List<PlanNode> kids = n.getChildren();
        return kids.isEmpty() ? null : kids.get(0);
    }
}
