-- =============================================================
-- 报告资产表（Phase01 P1：模板与指标资产入库管理）
--   mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/02-asset-tables.sql
--
-- 与 01-report-tables.sql 的「DROP 重建=清零」语义相反：资产表长期存续，
-- 禁止 DROP 重建——版本行一经写入不可变，被 run 引用过的版本永不物理删除。
-- 本脚本可重复执行（CREATE IF NOT EXISTS + 守卫式 ALTER）。
--
-- 两张表均为系统元数据表，必须列入 application.yml 的 schema.exclude-tables，
-- 不得进入 NL2SQL 的表白名单。
--
-- 状态流转规则（服务端唯一执行点，前端仅展示）：
--   DRAFT ─publish→ PUBLISHED ─deprecate→ DEPRECATED（终态）
--   DRAFT ─deprecate→ DEPRECATED（废弃草稿，不物理删除）
-- 不变量：
--   1) 同一资产业务 id 任意时刻至多 1 个 PUBLISHED 版本
--      （publish 时旧 PUBLISHED 自动置 DEPRECATED；加载缓存时检测到双 PUBLISHED 即启动失败）；
--   2) 行不可变：唯一允许的 UPDATE 是 status 列，body_json 永不 UPDATE；
--   3) classpath 种子（source=SEED）仅在库中不存在该业务 id 时种入
--      （DEPRECATED 也算存在——人为下架的资产不会被种子复活）。
-- =============================================================

CREATE TABLE IF NOT EXISTS report_template (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '行ID',
  template_id VARCHAR(64)  NOT NULL COMMENT '模板业务ID 如 treasury-weekly',
  version     INT          NOT NULL COMMENT '版本号 从1递增；行一经写入不可变',
  name        VARCHAR(128) NOT NULL COMMENT '模板名（冗余自 body_json，便于列表）',
  body_json   MEDIUMTEXT   NOT NULL COMMENT '模板完整JSON（ReportTemplateDef：keywords/chapters/stylePrompt）',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态 DRAFT/PUBLISHED/DEPRECATED',
  source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源 SEED=classpath种入/MANUAL=页面或API',
  created_by  VARCHAR(128) COMMENT '创建人（种子为 seed）',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  remark      VARCHAR(512) COMMENT '版本备注',
  UNIQUE KEY uk_tpl_ver (template_id, version),
  KEY idx_tpl_status (template_id, status)
) COMMENT='报告模板资产表（版本化，行不可变）';

CREATE TABLE IF NOT EXISTS report_metric (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '行ID',
  metric_id   VARCHAR(64)  NOT NULL COMMENT '指标业务ID 如 cny_total_balance',
  version     INT          NOT NULL COMMENT '版本号 从1递增；行一经写入不可变',
  name        VARCHAR(128) NOT NULL COMMENT '指标名（冗余自 body_json）',
  body_json   MEDIUMTEXT   NOT NULL COMMENT '指标完整JSON（MetricDefinition：mqlTemplate/derived/nullPolicy等）',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态 DRAFT/PUBLISHED/DEPRECATED',
  source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源 SEED/MANUAL',
  created_by  VARCHAR(128) COMMENT '创建人',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  remark      VARCHAR(512) COMMENT '版本备注',
  UNIQUE KEY uk_metric_ver (metric_id, version),
  KEY idx_metric_status (metric_id, status)
) COMMENT='指标语义定义资产表（版本化，行不可变）';

-- ---------- ALTER 段：既有库升级 ----------
-- report_run 加 template_version（新装库由 01-report-tables.sql 的 CREATE 直接带上此列）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫保证可重复执行。
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_run ADD COLUMN template_version INT NULL COMMENT ''命中模板的固化版本号（创建 run 时锁定，resume 不追新版）'' AFTER template_id',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_run' AND column_name = 'template_version');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
