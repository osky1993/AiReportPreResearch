package com.treasury.nl2sql.store;

import java.time.LocalDateTime;

/**
 * 口径资产：一条被人工核验采纳的「问题 -> 已校验 MQL」。
 * 资产单元是 MQL（已校验的 IR）而非裸 SQL：方言无关、可再校验（防 schema 漂移）、天然是 few-shot 格式。
 */
public record CaliberAsset(
        long id,
        String question,
        String mqlJson,
        String description,    // 中文口径描述（核验采纳时 AI 反翻译生成；旧资产/生成失败为 null）
        String createdBy,
        LocalDateTime createdAt,
        String status          // ACTIVE | DEPRECATED
) {}
