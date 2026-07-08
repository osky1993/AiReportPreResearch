package com.treasury.nl2sql.report.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 报告模板资产的持久层（report_template 表，版本化、行不可变）。 */
@Repository
public class TemplateAssetRepository extends VersionedAssetRepository {

    public TemplateAssetRepository(JdbcTemplate jdbc) {
        super(jdbc, "report_template", "template_id");
    }
}
