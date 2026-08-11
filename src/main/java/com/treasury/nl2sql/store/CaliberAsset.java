package com.treasury.nl2sql.store;

import java.time.LocalDateTime;

/**
 * 口径资产：一条被人工核验采纳的「问题 -> 已校验 MQL」。
 * 资产单元是 MQL（已校验的 IR）而非裸 SQL：方言无关、可再校验（防 schema 漂移）、天然是 few-shot 格式。
 *
 * <p>该 record 作为治理/复用边界，持久化时只落地描述性字段和状态，不承载运行时 trace，
 * 以便历史资产可独立追溯且不受上层会话状态污染。
 */
public record CaliberAsset(
        /**
         * 自增主键，系统内唯一标识。
         */
        long id,
        /**
         * 原问题文本。用于人工复核和后续命中展示。
         */
        String question,
        /**
         * 已校验的 MQL JSON。运行时执行前会再次经过校验器与编译器，保证反复可复现。
         */
        String mqlJson,
        /**
         * 中文口径说明。可为空（例如反译失败时）但不会阻断资产落库。
         */
        String description,    // 中文口径描述（核验采纳时 AI 反翻译生成；旧资产/生成失败为 null）
        /**
         * 创建者标识，供治理归因使用。
         */
        String createdBy,
        /**
         * 创建时间（数据库侧写入时区语义见数据源时区配置）。
         */
        LocalDateTime createdAt,
        /**
         * 状态机状态。当前有效集仅 ACTIVE/DEPRECATED，配合检索入口保证默认只读 ACTIVE。
         */
        String status          // ACTIVE | DEPRECATED
) {}
