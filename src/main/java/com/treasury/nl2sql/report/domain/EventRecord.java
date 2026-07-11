package com.treasury.nl2sql.report.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 业务事件（Phase05 契约2，report_event 行）：归因候选的唯一来源。
 * T0 拍板：事件是业务记录不是口径资产——单行可编辑 + 留痕列，不上多版本；DEPRECATED 即下架不物理删。
 * title/description 视为**不可信输入**（纪律 12）：录入白名单（EventAdminService）+ 进 prompt 前
 * 转义截断（EventMatcher）双闸防间接注入。
 */
public record EventRecord(
        long eventId,
        String title,
        LocalDate eventDate,
        Map<String, String> dimensions,
        List<String> relatedMetrics,
        String description,
        String source,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        String updatedBy,
        LocalDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
}
