package engine;

import sql_compiler.plan.PlanNode;

/**
 * 执行引擎：逐算子执行逻辑计划，调用存储引擎读写数据。
 */
public class Executor {
    private final StorageEngine storage;

    public Executor(StorageEngine storage) {
        this.storage = storage;
    }

    /**
     * 执行入口：根据计划节点类型分发到对应算子。
     * TODO: CreateTable / Insert / SeqScan / Filter / Project / Delete。
     * 返回执行结果（查询结果集或操作提示）。
     */
    public Object execute(PlanNode plan) {
        throw new UnsupportedOperationException("TODO: 实现 execute()");
    }
}
