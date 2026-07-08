package com.treasury.nl2sql.report.pipeline;

import com.treasury.nl2sql.compile.MqlSqlCompiler;
import com.treasury.nl2sql.ir.Mql;
import com.treasury.nl2sql.validate.MqlValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MQL 试执行小组件（指标保存链的 ③④ 重校验）：白名单校验 → jOOQ 编译 → 只读执行。
 * 独立成组件的目的：MetricAdminService 的五重校验链在单测中可用 stub 替换本类，
 * mvn test 保持不碰 DB。任何失败统一转 PolicyException（消息压单行，可读）。
 */
@Component
public class MqlTrialExecutor {

    private final MqlValidator validator;
    private final MqlSqlCompiler compiler;
    private final DSLContext dsl;

    public MqlTrialExecutor(MqlValidator validator, MqlSqlCompiler compiler, DSLContext dsl) {
        this.validator = validator;
        this.compiler = compiler;
        this.dsl = dsl;
    }

    /** 白名单校验（不执行）。 */
    public List<String> validate(Mql mql) {
        return validator.validate(mql);
    }

    /** 校验 + 编译 + 只读执行，返回行集（供恰 1 行 1 值检查）。 */
    public List<Map<String, Object>> execute(Mql mql) {
        List<String> errors = validator.validate(mql);
        if (!errors.isEmpty()) {
            throw new PolicyException("MQL 未通过白名单校验: " + String.join("；", errors));
        }
        try {
            SelectQuery<Record> query = compiler.compile(mql);
            return dsl.fetch(query).intoMaps();
        } catch (Exception e) {
            throw new PolicyException("试执行失败: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
        return msg.replaceAll("\\s+", " ").trim();
    }
}
