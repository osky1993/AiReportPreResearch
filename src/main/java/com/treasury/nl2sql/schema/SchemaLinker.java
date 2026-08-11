package com.treasury.nl2sql.schema;

import java.util.List;

/**
 * 决定一个问题该把哪些表的 schema 注入 prompt。
 *
 * <p>接口约束：
 * <ul>
 *   <li>select 返回的 tables 与 schemaText 必须可直接用于模型输入。</li>
 *   <li>在 embedding 不可用/异常时，应提供可接受的降级行为（通常是全量表注入）。</li>
 *   <li>可选分数列表仅供指标与排障，不参与编译执行链路。</li>
 * </ul>
 */
public interface SchemaLinker {

    /**
     * 按问题选择注入表集合。默认实现可按配置选择「全量」或「向量」。
     * @param question 用户问题
     * @return 选表 + schema 拼接文本 + 分数明细
     */
    LinkingResult select(String question);

    /**
     * 同上，复用上游已算好的问题向量（请求内只 embed 一次）。
     * 不依赖向量的实现（如 FullSchemaLinker）无需覆写。
     */
    default LinkingResult select(String question, float[] qv) {
        return select(question);
    }

    /** 表命中分数；用于评估召回质量，不作为执行参数。 */
    record Scored(String table, double score) {}

    /**
     * 统一返回体：
     * tables - 供后续可见性和审计使用的表名清单（含排序语义）
     * schemaText - 可直接拼接到系统提示的结构化 schema 文本
     * scores - Top-N 可选分数列表
     */
    record LinkingResult(List<String> tables, String schemaText, List<Scored> scores) {}
}
