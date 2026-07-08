package com.treasury.nl2sql.report.store;

import java.time.LocalDateTime;

/**
 * 版本化资产行（report_template / report_metric 同构落库形态）。
 * 行不可变：唯一允许的 UPDATE 是 status；bodyJson 为该版本资产的完整 JSON（唯一事实源）。
 */
public record AssetRow(
        long id,
        String assetId,        // template_id / metric_id
        int version,
        String name,
        String bodyJson,
        String status,         // DRAFT | PUBLISHED | DEPRECATED
        String source,         // SEED | MANUAL
        String createdBy,
        LocalDateTime createdAt,
        String remark
) {}
