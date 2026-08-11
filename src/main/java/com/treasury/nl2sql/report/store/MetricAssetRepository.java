package com.treasury.nl2sql.report.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 指标语义定义资产的持久层（report_metric 表，版本化、行不可变）。 */
@Repository
public class MetricAssetRepository extends VersionedAssetRepository {

    /**
     * 把资产 id 字段映射到 report_metric.metric_id，其余版本、状态与幂等规则沿用基类。
     */
    public MetricAssetRepository(JdbcTemplate jdbc) {
        super(jdbc, "report_metric", "metric_id");
    }
}
