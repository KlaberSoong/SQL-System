package engine;

import sql_compiler.ast.Expr;
import sql_compiler.plan.CreateTablePlan;
import sql_compiler.plan.DeletePlan;
import sql_compiler.plan.FilterPlan;
import sql_compiler.plan.InsertPlan;
import sql_compiler.plan.PlanNode;
import sql_compiler.plan.ProjectPlan;
import sql_compiler.plan.SeqScanPlan;
import utils.ColumnDef;
import utils.ColumnType;
import utils.DbException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎：逐算子执行逻辑计划，调用存储引擎读写数据。
 *
 * <p>算子与返回值约定：
 * <ul>
 *   <li>CreateTable / Insert / Delete -> 返回操作提示字符串；</li>
 *   <li>SeqScan / Filter / Project -> 返回 {@link QueryResult}（列名 + 行）。</li>
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
        if (plan instanceof SeqScanPlan) {
            return executeSeqScan((SeqScanPlan) plan);
        }
        if (plan instanceof FilterPlan) {
            return executeFilter((FilterPlan) plan);
        }
        if (plan instanceof ProjectPlan) {
            return executeProject((ProjectPlan) plan);
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

    private QueryResult executeSeqScan(SeqScanPlan plan) {
        List<ColumnDef> schema = requireSchema(plan.getTable());
        return new QueryResult(columnNames(schema), storage.scanTable(plan.getTable()));
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
            return values; // 按建表顺序的全列值
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
            full[j] = values.get(i);
        }
        return Arrays.asList(full);
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
