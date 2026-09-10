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
 * 规则式优化器（对应 plan.md 3.6）：对逻辑计划做等价规则重写。
 * 五条规则依次迭代到不动点：常量折叠、布尔化简、投影剪枝、谓词下推、冗余消除。
 * 所有规则都是「纯」的——不修改传入节点而是返回重建后的新节点，故可同时保留优化前后两棵树用于对比展示。
 */
public class Optimizer {
    private static final int MAX_PASSES = 4; // 最大优化轮数，防止异常情况下死循环

    // 优化主入口：迭代应用五条规则，直到计划结构不再变化（达不动点）
    public PlanNode optimize(PlanNode plan) {
        PlanNode current = plan;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            PlanNode next = applyAllRules(current);
            if (next.toTree().equals(current.toTree())) {
                return next;
            }
            current = next;
        }
        return current;
    }

    // 依次应用五条规则：先折叠/化简表达式，再做结构级改写
    private PlanNode applyAllRules(PlanNode n) {
        n = constantFold(n);
        n = booleanSimplify(n);
        n = projectPruning(n);
        n = predicatePushdown(n);
        n = removeRedundant(n);
        return n;
    }

    // 规则 1：常量折叠——自底向上折叠所有表达式位置上的算术常量
    private PlanNode constantFold(PlanNode n) {
        PlanNode r = mapChildren(n, this::constantFold);
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
        } else if (r instanceof ProjectPlan) {
            ProjectPlan p = (ProjectPlan) r;
            List<Expr> old = p.getExpressions();
            List<Expr> folded = foldExprList(old);
            if (folded != old) {
                return new ProjectPlan(folded, onlyChild(p));
            }
        }
        return r;
    }

    // 折叠单个表达式：算术常量直接求值，无法折叠则原样返回
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
        return e;
    }

    // 计算两个数值字面量的算术结果；非数值或非算术运算返回 null（表示无法折叠）
    private Literal evalArithmetic(Operator op, Literal l, Literal r) {
        if (!(l.getValue() instanceof Number) || !(r.getValue() instanceof Number)) {
            return null;
        }
        double a = ((Number) l.getValue()).doubleValue();
        double b = ((Number) r.getValue()).doubleValue();
        double result;
        switch (op) {
            case PLUS:  result = a + b; break;
            case MINUS: result = a - b; break;
            case MUL:   result = a * b; break;
            case DIV:   result = a / b; break;
            default:    return null;
        }
        boolean isFloat = op == Operator.DIV
                || l.getType() == ColumnType.FLOAT
                || r.getType() == ColumnType.FLOAT;
        if (isFloat) {
            return new Literal((float) result, ColumnType.FLOAT);
        }
        return new Literal((int) result, ColumnType.INT);
    }

    // 规则 2：布尔化简——先求值常量比较，再套用 AND/OR/NOT 恒等式
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
        } else if (r instanceof ProjectPlan) {
            ProjectPlan p = (ProjectPlan) r;
            List<Expr> old = p.getExpressions();
            List<Expr> s = simplifyExprList(old);
            if (s != old) {
                return new ProjectPlan(s, onlyChild(p));
            }
        }
        return r;
    }

    // 化简单个表达式：常量比较求值 + AND/OR/NOT 恒等式（如 true AND X -> X）
    private Expr simplifyExpr(Expr e) {
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            Expr l = simplifyExpr(c.getLeft());
            Expr r = simplifyExpr(c.getRight());
            Comparison rebuilt = (l != c.getLeft() || r != c.getRight())
                    ? new Comparison(c.getOp(), l, r) : c;
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
                if (isBoolLiteral(l, true))  return r;
                if (isBoolLiteral(r, true))  return l;
                if (isBoolLiteral(l, false) || isBoolLiteral(r, false)) {
                    return new Literal(false, ColumnType.BOOL);
                }
            } else if (b.getOp() == Operator.OR) {
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

    // 求值常量比较（两侧都是字面量）为布尔字面量；不可求值返回 null
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
            cmp = Double.compare(((Number) lv).doubleValue(), ((Number) rv).doubleValue());
        } else if (lv instanceof String && rv instanceof String) {
            cmp = ((String) lv).compareTo((String) rv);
        } else if (lv instanceof Boolean && rv instanceof Boolean) {
            cmp = ((Boolean) lv).compareTo((Boolean) rv);
        } else {
            return null;
        }
        boolean result;
        switch (c.getOp()) {
            case EQ:  result = cmp == 0; break;
            case NE:  result = cmp != 0; break;
            case LT:  result = cmp < 0;  break;
            case LE:  result = cmp <= 0; break;
            case GT:  result = cmp > 0;  break;
            case GE:  result = cmp >= 0; break;
            default:  return null;
        }
        return new Literal(result, ColumnType.BOOL);
    }

    // 规则 3：投影剪枝——剪掉 SELECT * 产生的冗余 Project["*"]
    private PlanNode projectPruning(PlanNode n) {
        PlanNode r = mapChildren(n, this::projectPruning);
        if (r instanceof ProjectPlan && isSelectAll(((ProjectPlan) r).getColumns())) {
            return onlyChild(r);
        }
        return r;
    }

    // 规则 4：谓词下推——把 Filter 下移到 Project 之下（靠近 SeqScan）
    private PlanNode predicatePushdown(PlanNode n) {
        PlanNode r = mapChildren(n, this::predicatePushdown);
        if (r instanceof FilterPlan) {
            PlanNode child = onlyChild(r);
            if (child instanceof ProjectPlan) {
                ProjectPlan proj = (ProjectPlan) child;
                Set<String> used = referencedColumns(((FilterPlan) r).getCondition());
                if (isSelectAll(proj.getColumns()) || proj.getColumns().containsAll(used)) {
                    PlanNode newFilter = new FilterPlan(((FilterPlan) r).getCondition(), onlyChild(proj));
                    return new ProjectPlan(proj.getExpressions(), newFilter);
                }
            }
        }
        return r;
    }

    // 规则 5：冗余消除——去掉恒真（true）的 Filter 等无意义节点
    private PlanNode removeRedundant(PlanNode n) {
        PlanNode r = mapChildren(n, this::removeRedundant);
        if (r instanceof FilterPlan && isBoolLiteral(((FilterPlan) r).getCondition(), true)) {
            return onlyChild(r);
        }
        return r;
    }

    // 对 n 的每个子节点递归应用规则，返回子节点被替换后的新节点（未变化返回 n 本身）
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

    // 用新的子节点列表重建一个与 n 同类型的节点（仅 Project / Filter 有子节点）
    private PlanNode withChildren(PlanNode n, List<PlanNode> newKids) {
        if (n instanceof ProjectPlan) {
            return new ProjectPlan(((ProjectPlan) n).getExpressions(), newKids.get(0));
        }
        if (n instanceof FilterPlan) {
            return new FilterPlan(((FilterPlan) n).getCondition(), newKids.get(0));
        }
        throw new IllegalStateException("unexpected node with children: " + n.getClass().getSimpleName());
    }

    // 折叠表达式列表；任一元素变化则返回新列表，否则返回原列表
    private List<Expr> foldExprList(List<Expr> exprs) {
        List<Expr> result = null;
        for (int i = 0; i < exprs.size(); i++) {
            Expr old = exprs.get(i);
            Expr folded = foldExpr(old);
            if (folded != old && result == null) {
                result = new ArrayList<>(exprs);
            }
            if (result != null) {
                result.set(i, folded);
            }
        }
        return result == null ? exprs : result;
    }

    // 化简表达式列表（语义同 foldExprList，套用布尔化简）
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

    // 收集表达式里引用的所有列名（用于谓词下推的安全性判断）
    private Set<String> referencedColumns(Expr e) {
        Set<String> cols = new HashSet<>();
        collectColumns(e, cols);
        return cols;
    }

    // 递归遍历表达式收集列引用
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
    }

    // 判断某表达式是否为指定真值的布尔字面量
    private boolean isBoolLiteral(Expr e, boolean target) {
        if (!(e instanceof Literal)) {
            return false;
        }
        Literal l = (Literal) e;
        return l.getType() == ColumnType.BOOL
                && l.getValue() instanceof Boolean
                && ((Boolean) l.getValue()) == target;
    }

    // 判断 Project 的列列表是否为 SELECT *（哨兵值 ["*"]）
    private boolean isSelectAll(List<String> columns) {
        return columns.size() == 1 && "*".equals(columns.get(0));
    }

    // 取节点的唯一子节点；无子节点返回 null
    private PlanNode onlyChild(PlanNode n) {
        List<PlanNode> kids = n.getChildren();
        return kids.isEmpty() ? null : kids.get(0);
    }
}
