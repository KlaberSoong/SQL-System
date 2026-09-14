package sql_compiler;

import utils.ColumnType;
import utils.Operator;

/**
 * 类型系统（对应 plan.md 3.4）：把运算符作用在各类型上的结果/合法性规则集中到一处，
 * 供 SemanticAnalyzer（编译期检查）与执行引擎复用，避免规则散落。
 * 规则：INT+INT→INT、INT/INT→FLOAT、FLOAT 参与算术→FLOAT、比较恒为 BOOL、
 * VARCHAR/BOOL 仅支持 =/!=、INT→FLOAT 可拓宽赋值；类型不匹配抛 IllegalArgumentException。
 */
public final class TypeSystem {
    private TypeSystem() {
    }

    // 是否为数值类型（INT / FLOAT / DECIMAL）
    public static boolean isNumeric(ColumnType t) {
        return t == ColumnType.INT || t == ColumnType.FLOAT || t == ColumnType.DECIMAL;
    }

    // 是否为字符串族类型（VARCHAR / CHAR / TEXT）
    public static boolean isStringFamily(ColumnType t) {
        return t == ColumnType.VARCHAR || t == ColumnType.CHAR || t == ColumnType.TEXT;
    }

    // 算术运算（+ - * /）的结果类型：两侧须为数值；含 DECIMAL 则结果为 DECIMAL，
    // 除法（无 DECIMAL）恒为 FLOAT，否则含 FLOAT 则为 FLOAT，全 INT 则为 INT
    public static ColumnType arithmetic(ColumnType left, Operator op, ColumnType right) {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw new IllegalArgumentException(
                    "arithmetic operator '" + op.getText() + "' requires numeric operands, but got "
                            + left + " and " + right);
        }
        if (left == ColumnType.DECIMAL || right == ColumnType.DECIMAL) {
            return ColumnType.DECIMAL;
        }
        if (op == Operator.DIV) {
            return ColumnType.FLOAT;
        }
        return (left == ColumnType.FLOAT || right == ColumnType.FLOAT)
                ? ColumnType.FLOAT
                : ColumnType.INT;
    }

    // 比较运算（= != < <= > >=）的类型合法性检查：数值任意比较；DATE 支持全序；
    // 字符串族（VARCHAR/CHAR/TEXT）与 BOOL 仅支持 =/!=
    public static void checkComparison(ColumnType left, Operator op, ColumnType right) {
        if (isNumeric(left) && isNumeric(right)) {
            return;
        }
        if (left == ColumnType.DATE && right == ColumnType.DATE) {
            return; // DATE 支持全部序比较
        }
        if (isStringFamily(left) && isStringFamily(right)) {
            if (op == Operator.EQ || op == Operator.NE) {
                return;
            }
            throw new IllegalArgumentException(
                    "operator '" + op.getText() + "' is not supported for type " + left);
        }
        if (left == right) {
            if (op == Operator.EQ || op == Operator.NE) {
                return;
            }
            throw new IllegalArgumentException(
                    "operator '" + op.getText() + "' is not supported for type " + left);
        }
        throw new IllegalArgumentException(
                "cannot compare values of type " + left + " and " + right);
    }

    // 逻辑运算（AND / OR）的类型合法性检查：两侧都必须是 BOOL
    public static void checkLogical(ColumnType left, Operator op, ColumnType right) {
        if (left != ColumnType.BOOL || right != ColumnType.BOOL) {
            throw new IllegalArgumentException(
                    "logical operator '" + op.getText() + "' requires BOOL operands, but got "
                            + left + " and " + right);
        }
    }

    // 一元逻辑运算（NOT）的类型合法性检查：操作数必须是 BOOL
    public static void checkUnaryLogical(ColumnType operand) {
        if (operand != ColumnType.BOOL) {
            throw new IllegalArgumentException(
                    "operator NOT requires a BOOL operand, but got " + operand);
        }
    }

    // INSERT 赋值兼容性检查：数值族互赋；字符串族互赋；DATE ← DATE|VARCHAR；DECIMAL ← 数值|VARCHAR
    public static void checkAssignable(ColumnType columnType, ColumnType valueType) {
        if (columnType == valueType) {
            return;
        }
        if (isNumeric(columnType) && isNumeric(valueType)) {
            return;
        }
        if (isStringFamily(columnType) && isStringFamily(valueType)) {
            return;
        }
        if (columnType == ColumnType.DATE && valueType == ColumnType.VARCHAR) {
            return;
        }
        if (columnType == ColumnType.DECIMAL && valueType == ColumnType.VARCHAR) {
            return;
        }
        throw new IllegalArgumentException(
                "cannot assign a value of type " + valueType + " to a column of type " + columnType);
    }

    // 聚合函数的结果类型检查：COUNT→INT、SUM→argType（须数值）、AVG→FLOAT（须数值）、MIN/MAX→argType
    public static ColumnType checkAggregate(String func, ColumnType argType) {
        switch (func) {
            case "COUNT":
                return ColumnType.INT;
            case "SUM":
            case "AVG":
                if (argType == null || !isNumeric(argType)) {
                    throw new IllegalArgumentException(
                            "aggregate '" + func.toLowerCase() + "' requires a numeric argument");
                }
                return func.equals("AVG") ? ColumnType.FLOAT : argType;
            case "MIN":
            case "MAX":
                if (argType == null) {
                    throw new IllegalArgumentException(
                            "aggregate '" + func.toLowerCase() + "' requires an argument");
                }
                return argType;
            default:
                throw new IllegalArgumentException("unknown aggregate function '" + func + "'");
        }
    }
}
