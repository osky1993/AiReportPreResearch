package com.treasury.nl2sql.report.domain;

import java.time.LocalDateTime;

/** report_step 表的落库行（每步输入输出留痕，断点恢复与审计追溯的依据）。 */
public record ReportStep(
        long stepId,
        long runId,
        String phase,
        int attempt,
        String status,
        String inputJson,
        String outputJson,
        String errorText,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {}
