-- =====================================================================
-- 演示业务库初始化脚本（treasury 资金示例数据，本项目库名 reportbi）
-- 用法：
--   mysql -h127.0.0.1 -P23306 -uroot -p < db/00-init.sql
-- 说明：可重复执行（先 DROP 再建）。⚠️ 重复执行会清空并重灌全部业务表，
--   且 caliber_asset（口径资产沉淀）也会被清空——已有演示环境慎重。
--   · 源自 nl2mql2sqlDemo 的 init.sql（彼处库名 chatbi），本文件已改为本项目的 reportbi。
--   · 核心三表（account / cash_transaction / currency_rate）：金标准评估依赖，勿改。
--   · 末尾「司库域扩展表」：20 张表 + 多跳外键链，用于验证 Schema Linking 的
--     Top-K 裁剪、多跳 FK 闭包（fk-hops≥2）与同义召回（≥20 表规模）。
--   · 报告流水线的状态表与资产表不在本文件：另见 01-report-tables.sql / 02-asset-tables.sql。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS reportbi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE reportbi;
SET NAMES utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS cash_transaction;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS currency_rate;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------- 资金账户表 ----------
CREATE TABLE account (
  account_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '账户ID',
  account_name VARCHAR(64)  NOT NULL COMMENT '账户名称',
  account_no   VARCHAR(32)  NOT NULL COMMENT '银行账号',
  bank_name    VARCHAR(64)  NOT NULL COMMENT '开户行',
  currency     VARCHAR(8)   NOT NULL COMMENT '币种 CNY/USD/EUR',
  account_type VARCHAR(16)  NOT NULL COMMENT '账户类型 SETTLEMENT/DEPOSIT/LOAN',
  balance      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '当前余额',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/FROZEN/CLOSED',
  open_date    DATE         NOT NULL COMMENT '开户日期'
) COMMENT='资金账户表';

-- ---------- 资金收支流水表 ----------
CREATE TABLE cash_transaction (
  txn_id       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '流水ID',
  account_id   BIGINT NOT NULL COMMENT '账户ID',
  txn_date     DATE   NOT NULL COMMENT '交易日期',
  direction    VARCHAR(8)  NOT NULL COMMENT '方向 IN/OUT',
  amount       DECIMAL(18,2) NOT NULL COMMENT '金额(原币)',
  currency     VARCHAR(8)  NOT NULL COMMENT '币种',
  counterparty VARCHAR(64) COMMENT '对手方',
  category     VARCHAR(32) COMMENT '业务类别 SALES/PAYROLL/TAX/INVEST/LOAN',
  status       VARCHAR(16) NOT NULL DEFAULT 'SETTLED' COMMENT '状态 SETTLED/IN_TRANSIT/FAILED',
  CONSTRAINT fk_txn_account FOREIGN KEY (account_id) REFERENCES account(account_id)
) COMMENT='资金收支流水表';

-- ---------- 币种汇率表 ----------
CREATE TABLE currency_rate (
  currency    VARCHAR(8) NOT NULL COMMENT '币种',
  rate_date   DATE       NOT NULL COMMENT '汇率日期',
  rate_to_cny DECIMAL(12,6) NOT NULL COMMENT '对人民币汇率',
  PRIMARY KEY (currency, rate_date)
) COMMENT='币种汇率表';

-- ---------- 种子数据 ----------
INSERT INTO account (account_name,account_no,bank_name,currency,account_type,balance,status,open_date) VALUES
 ('总部结算户','6225001','工商银行','CNY','SETTLEMENT',12500000.00,'ACTIVE','2023-01-10'),
 ('美元收汇户','6225002','中国银行','USD','SETTLEMENT',830000.00,'ACTIVE','2023-03-15'),
 ('欧元采购户','6225003','招商银行','EUR','SETTLEMENT',210000.00,'ACTIVE','2023-05-20'),
 ('工资专户','6225004','建设银行','CNY','SETTLEMENT',3200000.00,'ACTIVE','2023-02-01'),
 ('定期存款户','6225005','工商银行','CNY','DEPOSIT',50000000.00,'ACTIVE','2022-11-11'),
 ('已冻结户','6225006','农业银行','CNY','SETTLEMENT',0.00,'FROZEN','2021-06-30');

INSERT INTO cash_transaction (account_id,txn_date,direction,amount,currency,counterparty,category,status) VALUES
 (1,'2026-06-01','IN',1500000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2026-06-03','OUT',600000.00,'CNY','原料供应商A','INVEST','SETTLED'),
 (1,'2026-06-10','IN',2200000.00,'CNY','华南经销商','SALES','SETTLED'),
 (1,'2026-06-28','IN',900000.00,'CNY','线上渠道','SALES','IN_TRANSIT'),
 (2,'2026-06-05','IN',120000.00,'USD','US Buyer Inc','SALES','SETTLED'),
 (2,'2026-06-15','IN',95000.00,'USD','US Buyer Inc','SALES','SETTLED'),
 (2,'2026-06-20','OUT',40000.00,'USD','海外服务商','INVEST','SETTLED'),
 (2,'2026-06-29','IN',60000.00,'USD','EU Partner','SALES','IN_TRANSIT'),
 (3,'2026-06-08','OUT',55000.00,'EUR','德国设备商','INVEST','SETTLED'),
 (3,'2026-06-18','IN',80000.00,'EUR','EU Partner','SALES','SETTLED'),
 (4,'2026-06-25','OUT',2800000.00,'CNY','全体员工','PAYROLL','SETTLED'),
 (4,'2026-06-26','OUT',150000.00,'CNY','税务局','TAX','SETTLED'),
 (1,'2026-05-12','IN',1800000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2026-05-22','OUT',500000.00,'CNY','原料供应商B','INVEST','SETTLED'),
 (2,'2026-05-09','IN',100000.00,'USD','US Buyer Inc','SALES','SETTLED'),
 (1,'2026-06-30','IN',3500.00,'CNY',NULL,'INVEST','SETTLED'),      -- 银行利息入账，无对手方（判空能力演示）
 (2,'2026-06-27','OUT',200.00,'USD',NULL,'INVEST','SETTLED');      -- 银行手续费扣款，无对手方

INSERT INTO currency_rate (currency,rate_date,rate_to_cny) VALUES
 ('CNY','2026-06-30',1.000000),
 ('USD','2026-06-30',7.180000),
 ('EUR','2026-06-30',7.760000);

-- =====================================================================
-- 司库域扩展表（20 张，含多跳外键链）—— 用于 Schema Linking 规模/多跳/同义召回验证
-- 主要多跳链（可验证 fk-hops≥2）：
--   region → organization → legal_entity → cost_center → employee
--   legal_entity → contract → invoice → invoice_line → product
--   contract → invoice → payment_order → payment_approval → employee
--   bank → bank_branch → payment_order ；legal_entity → loan → loan_repayment
-- =====================================================================
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS loan_repayment;
DROP TABLE IF EXISTS loan;
DROP TABLE IF EXISTS fx_deal;
DROP TABLE IF EXISTS budget_line;
DROP TABLE IF EXISTS budget;
DROP TABLE IF EXISTS payment_approval;
DROP TABLE IF EXISTS payment_order;
DROP TABLE IF EXISTS invoice_line;
DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS contract;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS counterparty_contact;
DROP TABLE IF EXISTS counterparty;
DROP TABLE IF EXISTS bank_branch;
DROP TABLE IF EXISTS bank;
DROP TABLE IF EXISTS employee;
DROP TABLE IF EXISTS cost_center;
DROP TABLE IF EXISTS legal_entity;
DROP TABLE IF EXISTS organization;
DROP TABLE IF EXISTS region;
SET FOREIGN_KEY_CHECKS = 1;

-- ---- 组织维度链：region → organization → legal_entity → cost_center → employee ----
CREATE TABLE region (
  region_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '地区ID',
  region_name VARCHAR(64) NOT NULL COMMENT '地区名称 华东/华南/海外',
  country     VARCHAR(32) NOT NULL COMMENT '国家'
) COMMENT='地区维度表';

CREATE TABLE organization (
  org_id     BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '组织ID',
  org_name   VARCHAR(64) NOT NULL COMMENT '组织/事业部名称',
  region_id  BIGINT NOT NULL COMMENT '所属地区ID',
  CONSTRAINT fk_org_region FOREIGN KEY (region_id) REFERENCES region(region_id)
) COMMENT='组织/事业部表';

CREATE TABLE legal_entity (
  entity_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '法人实体ID',
  entity_name VARCHAR(64) NOT NULL COMMENT '法人实体名称',
  tax_no      VARCHAR(32) COMMENT '税号',
  org_id      BIGINT NOT NULL COMMENT '所属组织ID',
  CONSTRAINT fk_entity_org FOREIGN KEY (org_id) REFERENCES organization(org_id)
) COMMENT='法人实体表';

CREATE TABLE cost_center (
  cost_center_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '成本中心ID',
  cost_center_name VARCHAR(64) NOT NULL COMMENT '成本中心名称',
  entity_id        BIGINT NOT NULL COMMENT '所属法人实体ID',
  CONSTRAINT fk_cc_entity FOREIGN KEY (entity_id) REFERENCES legal_entity(entity_id)
) COMMENT='成本中心表';

CREATE TABLE employee (
  employee_id    BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '员工ID',
  employee_name  VARCHAR(64) NOT NULL COMMENT '员工姓名',
  title          VARCHAR(32) COMMENT '职务',
  cost_center_id BIGINT NOT NULL COMMENT '所属成本中心ID',
  CONSTRAINT fk_emp_cc FOREIGN KEY (cost_center_id) REFERENCES cost_center(cost_center_id)
) COMMENT='员工表';

-- ---- 银行维度链：bank → bank_branch ----
CREATE TABLE bank (
  bank_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '银行ID',
  bank_name VARCHAR(64) NOT NULL COMMENT '银行名称',
  swift     VARCHAR(16) COMMENT 'SWIFT代码'
) COMMENT='银行表';

CREATE TABLE bank_branch (
  branch_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '网点ID',
  branch_name VARCHAR(64) NOT NULL COMMENT '银行网点名称',
  bank_id     BIGINT NOT NULL COMMENT '所属银行ID',
  CONSTRAINT fk_branch_bank FOREIGN KEY (bank_id) REFERENCES bank(bank_id)
) COMMENT='银行网点表';

-- ---- 对手方：counterparty → counterparty_contact ----
CREATE TABLE counterparty (
  counterparty_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '对手方ID',
  counterparty_name VARCHAR(64) NOT NULL COMMENT '对手方名称(客户/供应商)',
  cp_type           VARCHAR(16) NOT NULL COMMENT '类型 CUSTOMER/SUPPLIER',
  country           VARCHAR(32) COMMENT '国家'
) COMMENT='交易对手方表';

CREATE TABLE counterparty_contact (
  contact_id      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '联系人ID',
  contact_name    VARCHAR(64) NOT NULL COMMENT '联系人姓名',
  phone           VARCHAR(32) COMMENT '电话',
  counterparty_id BIGINT NOT NULL COMMENT '所属对手方ID',
  CONSTRAINT fk_contact_cp FOREIGN KEY (counterparty_id) REFERENCES counterparty(counterparty_id)
) COMMENT='对手方联系人表';

-- ---- 产品 ----
CREATE TABLE product (
  product_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '产品ID',
  product_name VARCHAR(64) NOT NULL COMMENT '金融产品/货品名称',
  category     VARCHAR(32) COMMENT '产品类别'
) COMMENT='产品表';

-- ---- 合同 → 发票 → 发票明细 / 付款单 → 付款审批 ----
CREATE TABLE contract (
  contract_id     BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '合同ID',
  contract_no     VARCHAR(32) NOT NULL COMMENT '合同编号',
  entity_id       BIGINT NOT NULL COMMENT '签约法人实体ID',
  counterparty_id BIGINT NOT NULL COMMENT '对手方ID',
  amount          DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '合同金额',
  currency        VARCHAR(8) NOT NULL COMMENT '币种',
  sign_date       DATE COMMENT '签约日期',
  CONSTRAINT fk_contract_entity FOREIGN KEY (entity_id) REFERENCES legal_entity(entity_id),
  CONSTRAINT fk_contract_cp FOREIGN KEY (counterparty_id) REFERENCES counterparty(counterparty_id)
) COMMENT='合同表';

CREATE TABLE invoice (
  invoice_id  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发票ID',
  invoice_no  VARCHAR(32) NOT NULL COMMENT '发票号',
  contract_id BIGINT NOT NULL COMMENT '所属合同ID',
  invoice_date DATE COMMENT '开票日期',
  amount      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '发票金额',
  currency    VARCHAR(8) NOT NULL COMMENT '币种',
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态 OPEN/PAID/VOID',
  CONSTRAINT fk_invoice_contract FOREIGN KEY (contract_id) REFERENCES contract(contract_id)
) COMMENT='发票表';

CREATE TABLE invoice_line (
  line_id    BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发票明细ID',
  invoice_id BIGINT NOT NULL COMMENT '所属发票ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  quantity   INT NOT NULL DEFAULT 1 COMMENT '数量',
  unit_price DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '单价',
  CONSTRAINT fk_line_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(invoice_id),
  CONSTRAINT fk_line_product FOREIGN KEY (product_id) REFERENCES product(product_id)
) COMMENT='发票明细表';

CREATE TABLE payment_order (
  payment_id  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '付款单ID',
  payment_no  VARCHAR(32) NOT NULL COMMENT '付款单号',
  invoice_id  BIGINT NOT NULL COMMENT '关联发票ID',
  branch_id   BIGINT NOT NULL COMMENT '付款银行网点ID',
  amount      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '付款金额',
  currency    VARCHAR(8) NOT NULL COMMENT '币种',
  pay_date    DATE COMMENT '付款日期',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/PAID/REJECTED',
  CONSTRAINT fk_pay_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(invoice_id),
  CONSTRAINT fk_pay_branch FOREIGN KEY (branch_id) REFERENCES bank_branch(branch_id)
) COMMENT='付款单表';

CREATE TABLE payment_approval (
  approval_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '审批ID',
  payment_id  BIGINT NOT NULL COMMENT '付款单ID',
  approver_id BIGINT NOT NULL COMMENT '审批人(员工)ID',
  approved_at DATE COMMENT '审批日期',
  result      VARCHAR(16) NOT NULL DEFAULT 'APPROVED' COMMENT '审批结果 APPROVED/REJECTED',
  CONSTRAINT fk_appr_payment FOREIGN KEY (payment_id) REFERENCES payment_order(payment_id),
  CONSTRAINT fk_appr_emp FOREIGN KEY (approver_id) REFERENCES employee(employee_id)
) COMMENT='付款审批表';

-- ---- 预算 → 预算明细 ----
CREATE TABLE budget (
  budget_id      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '预算ID',
  budget_year    INT NOT NULL COMMENT '预算年度',
  cost_center_id BIGINT NOT NULL COMMENT '成本中心ID',
  total_amount   DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '预算总额',
  CONSTRAINT fk_budget_cc FOREIGN KEY (cost_center_id) REFERENCES cost_center(cost_center_id)
) COMMENT='预算表';

CREATE TABLE budget_line (
  budget_line_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '预算明细ID',
  budget_id      BIGINT NOT NULL COMMENT '所属预算ID',
  item_name      VARCHAR(64) NOT NULL COMMENT '预算科目名称',
  amount         DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '科目金额',
  CONSTRAINT fk_bline_budget FOREIGN KEY (budget_id) REFERENCES budget(budget_id)
) COMMENT='预算明细表';

-- ---- 外汇交易 ----
CREATE TABLE fx_deal (
  deal_id       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '外汇交易ID',
  entity_id     BIGINT NOT NULL COMMENT '交易法人实体ID',
  deal_date     DATE COMMENT '成交日期',
  buy_currency  VARCHAR(8) NOT NULL COMMENT '买入币种',
  sell_currency VARCHAR(8) NOT NULL COMMENT '卖出币种',
  buy_amount    DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '买入金额',
  rate          DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '成交汇率',
  CONSTRAINT fk_fx_entity FOREIGN KEY (entity_id) REFERENCES legal_entity(entity_id)
) COMMENT='外汇交易表';

-- ---- 贷款 → 还款计划 ----
CREATE TABLE loan (
  loan_id    BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '贷款ID',
  loan_no    VARCHAR(32) NOT NULL COMMENT '贷款合同号',
  entity_id  BIGINT NOT NULL COMMENT '借款法人实体ID',
  bank_id    BIGINT NOT NULL COMMENT '放款银行ID',
  principal  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '贷款本金',
  currency   VARCHAR(8) NOT NULL COMMENT '币种',
  rate       DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '年利率(%)',
  start_date DATE COMMENT '起息日',
  CONSTRAINT fk_loan_entity FOREIGN KEY (entity_id) REFERENCES legal_entity(entity_id),
  CONSTRAINT fk_loan_bank FOREIGN KEY (bank_id) REFERENCES bank(bank_id)
) COMMENT='贷款表';

CREATE TABLE loan_repayment (
  repayment_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '还款计划ID',
  loan_id      BIGINT NOT NULL COMMENT '所属贷款ID',
  due_date     DATE COMMENT '应还日期',
  amount       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '应还金额',
  paid         TINYINT NOT NULL DEFAULT 0 COMMENT '是否已还 0/1',
  CONSTRAINT fk_repay_loan FOREIGN KEY (loan_id) REFERENCES loan(loan_id)
) COMMENT='还款计划表';

-- ---- 扩展表种子数据（少量，满足外键完整性，按依赖顺序插入）----
INSERT INTO region (region_name,country) VALUES ('华东','中国'),('海外','美国');
INSERT INTO organization (org_name,region_id) VALUES ('华东事业部',1),('海外事业部',2);
INSERT INTO legal_entity (entity_name,tax_no,org_id) VALUES ('上海司库有限公司','9131TAX',1),('US Treasury LLC','US-TAX',2);
INSERT INTO cost_center (cost_center_name,entity_id) VALUES ('财务部',1),('销售部',1);
INSERT INTO employee (employee_name,title,cost_center_id) VALUES ('张三','财务经理',1),('李四','销售总监',2);
INSERT INTO bank (bank_name,swift) VALUES ('工商银行','ICBKCNBJ'),('中国银行','BKCHCNBJ');
INSERT INTO bank_branch (branch_name,bank_id) VALUES ('工行陆家嘴支行',1),('中行外滩支行',2);
INSERT INTO counterparty (counterparty_name,cp_type,country) VALUES ('华东经销商','CUSTOMER','中国'),('原料供应商A','SUPPLIER','中国');
INSERT INTO counterparty_contact (contact_name,phone,counterparty_id) VALUES ('王经理','13800000000',1);
INSERT INTO product (product_name,category) VALUES ('标准货品A','GOODS'),('咨询服务','SERVICE');
INSERT INTO contract (contract_no,entity_id,counterparty_id,amount,currency,sign_date) VALUES ('HT2026001',1,1,1000000.00,'CNY','2026-01-15');
INSERT INTO invoice (invoice_no,contract_id,invoice_date,amount,currency,status) VALUES ('INV2026001',1,'2026-06-01',500000.00,'CNY','OPEN');
INSERT INTO invoice_line (invoice_id,product_id,quantity,unit_price) VALUES (1,1,10,50000.00);
INSERT INTO payment_order (payment_no,invoice_id,branch_id,amount,currency,pay_date,status) VALUES ('PAY2026001',1,1,500000.00,'CNY','2026-06-10','PAID');
INSERT INTO payment_approval (payment_id,approver_id,approved_at,result) VALUES (1,1,'2026-06-09','APPROVED');
INSERT INTO budget (budget_year,cost_center_id,total_amount) VALUES (2026,1,2000000.00);
INSERT INTO budget_line (budget_id,item_name,amount) VALUES (1,'差旅费',300000.00);
INSERT INTO fx_deal (entity_id,deal_date,buy_currency,sell_currency,buy_amount,rate) VALUES (1,'2026-06-20','USD','CNY',100000.00,7.180000);
INSERT INTO loan (loan_no,entity_id,bank_id,principal,currency,rate,start_date) VALUES ('LN2026001',1,1,5000000.00,'CNY',3.8500,'2026-03-01');
INSERT INTO loan_repayment (loan_id,due_date,amount,paid) VALUES (1,'2026-09-01',1250000.00,0);

-- =====================================================================
-- 口径资产表（人在回路 / 可演进沉淀）—— 元数据表，非业务表。
--   · 存被人工核验采纳的「问题 -> 已校验 MQL」口径资产，供再次提问时语义召回直取复用。
--   · 由 schema.exclude-tables 配置排除，不会进入 NL2SQL 的表白名单与 prompt schema。
--   · 随本脚本 DROP 重建 = 每次 reset 回到「零沉淀」初态，便于演示从零学习的闭环。
-- =====================================================================
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS caliber_asset;
SET FOREIGN_KEY_CHECKS = 1;
CREATE TABLE caliber_asset (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资产ID',
  question   VARCHAR(512) NOT NULL COMMENT '代表问法（沉淀时的自然语言问题）',
  mql_json   TEXT         NOT NULL COMMENT '已校验的 MQL（资产单元，JSON）',
  created_by VARCHAR(128)          COMMENT '采纳人（demo 未接鉴权，默认 demo）',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '沉淀时间',
  status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DEPRECATED'
) COMMENT='口径资产表（人在回路沉淀）';

-- =====================================================================
-- Phase03 增量种子（P3-T3，纪律 8「只增不改」：上方已有行是 W25/W26 基线
-- 与 Phase02 评测 47 条期望值的地基，绝不修改）。四段用途：
--   · 2025-Q2 稀疏流水 —— 月/季同比基期（2025-06 撑 M06 同比）
--   · 2026-Q1 稀疏流水 —— Q2 季环比基期
--   · 2026-04 整月流水 —— 补齐 Q2 首月
--   · 2026-05 上旬流水 —— 补齐 M05（原种子自 05-12 起）
-- 全部日期 < 2026-05-12 或属 2025/2026Q1，不触碰 W25/W26 任何查询窗口。
-- =====================================================================

INSERT INTO cash_transaction (account_id,txn_date,direction,amount,currency,counterparty,category,status) VALUES
 -- 2025-Q2（同比基期）
 (1,'2025-04-08','IN' , 900000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2025-04-18','OUT', 350000.00,'CNY','原料供应商A','INVEST','SETTLED'),
 (1,'2025-05-09','IN' ,1100000.00,'CNY','华南经销商','SALES','SETTLED'),
 (1,'2025-05-22','OUT', 400000.00,'CNY',NULL,'PAYROLL','SETTLED'),
 (1,'2025-06-05','IN' ,1200000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2025-06-12','OUT', 500000.00,'CNY','税务局','TAX','SETTLED'),
 (2,'2025-06-19','IN' ,  30000.00,'USD','海外客户B','SALES','SETTLED'),
 (1,'2025-06-26','OUT', 450000.00,'CNY','设备供应商','INVEST','SETTLED'),
 -- 2026-Q1（Q2 季环比基期）
 (1,'2026-01-15','IN' ,1300000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2026-01-28','OUT', 520000.00,'CNY',NULL,'PAYROLL','SETTLED'),
 (1,'2026-02-11','IN' , 950000.00,'CNY','华北经销商','SALES','SETTLED'),
 (1,'2026-02-25','OUT', 380000.00,'CNY','原料供应商A','INVEST','SETTLED'),
 (1,'2026-03-10','IN' ,1450000.00,'CNY','华东经销商','SALES','SETTLED'),
 (2,'2026-03-18','OUT',  40000.00,'USD','海外供应商C','INVEST','SETTLED'),
 (1,'2026-03-27','OUT', 600000.00,'CNY','税务局','TAX','SETTLED'),
 -- 2026-04（Q2 首月）
 (1,'2026-04-03','IN' ,1250000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2026-04-09','OUT', 480000.00,'CNY',NULL,'PAYROLL','SETTLED'),
 (2,'2026-04-14','IN' ,  60000.00,'USD','海外客户B','SALES','SETTLED'),
 (3,'2026-04-17','OUT',  20000.00,'EUR','欧洲供应商D','INVEST','SETTLED'),
 (1,'2026-04-21','IN' , 800000.00,'CNY','华南经销商','SALES','SETTLED'),
 (1,'2026-04-24','OUT', 350000.00,'CNY','税务局','TAX','SETTLED'),
 -- 注意：增量段全部 SETTLED——in_transit_txn_count 等快照口径不带周期过滤，
 -- 任何非 SETTLED 增量行都会击穿 Phase02 既有期望值（实测教训）
 (1,'2026-04-28','OUT',  90000.00,'CNY','设备供应商','INVEST','SETTLED'),
 -- 2026-05 上旬（补齐 M05）
 (1,'2026-05-04','IN' ,1000000.00,'CNY','华东经销商','SALES','SETTLED'),
 (1,'2026-05-06','OUT', 420000.00,'CNY',NULL,'PAYROLL','SETTLED'),
 (2,'2026-05-07','IN' ,  25000.00,'USD','海外客户B','SALES','SETTLED'),
 (3,'2026-05-08','OUT',  15000.00,'EUR','欧洲供应商D','INVEST','SETTLED'),
 (1,'2026-05-11','OUT', 260000.00,'CNY','税务局','TAX','SETTLED');
