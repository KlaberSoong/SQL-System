package sql_compiler.ast;

/**
 * 聚合函数调用：func 为 COUNT/SUM/AVG/MIN/MAX（大写），arg 为 null 表示 COUNT(*)。
 */
public class AggregateCall extends Expr {
    private final String func;
    private final Expr arg; // 可为 null

    public AggregateCall(String func, Expr arg) {
        this.func = func;
        this.arg = arg;
    }

    public String getFunc() {
        return func;
    }

    public Expr getArg() {
        return arg;
    }

    @Override
    public String toString() {
        return func.toLowerCase() + "(" + (arg == null ? "*" : arg.toString()) + ")";
    }
}
