package com.treasury.nl2sql.report.domain;

/** 6 步流水线的步骤标识（report_run.phase / report_step.phase）。 */
public enum Phase {
    /** ① 需求 → 大纲（LLM，同步） */
    OUTLINE,
    /** ② 语义解析：大纲 → MetricQuerySpec（纯程序） */
    SPEC,
    /** ③ 安全取数：MQL 模板填充 → 校验 → 编译执行（纯程序，零 LLM） */
    FETCH,
    /** ④ 事实构建：结果 → FactRecord + 派生计算（纯程序） */
    FACT,
    /** ⑤ 章节撰写（LLM，只写 {{fact_key}} 占位符） */
    WRITE,
    /** ⑥ 证据审计：数字一致率硬门禁（纯程序） */
    AUDIT
}
