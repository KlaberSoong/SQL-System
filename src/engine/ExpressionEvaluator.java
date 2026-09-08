package engine;

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
 * 表达式求值器：在运行时对 {@link Expr} 求值，供执行引擎的 Filter / Project 与
 * 存储引擎的删除条件使用。
 *
 * <p>求值上下文为「列名 -> 列下标」映射 + 一行值列表（单表查询，列引用按列名解析，
 * 忽略可能存在的表前缀）。支持字面量、列引用、比较、逻辑（AND/OR/NOT）与算术（+ - * /）。
 * 算术分支主要为完整性保留（当前 Parser 未解析算术，但程序化构造的计划仍可到达）。
 */
public final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    /** 由列名列表构建「列名 -> 下标」映射。 */
    public static Map<String, Integer> indexMap(List<String> columns) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            m.put(columns.get(i), i);
        }
        return m;
    }

    /** 求值为布尔（供 WHERE / DELETE 条件），非布尔则抛异常。 */
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
            String column = ((ColumnRef) e).getColumn();
            Integer i = idx.get(column);
            if (i == null) {
                throw new DbException("column '" + column + "' not found in row");
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
        int cmp;
        if (l instanceof Number && r instanceof Number) {
            cmp = Double.compare(((Number) l).doubleValue(), ((Number) r).doubleValue());
        } else if (l instanceof String && r instanceof String) {
            cmp = ((String) l).compareTo((String) r);
        } else if (l instanceof Boolean && r instanceof Boolean) {
            cmp = Boolean.compare((Boolean) l, (Boolean) r);
        } else {
            throw new DbException("cannot compare " + l + " and " + r);
        }
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
        return isFloat ? (float) result : (int) result;
    }
}
