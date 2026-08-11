package com.treasury.nl2sql.schema;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 全量注入实现（默认回退）。
 *
 * <p>适用于小模型/小库场景：直接把可见表全部注入 prompt，牺牲 prompt 长度换取稳健。
 * 当 schema.linking.mode 未配置或配置为 full 时启用。
 */
@Component
@ConditionalOnProperty(name = "schema.linking.mode", havingValue = "full", matchIfMissing = true)
public class FullSchemaLinker implements SchemaLinker {

    /** schema 服务，提供全量表清单与组装后的 schema 文本。 */
    private final SchemaService schema;

    /** 构造器。 */
    public FullSchemaLinker(SchemaService schema) {
        this.schema = schema;
    }

    /**
     * 全量返回当前 schema 的表列表与文本，未进行语义过滤。
     * 该路径不依赖 embedding；故障点几乎只可能来自 schemaService（启动失败已阻断）。
     */
    @Override
    public LinkingResult select(String question) {
        List<String> tables = new ArrayList<>(schema.tableNames());
        return new LinkingResult(tables, schema.assemble(tables), List.of());
    }
}
