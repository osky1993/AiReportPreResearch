# gk_ai_dev 四表接入 —— 语义描述四层方案（内部工作文档）

> 配套文档：`../对外转发/gk-DDL信息待补充清单.md`（对外转发，等对方回复）。
> 口径输入：对方《段落接口说明 V3.0》（`../原始材料/段落接口说明V3.0.md`，来自微信群6月26日版本）——七个「段落接口」即报告段落取数包，已从中提炼大量权威口径，下文标注〔接口已证实〕；接口文档内部的矛盾项见清单第四节，**仲裁前相关口径不得定稿入库**。
> 本文档是**内部**的语义资产编写计划：DDL 注释只是第一层，我们引擎有完整的四层语义链路，各层机制、要为这四张表沉淀的内容、示例草稿如下。所有带「待确认」标记的内容，须等对方回复清单后修订，**枚举值与勾稽关系未确认前不得入库**（失败关闭原则，不猜测补全）。

## 层次总览

| 层 | 载体 | 谁提供 | 消费机制 | 状态 |
|---|------|--------|----------|------|
| 1 | DDL 表/字段注释 + 外键 | 对方 | `SchemaService` 启动反射 `information_schema`，拼进 NL2MQL prompt 的 `{{SCHEMA}}`；外键生成「可连接关系」提示 + 向量召回 fk-hops 补全 | 待对方回复清单 |
| 2 | 业务术语口径 `glossary/terms.json` | 我方 | `GlossaryService` 按问题向量召回 Top-N（`glossary.top-n`，术语少时全量注入），渲染进 prompt「业务术语口径」段 | 草稿见 §2 |
| 3 | few-shot 示例 `fewshot/examples.json` | 我方 | `FewShotSelector` 向量召回 Top-N（`fewshot.top-n: 3`）注入 prompt；启动时逐条过 `MqlValidator`，非法示例剔除 | 草稿见 §3 |
| 4 | 报告指标模板 `report_metric`（库） | 我方 | 报告流水线③零-LLM 确定性取数：参数化 MQL 模板（占位符仅 `{{period_start}}`/`{{period_end}}`）→ 填充 → 白名单校验 → 编译执行 | 视报告需求排期，见 §4 |

另有口径资产库（`caliber_asset`，HIT≥0.92 直接复用不调 LLM）——运行期人在回路自然沉淀，无需预先编写，不单列一层。

## 第 1 层：DDL 注释与外键（对方提供，我方落库）

内容即转发清单，不重复。我方动作：

- 对方回复后，在 `reportbi` 库（或独立接入库，待定）建这四张表，注释/外键**在我方副本补全**（对方生产库不允许建外键也不影响我们）。
- 若四表与现有 23 张演示表同库，注意表名无冲突（`treasury_*` 前缀目前无占用）。
- 反直觉口径（时点数、防重复计数）**不要只塞注释**——注释进 prompt 是好事，但查询口径的强约束靠第 2 层术语条目 + 第 4 层模板固化。

## 第 2 层：业务术语口径（`glossary/terms.json` 新增条目草稿）

价值最大的是**反直觉口径**：时点数不能 SUM、层级/科目防重复计数、代码值映射——同现有「折人民币」条目防汇率行数翻倍的套路。

```json
{
  "term": "国库库存",
  "aliases": ["库存", "库款", "国库存款余额", "库存余额"],
  "definition": "treasury_balance_journal.balance，为日终时点余额〔接口已证实：月末库存=当月最后一天的 balance〕。问『某期库存』应取该期最后一个有数日期的 balance（用 {\"field\":\"stat_date\",\"op\":\"=\",\"subquery\":{\"table\":\"treasury_balance_journal\",\"metrics\":[{\"op\":\"max\",\"field\":\"stat_date\",\"alias\":\"latest\"}]}} 限定），严禁对 balance 跨日 SUM。每行为单库自身数据，问『某库（含辖内）库存』按本库+子库（admin_treasury_code=本库）范围求和〔接口已证实〕。",
  "tables": ["treasury_balance_journal", "treasury_basic_info"]
}
```

```json
{
  "term": "预算级次",
  "aliases": ["级次", "中央级", "省级", "市级", "县级", "收入级次"],
  "definition": "treasury_income_detail.budget_level 的代码口径：1=中央级、2=省级、3/4/5=地方级〔接口已证实〕，3/4/5 各自含义【待确认】。『地方级』的级次集合按国库自身 treasury_level 动态确定：level3→IN(3,4,5)、level4→IN(4,5)、level5→IN(5)（接口间存在矛盾版本，仲裁前不定稿）。按级次统计用该字段过滤/分组，不要从科目名称猜级次。",
  "tables": ["treasury_income_detail"]
}
```

```json
{
  "term": "入库收入",
  "aliases": ["收入", "当月收入", "入库额", "收入完成"],
  "definition": "treasury_income_detail.current_period_amount（当月发生额，可为负，如出口退税）；年累计用 year_to_date_amount 字段直取，不要逐月 SUM 再算〔接口已证实〕。表内混存多层级科目行（类101/款10101/项1010101）并另有 T 前缀汇总码〔接口已证实〕——严禁不带科目条件裸 SUM，必须按明确科目集合 IN 或前缀匹配（并排除父科目自身）取数。stat_date 为 YYYYMM 月字符串。",
  "tables": ["treasury_income_detail"]
}
```

```json
{
  "term": "国库隶属",
  "aliases": ["上级国库", "主管国库", "辖内", "所属县库"],
  "definition": "国库层级关系在 treasury_basic_info：admin_treasury_code 自引用本表 treasury_code 指上级库〔接口已证实〕。『某库辖内』= 本库 OR admin_treasury_code=本库（一层下钻）；『同级国库』= admin_treasury_code 相同的库。问『某市辖内各县库』：先按 admin_treasury_code = 该市库代码过滤 basic_info，再关联事实表。",
  "tables": ["treasury_basic_info", "treasury_balance_journal", "treasury_income_detail"]
}
```

**从《段落接口说明》提炼的追加条目**（定稿时展开为 JSON；科目常量以源代码核对为准，涉及矛盾项的先仲裁）：

| 术语（含别名方向） | 口径要点 |
|---|---|
| 四本预算 / 全口径国库收入 | T 前缀汇总码：T010101=一般公共预算收入、T010201=政府性基金预算收入、T010401=社会保险基金预算收入、T010601=国有资本经营预算收入；全口径 = 四码合计、不加 budget_level 条件 |
| 统计口径（全口径/地方级） | statType 语义：1=全口径（无级次条件）；2=地方级（级次集合按 treasury_level 动态，见「预算级次」条目；接口间矛盾待仲裁） |
| 税收收入 | subject_code 以 101 开头的数字码合计（前缀匹配；防与 T 码混算） |
| 非税收入 | 加减科目集合（加：103、103060101、103060102、103060199、103060201、103060299、103060399、103060499；减：10301、10310、1030601、1030602、1030603、103060299、1030604、1030698）——【疑似勘误】103060299 两侧并存，核实前不入库 |
| 其他各项税收 | 101 前缀总计 − 指定税种集合（10101/10104/10106/10109/10110/10111/10112/10113/10118/10119） |
| 增值税 vs 国内增值税 | 增值税=10101（款）、国内增值税=1010101（项），语义相近极易混淆，别名要分开挂 |
| 房产五税 | 固定五科目：10110 房产税、10112 城镇土地使用税、10113 土地增值税、10118 耕地占用税、10119 契税 |
| 进口环节税 | 固定三科目：1010102 进口货物增值税、1010202 进口货物消费税、101170101 关税 |
| 出口业务退增值税 | 1010103 及其子科目，金额为**负数**，同比表述注意符号 |
| 一般公共预算收入 | T010101；与税收/非税的数字码体系并存，注意防重复计数 |
| 土地出让收入 / 地方财力 | 国有土地使用权出让收入=1030148；地方财力=T010101+T010201+T010601（地方级级次）；占比 = 土地出让 ÷ 地方财力 |
| 同比增减 | (本期 − 上年同期) ÷ 上年同期 × 100%；上年基数为 0 标「无同比」——对应 ④FactBuildStep 的 DERIVED 除零规则 |
| 同级排名 | 同上级国库的库按指标降序排名（接口二按「库存余额」排名疑似笔误待澄清）——属程序计算 DERIVED，不进 MQL |

另待回复后可能追加：月表增长率口径（环比/同比、百分数形态）、金额单位换算（若月表存万元、日记账存元）。

## 第 3 层：few-shot 示例（`fewshot/examples.json` 新增条目草稿）

每张核心表 2~3 条典型问法。注意启动时逐条过 `MqlValidator`，表未建好前**不要提前合入**（会被 log.warn 剔除，白占篇幅）。

```json
{
  "question": "2026年6月各国库当月入库收入合计，从高到低取前5",
  "mql": {
    "table": "treasury_income_detail",
    "filter": [{"field": "stat_date", "op": "=", "value": "202606"}],
    "groupBy": ["treasury_code", "treasury_short_name"],
    "metrics": [{"op": "sum", "field": "current_period_amount", "alias": "total_income"}],
    "orderBy": [{"field": "total_income", "direction": "desc"}],
    "limit": 5
  }
}
```

（【待确认】科目层级混存问题决定此例是否需加层级过滤条件。）

```json
{
  "question": "2026年7月10日各县级国库的库存余额",
  "mql": {
    "table": "treasury_balance_journal",
    "joins": [
      { "table": "treasury_basic_info", "type": "inner",
        "on": [ { "left": "treasury_balance_journal.treasury_code", "right": "treasury_basic_info.treasury_code" } ] }
    ],
    "filter": [
      {"field": "treasury_balance_journal.stat_date", "op": "=", "value": "2026-07-10"},
      {"field": "treasury_basic_info.treasury_level", "op": "=", "value": 3}
    ],
    "groupBy": ["treasury_balance_journal.treasury_code", "treasury_balance_journal.treasury_short_name"],
    "metrics": [{"op": "sum", "field": "treasury_balance_journal.balance", "alias": "day_balance"}],
    "orderBy": [{"field": "day_balance", "direction": "desc"}]
  }
}
```

（treasury_level=3 的取值【待确认】；单日单库一行时 sum 即原值，示范「时点数按日锁定」的正确姿势。）

```json
{
  "question": "2026年上半年市级国库逐月的中央级收入",
  "mql": {
    "table": "treasury_income_detail",
    "filter": [
      {"field": "stat_date", "op": ">=", "value": "202601"},
      {"field": "stat_date", "op": "<=", "value": "202606"},
      {"field": "budget_level", "op": "=", "value": 1}
    ],
    "groupBy": ["stat_date"],
    "metrics": [{"op": "sum", "field": "current_period_amount", "alias": "central_income"}],
    "orderBy": [{"field": "stat_date", "direction": "asc"}]
  }
}
```

（budget_level=1=中央级〔接口已证实，仍待对方书面确认〕；示范月字符串 YYYYMM 的区间写法——字典序即时间序，可直接 >=/<=。）

定稿时再追加两类示例（需先验证 IR 对 IN 集合 / 子查询过滤的支持形态）：**科目集合过滤**（如房产五税五科目）、**辖内子库范围**（treasury_code 按 admin_treasury_code 下钻一层）——若 IR 表达不了，相应口径只能落第 4 层模板或库端视图，不硬塞 few-shot。

## 第 4 层：报告指标模板（`report_metric`，仅当场景要进报告流水线）

若要出「国库收支月报」类报告，按硬约束①每个指标沉淀参数化 MQL 模板，走 metric-wizard 五步向导（试查 → 人工核验 → `MqlParameterizer` 参数化 → 保存），不手写 JSON 直接入库。

**《段落接口说明》的七个接口就是现成的段落级指标清单**——每个接口 ≈ 一个报告段落的事实包：金额类字段对应 MQL 模板指标；同比/排名/占比类字段全部归入 ④FactBuildStep 程序计算的 DERIVED fact（正好套用除零→「无同比」规则），不进 LLM：

| 接口 | 对应段落/指标组 | MQL 模板要点 | DERIVED 部分（程序算） |
|---|---|---|---|
| 一 收入分级汇总及排名 | 全口径/中央/省/地方级收入年累计 + 月末库存 | T 码集合 × budget_level 集合；库存 = 月末日 + 辖内范围 | 各口径同比、全市排名 |
| 二 一般公共预算收入 | 逐月本期/年累计（T010101） | 按月取数（或一次取全年逐月分组） | 本期/累计同比、同级排名 |
| 三 税收收入明细 | 税收/非税 + 12 税种 + 4 非税项 | 每项一个科目集合；非税加减集合待勘误 | 各项同比、占一般公共预算比重 |
| 四 税种明细 | 三大税种 + 动态子科目 | 子科目动态发现（LIKE 前缀排自身）超出静态模板，见适配点 2 | 各项同比、上月累计同比 |
| 五 进出口税 | 进口环节税三科目 + 出口退税（负数） | 固定科目集合 | 同比（注意负数符号表述） |
| 六 政府性基金 | 基金收入 / 土地出让 / 房产五税 | T010201、1030148、五税集合 | 同比、占地方财力比重 |
| 七 国资与社保 | T010601 / T010401 + 子科目 | 固定 + 动态子科目 | 同比 |

**已知适配点（明确需要开发的事项）**：

1. **期间形态**：`treasury_income_detail`/`treasury_balance_monthly` 的期间字段是 `varchar(6)` 月字符串，而模板占位符 `{{period_start}}/{{period_end}}` 由 `PeriodResolver` 解析为日期。要么让 `PeriodResolver`/`MqlTemplateFiller` 支持月字符串占位符（如 `{{period_month}}`），要么建视图把 `stat_date` 转成 date；倾向前者（改动小、口径显式）。
2. **参数化表达力**：这些口径需要「科目集合」「按 treasury_level 动态确定的 budget_level 集合」「辖内子库集合」等参数，超出现有占位符（仅期间两枚）。候选方案：按（指标 × 口径）枚举拆成多个静态模板（零框架改动但模板数量膨胀）/ 扩展受控占位符白名单 / 库端建口径视图把科目集合固化。接口四的「动态子科目发现」本质是运行期数据探查，静态模板做不了——要么拆成「先查子科目清单、再逐科目取数」两段，要么降级为固定清单。
3. **同比取数**：上年同期要求库中至少 13 个月历史（已列入清单通用要求第 7 条）。

## 执行顺序

1. **现在**：转发清单（含《段落接口说明》澄清项），并索取数据服务源代码（只读）与需求文档。
2. **回复后**：以源代码核对科目常量、仲裁口径矛盾 → 建表落库（注释+外键补全）→ 修订本文档所有【待确认】与矛盾项 → 第 2/3 层条目定稿合入 → 启动验证（`SchemaService` 反射 + few-shot 校验全过）。
3. **视需求**：第 4 层按七接口映射表沉淀指标（走向导）+ 适配点 1/2 的开发。
