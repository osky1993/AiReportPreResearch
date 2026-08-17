#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
业务数据扩容 v2 生成器（2026-08-17）。

产物 = `db/04-business-data-v2.sql`，**产物才是权威**：库以 SQL 文件为准，本脚本只是
「当初怎么造出来的」的可复现记录。改数据请改本脚本后重新生成整份 SQL，不要手改 SQL 局部
（否则两边漂移）。

设计要点：
  · 全部随机性走固定种子（SEED），同一份脚本必产出逐字节相同的 SQL；
  · 时间轴 2024-01-01 ~ 2026-08-16（32.5 个月），月均约 47 笔资金流水；
  · 币种扩到 5 个（CNY/USD/EUR/HKD/JPY），账户扩到 26 个（含 DEPOSIT/LOAN 类与二次冻结户）；
  · 汇率补成「5 币种 × 每月末 + 2026-08-14」的完整矩阵——注意 CNY 原先只有一行，
    折人民币口径是 INNER JOIN 到 max(rate_date)，缺 CNY 行会让人民币流水整体掉出统计；
  · 组织/银行/对手方/单据/预算/外汇/贷款各链路同步加厚，多表 JOIN 与 Schema Linking
    演示不再「一表三行」。

⚠️ 本批次已解除历史「只增不改、避开锁死窗口」红线：数据铺满全时间轴（含 2026-Q2 等
   原黄金评测窗口），因此 `report/eval/golden-set.json` 的 105 个期望值必须用手写
   referenceSql 直查重算（纪律 7：期望值不得取自被评测系统自身）。
"""

import random
from datetime import date, timedelta

SEED = 20260817
OUT = "db/04-business-data-v2.sql"

START = date(2024, 1, 1)
END = date(2026, 8, 16)          # 数据截止日（含）：使 2026-W33 = 08-10~08-16 为完整当期周
RATE_LATEST = date(2026, 8, 14)  # 最新汇率日（周五）：折人民币口径锚点

rnd = random.Random(SEED)

# ---------------------------------------------------------------- 工具

def money(lo, hi, step=100):
    """在 [lo,hi] 取一个「像人写的」金额：按 step 取整，保留两位小数。"""
    v = rnd.randint(int(lo // step), int(hi // step)) * step
    return f"{v:.2f}"


def month_iter(a, b):
    y, m = a.year, a.month
    while (y, m) <= (b.year, b.month):
        yield y, m
        m += 1
        if m == 13:
            y, m = y + 1, 1


def month_end(y, m):
    return date(y + (m == 12), 1 if m == 12 else m + 1, 1) - timedelta(days=1)


def bizday(y, m, lo, hi):
    """月内 [lo,hi] 日之间取一个工作日；越界或落到周末则前后就近调整。"""
    last = month_end(y, m).day
    hi = min(hi, last)
    lo = min(lo, hi)
    d = date(y, m, rnd.randint(lo, hi))
    while d.weekday() >= 5:
        d += timedelta(days=1)
        if d.month != m:
            d = date(y, m, min(lo, last))
            while d.weekday() >= 5:
                d -= timedelta(days=1)
            break
    return d


def sql_str(v):
    return "NULL" if v is None else "'" + str(v).replace("'", "''") + "'"


BUF = []


def emit(line=""):
    BUF.append(line)


def insert_block(table, cols, rows, comment=None):
    """输出一条多行 VALUES 的 INSERT；rows 里每个元素是已格式化好的字段串列表。"""
    if not rows:
        return
    if comment:
        emit(f"-- {comment}")
    emit(f"INSERT INTO {table} ({','.join(cols)}) VALUES")
    for i, r in enumerate(rows):
        tail = ";" if i == len(rows) - 1 else ","
        emit(f" ({','.join(r)}){tail}")
    emit()


# ---------------------------------------------------------------- 账户

# (id, 名称, 账号, 开户行, 币种, 类型, 余额, 状态, 开户日)
# 说明：LOAN 类为「贷款专户」，放款后即划出，余额留 0；未偿本金以 loan 表为准，
#       避免把贷款额度算进「人民币活跃账户余额合计」。
ACCOUNTS = [
    (9,  '华东分公司结算户', '6225009', '工商银行', 'CNY', 'SETTLEMENT', 8600000.00,  'ACTIVE', '2023-04-06'),
    (10, '华南分公司结算户', '6225010', '招商银行', 'CNY', 'SETTLEMENT', 5400000.00,  'ACTIVE', '2023-06-19'),
    (11, '华北分公司结算户', '6225011', '建设银行', 'CNY', 'SETTLEMENT', 4150000.00,  'ACTIVE', '2023-08-21'),
    (12, '税费专户',        '6225012', '建设银行', 'CNY', 'SETTLEMENT', 1800000.00,  'ACTIVE', '2023-02-15'),
    (13, '备用金户',        '6225013', '农业银行', 'CNY', 'SETTLEMENT', 620000.00,   'ACTIVE', '2023-09-01'),
    (14, '票据保证金户',    '6225014', '中国银行', 'CNY', 'SETTLEMENT', 2000000.00,  'ACTIVE', '2024-01-08'),
    (15, '港币结算户',      '6225015', '中国银行', 'HKD', 'SETTLEMENT', 3600000.00,  'ACTIVE', '2023-11-13'),
    (16, '日元采购户',      '6225016', '三菱日联银行', 'JPY', 'SETTLEMENT', 48000000.00, 'ACTIVE', '2024-02-05'),
    (17, '美元定期存款户',  '6225017', '中国银行', 'USD', 'DEPOSIT',    1500000.00,  'ACTIVE', '2024-03-11'),
    (18, '通知存款户',      '6225018', '工商银行', 'CNY', 'DEPOSIT',    12000000.00, 'ACTIVE', '2023-07-03'),
    (19, '项目专项存款户',  '6225019', '建设银行', 'CNY', 'DEPOSIT',    8000000.00,  'ACTIVE', '2024-05-20'),
    (20, '流动资金贷款户',  '6225020', '工商银行', 'CNY', 'LOAN',       0.00,        'ACTIVE', '2024-03-01'),
    (21, '并购贷款户',      '6225021', '建设银行', 'CNY', 'LOAN',       0.00,        'ACTIVE', '2025-02-10'),
    (22, '美元贷款户',      '6225022', '中国银行', 'USD', 'LOAN',       0.00,        'ACTIVE', '2024-06-01'),
    (23, '欧元收汇户',      '6225023', '招商银行', 'EUR', 'SETTLEMENT', 145000.00,   'ACTIVE', '2024-04-17'),
    (24, '涉诉冻结户',      '6225024', '农业银行', 'CNY', 'SETTLEMENT', 380000.00,   'FROZEN', '2022-05-09'),
    (25, '旧欧元采购户',    '6225025', '招商银行', 'EUR', 'SETTLEMENT', 0.00,        'CLOSED', '2019-10-22'),
    (26, '撤并分公司结算户','6225026', '工商银行', 'CNY', 'SETTLEMENT', 0.00,        'CLOSED', '2020-03-16'),
]

# 账户 → 币种（含 00-init 既有 1~6 号户），生成流水时据此保证「账户币种 = 流水币种」
ACC_CCY = {1: 'CNY', 2: 'USD', 3: 'EUR', 4: 'CNY', 5: 'CNY', 6: 'CNY'}
for a in ACCOUNTS:
    ACC_CCY[a[0]] = a[4]

# ---------------------------------------------------------------- 汇率

RATE_DATES = [month_end(y, m) for y, m in month_iter(START, date(2026, 7, 1))] + [RATE_LATEST]

# 00-init.sql 已有的行（不能重复插入，PK=currency+rate_date）
EXISTING_RATES = {('USD', d) for d in RATE_DATES if d <= date(2026, 5, 31)}
EXISTING_RATES |= {('EUR', d) for d in RATE_DATES if d <= date(2026, 5, 31)}
EXISTING_RATES |= {('CNY', date(2026, 6, 30)), ('USD', date(2026, 6, 30)), ('EUR', date(2026, 6, 30))}

# 各币种基准与波动（HKD≈USD/7.8，JPY 走弱区间），按月末小幅游走
RATE_BASE = {'CNY': 1.0, 'USD': 7.13, 'EUR': 7.70, 'HKD': 0.912, 'JPY': 0.0489}
RATE_SWING = {'CNY': 0.0, 'USD': 0.06, 'EUR': 0.09, 'HKD': 0.011, 'JPY': 0.0021}
RATE_TAIL = {  # 2026-06-30 之后两期显式给定，保证「最新汇率日」口径稳定可讲
    date(2026, 7, 31): {'CNY': 1.0, 'USD': 7.210000, 'EUR': 7.790000, 'HKD': 0.921000, 'JPY': 0.048200},
    RATE_LATEST:       {'CNY': 1.0, 'USD': 7.200000, 'EUR': 7.810000, 'HKD': 0.919000, 'JPY': 0.048600},
}

# ---------------------------------------------------------------- 主数据

REGIONS = [(6, '西南', '中国'), (7, '华中', '中国'), (8, '亚太', '中国香港')]
ORGS = [(6, '西南事业部', 6), (7, '华中事业部', 7), (8, '亚太事业部', 8),
        (9, '集团财务共享中心', 1), (10, '数字科技事业部', 1),
        (11, '国际贸易事业部', 2), (12, '供应链事业部', 4)]
ENTITIES = [(6, '成都司库供应链有限公司', '9151TAX', 6), (7, '武汉司库物流有限公司', '9142TAX', 7),
            (8, 'HK Treasury Holdings Ltd', 'HK-TAX', 8), (9, '上海司库数字科技有限公司', '9131TAXD', 10),
            (10, '深圳司库国际贸易有限公司', '9144TAXI', 11)]
COST_CENTERS = [
    (8, '人力资源部', 1), (9, '信息技术部', 1), (10, '法务合规部', 1), (11, '风险管理部', 1),
    (12, '总经理办公室', 1), (13, '财务共享中心', 1), (14, '销售一部', 3), (15, '销售二部', 3),
    (16, '仓储物流部', 4), (17, '生产制造部', 4), (18, '质量管理部', 4), (19, '研发中心', 9),
    (20, '海外销售部', 2), (21, '国际结算部', 2), (22, '西南营销中心', 6), (23, '华中运营中心', 7),
    (24, '亚太资金中心', 8),
]
EMPLOYEES = [
    (10, '冯磊', '资金主管', 3), (11, '陈静', '出纳', 3), (12, '许伟', '资金分析师', 3),
    (13, '何敏', '采购主管', 4), (14, '吕强', '采购专员', 4), (15, '施雨', '投资经理', 5),
    (16, '沈涛', '投资分析师', 5), (17, '韩雪', '人力资源经理', 8), (18, '曹阳', '薪酬专员', 8),
    (19, '范斌', 'IT 经理', 9), (20, '章丽', '系统运维工程师', 9), (21, '苏航', '法务经理', 10),
    (22, '谢磊', '合规专员', 10), (23, '任洁', '风险经理', 11), (24, '邵峰', '风控专员', 11),
    (25, '崔娜', '总经理助理', 12), (26, '孟凡', '共享中心主管', 13), (27, '邱月', '应付会计', 13),
    (28, '姚辉', '应收会计', 13), (29, '钟琳', '销售经理', 14), (30, '汪洋', '销售代表', 14),
    (31, '田甜', '销售经理', 15), (32, '易强', '仓储主管', 16), (33, '万青', '生产经理', 17),
    (34, '尹涛', '质量经理', 18), (35, '路遥', '研发总监', 19), (36, 'Emily Zhang', '海外销售经理', 20),
    (37, 'David Lin', '国际结算主管', 21), (38, '简宁', '西南区经理', 22), (39, '贺敏', '内控专员', 11),
    (40, '穆凡', '华中区经理', 23), (41, 'Kelvin Ho', '亚太资金经理', 24),
]
BANKS = [(7, '交通银行', 'COMMCNSH'), (8, '浦发银行', 'SPDBCNSH'), (9, '三菱日联银行', 'BOTKJPJT'),
         (10, '汇丰银行', 'HSBCHKHH')]
BRANCHES = [
    (8, '工行浦东分行', 1), (9, '中行虹口支行', 2), (10, '建行徐汇支行', 3), (11, '招行南山支行', 4),
    (12, '农行成都高新支行', 5), (13, '德银慕尼黑分行', 6), (14, '交行静安支行', 7),
    (15, '浦发张江支行', 8), (16, '三菱日联上海分行', 9), (17, '汇丰香港中环分行', 10),
    (18, '工行广州分行', 1), (19, '中行深圳分行', 2), (20, '建行北京分行', 3), (21, '招行杭州分行', 4),
    (22, '农行武汉分行', 5), (23, '交行南京分行', 7), (24, '工行成都分行', 1),
]

# 对手方主数据（与流水 counterparty 文本一一对齐，便于主数据/流水两侧互指演示）
CUSTOMERS_CNY = ['华东经销商', '华南经销商', '华北经销商', '线上渠道', '西南经销商', '华中经销商',
                 '东北经销商', '连锁零售集团', '省级医药商业', '政府采购中心', '大型商超集团', '电商平台旗舰店']
CUSTOMERS_USD = ['US Buyer Inc', '海外客户B', 'Global Retail Corp', 'Korea Electronics Co.']
CUSTOMERS_EUR = ['EU Partner', 'Nordic Trading AB']
CUSTOMERS_HKD = ['HK Trading Ltd', 'Asia Pacific Sourcing Ltd']
SUPPLIERS_CNY = ['原料供应商A', '原料供应商B', '设备供应商', '包装材料商', '物流承运商', '能源供应商',
                 '五金配件厂', '检测服务商', '广告代理商', '软件服务商']
SUPPLIERS_USD = ['海外服务商', 'US Cloud Services Inc']
SUPPLIERS_EUR = ['德国设备商', '欧洲供应商D']
SUPPLIERS_JPY = ['Japan Precision KK', '日本精密部件商社']

NEW_COUNTERPARTIES = [
    (14, '西南经销商', 'CUSTOMER', '中国'), (15, '华中经销商', 'CUSTOMER', '中国'),
    (16, '东北经销商', 'CUSTOMER', '中国'), (17, '连锁零售集团', 'CUSTOMER', '中国'),
    (18, '省级医药商业', 'CUSTOMER', '中国'), (19, '政府采购中心', 'CUSTOMER', '中国'),
    (20, '大型商超集团', 'CUSTOMER', '中国'), (21, '电商平台旗舰店', 'CUSTOMER', '中国'),
    (22, 'Global Retail Corp', 'CUSTOMER', '美国'), (23, 'Korea Electronics Co.', 'CUSTOMER', '韩国'),
    (24, 'Nordic Trading AB', 'CUSTOMER', '瑞典'), (25, 'HK Trading Ltd', 'CUSTOMER', '中国香港'),
    (26, 'Asia Pacific Sourcing Ltd', 'CUSTOMER', '新加坡'),
    (27, '包装材料商', 'SUPPLIER', '中国'), (28, '物流承运商', 'SUPPLIER', '中国'),
    (29, '能源供应商', 'SUPPLIER', '中国'), (30, '五金配件厂', 'SUPPLIER', '中国'),
    (31, '检测服务商', 'SUPPLIER', '中国'), (32, '广告代理商', 'SUPPLIER', '中国'),
    (33, '软件服务商', 'SUPPLIER', '中国'), (34, 'US Cloud Services Inc', 'SUPPLIER', '美国'),
    (35, 'Japan Precision KK', 'SUPPLIER', '日本'), (36, '日本精密部件商社', 'SUPPLIER', '日本'),
    (37, '社保中心', 'SUPPLIER', '中国'), (38, '住房公积金中心', 'SUPPLIER', '中国'),
]
NEW_CONTACTS = [
    (8, '孙经理', '13910000008', 14), (9, '李主管', '13910000009', 15), (10, '张总', '13910000010', 17),
    (11, '周采购', '13910000011', 18), (12, '黄经理', '13910000012', 20), (13, '林运营', '13910000013', 21),
    (14, 'Sarah Miller', '+1-212-0014', 22), (15, 'Jun Park', '+82-2-0015', 23),
    (16, 'Lars Olsen', '+46-8-0016', 24), (17, 'Wong Ka Ming', '+852-0017', 25),
    (18, 'Rachel Tan', '+65-0018', 26), (19, '徐厂长', '13910000019', 27), (20, '马调度', '13910000020', 28),
    (21, '罗经理', '13910000021', 29), (22, '梁工', '13910000022', 30), (23, '毕主任', '13910000023', 31),
    (24, '尤总监', '13910000024', 32), (25, '汤经理', '13910000025', 33),
    (26, 'Peter Novak', '+1-408-0026', 34), (27, 'Kenji Sato', '+81-3-0027', 35),
    (28, 'Yuki Tanaka', '+81-6-0028', 36), (29, '钱专员', '13910000029', 37), (30, '孔专员', '13910000030', 38),
]
NEW_PRODUCTS = [
    (9, '标准货品B', 'GOODS'), (10, '标准货品C', 'GOODS'), (11, '大宗原料C', 'MATERIAL'),
    (12, '包装材料', 'MATERIAL'), (13, '精密设备D', 'EQUIPMENT'), (14, '自动化产线', 'EQUIPMENT'),
    (15, '仓储服务', 'SERVICE'), (16, '检测认证', 'SERVICE'), (17, '云资源', 'SERVICE'),
    (18, '广告投放', 'SERVICE'), (19, '培训服务', 'SERVICE'), (20, '结构件A', 'GOODS'),
]

# ---------------------------------------------------------------- 流水生成

YEAR_FACTOR = {2024: 1.00, 2025: 1.12, 2026: 1.26}
SEASON = {1: 0.95, 2: 0.72, 3: 1.05, 4: 1.00, 5: 1.02, 6: 1.10,
          7: 0.98, 8: 0.96, 9: 1.08, 10: 1.02, 11: 1.12, 12: 1.20}

TXNS = []  # (date, account_id, direction, amount_str, currency, counterparty, category, status)


def add_txn(d, acc, direction, amount, cp, category, status='SETTLED'):
    if d > END:
        return
    TXNS.append((d, acc, direction, amount, ACC_CCY[acc], cp, category, status))


def scale(y, m, lo, hi):
    f = YEAR_FACTOR[y] * SEASON[m]
    return lo * f, hi * f


def gen_month(y, m):
    # 1) 人民币销售回款：总部与三家分公司
    for _ in range(rnd.randint(12, 16)):
        acc = rnd.choices([1, 9, 10, 11], weights=[6, 3, 3, 2])[0]
        lo, hi = scale(y, m, 250000, 2600000)
        add_txn(bizday(y, m, 1, 28), acc, 'IN', money(lo, hi, 1000), rnd.choice(CUSTOMERS_CNY), 'SALES')
    # 2) 外币销售回款
    for _ in range(rnd.randint(2, 4)):
        lo, hi = scale(y, m, 40000, 180000)
        add_txn(bizday(y, m, 1, 28), 2, 'IN', money(lo, hi, 500), rnd.choice(CUSTOMERS_USD), 'SALES')
    for _ in range(rnd.randint(1, 3)):
        acc = rnd.choice([3, 23])
        lo, hi = scale(y, m, 25000, 110000)
        add_txn(bizday(y, m, 1, 28), acc, 'IN', money(lo, hi, 500), rnd.choice(CUSTOMERS_EUR), 'SALES')
    for _ in range(rnd.randint(1, 3)):
        lo, hi = scale(y, m, 180000, 900000)
        add_txn(bizday(y, m, 1, 28), 15, 'IN', money(lo, hi, 1000), rnd.choice(CUSTOMERS_HKD), 'SALES')
    # 3) 采购与投资性支出
    for _ in range(rnd.randint(9, 12)):
        acc = rnd.choices([1, 9, 10, 13], weights=[7, 2, 1, 1])[0]
        lo, hi = scale(y, m, 60000, 950000)
        add_txn(bizday(y, m, 2, 27), acc, 'OUT', money(lo, hi, 1000), rnd.choice(SUPPLIERS_CNY), 'INVEST')
    for _ in range(rnd.randint(1, 3)):
        lo, hi = scale(y, m, 8000, 60000)
        add_txn(bizday(y, m, 2, 27), 2, 'OUT', money(lo, hi, 500), rnd.choice(SUPPLIERS_USD), 'INVEST')
    if rnd.random() < 0.8:
        lo, hi = scale(y, m, 8000, 55000)
        add_txn(bizday(y, m, 2, 27), 3, 'OUT', money(lo, hi, 500), rnd.choice(SUPPLIERS_EUR), 'INVEST')
    for _ in range(rnd.randint(1, 2)):
        lo, hi = scale(y, m, 1500000, 14000000)
        add_txn(bizday(y, m, 2, 27), 16, 'OUT', money(lo, hi, 10000), rnd.choice(SUPPLIERS_JPY), 'INVEST')
    # 4) 理财赎回与存款利息（对手方留空，演示判空能力）
    if rnd.random() < 0.55:
        lo, hi = scale(y, m, 300000, 1200000)
        add_txn(bizday(y, m, 5, 25), 18, 'IN', money(lo, hi, 10000), '理财赎回', 'INVEST')
    add_txn(month_end(y, m), 5, 'IN', money(*scale(y, m, 3000, 42000), step=100), None, 'INVEST')
    # 5) 薪酬三件套：工资 / 社保 / 公积金
    add_txn(bizday(y, m, 24, 26), 4, 'OUT', money(*scale(y, m, 2400000, 3400000), step=1000), '全体员工', 'PAYROLL')
    add_txn(bizday(y, m, 9, 11), 4, 'OUT', money(*scale(y, m, 620000, 780000), step=1000), '社保中心', 'PAYROLL')
    add_txn(bizday(y, m, 11, 13), 4, 'OUT', money(*scale(y, m, 380000, 460000), step=1000), '住房公积金中心', 'PAYROLL')
    # 6) 税款：增值税 + 附加税（月）、企业所得税（季）
    add_txn(bizday(y, m, 14, 16), 12, 'OUT', money(*scale(y, m, 380000, 900000), step=1000), '税务局', 'TAX')
    add_txn(bizday(y, m, 14, 16), 12, 'OUT', money(*scale(y, m, 40000, 90000), step=100), '税务局', 'TAX')
    if m in (1, 4, 7, 10):
        add_txn(bizday(y, m, 17, 19), 12, 'OUT', money(*scale(y, m, 600000, 1500000), step=1000), '税务局', 'TAX')
    # 7) 贷款：按月付息，按季还本；偶发提款
    add_txn(bizday(y, m, 19, 21), 20, 'OUT', money(12000, 32000, 100), '工商银行', 'LOAN')
    if (y, m) >= (2025, 2):
        add_txn(bizday(y, m, 19, 21), 21, 'OUT', money(18000, 45000, 100), '建设银行', 'LOAN')
    if m in (3, 6, 9, 12):
        add_txn(bizday(y, m, 20, 22), 20, 'OUT', money(500000, 1500000, 10000), '工商银行', 'LOAN')
    if (y, m) in ((2024, 3), (2025, 2), (2025, 9), (2026, 5)):
        add_txn(bizday(y, m, 3, 8), 20 if m != 2 else 21, 'IN', money(3000000, 8000000, 100000),
                '工商银行' if m != 2 else '建设银行', 'LOAN')
    # 8) 失败流水（季度一笔左右）：演示 status<>'FAILED' 口径确实在剔数
    if rnd.random() < 0.34:
        acc = rnd.choice([1, 9, 10])
        add_txn(bizday(y, m, 3, 26), acc, rnd.choice(['IN', 'OUT']), money(*scale(y, m, 80000, 600000), step=1000),
                rnd.choice(CUSTOMERS_CNY + SUPPLIERS_CNY), rnd.choice(['SALES', 'INVEST']), 'FAILED')


for _y, _m in month_iter(START, END):
    gen_month(_y, _m)

# 9) 在途流水只落最近两周（IN_TRANSIT 是无周期过滤的当前快照口径，放在历史上不合业务常识）
for _d, _acc, _dir, _amt, _cp, _cat in [
    (date(2026, 8, 6),  1,  'IN',  '1360000.00', '连锁零售集团', 'SALES'),
    (date(2026, 8, 11), 2,  'IN',  '86000.00',   'US Buyer Inc', 'SALES'),
    (date(2026, 8, 12), 9,  'IN',  '780000.00',  '华东经销商',   'SALES'),
    (date(2026, 8, 13), 15, 'IN',  '420000.00',  'HK Trading Ltd', 'SALES'),
    (date(2026, 8, 14), 1,  'OUT', '640000.00',  '设备供应商',   'INVEST'),
    (date(2026, 8, 14), 3,  'OUT', '38000.00',   '德国设备商',   'INVEST'),
]:
    add_txn(_d, _acc, _dir, _amt, _cp, _cat, 'IN_TRANSIT')

TXNS.sort(key=lambda t: (t[0], t[1]))

# ---------------------------------------------------------------- 单据链

CONTRACTS, INVOICES, INV_LINES, PAYMENTS, APPROVALS = [], [], [], [], []
_cid, _iid, _pid = 10, 16, 12
_ENTITIES_ALL = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
_CP_ALL = list(range(1, 39))
_APPROVERS = [1, 3, 4, 6, 9, 10, 12, 13, 15, 21, 23, 26]
_BRANCH_ALL = list(range(1, 25))
_PROD_ALL = list(range(1, 21))
_CCY_W = [('CNY', 0.68), ('USD', 0.14), ('EUR', 0.08), ('HKD', 0.06), ('JPY', 0.04)]
_CCY_SCALE = {'CNY': 1.0, 'USD': 0.14, 'EUR': 0.13, 'HKD': 1.1, 'JPY': 21.0}

for _y, _m in month_iter(START, date(2026, 8, 1)):
    for _ in range(rnd.randint(2, 3)):                       # 合同：月均 2~3 份
        ccy = rnd.choices([c for c, _w in _CCY_W], weights=[w for _c, w in _CCY_W])[0]
        s = _CCY_SCALE[ccy]
        sign = bizday(_y, _m, 1, 27)
        amt = money(400000 * s, 4200000 * s, max(1, int(1000 * s)))
        CONTRACTS.append((_cid, f"HT{_y}{_m:02d}{_cid:03d}", rnd.choice(_ENTITIES_ALL), rnd.choice(_CP_ALL),
                          amt, ccy, sign.isoformat()))
        # 每份合同挂 1~3 张发票，发票再挂 1~3 条明细，多数发票有付款单与审批
        for _k in range(rnd.randint(1, 4)):
            idt = sign + timedelta(days=rnd.randint(20, 300))
            if idt > END:
                continue
            iamt = money(float(amt) * 0.18, float(amt) * 0.55, max(1, int(1000 * s)))
            r = rnd.random()
            istatus = 'PAID' if r < 0.62 else ('OPEN' if r < 0.92 else 'VOID')
            INVOICES.append((_iid, f"INV{idt.year}{idt.month:02d}{_iid:04d}", _cid, idt.isoformat(),
                             iamt, ccy, istatus))
            rest = float(iamt)
            for _j in range(rnd.randint(1, 3)):
                qty = rnd.randint(1, 30)
                share = rest / rnd.randint(1, 3)
                INV_LINES.append((_iid, rnd.choice(_PROD_ALL), qty, f"{max(share / qty, 1):.2f}"))
            if istatus == 'PAID':
                pdt = idt + timedelta(days=rnd.randint(5, 45))
                if pdt <= END:
                    PAYMENTS.append((_pid, f"PAY{pdt.year}{pdt.month:02d}{_pid:04d}", _iid,
                                     rnd.choice(_BRANCH_ALL), iamt, ccy, pdt.isoformat(), 'PAID'))
                    APPROVALS.append((_pid, rnd.choice(_APPROVERS), (pdt - timedelta(days=1)).isoformat(), 'APPROVED'))
                    _pid += 1
            # 未结发票：近三个月的挂「待审批」（PENDING 是当前快照口径，不该沉在历史里），
            # 更早的少量挂「已驳回」
            elif istatus == 'OPEN' and rnd.random() < (0.75 if idt >= date(2026, 5, 20) else 0.22):
                pdt = idt + timedelta(days=rnd.randint(3, 30))
                if pdt <= END:
                    st = 'PENDING' if idt >= date(2026, 5, 20) else 'REJECTED'
                    PAYMENTS.append((_pid, f"PAY{pdt.year}{pdt.month:02d}{_pid:04d}", _iid,
                                     rnd.choice(_BRANCH_ALL), iamt, ccy, pdt.isoformat(), st))
                    if st == 'REJECTED':
                        APPROVALS.append((_pid, rnd.choice(_APPROVERS), (pdt - timedelta(days=1)).isoformat(), 'REJECTED'))
                    _pid += 1
            _iid += 1
        _cid += 1

# 待审批付款单：pending_payment_count 是「当前快照」口径，必须有一批真实在途的待办。
# 从最近的未结发票里补齐到 9 张 PENDING（不足则向前放宽日期）。
_paid_inv = {p[2] for p in PAYMENTS}
_recent_open = sorted([i for i in INVOICES if i[6] == 'OPEN' and i[0] not in _paid_inv],
                      key=lambda i: i[3], reverse=True)
for _inv in _recent_open:
    if sum(1 for p in PAYMENTS if p[7] == 'PENDING') >= 9:
        break
    _pdt = min(date.fromisoformat(_inv[3]) + timedelta(days=rnd.randint(3, 25)), END)
    PAYMENTS.append((_pid, f"PAY{_pdt.year}{_pdt.month:02d}{_pid:04d}", _inv[0],
                     rnd.choice(_BRANCH_ALL), _inv[4], _inv[5], _pdt.isoformat(), 'PENDING'))
    _pid += 1
PAYMENTS.sort(key=lambda p: p[0])

# ---------------------------------------------------------------- 预算 / 外汇 / 贷款

BUDGETS, BUDGET_LINES = [], []
_bid = 6
_BUDGET_ITEMS = ['差旅费', '办公费', '系统运维', '培训费', '市场推广费', '咨询费', '设备折旧', '业务招待费']
for _y in (2024, 2025, 2026):
    for _cc in (1, 2, 3, 4, 5, 8, 9, 13, 19):
        total = money(600000, 3600000, 10000)
        BUDGETS.append((_bid, _y, _cc, total))
        rest = float(total)
        for _it in rnd.sample(_BUDGET_ITEMS, rnd.randint(2, 4)):
            part = round(rest / rnd.randint(2, 5), 2)
            BUDGET_LINES.append((_bid, _it, f"{part:.2f}"))
            rest -= part
        _bid += 1

FX_DEALS = []
for _y, _m in month_iter(START, date(2026, 8, 1)):
    for _ in range(rnd.randint(1, 2)):
        buy, sell = rnd.choice([('USD', 'CNY'), ('CNY', 'USD'), ('EUR', 'CNY'), ('CNY', 'EUR'),
                                ('HKD', 'CNY'), ('CNY', 'JPY'), ('USD', 'HKD')])
        base = {'USD': 7.15, 'EUR': 7.72, 'HKD': 0.915, 'JPY': 0.0489, 'CNY': 1.0}[buy if buy != 'CNY' else sell]
        rate = round(base * rnd.uniform(0.985, 1.015), 6)
        amt_scale = {'CNY': 1.0, 'USD': 0.14, 'EUR': 0.13, 'HKD': 1.1, 'JPY': 21.0}[buy]
        FX_DEALS.append((rnd.choice(_ENTITIES_ALL), bizday(_y, _m, 2, 26).isoformat(), buy, sell,
                         money(200000 * amt_scale, 2000000 * amt_scale, max(1, int(1000 * amt_scale))),
                         f"{rate:.6f}"))

LOANS = [
    (4,  'LN2024008', 1, 1, 12000000.00, 'CNY', 3.6500, '2024-03-05'),
    (5,  'LN2024012', 1, 3,  8000000.00, 'CNY', 3.9000, '2024-06-18'),
    (6,  'LN2024019', 3, 4,  6000000.00, 'CNY', 4.1500, '2024-09-02'),
    (7,  'LN2025004', 1, 1, 15000000.00, 'CNY', 3.5000, '2025-02-10'),
    (8,  'LN2025011', 4, 3,  4500000.00, 'CNY', 4.0500, '2025-05-21'),
    (9,  'LN2025017', 6, 7,  7200000.00, 'CNY', 3.8000, '2025-09-08'),
    (10, 'LN2026002', 1, 8, 10000000.00, 'CNY', 3.4500, '2026-01-19'),
    (11, 'LN2026006', 9, 1,  5500000.00, 'CNY', 3.7500, '2026-04-07'),
    (12, 'LN2025008', 2, 2,  1200000.00, 'USD', 5.4000, '2025-04-14'),
    (13, 'LN2026009', 8, 10, 2400000.00, 'HKD', 4.6000, '2026-06-03'),
    (14, 'LN2024021', 5, 6,   900000.00, 'EUR', 3.9500, '2024-10-15'),
]
REPAYMENTS = []
for _lid, _no, _e, _b, _principal, _ccy, _rate, _start in LOANS:
    _sd = date.fromisoformat(_start)
    _n = rnd.randint(6, 10)
    _each = round(_principal / _n, 2)
    for _k in range(1, _n + 1):
        _due = _sd + timedelta(days=91 * _k)
        REPAYMENTS.append((_lid, _due.isoformat(), f"{_each:.2f}", 1 if _due <= END else 0))

# ---------------------------------------------------------------- 输出

emit("-- =====================================================================")
emit("-- 业务数据扩容 v2（2026-08-17）：把演示库从「刚好够验收」扩到「像一家公司的真账」")
emit("--   用法：mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/04-business-data-v2.sql")
emit("--   前置：db/00-init.sql 已执行（本脚本在其种子之上做纯增量，显式主键，重复执行会报主键冲突）")
emit("--")
emit("-- 覆盖：2024-01-01 ~ 2026-08-16 全时间轴逐月铺数；币种扩到 5 个（新增 HKD/JPY）；")
emit("--   账户扩到 26 个（新增 DEPOSIT/LOAN 类、分公司户、第二个冻结户与销户）；")
emit("--   汇率补成「5 币种 × 每月末 + 2026-08-14」完整矩阵（最新汇率日 = 2026-08-14，折算锚点）；")
emit("--   组织/银行/对手方/单据/预算/外汇/贷款各链路同步加厚。")
emit("--")
emit("-- ⚠️ 本批次解除了 2026-07-13 扩容时的「避开黄金评测窗口」红线：数据铺满全时间轴，")
emit("--   report/eval/golden-set.json 的 105 个期望值已按手写 referenceSql 直查同批重算。")
emit("--   本文件由 db/tools/gen-expansion-v2.py 确定性生成（固定随机种子），改数据请改脚本重生成。")
emit("-- =====================================================================")
emit()
emit("SET NAMES utf8mb4;")
emit()

insert_block("account", ["account_id", "account_name", "account_no", "bank_name", "currency",
                         "account_type", "balance", "status", "open_date"],
             [[str(a[0]), sql_str(a[1]), sql_str(a[2]), sql_str(a[3]), sql_str(a[4]), sql_str(a[5]),
               f"{a[6]:.2f}", sql_str(a[7]), sql_str(a[8])] for a in ACCOUNTS],
             "A. 账户扩充：新增 15 个活跃户（含 3 个 DEPOSIT、3 个 LOAN 专户、HKD/JPY 户）、1 个冻结户、2 个销户")

rate_rows = []
for i, d in enumerate(RATE_DATES):
    for ccy in ('CNY', 'USD', 'EUR', 'HKD', 'JPY'):
        if (ccy, d) in EXISTING_RATES:
            continue
        if d in RATE_TAIL:
            r = RATE_TAIL[d][ccy]
        elif ccy == 'CNY':
            r = 1.0
        else:
            base, swing = RATE_BASE[ccy], RATE_SWING[ccy]
            r = base + swing * ((i % 7) - 3) / 3.0 + swing * 0.3 * ((i % 3) - 1)
        rate_rows.append([sql_str(ccy), sql_str(d.isoformat()), f"{r:.6f}"])
insert_block("currency_rate", ["currency", "rate_date", "rate_to_cny"], rate_rows,
             "B. 汇率矩阵补全：CNY 原先只有 2026-06-30 一行——折人民币是 INNER JOIN，缺 CNY 行会让人民币流水整体掉出统计")

insert_block("cash_transaction",
             ["account_id", "txn_date", "direction", "amount", "currency", "counterparty", "category", "status"],
             [[str(t[1]), sql_str(t[0].isoformat()), sql_str(t[2]), t[3], sql_str(t[4]),
               sql_str(t[5]), sql_str(t[6]), sql_str(t[7])] for t in TXNS],
             f"C. 资金流水 {len(TXNS)} 笔（2024-01 ~ 2026-08-16 逐月）：销售回款 / 采购付汇 / 薪酬三件套 / 税款 / 贷款还本付息 / 理财与利息；FAILED 零星、IN_TRANSIT 只落最近两周")

insert_block("region", ["region_id", "region_name", "country"],
             [[str(r[0]), sql_str(r[1]), sql_str(r[2])] for r in REGIONS], "D. 组织维度链扩充")
insert_block("organization", ["org_id", "org_name", "region_id"],
             [[str(o[0]), sql_str(o[1]), str(o[2])] for o in ORGS])
insert_block("legal_entity", ["entity_id", "entity_name", "tax_no", "org_id"],
             [[str(e[0]), sql_str(e[1]), sql_str(e[2]), str(e[3])] for e in ENTITIES])
insert_block("cost_center", ["cost_center_id", "cost_center_name", "entity_id"],
             [[str(c[0]), sql_str(c[1]), str(c[2])] for c in COST_CENTERS])
insert_block("employee", ["employee_id", "employee_name", "title", "cost_center_id"],
             [[str(e[0]), sql_str(e[1]), sql_str(e[2]), str(e[3])] for e in EMPLOYEES])

insert_block("bank", ["bank_id", "bank_name", "swift"],
             [[str(b[0]), sql_str(b[1]), sql_str(b[2])] for b in BANKS], "E. 银行与网点扩充")
insert_block("bank_branch", ["branch_id", "branch_name", "bank_id"],
             [[str(b[0]), sql_str(b[1]), str(b[2])] for b in BRANCHES])

insert_block("counterparty", ["counterparty_id", "counterparty_name", "cp_type", "country"],
             [[str(c[0]), sql_str(c[1]), sql_str(c[2]), sql_str(c[3])] for c in NEW_COUNTERPARTIES],
             "F. 对手方主数据扩充（名称与流水 counterparty 文本一一对齐）")
insert_block("counterparty_contact", ["contact_id", "contact_name", "phone", "counterparty_id"],
             [[str(c[0]), sql_str(c[1]), sql_str(c[2]), str(c[3])] for c in NEW_CONTACTS])
insert_block("product", ["product_id", "product_name", "category"],
             [[str(p[0]), sql_str(p[1]), sql_str(p[2])] for p in NEW_PRODUCTS], "G. 产品扩充")

insert_block("contract", ["contract_id", "contract_no", "entity_id", "counterparty_id", "amount", "currency", "sign_date"],
             [[str(c[0]), sql_str(c[1]), str(c[2]), str(c[3]), c[4], sql_str(c[5]), sql_str(c[6])] for c in CONTRACTS],
             f"H. 单据链扩充：合同 {len(CONTRACTS)} / 发票 {len(INVOICES)} / 明细 {len(INV_LINES)} / 付款单 {len(PAYMENTS)} / 审批 {len(APPROVALS)}")
insert_block("invoice", ["invoice_id", "invoice_no", "contract_id", "invoice_date", "amount", "currency", "status"],
             [[str(i[0]), sql_str(i[1]), str(i[2]), sql_str(i[3]), i[4], sql_str(i[5]), sql_str(i[6])] for i in INVOICES])
insert_block("invoice_line", ["invoice_id", "product_id", "quantity", "unit_price"],
             [[str(l[0]), str(l[1]), str(l[2]), l[3]] for l in INV_LINES])
insert_block("payment_order", ["payment_id", "payment_no", "invoice_id", "branch_id", "amount", "currency", "pay_date", "status"],
             [[str(p[0]), sql_str(p[1]), str(p[2]), str(p[3]), p[4], sql_str(p[5]), sql_str(p[6]), sql_str(p[7])] for p in PAYMENTS])
insert_block("payment_approval", ["payment_id", "approver_id", "approved_at", "result"],
             [[str(a[0]), str(a[1]), sql_str(a[2]), sql_str(a[3])] for a in APPROVALS])

insert_block("budget", ["budget_id", "budget_year", "cost_center_id", "total_amount"],
             [[str(b[0]), str(b[1]), str(b[2]), b[3]] for b in BUDGETS], "I. 预算扩充（三年 × 九个成本中心）")
insert_block("budget_line", ["budget_id", "item_name", "amount"],
             [[str(b[0]), sql_str(b[1]), b[2]] for b in BUDGET_LINES])

insert_block("fx_deal", ["entity_id", "deal_date", "buy_currency", "sell_currency", "buy_amount", "rate"],
             [[str(f[0]), sql_str(f[1]), sql_str(f[2]), sql_str(f[3]), f[4], f[5]] for f in FX_DEALS],
             f"J. 外汇交易扩充（{len(FX_DEALS)} 笔）")
insert_block("loan", ["loan_id", "loan_no", "entity_id", "bank_id", "principal", "currency", "rate", "start_date"],
             [[str(l[0]), sql_str(l[1]), str(l[2]), str(l[3]), f"{l[4]:.2f}", sql_str(l[5]), f"{l[6]:.4f}", sql_str(l[7])] for l in LOANS],
             f"K. 贷款与还款计划扩充（贷款 {len(LOANS)} 笔 / 还款计划 {len(REPAYMENTS)} 期，到期未还的即为「未偿还款计划」）")
insert_block("loan_repayment", ["loan_id", "due_date", "amount", "paid"],
             [[str(r[0]), sql_str(r[1]), r[2], str(r[3])] for r in REPAYMENTS])

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write("\n".join(BUF) + "\n")

print(f"written {OUT}")
print(f"  cash_transaction  +{len(TXNS)}")
print(f"  currency_rate     +{len(rate_rows)}")
print(f"  account           +{len(ACCOUNTS)}")
print(f"  contract/invoice/line/payment/approval  +{len(CONTRACTS)}/{len(INVOICES)}/{len(INV_LINES)}/{len(PAYMENTS)}/{len(APPROVALS)}")
print(f"  loan/repayment    +{len(LOANS)}/{len(REPAYMENTS)}   fx_deal +{len(FX_DEALS)}   budget/line +{len(BUDGETS)}/{len(BUDGET_LINES)}")
