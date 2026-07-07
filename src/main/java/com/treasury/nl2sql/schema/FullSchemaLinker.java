package com.treasury.nl2sql.schema;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 全量注入（默认）。表不多时最简单可靠。 */
@Component
@ConditionalOnProperty(name = "schema.linking.mode", havingValue = "full", matchIfMissing = true)
public class FullSchemaLinker implements SchemaLinker {

    private final SchemaService schema;

    public FullSchemaLinker(SchemaService schema) {
        this.schema = schema;
    }

    @Override
    public LinkingResult select(String question) {
        List<String> tables = new ArrayList<>(schema.tableNames());
        return new LinkingResult(tables, schema.assemble(tables), List.of());
    }
}
