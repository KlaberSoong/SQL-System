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
 * 语义分析器（对应 plan.md 3.3）：在 AST 上做表/列存在性检查、名字绑定、类型检查与 INSERT 匹配，
 * 并把合法的 CREATE TABLE 注册进 Catalog（编译期符号表）。类型规则集中查询 TypeSystem。
 * 因 AST 节点暂不携带行列号，语义错误位置统一用 (0,0) 占位。
 */
public class SemanticAnalyzer {
    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    // 语义分析主入口：逐条语句分析，非法时抛 SemanticError
    public void analyze(List<Statement> statements) {
        for (Statement stmt : statements) {
            analyzeStatement(stmt);
        }
    }

    // 按语句类型分发到对应的检查方法
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

    // CREATE TABLE：检查重复建表、列名重复后注册进符号表
    private void analyzeCreate(CreateTableStmt stmt) {
        String table = stmt.getTable();
        if (catalog.containsTable(table)) {
            fail("DuplicateTable", "table '" + table + "' already exists");
        }
        Set<String> seen = new HashSet<>();
        for (ColumnDef col : stmt.getColumns()) {
            if (!seen.add(col.getName())) {
                fail("DuplicateColumn", "duplicate column name '" + col.getName() + "' in table '" + table + "'");
            }
        }
        catalog.createTable(table, stmt.getColumns());
    }

    // INSERT：检查表存在、目标列存在、列数一致、值类型与列类型兼容
    private void analyzeInsert(InsertStmt stmt) {
        String table = stmt.getTable();
        List<ColumnDef> schema = requireTable(table);

        List<ColumnDef> targetCols = new ArrayList<>();
        if (stmt.getColumns() == null) {
            targetCols.addAll(schema);
        } else {
            for (String colName : stmt.getColumns()) {
                ColumnDef col = catalog.getColumn(table, colName);
                if (col == null) {
                    fail("ColumnNotFound", "column '" + colName + "' does not exist in table '" + table + "'");
                }
                targetCols.add(col);
            }
        }

        if (stmt.getValues().size() != targetCols.size()) {
            fail("ColumnCountMismatch",
                    "INSERT into '" + table + "' expects " + targetCols.size()
                            + " values, but got " + stmt.getValues().size());
        }

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

    // 若值是 VARCHAR 字面量，检查其长度不超过列声明的 VARCHAR(n)
    private void checkVarcharLength(ColumnDef col, Expr valueExpr) {
        if (col.getType() == ColumnType.VARCHAR && valueExpr instanceof Literal) {
            Object v = ((Literal) valueExpr).getValue();
            if (v instanceof String && ((String) v).length() > col.getVarcharLength()) {
                fail("ValueTooLong", "value for column '" + col.getName()
                        + "' exceeds VARCHAR(" + col.getVarcharLength() + ")");
            }
        }
    }

    // SELECT：检查表存在，逐个投影项推导类型（含列名绑定），并检查 WHERE 为 BOOL
    private void analyzeSelect(SelectStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);
        for (Expr item : stmt.getSelectItems()) {
            if (item instanceof Star) {
                continue;
            }
            inferType(item, table);
        }
        checkWhere(stmt.getWhere(), table);
    }

    // DELETE：检查表存在与 WHERE 为 BOOL
    private void analyzeDelete(DeleteStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);
        checkWhere(stmt.getWhere(), table);
    }

    // 检查 WHERE 条件（可为 null）；非空时其类型必须为 BOOL
    private void checkWhere(Expr where, String defaultTable) {
        if (where == null) {
            return;
        }
        ColumnType t = inferType(where, defaultTable);
        if (t != ColumnType.BOOL) {
            fail("TypeError", "WHERE condition must be BOOL, but got " + t);
        }
    }

    // 递归推导表达式类型，同时完成列名绑定与类型检查
    private ColumnType inferType(Expr e, String defaultTable) {
        if (e instanceof Literal) {
            return ((Literal) e).getType();
        }
        if (e instanceof ColumnRef) {
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
                try {
                    TypeSystem.checkLogical(left, b.getOp(), right);
                } catch (IllegalArgumentException ex) {
                    fail("TypeError", ex.getMessage());
                }
                return ColumnType.BOOL;
            }
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
        return null; // 不可达
    }

    // 名字绑定：把 ColumnRef 解析为 Catalog 中具体的列类型（表/列不存在则报错）
    private ColumnType resolveColumn(ColumnRef ref, String defaultTable) {
        String table = ref.getTable() != null ? ref.getTable() : defaultTable;
        if (!catalog.containsTable(table)) {
            fail("TableNotFound", "table '" + table + "' does not exist");
        }
        if (!catalog.containsColumn(table, ref.getColumn())) {
            fail("ColumnNotFound", "column '" + ref.getColumn() + "' does not exist in table '" + table + "'");
        }
        return catalog.getColumn(table, ref.getColumn()).getType();
    }

    // 取表结构；表不存在则报错
    private List<ColumnDef> requireTable(String table) {
        List<ColumnDef> schema = catalog.getColumns(table);
        if (schema == null) {
            fail("TableNotFound", "table '" + table + "' does not exist");
        }
        return schema;
    }

    // 统一的语义错误抛出入口（位置暂用 (0,0) 占位）
    private void fail(String errorType, String reason) {
        throw new SemanticError(errorType, 0, 0, reason);
    }
}
