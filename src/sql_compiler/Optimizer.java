package sql_compiler;

import sql_compiler.plan.PlanNode;

/**
 * 规则式优化器：对逻辑计划做等价的规则重写。
 */
public class Optimizer {
    /**
     * 优化主入口，依次应用以下规则（可迭代到不动点）：
     *   1. constantFold       常量折叠（age > 10+8 -> age > 18）
     *   2. booleanSimplify    布尔化简（1=1 AND X -> X；1=0 OR X -> X）
     *   3. projectPruning     投影剪枝（只保留真正需要的列）
     *   4. predicatePushdown  谓词下推（Filter 尽量靠近 SeqScan）
     *   5. removeRedundant    冗余节点消除
     */
    public PlanNode optimize(PlanNode plan) {
        throw new UnsupportedOperationException("TODO: 实现 optimize()");
    }

    // 私有方法签名（供实现）：
    // private PlanNode constantFold(PlanNode n)
    // private PlanNode booleanSimplify(PlanNode n)
    // private PlanNode projectPruning(PlanNode n)
    // private PlanNode predicatePushdown(PlanNode n)
    // private PlanNode removeRedundant(PlanNode n)
}
