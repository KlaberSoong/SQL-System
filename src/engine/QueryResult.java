package engine;

import java.util.List;

/**
 * 查询结果集：列名列表 + 行数据（每行是值列表）。
 * 执行引擎的 SeqScan / Filter / Project 算子统一返回本类型，便于 CLI 按表格式输出。
 */
public class QueryResult {
    private final List<String> columns;
    private final List<List<Object>> rows;

    public QueryResult(List<String> columns, List<List<Object>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }
}
