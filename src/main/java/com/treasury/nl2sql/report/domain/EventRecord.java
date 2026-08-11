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
 * @param eventId 事件主键（用于 EVT-n 映射）
 * @param title 事件标题（会被转义后注入归因 prompt）
 * @param eventDate 事件发生日期（用于 candidate 时间窗打分）
 * @param dimensions 维度标签（示例: currency）与异常贡献维度交集匹配
 * @param relatedMetrics 关联指标 ID 列表（匹配优先分）
 * @param description 事件说明（会被转义/截断后进入 prompt）
 * @param source 事件来源渠道
 * @param status ACTIVE/DEPRECATED（DEPRECATED 保留历史）
 * @param createdBy 录入人
 * @param createdAt 创建时间
 * @param updatedBy 最近更新人
 * @param updatedAt 最近更新时间
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
