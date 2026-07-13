# gk_ai_dev 四表接入 AI 问数/报告 —— DDL 信息待补充清单

> 背景：我方 AI 问数与报告生成引擎在启动时会自动反射数据库 `information_schema` 中的**表注释、字段注释、主键/唯一键、外键关系**，并将其作为大模型理解表结构的唯一依据（模型被禁止臆造表/列/连接条件）。因此以下信息不是「锦上添花」，而是接入的**前置条件**。请贵方按清单补充，可直接回填到 DDL 注释中，或以书面形式逐条回复。
>
> 涉及表（依据 2026-07-13 提供的 gk.sql，源库 gk_ai_dev）：
>
> 1. `treasury_balance_journal`（国库库存日记账）
> 2. `treasury_balance_monthly`
> 3. `treasury_basic_info`
> 4. `treasury_income_detail`
>
> 另：我方已研读贵方《段落接口说明 V3.0》，其业务逻辑说明已回答了我们的部分疑问——此类条目下文标注【请确认理解】，只需确认或指正即可；接口文档自身的疑点集中列在第四节。

## 一、通用要求（四张表均适用）

| # | 项目 | 要求 |
|---|------|------|
| 1 | 表注释 | 每张表加 `COMMENT`：一句话业务定位 + 数据粒度（一行代表什么）+ 数据来源/加工方式 |
| 2 | 字段注释 | 每个字段加 `COMMENT`；目前仅 `treasury_balance_journal` 有注释，**其余三张表全部空白** |
| 3 | 代码值枚举 | 所有代码型字段（如 `treasury_level`、`budget_level`）必须在注释中**枚举全部合法取值及含义**，例：`COMMENT '预算级次：1=中央级 2=省级 3=市级 4=县级'` |
| 4 | 金额口径 | 所有金额字段说明：单位（元/万元/亿元）、币种、正负号约定（冲正/退库是否记负数）；另请解释 `decimal(18,8)` 保留 8 位小数的原因（金额通常 2 位） |
| 5 | 唯一业务键 | 声明每张表的业务唯一键（数据粒度），最好落成 `UNIQUE KEY`；目前仅日记账有 `uk_date_treasury` |
| 6 | 外键关系 | 表间关联请以 `FOREIGN KEY` 写进 DDL；若生产规范不允许建外键，请**书面确认逻辑关联关系**（见第三节），我方在本地副本库补建 |
| 7 | 数据机制 | 说明：更新频率与可用时间（T+1 几点？月表几号出数？）、历史数据起点（做同比至少需 13 个月）、修数方式（原地 UPDATE 还是冲正新增） |

## 二、逐表待补充问题

### 1. treasury_balance_journal（国库库存日记账 —— 注释最全，仍有口径疑问）

- `balance` 口径【请确认理解】：据《段落接口说明》§7.3，月末库存取当月最后一天的 `balance` 并按「本库 + 直接子库」求和——我们据此理解 `balance` 为**日终时点余额**，且每行为单库自身数据（不含下级汇总）。请确认；并请补充与借贷方的勾稽关系（当日余额 = 上日余额 + 贷 − 借？）及借/贷方向对库存增减的含义。
- 数据覆盖：表中包含哪些层级的国库？各级库是否都有自己的数据行、互不包含（接口按 `admin_treasury_code` 下钻一层汇总，隐含此假设）——请确认。
- 无发生额的日期（节假日等）是否也有余额行？余额是否逐日连续（影响任意日期取数与环比）。
- `county_treasury_code`/`county_treasury_name` 与基础信息表的 `admin_treasury_code` 是什么关系？
- 冗余名称字段 `treasury_short_name`：是入库时快照还是与基础信息表保持同步？名称以哪张表为准？
- 缺外键：`treasury_code` → `treasury_basic_info.treasury_code`。

### 2. treasury_balance_monthly（无任何注释）

- 表的业务定位：与日记账是什么关系？是否由日记账按月加工汇总？两者数据不一致时以谁为准？
- `stat_date varchar(6)`：确认格式为 `YYYYMM`？该字段与日记账的 `stat_date`（date 类型、按日）**同名不同义**，建议改名 `stat_month` 或至少注释写明，避免误用。
- `city_level_balance` / `town_level_balance`：分别指什么（市本级库存/区县级库存合计）？单位是什么——精度 `decimal(12,2)` 与日记账 `decimal(18,8)` 不一致，是否一个存万元一个存元？
- `city_level_growth_rate` / `town_level_growth_rate`：**环比还是同比**？存百分数（12.34 表示 12.34%）还是小数（0.1234）？
- 既然已按市/县分列，`treasury_code`/`treasury_name` 在本表指哪一级机构？
- 粒度唯一键 `(stat_date, treasury_code)` 是否唯一？目前只有普通索引，建议升级 `UNIQUE`。
- 现库中仅约 15 行数据：是人工维护还是程序生成？后续如何保障与日记账口径一致？

### 3. treasury_basic_info（无任何注释）

- 表注释 + 全部字段注释（这是维度主表，注释质量直接决定 AI 对「国库」概念的理解）。
- `treasury_level int`：请给**全部**取值枚举及含义——《段落接口说明》中出现 3/4/5 三档（并以其动态推导 budget_level 条件），是否还有 1/2 档？各档分别对应什么层级机构？
- `admin_treasury_code`【请确认理解】：接口逻辑将其用作「上级国库代码」（同级国库 = 同上级、子库 = `admin_treasury_code` 等于本库），即自引用本表 `treasury_code` 的单亲树。请确认，并说明顶层国库该字段取什么值（NULL？自身？）。
- 只有 `treasury_short_name`（简称），是否需要补全称字段？简称是否全库唯一？
- 是否存在已撤并/停用的国库？是否需要状态字段（生效/失效日期），否则历史报表口径无法追溯。
- 小问题（非阻塞）：`idx_admin_treasury_code` 与 `idx_tbi_admin_treasury` 完全重复，可删其一；唯一键名 `UKso3ks9b4klanligcjn69sa0qk` 为 ORM 自动生成，建议改为可读名（如 `uk_treasury_code`）。

### 4. treasury_income_detail（无任何注释）

- `stat_date varchar(6)`：确认为 `YYYYMM` 月度？同样建议改名/注释明确（同上）。
- `budget_level int`【请确认理解】：据接口文档，1=中央级、2=省级、3/4/5=地方级。请确认，并给出 3/4/5 各自的确切含义（市级/县区级/乡镇级？）。
- `subject_code` / `subject_name`：接口文档表明**表内混存多层级科目行**（类 101 / 款 10101 / 项 1010101 并存，不带科目条件直接 SUM 会重复计数），且另有一套 `T` 前缀汇总码（T010101/T010201/T010401/T010601）。请提供：**完整科目字典**（或编码层级规则）、`T` 码体系的定义、以及 `T` 码与数字码之间的包含关系（两套并存如何防重复计数）。
- `current_period_amount`：「当期」= 当月发生额？退库/冲正是否记负数（接口示例中出口退增值税为负，请确认符号约定全表一致）？单位与币种。
- `year_to_date_amount`【请确认理解】：接口取「上月年累计」时直接读上月行的该字段，即它是**权威累计值**。请确认，并说明与逐月 `current_period_amount` 累加是否恒等（何种场景会有差异）。
- 粒度唯一键：`(stat_date, treasury_code, subject_code, budget_level)` 是否唯一？建议落 `UNIQUE`。
- `treasury_short_name varchar(50)` 与其他表的 `varchar(100)` 长度不一致，建议统一。
- 缺外键：`treasury_code` → `treasury_basic_info.treasury_code`。

## 三、表间关联关系（请确认，最好落成 FOREIGN KEY）

```
treasury_balance_journal.treasury_code        → treasury_basic_info.treasury_code
treasury_balance_journal.county_treasury_code → treasury_basic_info.treasury_code   ？（请确认）
treasury_balance_monthly.treasury_code        → treasury_basic_info.treasury_code
treasury_income_detail.treasury_code          → treasury_basic_info.treasury_code
treasury_basic_info.admin_treasury_code       → treasury_basic_info.treasury_code   （自引用，请确认）
```

> 说明：我方引擎从 `information_schema.key_column_usage` 读取外键，自动生成「可连接关系」供大模型使用，并据此做多表 JOIN 路径补全——**没有外键声明，AI 就没有任何合法的连表依据**。

## 四、《段落接口说明 V3.0》待澄清问题

1. **「地方级」口径在文档内部不一致**：接口一为固定 `budget_level IN (3,4,5)`；接口二/四/六/七为按国库 `treasury_level` 动态确定（3→IN(3,4,5)、4→IN(4,5)、5→IN(5)）；接口三又是固定 `IN (4,5)`。请说明以哪个为准，或各段落业务上本就口径不同。
2. **非税收入的加减科目集合疑似有误**：`103060299` 同时出现在加法科目与减法科目中，请核实。
3. **接口二「同级国库排名」的排序依据**写的是「按库存余额降序」——一般公共预算收入查询接口按库存排名，是笔误（应按收入？）还是业务如此？
4. 同比增减幅度的返回格式不统一（接口一为 `"2.35"` 无百分号，其余接口带 `%`），请给统一口径。
5. `budget_level` 在 DDL 中为 int，接口文档 SQL 均按字符串（`IN ('4','5')`）比较，请确认实际存储与比较类型。
6. **请提供该数据服务的源代码（只读即可）及接口文档所引用的需求文档**——用于核对科目常量与口径细节（上述矛盾正是文档与实现可能漂移的例证），我方不复用代码。

## 五、附：期望的注释风格示例（以基础信息表为例）

```sql
CREATE TABLE `treasury_basic_info` (
  `id`                  bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `treasury_code`       varchar(50)  NOT NULL COMMENT '国库代码（全局唯一，业务主键）',
  `treasury_short_name` varchar(100) NOT NULL COMMENT '国库简称，如"XX市中心支库"',
  `treasury_level`      int          DEFAULT NULL COMMENT '国库层级：1=省级分库 2=市级中心支库 3=县级支库（示例，以贵方权威定义为准）',
  `admin_treasury_code` varchar(50)  DEFAULT NULL COMMENT '上级主管国库代码，自引用本表 treasury_code，省级库为空',
  `create_time`         datetime(6)  DEFAULT NULL COMMENT '记录创建时间',
  `update_time`         datetime(6)  DEFAULT NULL COMMENT '记录更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_treasury_code` (`treasury_code`),
  KEY `idx_admin_treasury_code` (`admin_treasury_code`),
  CONSTRAINT `fk_tbi_admin` FOREIGN KEY (`admin_treasury_code`) REFERENCES `treasury_basic_info` (`treasury_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='国库机构基本信息表（维度主表）：一行一个国库机构，含层级与上下级隶属关系';
```
