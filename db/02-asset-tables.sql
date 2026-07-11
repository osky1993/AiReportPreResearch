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

-- 事件知识库（Phase05 契约2，T0 拍板：业务记录不是口径资产——单行可编辑 + 留痕列，不上多版本；
-- DEPRECATED 即下架不物理删除。description 视为不可信输入：录入白名单 + 进 prompt 前转义双闸）。
CREATE TABLE IF NOT EXISTS report_event (
  event_id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '事件ID',
  title           VARCHAR(64)  NOT NULL COMMENT '事件标题（≤64，字符白名单）',
  event_date      DATE         NOT NULL COMMENT '事件日期（EventMatcher 时间窗匹配键）',
  dimensions_json TEXT         COMMENT '关联维度取值 JSON 如 {"currency":"USD"}（可空）',
  related_metrics VARCHAR(512) COMMENT '关联指标 id 逗号分隔（可空，匹配加权）',
  description     VARCHAR(500) COMMENT '事件描述（≤500，字符白名单，禁花括号/围栏字符）',
  source          VARCHAR(128) COMMENT '信息来源（如 资金部周会纪要）',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DEPRECATED',
  created_by      VARCHAR(128) COMMENT '录入人',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
  updated_by      VARCHAR(128) COMMENT '最后修改人（留痕）',
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  KEY idx_event_date (event_date, status)
) COMMENT='业务事件知识库（归因候选来源；Phase05）';

-- 种子事件（幂等：同标题存在即跳过；与增量种子的异动数据呼应，撑归因演示与评测）
INSERT INTO report_event (title, event_date, dimensions_json, related_metrics, description, source, created_by)
SELECT * FROM (SELECT '华东大客户季度回款集中到账' t, DATE '2026-06-23' d,
  '{"currency":"CNY"}' dj, 'week_txn_amount_cny,week_inflow_amount_cny' rm,
  '华东区两家经销商季度货款于本周集中结算，单周流入显著高于常态水平。' de, '资金部周会纪要' s, 'seed' c) x
WHERE NOT EXISTS (SELECT 1 FROM report_event WHERE title = x.t);
INSERT INTO report_event (title, event_date, dimensions_json, related_metrics, description, source, created_by)
SELECT * FROM (SELECT '六月工资集中发放' t, DATE '2026-06-25' d,
  NULL dj, 'payroll_out_amount,week_outflow_amount_cny' rm,
  '当月工资批次于二十五日统一发放，含季度绩效部分，支出高于上月批次。' de, '人力资源部通知' s, 'seed' c) x
WHERE NOT EXISTS (SELECT 1 FROM report_event WHERE title = x.t);
INSERT INTO report_event (title, event_date, dimensions_json, related_metrics, description, source, created_by)
SELECT * FROM (SELECT '核心收付系统迁移（历史）' t, DATE '2025-06-12' d,
  NULL dj, 'week_txn_amount_cny,week_txn_count' rm,
  '上年六月核心收付系统切换停机三个交易日，当期交易量为历史低基数。' de, '科技部变更记录' s, 'seed' c) x
WHERE NOT EXISTS (SELECT 1 FROM report_event WHERE title = x.t);
INSERT INTO report_event (title, event_date, dimensions_json, related_metrics, description, source, created_by)
SELECT * FROM (SELECT '美元供应商付款周期调整' t, DATE '2026-05-20' d,
  '{"currency":"USD"}' dj, 'week_outflow_amount_cny' rm,
  '海外供应商账期由月结改为季结，美元流出节奏后移。' de, '采购部备忘' s, 'seed' c) x
WHERE NOT EXISTS (SELECT 1 FROM report_event WHERE title = x.t);

-- ---------- ALTER 段：既有库升级 ----------
-- report_run 加 template_version（新装库由 01-report-tables.sql 的 CREATE 直接带上此列）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫保证可重复执行。
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_run ADD COLUMN template_version INT NULL COMMENT ''命中模板的固化版本号（创建 run 时锁定，resume 不追新版）'' AFTER template_id',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_run' AND column_name = 'template_version');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- report_run 加 metric_versions_json（Phase02 P2-T1：指标版本快照，卡点1 确认时固化）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_run ADD COLUMN metric_versions_json TEXT NULL COMMENT ''指标版本快照 JSON {metricId: version}（卡点1 确认时固化，含派生操作数；resume 不追新版；NULL=Phase02 前存量 run 未固化）'' AFTER template_version',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_run' AND column_name = 'metric_versions_json');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- report_fact 加 metric_version（Phase02 P2-T1：事实记录取数所用指标定义版本）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_fact ADD COLUMN metric_version INT NULL COMMENT ''取数时使用的指标定义版本（快照固化；DERIVED 记其结果指标的版本；NULL=未固化存量）'' AFTER metric_id',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_fact' AND column_name = 'metric_version');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- report_run 加 yoy_start/yoy_end（Phase03 P3-T3：同比基期窗口留痕；resume 不读窗口列，一律现场重推）。
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_run ADD COLUMN yoy_start DATE NULL COMMENT ''同比基期起（去年同期；模板未声明同比则 NULL）'' AFTER compare_end',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_run' AND column_name = 'yoy_start');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE report_run ADD COLUMN yoy_end DATE NULL COMMENT ''同比基期止'' AFTER yoy_start',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'report_run' AND column_name = 'yoy_end');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
