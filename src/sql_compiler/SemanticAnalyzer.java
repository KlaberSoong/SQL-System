package sql_compiler;

import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.Literal;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.ast.UnaryExpr;
import utils.ColumnDef;
import utils.ColumnType;
import utils.SemanticError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义分析器（对应 plan.md 3.3）：在 AST 之上做
 *   1. 表存在性检查（含重复建表）；
 *   2. 列存在性检查 + 名字绑定（把 ColumnRef 绑定到 Catalog 中具体的列定义）；
 *   3. 类型一致性检查（规则集中在 TypeSystem，见 plan.md 3.4）；
 *   4. INSERT 的列数 / 列序 / 值类型与列定义一致的检查。
 * 并把合法的 CREATE TABLE 注册进 Catalog（编译原理中的符号表）。
 *
 * 与其它模块的对接：
 *   - 上游：人 A 的 Parser 产出 List&lt;Statement&gt;（AST）。
 *   - 本类：消费 AST，校验并填充 Catalog；非法时抛 utils.SemanticError，绝不崩溃。
 *   - 下游：人 C 的 Planner 读 AST 生成计划；执行引擎通过 engine.CatalogManager 拿到
 *     同一份表结构（本类只维护编译期符号表 sql_compiler.Catalog，持久化由引擎负责）。
 *
 * 关于「位置」的说明：
 *   当前 AST 节点（ColumnRef / Literal / 各 Statement 等）不携带行列号信息，
 *   因此语义错误暂时无法精确定位到具体列，这里统一用 (0, 0) 占位。
 *   若要精确定位，需要与前端（人 A）协调，在 AST 节点上补充 line/col 字段；
 *   届时只需把本类中 fail() 的参数换成真实位置即可，其余逻辑不变。
 */
public class SemanticAnalyzer {
    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    /** 语义分析主入口：逐条语句分析，非法时抛 SemanticError（[类型, 位置, 原因]）。 */
    public void analyze(List<Statement> statements) {
        for (Statement stmt : statements) {
            analyzeStatement(stmt);
        }
    }

    /** 按语句类型分发到对应的检查方法。 */
    private void analyzeStatement(Statement stmt) {
        if (stmt instanceof CreateTableStmt) {
            analyzeCreate((CreateTableStmt) stmt);
        } else if (stmt instanceof InsertStmt) {
            analyzeInsert((InsertStmt) stmt);
        } else if (stmt instanceof SelectStmt) {
            analyzeSelect((SelectStmt) stmt);
        } else if (stmt instanceof DeleteStmt) {
            analyzeDelete((DeleteStmt) stmt);
        } else {
            fail("UnknownStatement", "unknown statement type: " + stmt.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------
    // CREATE TABLE
    // ---------------------------------------------------------------------

    private void analyzeCreate(CreateTableStmt stmt) {
        String table = stmt.getTable();

        // 1. 重复建表检查
        if (catalog.containsTable(table)) {
            fail("DuplicateTable", "table '" + table + "' already exists");
        }

        // 2. 同一张表内列名不得重复
        Set<String> seen = new HashSet<>();
        for (ColumnDef col : stmt.getColumns()) {
            if (!seen.add(col.getName())) {
                fail("DuplicateColumn", "duplicate column name '" + col.getName() + "' in table '" + table + "'");
            }
        }

        // 3. 注册进符号表，后续语句即可引用该表
        catalog.createTable(table, stmt.getColumns());
    }

    // ---------------------------------------------------------------------
    // INSERT
    // ---------------------------------------------------------------------

    private void analyzeInsert(InsertStmt stmt) {
        String table = stmt.getTable();

        // 1. 表存在性
        List<ColumnDef> schema = requireTable(table);

        // 2. 解析目标列：省略列名列表时按建表顺序取全部列；否则逐个做存在性检查
        List<ColumnDef> targetCols = new ArrayList<>();
        if (stmt.getColumns() == null) {
            targetCols.addAll(schema); // INSERT INTO t VALUES (...) —— 按建表顺序
        } else {
            for (String colName : stmt.getColumns()) {
                ColumnDef col = catalog.getColumn(table, colName);
                if (col == null) {
                    fail("ColumnNotFound", "column '" + colName + "' does not exist in table '" + table + "'");
                }
                targetCols.add(col);
            }
        }

        // 3. 列数一致检查
        if (stmt.getValues().size() != targetCols.size()) {
            fail("ColumnCountMismatch",
                    "INSERT into '" + table + "' expects " + targetCols.size()
                            + " values, but got " + stmt.getValues().size());
        }

        // 4. 逐列检查：值类型与列类型一致（含 VARCHAR 长度上限）
        for (int i = 0; i < targetCols.size(); i++) {
            ColumnDef col = targetCols.get(i);
            Expr valueExpr = stmt.getValues().get(i);
            ColumnType valueType = inferType(valueExpr, table);
            try {
                TypeSystem.checkAssignable(col.getType(), valueType);
            } catch (IllegalArgumentException e) {
                fail("TypeError", e.getMessage() + " (column '" + col.getName() + "')");
            }
            checkVarcharLength(col, valueExpr);
        }
    }

    /** 若值是 VARCHAR 字面量，检查其长度不超过列声明的 VARCHAR(n)。 */
    private void checkVarcharLength(ColumnDef col, Expr valueExpr) {
        if (col.getType() == ColumnType.VARCHAR && valueExpr instanceof Literal) {
            Object v = ((Literal) valueExpr).getValue();
            if (v instanceof String && ((String) v).length() > col.getVarcharLength()) {
                fail("ValueTooLong", "value for column '" + col.getName()
                        + "' exceeds VARCHAR(" + col.getVarcharLength() + ")");
            }
        }
    }

    // ---------------------------------------------------------------------
    // SELECT / DELETE
    // ---------------------------------------------------------------------

    private void analyzeSelect(SelectStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);

        // 1. 投影项检查：Star 表示全列无需检查；其余（列引用 / 常量 / 算术 / 比较 / 逻辑）
        //    走统一的类型推导，内部完成列名绑定与类型检查
        for (Expr item : stmt.getSelectItems()) {
            if (item instanceof Star) {
                // SELECT *：合法，无需检查
                continue;
            }
            inferType(item, table);
        }

        // 2. WHERE 条件：最终类型必须是 BOOL
        checkWhere(stmt.getWhere(), table);
    }

    private void analyzeDelete(DeleteStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);
        checkWhere(stmt.getWhere(), table);
    }

    /** 检查 WHERE 条件（可为 null 表示无条件）；非空时其类型必须为 BOOL。 */
    private void checkWhere(Expr where, String defaultTable) {
        if (where == null) {
            return;
        }
        ColumnType t = inferType(where, defaultTable);
        if (t != ColumnType.BOOL) {
            fail("TypeError", "WHERE condition must be BOOL, but got " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 表达式类型推导（核心，供上方各检查复用）
    // ---------------------------------------------------------------------

    /**
     * 递归推导表达式类型，同时完成列名绑定与类型检查。
     * 非法时抛 SemanticError；返回值即该表达式的静态类型。
     */
    private ColumnType inferType(Expr e, String defaultTable) {
        if (e instanceof Literal) {
            // 字面量类型在词法阶段就已确定，直接取用
            return ((Literal) e).getType();
        }
        if (e instanceof ColumnRef) {
            // 名字绑定：ColumnRef -> Catalog 中的列类型
            return resolveColumn((ColumnRef) e, defaultTable);
        }
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            ColumnType left = inferType(c.getLeft(), defaultTable);
            ColumnType right = inferType(c.getRight(), defaultTable);
            try {
                TypeSystem.checkComparison(left, c.getOp(), right);
            } catch (IllegalArgumentException ex) {
                fail("TypeError", ex.getMessage());
            }
            return ColumnType.BOOL;
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            ColumnType left = inferType(b.getLeft(), defaultTable);
            ColumnType right = inferType(b.getRight(), defaultTable);
            if (b.getOp().isLogical()) {
                // AND / OR：两侧必须 BOOL，结果 BOOL
                try {
                    TypeSystem.checkLogical(left, b.getOp(), right);
                } catch (IllegalArgumentException ex) {
                    fail("TypeError", ex.getMessage());
                }
                return ColumnType.BOOL;
            }
            // 算术运算：+ - * /
            try {
                return TypeSystem.arithmetic(left, b.getOp(), right);
            } catch (IllegalArgumentException ex) {
                fail("TypeError", ex.getMessage());
            }
        }
        if (e instanceof UnaryExpr) {
            UnaryExpr u = (UnaryExpr) e;
            ColumnType operand = inferType(u.getOperand(), defaultTable);
            try {
                TypeSystem.checkUnaryLogical(operand);
            } catch (IllegalArgumentException ex) {
                fail("TypeError", ex.getMessage());
            }
            return ColumnType.BOOL;
        }
        if (e instanceof Star) {
            fail("SemanticError", "'*' is not allowed in an expression");
        }
        fail("SemanticError", "unexpected expression: " + e.getClass().getSimpleName());
        return null; // 不可达：fail() 总是抛异常
    }

    /** 名字绑定：把 ColumnRef 解析为 Catalog 中具体的列类型（表/列不存在则报错）。 */
    private ColumnType resolveColumn(ColumnRef ref, String defaultTable) {
        // 单表查询：优先用列引用里显式的表名，缺省则用当前语句的表名
        String table = ref.getTable() != null ? ref.getTable() : defaultTable;
        if (!catalog.containsTable(table)) {
            fail("TableNotFound", "table '" + table + "' does not exist");
        }
        if (!catalog.containsColumn(table, ref.getColumn())) {
            fail("ColumnNotFound", "column '" + ref.getColumn() + "' does not exist in table '" + table + "'");
        }
        return catalog.getColumn(table, ref.getColumn()).getType();
    }

    /** 取表结构；表不存在则报错。 */
    private List<ColumnDef> requireTable(String table) {
        List<ColumnDef> schema = catalog.getColumns(table);
        if (schema == null) {
            fail("TableNotFound", "table '" + table + "' does not exist");
        }
        return schema;
    }

    /** 统一的语义错误抛出入口（位置暂用 (0,0) 占位，见类顶部注释）。 */
    private void fail(String errorType, String reason) {
        throw new SemanticError(errorType, 0, 0, reason);
    }
}
