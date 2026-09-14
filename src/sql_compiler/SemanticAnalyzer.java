package sql_compiler;

import sql_compiler.ast.AggregateCall;
import sql_compiler.ast.Assignment;
import sql_compiler.ast.BinaryExpr;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Comparison;
import sql_compiler.ast.CreateTableStmt;
import sql_compiler.ast.DeleteStmt;
import sql_compiler.ast.Expr;
import sql_compiler.ast.InsertStmt;
import sql_compiler.ast.JoinRelation;
import sql_compiler.ast.Literal;
import sql_compiler.ast.OrderByItem;
import sql_compiler.ast.Relation;
import sql_compiler.ast.SelectStmt;
import sql_compiler.ast.Star;
import sql_compiler.ast.Statement;
import sql_compiler.ast.TableRelation;
import sql_compiler.ast.UnaryExpr;
import sql_compiler.ast.UpdateStmt;
import utils.ColumnDef;
import utils.ColumnType;
import utils.SemanticError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义分析器（对应 plan.md 3.3）：在 AST 上做表/列存在性检查、名字绑定、类型检查与 INSERT/UPDATE 匹配，
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
        } else if (stmt instanceof UpdateStmt) {
            analyzeUpdate((UpdateStmt) stmt);
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
        Scope scope = singleScope(table);

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
            ColumnType valueType = inferType(valueExpr, scope);
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

    // SELECT：检查 FROM 各表存在、投影项/分组/排序列类型，并做聚合与分组合法性检查
    private void analyzeSelect(SelectStmt stmt) {
        Scope scope = buildScope(stmt.getFrom());
        for (String table : scope.tables) {
            requireTable(table);
        }

        boolean hasGroupBy = stmt.getGroupBy() != null && !stmt.getGroupBy().isEmpty();
        boolean hasAggregate = false;
        for (Expr item : stmt.getSelectItems()) {
            if (item instanceof Star) {
                continue;
            }
            if (containsAggregate(item)) {
                hasAggregate = true;
            }
            inferType(item, scope);
        }

        if (hasGroupBy || hasAggregate) {
            Set<String> groupCols = collectGroupColumns(stmt.getGroupBy());
            for (Expr item : stmt.getSelectItems()) {
                if (item instanceof Star) {
                    fail("SemanticError", "'*' is not allowed with GROUP BY or aggregate functions");
                }
                if (!containsAggregate(item) && !isGrouped(item, groupCols)) {
                    fail("SemanticError", "column '" + item + "' must appear in GROUP BY or be used in an aggregate");
                }
            }
        }

        if (stmt.getGroupBy() != null) {
            for (Expr g : stmt.getGroupBy()) {
                inferType(g, scope);
            }
        }
        if (stmt.getOrderBy() != null) {
            for (OrderByItem o : stmt.getOrderBy()) {
                inferType(o.getExpr(), scope);
            }
        }
        checkWhere(stmt.getWhere(), scope);
    }

    // DELETE：检查表存在与 WHERE 为 BOOL
    private void analyzeDelete(DeleteStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);
        checkWhere(stmt.getWhere(), singleScope(table));
    }

    // UPDATE：检查表存在、SET 列存在且值类型可赋值、WHERE 为 BOOL
    private void analyzeUpdate(UpdateStmt stmt) {
        String table = stmt.getTable();
        requireTable(table);
        Scope scope = singleScope(table);
        for (Assignment a : stmt.getAssignments()) {
            ColumnDef col = catalog.getColumn(table, a.getColumn());
            if (col == null) {
                fail("ColumnNotFound", "column '" + a.getColumn() + "' does not exist in table '" + table + "'");
            }
            ColumnType vt = inferType(a.getValue(), scope);
            try {
                TypeSystem.checkAssignable(col.getType(), vt);
            } catch (IllegalArgumentException e) {
                fail("TypeError", e.getMessage() + " (column '" + col.getName() + "')");
            }
        }
        checkWhere(stmt.getWhere(), scope);
    }

    // 检查 WHERE 条件（可为 null）；非空时其类型必须为 BOOL
    private void checkWhere(Expr where, Scope scope) {
        if (where == null) {
            return;
        }
        ColumnType t = inferType(where, scope);
        if (t != ColumnType.BOOL) {
            fail("TypeError", "WHERE condition must be BOOL, but got " + t);
        }
    }

    // 递归推导表达式类型，同时完成列名绑定与类型检查
    private ColumnType inferType(Expr e, Scope scope) {
        if (e instanceof Literal) {
            return ((Literal) e).getType();
        }
        if (e instanceof ColumnRef) {
            return resolveColumn((ColumnRef) e, scope);
        }
        if (e instanceof AggregateCall) {
            AggregateCall ag = (AggregateCall) e;
            ColumnType argType = null;
            if (ag.getArg() != null) {
                argType = inferType(ag.getArg(), scope);
            }
            try {
                return TypeSystem.checkAggregate(ag.getFunc(), argType);
            } catch (IllegalArgumentException ex) {
                fail("TypeError", ex.getMessage());
            }
        }
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            ColumnType left = inferType(c.getLeft(), scope);
            ColumnType right = inferType(c.getRight(), scope);
            try {
                TypeSystem.checkComparison(left, c.getOp(), right);
            } catch (IllegalArgumentException ex) {
                fail("TypeError", ex.getMessage());
            }
            return ColumnType.BOOL;
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            ColumnType left = inferType(b.getLeft(), scope);
            ColumnType right = inferType(b.getRight(), scope);
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
            ColumnType operand = inferType(u.getOperand(), scope);
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

    // 名字绑定：把 ColumnRef 解析为 Catalog 中具体的列类型（表/列不存在或歧义则报错）
    private ColumnType resolveColumn(ColumnRef ref, Scope scope) {
        return resolveColumnDef(ref, scope).getType();
    }

    // 把 ColumnRef 解析为列定义（含表限定/别名解析与多表歧义检查）
    private ColumnDef resolveColumnDef(ColumnRef ref, Scope scope) {
        if (ref.getTable() != null) {
            String real = scope.aliasToTable.get(ref.getTable());
            if (real == null) {
                fail("TableNotFound", "table or alias '" + ref.getTable() + "' does not exist");
            }
            ColumnDef col = catalog.getColumn(real, ref.getColumn());
            if (col == null) {
                fail("ColumnNotFound", "column '" + ref.getColumn() + "' does not exist in table '" + real + "'");
            }
            return col;
        }
        if (scope.tables.size() == 1) {
            ColumnDef col = catalog.getColumn(scope.tables.get(0), ref.getColumn());
            if (col == null) {
                fail("ColumnNotFound", "column '" + ref.getColumn() + "' does not exist");
            }
            return col;
        }
        // 多表：在所有表里找，唯一命中可用，多命中报歧义
        ColumnDef found = null;
        for (String t : scope.tables) {
            if (catalog.containsColumn(t, ref.getColumn())) {
                if (found != null) {
                    fail("AmbiguousColumn", "column '" + ref.getColumn() + "' is ambiguous");
                }
                found = catalog.getColumn(t, ref.getColumn());
            }
        }
        if (found == null) {
            fail("ColumnNotFound", "column '" + ref.getColumn() + "' does not exist");
        }
        return found;
    }

    // 取表结构；表不存在则报错
    private List<ColumnDef> requireTable(String table) {
        List<ColumnDef> schema = catalog.getColumns(table);
        if (schema == null) {
            fail("TableNotFound", "table '" + table + "' does not exist");
        }
        return schema;
    }

    // 判断表达式树中是否含聚合调用
    private boolean containsAggregate(Expr e) {
        if (e instanceof AggregateCall) {
            return true;
        }
        if (e instanceof Comparison) {
            Comparison c = (Comparison) e;
            return containsAggregate(c.getLeft()) || containsAggregate(c.getRight());
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr b = (BinaryExpr) e;
            return containsAggregate(b.getLeft()) || containsAggregate(b.getRight());
        }
        if (e instanceof UnaryExpr) {
            return containsAggregate(((UnaryExpr) e).getOperand());
        }
        return false;
    }

    // 收集 GROUP BY 键的列名（ColumnRef 取列名，其余取表达式文本），用于分组合法性判断
    private Set<String> collectGroupColumns(List<Expr> groupBy) {
        Set<String> cols = new HashSet<>();
        if (groupBy == null) {
            return cols;
        }
        for (Expr g : groupBy) {
            cols.add(g instanceof ColumnRef ? ((ColumnRef) g).getColumn() : g.toString());
        }
        return cols;
    }

    // 判断 SELECT 项是否等于某个分组键（按列名/文本近似匹配）
    private boolean isGrouped(Expr item, Set<String> groupCols) {
        if (item instanceof ColumnRef) {
            return groupCols.contains(((ColumnRef) item).getColumn());
        }
        return groupCols.contains(item.toString());
    }

    // —— 作用域：FROM 中的别名/表名 -> 真实表名，以及参与的表名列表 ——
    private static final class Scope {
        final Map<String, String> aliasToTable = new LinkedHashMap<>();
        final List<String> tables = new ArrayList<>();
    }

    // 构造单表作用域（INSERT/DELETE/UPDATE 的目标表）
    private Scope singleScope(String table) {
        Scope s = new Scope();
        s.aliasToTable.put(table, table);
        s.tables.add(table);
        return s;
    }

    // 由 FROM 关系构建多表作用域
    private Scope buildScope(Relation r) {
        Scope s = new Scope();
        collectRelations(r, s);
        return s;
    }

    // 递归收集 FROM 中的表与别名
    private void collectRelations(Relation r, Scope s) {
        if (r instanceof TableRelation) {
            TableRelation t = (TableRelation) r;
            s.aliasToTable.put(t.getAlias() != null ? t.getAlias() : t.getTable(), t.getTable());
            if (!s.tables.contains(t.getTable())) {
                s.tables.add(t.getTable());
            }
        } else if (r instanceof JoinRelation) {
            JoinRelation j = (JoinRelation) r;
            collectRelations(j.getLeft(), s);
            collectRelations(j.getRight(), s);
        }
    }

    // 统一的语义错误抛出入口（位置暂用 (0,0) 占位）
    private void fail(String errorType, String reason) {
        throw new SemanticError(errorType, 0, 0, reason);
    }
}
