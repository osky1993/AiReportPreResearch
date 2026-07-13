/*
 Navicat Premium Dump SQL

 Source Server         : 172.1.3.69
 Source Server Type    : MySQL
 Source Server Version : 80024 (8.0.24)
 Source Host           : 172.1.3.69:3306
 Source Schema         : gk_ai_dev

 Target Server Type    : MySQL
 Target Server Version : 80024 (8.0.24)
 File Encoding         : 65001

 Date: 13/07/2026 09:33:00
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for treasury_balance_journal
-- ----------------------------
DROP TABLE IF EXISTS `treasury_balance_journal`;
CREATE TABLE `treasury_balance_journal` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日期（YYYY-MM-DD）',
  `stat_month` char(6) GENERATED ALWAYS AS (date_format(`stat_date`,_utf8mb4'%Y%m')) STORED COMMENT '统计年月（YYYYMM，冗余字段，方便按月汇总）',
  `treasury_code` varchar(50) NOT NULL COMMENT '国库代码',
  `treasury_short_name` varchar(100) DEFAULT NULL COMMENT '国库简称',
  `county_treasury_code` varchar(50) DEFAULT NULL COMMENT '所属县国库代码',
  `county_treasury_name` varchar(100) DEFAULT NULL COMMENT '所属县国库名称',
  `debit_amount` decimal(18,8) DEFAULT NULL COMMENT '借方发生额',
  `credit_amount` decimal(18,8) DEFAULT NULL COMMENT '贷方发生额',
  `balance` decimal(18,8) DEFAULT NULL COMMENT '余额',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_treasury` (`stat_date`,`treasury_code`),
  KEY `idx_stat_month` (`stat_month`),
  KEY `idx_treasury_code` (`treasury_code`)
) ENGINE=InnoDB AUTO_INCREMENT=95829 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='国库库存日记账';

-- ----------------------------
-- Table structure for treasury_balance_monthly
-- ----------------------------
DROP TABLE IF EXISTS `treasury_balance_monthly`;
CREATE TABLE `treasury_balance_monthly` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `city_level_balance` decimal(12,2) DEFAULT NULL,
  `city_level_growth_rate` decimal(10,2) DEFAULT NULL,
  `create_time` datetime(6) DEFAULT NULL,
  `stat_date` varchar(6) NOT NULL,
  `town_level_balance` decimal(12,2) DEFAULT NULL,
  `town_level_growth_rate` decimal(10,2) DEFAULT NULL,
  `treasury_code` varchar(50) NOT NULL,
  `treasury_name` varchar(100) NOT NULL,
  `update_time` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tbm_statdate_treasury` (`stat_date`,`treasury_code`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for treasury_basic_info
-- ----------------------------
DROP TABLE IF EXISTS `treasury_basic_info`;
CREATE TABLE `treasury_basic_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_treasury_code` varchar(50) DEFAULT NULL,
  `admin_treasury_name` varchar(100) DEFAULT NULL,
  `create_time` datetime(6) DEFAULT NULL,
  `treasury_code` varchar(50) NOT NULL,
  `treasury_level` int DEFAULT NULL,
  `treasury_short_name` varchar(100) NOT NULL,
  `update_time` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKso3ks9b4klanligcjn69sa0qk` (`treasury_code`),
  KEY `idx_admin_treasury_code` (`admin_treasury_code`),
  KEY `idx_tbi_admin_treasury` (`admin_treasury_code`)
) ENGINE=InnoDB AUTO_INCREMENT=247 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for treasury_income_detail
-- ----------------------------
DROP TABLE IF EXISTS `treasury_income_detail`;
CREATE TABLE `treasury_income_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `budget_level` int DEFAULT NULL,
  `create_time` datetime(6) DEFAULT NULL,
  `current_period_amount` decimal(18,8) DEFAULT NULL,
  `stat_date` varchar(6) NOT NULL,
  `subject_code` varchar(50) DEFAULT NULL,
  `subject_name` varchar(100) DEFAULT NULL,
  `treasury_code` varchar(50) NOT NULL,
  `treasury_short_name` varchar(50) DEFAULT NULL,
  `update_time` datetime(6) DEFAULT NULL,
  `year_to_date_amount` decimal(18,8) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tid_statdate_treasury` (`stat_date`,`treasury_code`),
  KEY `idx_tid_statdate_treasury_subject` (`stat_date`,`treasury_code`,`subject_code`),
  KEY `idx_tid_statdate_treasury_budget` (`stat_date`,`treasury_code`,`budget_level`),
  KEY `idx_treasury_income_covering` (`subject_code`,`treasury_code`,`budget_level`,`stat_date`,`current_period_amount`)
) ENGINE=InnoDB AUTO_INCREMENT=1532838 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
