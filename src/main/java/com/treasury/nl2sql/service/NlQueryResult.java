package com.treasury.nl2sql.service;

import com.treasury.nl2sql.ir.Mql;

import java.util.List;
import java.util.Map;

/** 一次 NL→MQL→SQL→结果 的完整产物，便于前端/调试观察每一跳。 */
public record NlQueryResult(
        String question,
        Mql mql,
        String sql,
        List<Map<String, Object>> rows,
        List<String> errors,
        int fixRounds,
        boolean success,
        List<String> warnings,
        /** 非空=模型判定问题无法用本库只读查询回答（可引导澄清）；机械失败或成功时为 null。 */
        String clarifyReason
) {}
