package engine;

import sql_compiler.ast.AggregateCall;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.Expr;
import sql_compiler.ast.Literal;
import sql_compiler.ast.UnaryExpr;
import utils.DbException;
import utils.Operator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达式求值器：在运行时对 {@link Expr} 求值，供执行引擎的 Filter / Project / Sort / Join 与
 * 存储引擎的删除/更新条件使用。
 *
 * <p>求值上下文为「列名 -> 列下标」映射 + 一行值列表。列引用优先按「表名.列名」解析，退回裸列名
 * （多表连接时列名会带别名前缀）。支持字面量、列引用、比较、逻辑（AND/OR/NOT）、算术（+ - * /）与
 * 聚合函数（COUNT/SUM/AVG/MIN/MAX，由分组算子统一求值）。
 */
public final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    /** 由列名列表构建「列名 -> 下标」映射；带点号的列名同时注册其裸列名（首个优先）。 */
    public static Map<String, Integer> indexMap(List<String> columns) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i);
            m.put(name, i);
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                m.putIfAbsent(name.substring(dot + 1), i);
            }
        }
        return m;
    }

    /** 求值为布尔（供 WHERE / DELETE / UPDATE / JOIN 条件），非布尔则抛异常。 */
    public static boolean evalCondition(Expr e, Map<String, Integer> idx, List<Object> row) {
        Object v = eval(e, idx, row);
        if (!(v instanceof Boolean)) {
            throw new DbException("condition did not evaluate to BOOL: " + e);
        }
        return (Boolean) v;
    }

    /** 递归求值表达式。 */
    public static Object eval(Expr e, Map<String, Integer> idx, List<Object> row) {
        if (e instanceof Literal) {
            return ((Literal) e).getValue();
        }
        if (e instanceof ColumnRef) {
            ColumnRef ref = (ColumnRef) e;
            Integer i = null;
            if (ref.getTable() != null) {
                i = idx.get(ref.getTable() + "." + ref.getColumn());
            }
            if (i == null) {
                i = idx.get(ref.getColumn());
            }
            if (i == null) {
                throw new DbException("column '" + ref + "' not found in row");
            }
            return row.get(i);
        }
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            return compare(c.getOp(), eval(c.getLeft(), idx, row), eval(c.getRight(), idx, row));
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            Object l = eval(b.getLeft(), idx, row);
            Object r = eval(b.getRight(), idx, row);
            if (b.getOp().isLogical()) {
                boolean bl = toBool(l);
                boolean br = toBool(r);
                return b.getOp() == Operator.AND ? bl && br : bl || br;
            }
            return arithmetic(b.getOp(), l, r);
        }
        if (e instanceof UnaryExpr) {
            return !toBool(eval(((UnaryExpr) e).getOperand(), idx, row));
        }
        if (e instanceof AggregateCall) {
            throw new DbException("aggregate call must be evaluated by the group operator: " + e);
        }
        throw new DbException("cannot evaluate expression: " + e.getClass().getSimpleName());
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        throw new DbException("expected BOOL, got " + v);
    }

    /** 比较两个值；数值按 double、字符串按字典序、布尔按 false&lt;true。 */
    private static boolean compare(Operator op, Object l, Object r) {
        int cmp = compareValues(l, r);
        switch (op) {
            case EQ: return cmp == 0;
            case NE: return cmp != 0;
            case LT: return cmp < 0;
            case LE: return cmp <= 0;
            case GT: return cmp > 0;
            case GE: return cmp >= 0;
            default: throw new DbException("not a comparison operator: " + op);
        }
    }

    /** 比较两个值并返回 -1/0/1；NULL 视为最小。 */
    public static int compareValues(Object l, Object r) {
        if (l == null && r == null) {
            return 0;
        }
        if (l == null) {
            return -1;
        }
        if (r == null) {
            return 1;
        }
        if (l instanceof Number && r instanceof Number) {
            return Double.compare(((Number) l).doubleValue(), ((Number) r).doubleValue());
        }
        if (l instanceof String && r instanceof String) {
            return ((String) l).compareTo((String) r);
        }
        if (l instanceof Boolean && r instanceof Boolean) {
            return Boolean.compare((Boolean) l, (Boolean) r);
        }
        throw new DbException("cannot compare " + l + " and " + r);
    }

    /** 算术运算；除法或任一操作数为浮点时结果为 FLOAT，否则为 INT（与 TypeSystem 一致）。 */
    private static Object arithmetic(Operator op, Object l, Object r) {
        if (!(l instanceof Number) || !(r instanceof Number)) {
            throw new DbException("arithmetic requires numbers, got " + l + " and " + r);
        }
        double a = ((Number) l).doubleValue();
        double b = ((Number) r).doubleValue();
        boolean isFloat = l instanceof Float || l instanceof Double
                || r instanceof Float || r instanceof Double
                || op == Operator.DIV;
        double result;
        switch (op) {
            case PLUS:  result = a + b; break;
            case MINUS: result = a - b; break;
            case MUL:   result = a * b; break;
            case DIV:   result = a / b; break;
            default: throw new DbException("not an arithmetic operator: " + op);
        }
        if (isFloat) {
            return (float) result;
        }
        return (int) result;
    }

    /** 对一组行求聚合函数值。 */
    public static Object evalAggregate(AggregateCall call, Map<String, Integer> idx, List<List<Object>> groupRows) {
        switch (call.getFunc()) {
            case "COUNT": {
                if (call.getArg() == null) {
                    return groupRows.size();
                }
                int c = 0;
                for (List<Object> row : groupRows) {
                    if (eval(call.getArg(), idx, row) != null) {
                        c++;
                    }
                }
                return c;
            }
            case "SUM": {
                double sum = 0;
                boolean isFloat = false;
                for (List<Object> row : groupRows) {
                    Object v = eval(call.getArg(), idx, row);
                    if (v == null) {
                        continue;
                    }
                    isFloat |= v instanceof Float || v instanceof Double;
                    sum += ((Number) v).doubleValue();
                }
                return isFloat ? (float) sum : (int) sum;
            }
            case "AVG": {
                double sum = 0;
                int n = 0;
                for (List<Object> row : groupRows) {
                    Object v = eval(call.getArg(), idx, row);
                    if (v == null) {
                        continue;
                    }
                    n++;
                    sum += ((Number) v).doubleValue();
                }
                return n == 0 ? null : (float) (sum / n);
            }
            case "MIN": {
                Object best = null;
                for (List<Object> row : groupRows) {
                    Object v = eval(call.getArg(), idx, row);
                    if (v == null) {
                        continue;
                    }
                    if (best == null || compareValues(v, best) < 0) {
                        best = v;
                    }
                }
                return best;
            }
            case "MAX": {
                Object best = null;
                for (List<Object> row : groupRows) {
                    Object v = eval(call.getArg(), idx, row);
                    if (v == null) {
                        continue;
                    }
                    if (best == null || compareValues(v, best) > 0) {
                        best = v;
                    }
                }
                return best;
            }
            default:
                throw new DbException("unknown aggregate function: " + call.getFunc());
        }
    }
}
