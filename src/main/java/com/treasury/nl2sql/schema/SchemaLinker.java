package com.treasury.nl2sql.schema;

import java.util.List;

/** 决定一个问题该把哪些表的 schema 注入 prompt。 */
public interface SchemaLinker {

    LinkingResult select(String question);

    /**
     * 同上，复用上游已算好的问题向量（请求内只 embed 一次）。
     * 不依赖向量的实现（如 FullSchemaLinker）无需覆写。
     */
    default LinkingResult select(String question, float[] qv) {
        return select(question);
    }

    record Scored(String table, double score) {}

    record LinkingResult(List<String> tables, String schemaText, List<Scored> scores) {}
}
