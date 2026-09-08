package sql_compiler;

import utils.ColumnType;
import utils.Operator;

/**
 * 类型系统（集中管理，对应 plan.md 3.4）。
 *
 * 设计目标：把「某个运算符作用在某两个类型上会产生什么结果 / 是否合法」的全部规则
 * 集中到这一个类里，避免散落在 SemanticAnalyzer / Executor 的 if/else 中。
 * SemanticAnalyzer 在做类型检查时只「查询」本类，不自己写类型规则；
 * 未来执行引擎（Executor）在运行时若也需要类型判断，同样可以复用本类。
 *
 * 类型规则速查（详见各方法）：
 *   INT   + INT        -> INT
 *   INT   / INT        -> FLOAT（除法可能产生小数，统一升为 FLOAT）
 *   FLOAT 参与算术运算  -> FLOAT
 *   INT   > INT        -> BOOL（所有比较的结果都是 BOOL）
 *   VARCHAR = VARCHAR  -> BOOL（VARCHAR/BOOL 仅支持 = 与 !=，不支持大小比较）
 *   BOOL  AND BOOL     -> BOOL
 *   NOT   BOOL         -> BOOL
 *   INT   + VARCHAR    -> 抛异常（类型不匹配）
 *
 * 约定：本类方法在类型不匹配时抛出 IllegalArgumentException（消息为人类可读的原因），
 * 由调用方（SemanticAnalyzer）捕获后包装成带位置的 utils.SemanticError。
 * 这样本类与「位置」解耦，保持纯粹，也方便被其他模块复用。
 */
public final class TypeSystem {
    private TypeSystem() {
    }

    /** 是否为数值类型（INT / FLOAT）。 */
    public static boolean isNumeric(ColumnType t) {
        return t == ColumnType.INT || t == ColumnType.FLOAT;
    }

    /**
     * 算术运算（+ - * /）的结果类型。
     * 规则：
     *   1. 两侧都必须是数值（INT/FLOAT），否则报错（如 INT + VARCHAR）；
     *   2. 除法结果统一为 FLOAT（整数相除也可能产生小数，如 5/2 = 2.5）；
     *   3. 只要有一侧是 FLOAT，结果就是 FLOAT；否则（INT op INT）结果是 INT。
     */
    public static ColumnType arithmetic(ColumnType left, Operator op, ColumnType right) {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw new IllegalArgumentException(
                    "arithmetic operator '" + op.getText() + "' requires numeric operands, but got "
                            + left + " and " + right);
        }
        if (op == Operator.DIV) {
            return ColumnType.FLOAT;
        }
        return (left == ColumnType.FLOAT || right == ColumnType.FLOAT)
                ? ColumnType.FLOAT
                : ColumnType.INT;
    }

    /**
     * 比较运算（= != < <= > >=）的类型合法性检查；结果恒为 BOOL，因此本方法只做校验、不返回类型。
     * 规则：
     *   1. 两侧都是数值（INT/FLOAT）：任意比较运算符都合法（支持 INT 与 FLOAT 混比）；
     *   2. 两侧同类型（VARCHAR 或 BOOL）：仅支持相等性比较 = 与 !=；
     *   3. 其余（如 INT 与 VARCHAR、VARCHAR 与 BOOL）：类型不匹配，报错。
     */
    public static void checkComparison(ColumnType left, Operator op, ColumnType right) {
        if (isNumeric(left) && isNumeric(right)) {
            return; // 数值之间：全部比较运算符都合法
        }
        if (left == right) {
            if (op == Operator.EQ || op == Operator.NE) {
                return; // VARCHAR/BOOL 仅支持相等性比较
            }
            throw new IllegalArgumentException(
                    "operator '" + op.getText() + "' is not supported for type " + left);
        }
        throw new IllegalArgumentException(
                "cannot compare values of type " + left + " and " + right);
    }

    /** 逻辑运算（AND / OR）的类型合法性检查：两侧都必须是 BOOL。 */
    public static void checkLogical(ColumnType left, Operator op, ColumnType right) {
        if (left != ColumnType.BOOL || right != ColumnType.BOOL) {
            throw new IllegalArgumentException(
                    "logical operator '" + op.getText() + "' requires BOOL operands, but got "
                            + left + " and " + right);
        }
    }

    /** 一元逻辑运算（NOT）的类型合法性检查：操作数必须是 BOOL。 */
    public static void checkUnaryLogical(ColumnType operand) {
        if (operand != ColumnType.BOOL) {
            throw new IllegalArgumentException(
                    "operator NOT requires a BOOL operand, but got " + operand);
        }
    }

    /**
     * INSERT 时「值类型 -> 列类型」的赋值兼容性检查。
     * 规则：
     *   1. 类型完全一致 -> 合法；
     *   2. INT 值写入 FLOAT 列 -> 合法（整数向浮点拓宽，不损失信息）；
     *   3. 其余（如 VARCHAR 值写入 INT 列、FLOAT 值写入 INT 列）-> 报错。
     */
    public static void checkAssignable(ColumnType columnType, ColumnType valueType) {
        if (columnType == valueType) {
            return;
        }
        if (columnType == ColumnType.FLOAT && valueType == ColumnType.INT) {
            return; // INT -> FLOAT 拓宽
        }
        throw new IllegalArgumentException(
                "cannot assign a value of type " + valueType + " to a column of type " + columnType);
    }
}
