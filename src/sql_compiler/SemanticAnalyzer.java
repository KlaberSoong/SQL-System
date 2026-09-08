package sql_compiler;

import sql_compiler.ast.Statement;

import java.util.List;

/**
 * 语义分析器：在 AST 基础上做存在性 / 类型一致性 / 列数列序检查，并维护 Catalog。
 */
public class SemanticAnalyzer {
    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 语义分析主入口：逐条语句分析，非法时抛 utils.SemanticError（[类型, 位置, 原因]）。
     *
     * 检查项：
     *   1. CREATE TABLE：重复建表、列名重复、类型合法
     *   2. INSERT：表存在性、列数/列序、值类型与列类型匹配
     *   3. SELECT / DELETE：表存在性、列存在性、WHERE 中列存在性、类型一致性
     */
    public void analyze(List<Statement> statements) {
        throw new UnsupportedOperationException("TODO: 实现 analyze()");
    }
}
