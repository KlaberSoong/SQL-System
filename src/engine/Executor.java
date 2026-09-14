package engine;

import sql_compiler.ast.AggregateCall;
import sql_compiler.ast.ColumnRef;
import sql_compiler.ast.Expr;
import sql_compiler.ast.OrderByItem;
import sql_compiler.ast.Star;
import sql_compiler.plan.AggregatePlan;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.JoinPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import sql_compiler.plan.SortPlan;
import sql_compiler.plan.UpdatePlan;
import utils.ColumnDef;
import utils.ColumnType;
import utils.DbException;
import utils.JoinType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎：逐算子执行逻辑计划，调用存储引擎读写数据。
 *
 * <p>算子与返回值约定：
 * <ul>
 *   <li>CreateTable / Insert / Delete / Update -> 返回操作提示字符串；</li>
 *   <li>SeqScan / Filter / Project / Sort / Aggregate / Join -> 返回 {@link QueryResult}（列名 + 行）。</li>
 * </ul>
 * 注意：{@code SELECT *} 经优化器剪枝后计划退化为孤立的 SeqScan，约定「孤立 SeqScan =
 * 输出全列」，因此 SeqScan 直接产出含全列的 QueryResult。
 */
public class Executor {
    private final StorageEngine storage;
    private final CatalogManager catalogManager;

    public Executor(StorageEngine storage, CatalogManager catalogManager) {
        this.storage = storage;
        this.catalogManager = catalogManager;
    }

    /** 执行入口：根据计划节点类型分发到对应算子。 */
    public Object execute(PlanNode plan) {
        if (plan instanceof CreateTablePlan) {
            return executeCreate((CreateTablePlan) plan);
        }
        if (plan instanceof InsertPlan) {
            return executeInsert((InsertPlan) plan);
        }
        if (plan instanceof DeletePlan) {
            return executeDelete((DeletePlan) plan);
        }
        if (plan instanceof UpdatePlan) {
            return executeUpdate((UpdatePlan) plan);
        }
        if (plan instanceof SeqScanPlan) {
            return executeSeqScan((SeqScanPlan) plan);
        }
        if (plan instanceof FilterPlan) {
            return executeFilter((FilterPlan) plan);
        }
        if (plan instanceof ProjectPlan) {
            return executeProject((ProjectPlan) plan);
        }
        if (plan instanceof SortPlan) {
            return executeSort((SortPlan) plan);
        }
        if (plan instanceof AggregatePlan) {
            return executeAggregate((AggregatePlan) plan);
        }
        if (plan instanceof JoinPlan) {
            return executeJoin((JoinPlan) plan);
        }
        throw new DbException("unknown plan node: " + plan.getClass().getSimpleName());
    }

    private String executeCreate(CreateTablePlan plan) {
        String table = plan.getTable();
        storage.createTable(table, plan.getColumns());
        catalogManager.registerTable(table, plan.getColumns());
        return "CREATE TABLE '" + table + "' (" + plan.getColumns().size() + " columns)";
    }

    private String executeInsert(InsertPlan plan) {
        String table = plan.getTable();
        List<ColumnDef> schema = requireSchema(table);
        List<Object> row = buildInsertRow(table, schema, plan);
        storage.insertRow(table, row);
        return "INSERT 1";
    }

    private String executeDelete(DeletePlan plan) {
        int n = storage.deleteRows(plan.getTable(), plan.getCondition());
        return "DELETE " + n;
    }

    private String executeUpdate(UpdatePlan plan) {
        int n = storage.updateRows(plan.getTable(), plan.getCondition(), plan.getAssignments());
        return "UPDATE " + n;
    }

    private QueryResult executeSeqScan(SeqScanPlan plan) {
        List<ColumnDef> schema = requireSchema(plan.getTable());
        List<String> cols = columnNames(schema);
        if (plan.getAlias() != null) {
            List<String> qualified = new ArrayList<>(cols.size());
            for (String c : cols) {
                qualified.add(plan.getAlias() + "." + c);
            }
            cols = qualified;
        }
        return new QueryResult(cols, storage.scanTable(plan.getTable()));
    }

    private QueryResult executeFilter(FilterPlan plan) {
        QueryResult child = (QueryResult) execute(onlyChild(plan));
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(child.getColumns());
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : child.getRows()) {
            if (ExpressionEvaluator.evalCondition(plan.getCondition(), idx, row)) {
                out.add(row);
            }
        }
        return new QueryResult(child.getColumns(), out);
    }

    private QueryResult executeProject(ProjectPlan plan) {
        QueryResult child = (QueryResult) execute(onlyChild(plan));
        boolean selectAll = isSelectAll(plan.getColumns());
        List<String> outColumns = selectAll ? child.getColumns() : plan.getColumns();
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(child.getColumns());

        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : child.getRows()) {
            if (selectAll) {
                out.add(row);
                continue;
            }
            // 逐表达式求值（列引用 / 常量 / 算术 / 比较 / 逻辑均可）
            List<Object> projected = new ArrayList<>(plan.getExpressions().size());
            for (Expr e : plan.getExpressions()) {
                projected.add(ExpressionEvaluator.eval(e, idx, row));
            }
            out.add(projected);
        }
        return new QueryResult(outColumns, out);
    }

    private QueryResult executeSort(SortPlan plan) {
        QueryResult child = (QueryResult) execute(onlyChild(plan));
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(child.getColumns());
        List<List<Object>> rows = new ArrayList<>(child.getRows());
        rows.sort((r1, r2) -> compareRows(plan.getKeys(), idx, r1, r2));
        return new QueryResult(child.getColumns(), rows);
    }

    private QueryResult executeAggregate(AggregatePlan plan) {
        QueryResult child = (QueryResult) execute(onlyChild(plan));
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(child.getColumns());
        List<String> outColumns = displayNames(plan.getSelectItems());

        // 按分组键分桶（无分组键 = 全局单桶），保持首次出现顺序
        Map<List<Object>, List<List<Object>>> groups = new LinkedHashMap<>();
        boolean global = plan.getGroupBy() == null || plan.getGroupBy().isEmpty();
        if (global) {
            groups.put(Collections.emptyList(), child.getRows());
        } else {
            for (List<Object> row : child.getRows()) {
                List<Object> key = new ArrayList<>(plan.getGroupBy().size());
                for (Expr g : plan.getGroupBy()) {
                    key.add(ExpressionEvaluator.eval(g, idx, row));
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        List<List<Object>> out = new ArrayList<>();
        for (List<List<Object>> groupRows : groups.values()) {
            List<Object> first = groupRows.isEmpty() ? Collections.emptyList() : groupRows.get(0);
            List<Object> outRow = new ArrayList<>(plan.getSelectItems().size());
            for (Expr item : plan.getSelectItems()) {
                if (item instanceof AggregateCall) {
                    outRow.add(ExpressionEvaluator.evalAggregate((AggregateCall) item, idx, groupRows));
                } else {
                    outRow.add(ExpressionEvaluator.eval(item, idx, first));
                }
            }
            out.add(outRow);
        }
        return new QueryResult(outColumns, out);
    }

    private QueryResult executeJoin(JoinPlan plan) {
        QueryResult left = (QueryResult) execute(plan.getLeft());
        QueryResult right = (QueryResult) execute(plan.getRight());

        List<String> cols = new ArrayList<>(left.getColumns());
        cols.addAll(right.getColumns());
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(cols);

        List<List<Object>> out = new ArrayList<>();
        for (List<Object> lrow : left.getRows()) {
            boolean matched = false;
            for (List<Object> rrow : right.getRows()) {
                List<Object> combined = new ArrayList<>(lrow);
                combined.addAll(rrow);
                boolean ok = plan.getCondition() == null
                        || ExpressionEvaluator.evalCondition(plan.getCondition(), idx, combined);
                if (ok) {
                    out.add(combined);
                    matched = true;
                }
            }
            if (plan.getType() == JoinType.LEFT && !matched) {
                List<Object> combined = new ArrayList<>(lrow);
                for (int i = 0; i < right.getColumns().size(); i++) {
                    combined.add(null);
                }
                out.add(combined);
            }
        }
        return new QueryResult(cols, out);
    }

    /** 按 ORDER BY 键列表对两行比较，返回 -1/0/1。 */
    private static int compareRows(List<OrderByItem> keys, Map<String, Integer> idx,
                                   List<Object> r1, List<Object> r2) {
        for (OrderByItem key : keys) {
            Object a = ExpressionEvaluator.eval(key.getExpr(), idx, r1);
            Object b = ExpressionEvaluator.eval(key.getExpr(), idx, r2);
            int cmp = ExpressionEvaluator.compareValues(a, b);
            if (cmp != 0) {
                return key.isAsc() ? cmp : -cmp;
            }
        }
        return 0;
    }

    /** 投影/聚合输出列名（Star→"*"、ColumnRef→列名、其余→表达式文本）。 */
    private static List<String> displayNames(List<Expr> exprs) {
        List<String> names = new ArrayList<>(exprs.size());
        for (Expr e : exprs) {
            if (e instanceof Star) {
                names.add("*");
            } else if (e instanceof ColumnRef) {
                names.add(((ColumnRef) e).getColumn());
            } else {
                names.add(e.toString());
            }
        }
        return names;
    }

    /**
     * 把 INSERT 的列名列表与值表达式列表合并为「按建表顺序的完整行」。
     * 省略列名列表时按建表顺序取全部列；显式列名列表时未指定的列填类型默认值
     * （INT/FLOAT=0、BOOL=false、VARCHAR=""，本子集无 NULL）。
     */
    private List<Object> buildInsertRow(String table, List<ColumnDef> schema, InsertPlan plan) {
        List<Object> values = new ArrayList<>(plan.getValues().size());
        for (Expr v : plan.getValues()) {
            values.add(ExpressionEvaluator.eval(v, Collections.emptyMap(), Collections.emptyList()));
        }
        if (plan.getColumns() == null) {
            // 按建表顺序取全列；值需按目标列类型转换（如 INT 字面量写入 FLOAT 列时拓宽为 Float）
            List<Object> coerced = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                coerced.add(coerce(values.get(i), schema.get(i).getType()));
            }
            return coerced;
        }
        if (plan.getColumns().size() != values.size()) {
            throw new DbException("INSERT column/value count mismatch");
        }
        Object[] full = new Object[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            full[i] = defaultValue(schema.get(i).getType());
        }
        Map<String, Integer> idx = ExpressionEvaluator.indexMap(columnNames(schema));
        for (int i = 0; i < plan.getColumns().size(); i++) {
            Integer j = idx.get(plan.getColumns().get(i));
            if (j == null) {
                throw new DbException("column '" + plan.getColumns().get(i)
                        + "' not found in table '" + table + "'");
            }
            full[j] = coerce(values.get(i), schema.get(j).getType());
        }
        return Arrays.asList(full);
    }

    /** 值类型 -> 列类型 的运行时转换（语义分析已保证兼容，这里补齐 INT -> FLOAT 拓宽）。 */
    private static Object coerce(Object value, ColumnType target) {
        if (value == null) {
            return value;
        }
        if (target == ColumnType.FLOAT && value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (target == ColumnType.INT && value instanceof Number) {
            return ((Number) value).intValue();
        }
        return value;
    }

    private static Object defaultValue(ColumnType t) {
        switch (t) {
            case INT: return 0;
            case FLOAT: return 0.0f;
            case BOOL: return false;
            case VARCHAR: return "";
            default: throw new DbException("unknown column type: " + t);
        }
    }

    private List<ColumnDef> requireSchema(String table) {
        List<ColumnDef> schema = catalogManager.getTableSchema(table);
        if (schema == null) {
            throw new DbException("table '" + table + "' does not exist");
        }
        return schema;
    }

    private PlanNode onlyChild(PlanNode n) {
        List<PlanNode> kids = n.getChildren();
        return kids.isEmpty() ? null : kids.get(0);
    }

    private boolean isSelectAll(List<String> columns) {
        return columns.size() == 1 && "*".equals(columns.get(0));
    }

    private static List<String> columnNames(List<ColumnDef> schema) {
        List<String> names = new ArrayList<>(schema.size());
        for (ColumnDef c : schema) {
            names.add(c.getName());
        }
        return names;
    }
}
