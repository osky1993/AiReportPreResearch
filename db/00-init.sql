-- =====================================================================
-- 00-init.sql（gk 分支版）—— reportbigk 库：gk 三张国库业务表 + 底座口径资产表
-- 用法：
--   mysql -h127.0.0.1 -P23306 -uroot -p < db/00-init.sql
--
-- 与 master 分支的区别：
--   · master 的本脚本建 23 张司库 demo 业务表并灌种子；gk 分支抛弃 demo 业务表，
--     业务表换成对方 gk_ai_dev 库的国库表。
--   · 业务数据为对方提供的真实数据，**人工导入，不在本脚本内**——因此本脚本
--     全部 CREATE TABLE IF NOT EXISTS、绝无 DROP，对已导入数据安全幂等。
--   · 2026-07-15：原第四张表 treasury_balance_monthly 经确认无用已从库中删除
--     （取数逻辑材料零引用、仅 ~15 行），本脚本不再包含；三张在用表已补齐
--     表/字段注释（金额单位亿元等），DDL 以下方为准（SchemaService 启动反射
--     注释注入 prompt，注释即语义第 1 层）。
--   · 注释遗留疑点（待对方确认，见 docs/todo 仲裁台账）：treasury_level 注释
--     枚举「1总库/2分库/3市中心支库/4区县支库」未列 5，但数据中 71 行 level=5
--     （按乡镇支库理解）；treasury_short_name 注释写「国库完整名称」与字段名
--     语义相悖；budget_level 注释仍无枚举。
--
-- 执行顺序：本脚本 → 01-report-tables.sql → 02-asset-tables.sql → 人工导入业务数据
-- =====================================================================

CREATE DATABASE IF NOT EXISTS reportbigk DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE reportbigk;

SET NAMES utf8mb4;

-- ----------------------------
-- 国库库存日记账（粒度：日 × 库，唯一键 uk_date_treasury；balance 为日终时点数）
-- 注意：市级库本级行的 county_treasury_code/name 以 'NNNNNNNNNN' 占位表示「无所属县」（业务约定，非脏数据）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `treasury_balance_journal` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日期（YYYY-MM-DD）',
  `stat_month` char(6) GENERATED ALWAYS AS (date_format(`stat_date`,_utf8mb4'%Y%m')) STORED COMMENT '统计年月（YYYYMM，冗余字段，方便按月汇总）',
  `treasury_code` varchar(50) NOT NULL COMMENT '国库代码',
  `treasury_short_name` varchar(100) DEFAULT NULL COMMENT '国库简称',
  `county_treasury_code` varchar(50) DEFAULT NULL COMMENT '所属县国库代码',
  `county_treasury_name` varchar(100) DEFAULT NULL COMMENT '所属县国库名称',
  `debit_amount` decimal(18,8) DEFAULT NULL COMMENT '借方发生额，亿元',
  `credit_amount` decimal(18,8) DEFAULT NULL COMMENT '贷方发生额，亿元',
  `balance` decimal(18,8) DEFAULT NULL COMMENT '余额，亿元',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_treasury` (`stat_date`,`treasury_code`),
  KEY `idx_stat_month` (`stat_month`),
  KEY `idx_treasury_code` (`treasury_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='国库库存日记账';

-- ----------------------------
-- 国库基础信息（维表：admin_treasury_code 自引用单亲树）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `treasury_basic_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
  `admin_treasury_code` varchar(50) DEFAULT NULL COMMENT '上级管辖国库代码',
  `admin_treasury_name` varchar(100) DEFAULT NULL COMMENT '上级管辖国库名称',
  `create_time` datetime(6) DEFAULT NULL COMMENT '记录创建时间',
  `treasury_code` varchar(50) NOT NULL COMMENT '国库唯一编码',
  `treasury_level` int NOT NULL COMMENT '国库级次：1总库/2分库/3市中心支库/4区县支库',
  `treasury_short_name` varchar(100) NOT NULL COMMENT '国库完整名称',
  `update_time` datetime(6) DEFAULT NULL COMMENT '记录更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKso3ks9b4klanligcjn69sa0qk` (`treasury_code`),
  KEY `idx_admin_treasury_code` (`admin_treasury_code`),
  KEY `idx_tbi_admin_treasury` (`admin_treasury_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='国库基础信息表';

-- ----------------------------
-- 国库收入分科目明细（事实主表：月 × 库 × 科目 × 级次；subject_code 混存多层级码 + T 汇总码，
-- 严禁跨层级裸加——完整科目字典已向对方索取）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `treasury_income_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
  `budget_level` int DEFAULT NULL COMMENT '预算级次',
  `create_time` datetime(6) DEFAULT NULL COMMENT '记录创建时间',
  `current_period_amount` decimal(18,8) DEFAULT NULL COMMENT '本期执行金额，亿元',
  `stat_date` varchar(6) NOT NULL COMMENT '统计年月，格式YYYYMM',
  `subject_code` varchar(50) DEFAULT NULL COMMENT '预算科目编码',
  `subject_name` varchar(100) DEFAULT NULL COMMENT '预算科目名称',
  `treasury_code` varchar(50) NOT NULL COMMENT '国库代码',
  `treasury_short_name` varchar(50) DEFAULT NULL COMMENT '国库简称',
  `update_time` datetime(6) DEFAULT NULL COMMENT '记录更新时间',
  `year_to_date_amount` decimal(18,8) DEFAULT NULL COMMENT '本年累计执行金额，亿元',
  PRIMARY KEY (`id`),
  KEY `idx_tid_statdate_treasury` (`stat_date`,`treasury_code`),
  KEY `idx_tid_statdate_treasury_subject` (`stat_date`,`treasury_code`,`subject_code`),
  KEY `idx_tid_statdate_treasury_budget` (`stat_date`,`treasury_code`,`budget_level`),
  KEY `idx_treasury_income_covering` (`subject_code`,`treasury_code`,`budget_level`,`stat_date`,`current_period_amount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='国库收入分科目明细表';

-- =====================================================================
-- 底座口径资产表（人在回路沉淀，与业务域无关，随底座引擎必备）
--   · 存被人工核验采纳的「问题 -> 已校验 MQL」口径资产，供再次提问时语义召回直取复用。
--   · 由 schema.exclude-tables 配置排除，不进 NL2SQL 表白名单与 prompt schema。
-- =====================================================================
CREATE TABLE IF NOT EXISTS caliber_asset (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资产ID',
  question    VARCHAR(512) NOT NULL COMMENT '代表问法（沉淀时的自然语言问题）',
  mql_json    TEXT         NOT NULL COMMENT '已校验的 MQL（资产单元，JSON）',
  description VARCHAR(1000)         COMMENT '中文口径描述（核验采纳时 AI 反翻译 MQL 生成，失败为 NULL；存量资产见 db/03）',
  created_by  VARCHAR(128)          COMMENT '采纳人（demo 未接鉴权，默认 demo）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '沉淀时间',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DEPRECATED'
) COMMENT='口径资产表（人在回路沉淀）';
