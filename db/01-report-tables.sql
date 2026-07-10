-- =============================================================
-- 报告流水线状态表（ideaV2 核心路径「最小运行状态」落库形态）
-- 独立于 db/00-init.sql：reportbi 业务库已存在，本脚本只增流水线元数据表。
--   mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/01-report-tables.sql
-- 三张表均为系统元数据表，必须列入 application.yml 的 schema.exclude-tables，
-- 不得进入 NL2SQL 的表白名单。
-- 可重复执行（DROP 后重建 = 清空全部运行记录，回到零状态）。
-- =============================================================

DROP TABLE IF EXISTS report_fact;
DROP TABLE IF EXISTS report_step;
DROP TABLE IF EXISTS report_run;

CREATE TABLE report_run (
  run_id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '运行ID（report_run_id）',
  request_text   TEXT NOT NULL COMMENT '原始自然语言需求',
  template_id    VARCHAR(64) COMMENT '命中的报告模板ID',
  template_version INT COMMENT '命中模板的固化版本号（创建 run 时锁定，resume 不追新版）',
  metric_versions_json TEXT COMMENT '指标版本快照 JSON {metricId: version}（卡点1 确认时固化，含派生操作数；resume 不追新版；NULL=Phase02 前存量 run 未固化）',
  period_label   VARCHAR(16) COMMENT '报告期标签 如 2026-W26',
  period_start   DATE COMMENT '报告期起（程序由标签推导）',
  period_end     DATE COMMENT '报告期止',
  compare_start  DATE COMMENT '对比期起',
  compare_end    DATE COMMENT '对比期止',
  status         VARCHAR(40) NOT NULL DEFAULT 'AWAITING_OUTLINE_APPROVAL'
                 COMMENT '状态 AWAITING_OUTLINE_APPROVAL/RUNNING/BLOCKED/AWAITING_PUBLISH_APPROVAL/PUBLISHED/REJECTED',
  phase          VARCHAR(16) COMMENT '当前/最后步骤 OUTLINE/SPEC/FETCH/FACT/WRITE/AUDIT',
  outline_json   MEDIUMTEXT COMMENT '大纲（章节树+每章指标+未解析项）JSON',
  report_md      MEDIUMTEXT COMMENT '审计通过的报告终稿 markdown（数值后带[fact_xxx]引用）',
  audit_json     MEDIUMTEXT COMMENT '审计结果 JSON（一致率、逐数字核对明细、重写轮次）',
  blocked_reason TEXT COMMENT 'BLOCKED 时的失败关闭原因（前缀 [POLICY]/[EXCEPTION]）',
  outline_approved_by VARCHAR(128) COMMENT '大纲确认人（HITL卡点1）',
  outline_approved_at DATETIME    COMMENT '大纲确认时间',
  publish_approved_by VARCHAR(128) COMMENT '发布审批人（HITL卡点2）',
  publish_approved_at DATETIME    COMMENT '发布审批时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT='报告运行主表（最小运行状态）';

CREATE TABLE report_step (
  step_id     BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '步骤记录ID',
  run_id      BIGINT NOT NULL COMMENT '所属运行ID',
  phase       VARCHAR(16) NOT NULL COMMENT '步骤 OUTLINE/SPEC/FETCH/FACT/WRITE/AUDIT',
  attempt     INT NOT NULL DEFAULT 1 COMMENT '第几次尝试（审计打回重写/断点重跑时递增）',
  status      VARCHAR(16) NOT NULL COMMENT '结果 RUNNING/OK/BLOCKED',
  input_json  MEDIUMTEXT COMMENT '本步输入快照（断点恢复与审计追溯）',
  output_json MEDIUMTEXT COMMENT '本步输出快照',
  error_text  TEXT COMMENT 'BLOCKED 时的错误明细',
  started_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  KEY idx_step_run (run_id, phase)
) COMMENT='步骤输入输出留痕表';

CREATE TABLE report_fact (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增ID',
  run_id          BIGINT NOT NULL COMMENT '所属运行ID',
  fact_key        VARCHAR(64) NOT NULL COMMENT '运行内唯一事实编号 如 fact_001 / fact_001_wow',
  metric_id       VARCHAR(64) NOT NULL COMMENT '指标ID',
  metric_version  INT COMMENT '取数时使用的指标定义版本（快照固化；DERIVED 记其结果指标的版本；NULL=未固化存量）',
  metric_name     VARCHAR(128) COMMENT '指标名（冗余，便于演示页直读）',
  chapter_id      VARCHAR(32) COMMENT '所属章节',
  fact_type       VARCHAR(16) NOT NULL DEFAULT 'BASE' COMMENT 'BASE=取数事实/DERIVED=程序派生（环比/净流入等）',
  value_num       DECIMAL(20,4) COMMENT '数值',
  unit            VARCHAR(16) COMMENT '单位 CNY/笔/个/户/percent',
  display_value   VARCHAR(64) COMMENT '程序渲染的展示串 如 280.00 万元 / +12.5%',
  period_label    VARCHAR(16) COMMENT '所属周期标签',
  dimensions_json TEXT COMMENT '维度取值 JSON（MVP 恒为 {}）',
  spec_json       TEXT COMMENT '来源 MetricQuerySpec 快照',
  sql_text        TEXT COMMENT '实际执行的 SQL（DERIVED 为空）',
  sql_hash        CHAR(64) COMMENT 'SQL 的 sha256',
  result_hash     CHAR(64) COMMENT '结果行集 canonical JSON 的 sha256',
  derived_from    VARCHAR(256) COMMENT 'DERIVED 时的来源 fact_key 逗号分隔',
  quality_status  VARCHAR(16) NOT NULL DEFAULT 'PASSED' COMMENT '质量断言 PASSED/FAILED',
  quality_note    VARCHAR(256) COMMENT '质量断言说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_run_fact (run_id, fact_key)
) COMMENT='事实记录表（FactRecord 落库形态）';
