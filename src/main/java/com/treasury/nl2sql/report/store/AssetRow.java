package com.treasury.nl2sql.report.store;

import java.time.LocalDateTime;

/**
 * 版本化资产行（report_template / report_metric 同构落库形态）。
 * 行不可变：唯一允许的 UPDATE 是 status；bodyJson 为该版本资产的完整 JSON（唯一事实源）。
 */
public record AssetRow(
        long id,               // 报表资产行的数据库主键（自增）
        String assetId,        // template_id / metric_id
        int version,           // 资产版本号，自增递增，1,2,3...
        String name,           // 人类可读名称
        String bodyJson,       // 某版本的完整 JSON 语义快照
        String status,         // DRAFT | PUBLISHED | DEPRECATED
        String source,         // SEED | MANUAL
        String createdBy,      // 首次入库时的操作者
        LocalDateTime createdAt,// 事实入库时间（不可篡改）
        String remark          // 手工说明/变更摘要
) {}
