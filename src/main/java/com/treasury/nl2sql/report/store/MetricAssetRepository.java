package com.treasury.nl2sql.report.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 指标语义定义资产的持久层（report_metric 表，版本化、行不可变）。 */
@Repository
public class MetricAssetRepository extends VersionedAssetRepository {

    public MetricAssetRepository(JdbcTemplate jdbc) {
        super(jdbc, "report_metric", "metric_id");
    }
}
