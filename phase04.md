# Phase 04 规划：图表与表达力（P4-T0～T6）

> 目标一句话：报告带图，且**图表数据与 fact 同源同审计**——把「每个数字有出生证明」延伸到
> 「每张图有出生证明」。ECharts option 的 series 数据 100% 由**确定性程序**从 fact 绑定，
> LLM 全程不接触图表数据（运行期零 LLM，对齐硬约束 1）。
>
> 上位规划：`roadmap.md` §五。**与 Phase05 并行**：本阶段主战场在「④→⑥ 之间的图表节点 +
> 前端渲染 + 模板编辑器」，Phase05 在「pipeline 新增归因节点 + 事件资产表」——文件面天然分离，
> 交叉点仅 `report.html`（分区渲染）与 ⑥ 审计包结构（各自追加独立段），合流前互跑对方 Gate 用例。
> 工作模式承袭 phase02/03：T0 契约定稿（过目后开工）→ 任务串行为主 → G04 扎口。预估单人 4～6 天。

---

## 一、总体结构

```mermaid
flowchart LR
    T0["P4-T0 契约定稿<br/>（半天，阻塞全部）"] --> T1["T1 图表声明 schema<br/>+ 校验"]
    T1 --> T2["T2 趋势序列取数<br/>（③ 扩展）"]
    T2 --> T3["T3 ChartBuildStep<br/>确定性 option 生成 + ⑥ 核对"]
    T3 --> T4["T4 前端渲染<br/>+ 编辑器图表配置"]
    T4 --> T5["T5 评测扩充"]
    T5 --> G{"G04 扎口<br/>T6 集成验收"}
```

**Gate G04 验收标准**（四条，源自 roadmap §五）：

1. **周报含趋势图与结构图**：趋势图（近 N 周交易额折线）+ 结构图（按币种拆解饼图/柱图）随报告签发。
2. **零 LLM 数据绑定**：ECharts option 的 series 数据 100% 由程序从 fact 绑定——图表通路上没有任何
   LLM 调用（模板制作期的图型建议不在此列，运行期为零）。
3. **图表 JSON Schema 校验 100% 通过**：图表声明（模板资产）过 TemplateValidator 扩展规则；
   生成的 option 过程序自检（series 点数 = 绑定 fact 数、每点可溯源）。
4. **图表数据一致性进 ⑥ 审计**：审计包新增图表核对段——series 每个数据点与所引 fact.value 逐点比对，
   100% 才放行（与数字一致率同级门禁）；`mvn test` 全绿 + 既有基线（评测两层 100%、W26/M06/Q2 E2E）不回退。

---

## 二、通用纪律（承袭 phase01~03 全部九条，本阶段追加一条）

1～9 同 phase03（回归门禁 / 四硬约束 / 服务端把关 / 资产留痕 / commit 粒度 / 审计对抗先行 /
评测期望值手写 SQL / 种子只增不改 / 无声上限即违规）。追加：

10. **前端资源自包含**：ECharts 以本地 vendored 文件进 `static/`（演示环境无外网假设），
    禁止 CDN 引用；版本一次固定，随 commit 留痕。

---

## 三、启动节点（半天，阻塞后续所有任务）

| 任务 | 内容 | 产出物 | 验证方式 |
|---|---|---|---|
| **P4-T0** 契约定稿 | 三个拍板项均按草案推荐定稿（2026-07-11 过目拍板）：① **序列点即 fact**；② **图表独立配额**（单图 ≤12 点、单章图表 fact ≤24，不计入章 20 上限）；③ **全局编辑节点后置**（章数变多后再评估） | 本文件契约两节定稿 | ✅ 过目评审通过（2026-07-11） |

### 契约草案 1：图表声明与序列取数（T1/T2 依据）

- **声明位置**：`ChapterDef` 加可选字段 `charts`（列表）：
  `{ "chartId": "trend_txn", "type": "line|bar|pie", "title": "近六周交易总额",`
  `  "binding": { "kind": "series|dimension", ... } }`
  两种绑定（本阶段只做这两种，枚举收口）：
  - `series`：`{ "kind": "series", "metricId": "week_txn_amount_cny", "periods": 6 }`——
    该指标近 N 个同粒度周期的时间序列（含本期），适配 line/bar；
  - `dimension`：`{ "kind": "dimension", "metricId": "week_txn_amount_by_currency" }`——
    绑定维度指标的行 fact 组（P3 现成），适配 pie/bar。
- **TemplateValidator 扩展**：chartId 章内唯一且 `^[a-z][a-z0-9_]{2,31}$`；type/kind 枚举合法；
  series 绑定的 metricId 须 PUBLISHED 且 `timeBound=true`、`periods` 2~12；dimension 绑定的
  metricId 须声明了 `dimensions`；引用指标必须同时挂在本章 `metrics`（图表不引入章外口径）。
  三处共用校验不变（保存/干跑/启动自检）。
- **趋势序列取数（拍板项 ①，推荐方案）**：**序列点即 fact**——② `SpecResolveStep` 对声明了
  series 图表的章节追加 spec：purpose 新常量 `CHART_SERIES`，每个历史周期一条
  （窗口由 `PeriodResolver.previous()` 迭代推导，periodLabel 即各期标签）；③ 走既有确定性通路
  逐期取数（同模板同校验）；④ 造 fact：`fact_NNN_s1..sN`（s1=最早期），metricName 加「（序列·<期标签>）」。
  好处：审计/落库/证据钻取全部复用，「每个点有出生证明」不需要新机制。
  代价：fact 数 +N——见拍板项 ②。**本期点复用既有 CURRENT fact，不重复取数**。
- **fact 上限开口（拍板项 ②，T2 实施裁定微调）**：`CHART_SERIES` 序列 fact 走**独立图表配额**
  （单图 ≤12 点、单章序列 fact ≤24），不挤占章 20 上限——20 管的是 ⑤ 的占位符纪律，序列 fact
  不进 ⑤ prompt；**维度行 fact 维持 P3 语义留在章 20 上限内**（维度行以表格占位符进 prompt，
  正属占位符纪律射程，草案「移入图表配额」的设想与 P3 实现相悖，按实现纠正）。
  序列 fact 命名走独立域 `fact_c<NN>_s<K>`（s1=最早期），主序列 `fact_NNN` 编号零扰动；
  序列判定按 spec 快照 purpose（不靠 key 模式——维度 slug 可能撞形）。触顶仍 BLOCKED 不截断（纪律 9）。
- **序列缺数据**：某历史周期取数为 NULL/0 行按指标 `nullPolicy`（ZERO 归 0 画点；BLOCK 失败关闭）；
  全序列为 0 → 图表照画 + note。

### 契约草案 2：ChartBuildStep 与审计扩展（T3 依据）

- **管线位置**：④ 与 ⑤ 之间新增确定性节点 `ChartBuildStep`（程序，零 LLM）：
  按章节 charts 声明从 fact 组装 ECharts option（title/xAxis=期标签或维度值/series.data=fact 值
  + 每点 `factKey` 溯源字段塞进 option 的自定义 meta），产出 `ChartRecord{chartId, chapterId,
  optionJson, boundFactKeys}` 列表，落 `report_run.charts_json`（新列，迁移照 yoy 列既例：
  db/01 CREATE + db/02 守卫式 ALTER）。
- **⑤ 不接触图表**：图表不进 WriteStep 的 prompt、正文不写图表占位符——前端按 chapterId 在章节尾
  渲染（LLM 连图表的存在都不知道，「零 LLM 接触数据」在构造上成立）。
- **⑥ 审计扩展（第三段独立核对）**：`ChartAuditor`（纯静态可单测，照 NumberAuditor 范式）——
  对每张图逐点比对 option.series.data[i] 与 boundFactKeys[i] 所指 fact.value（严格相等，
  图表数据是程序绑定的，不存在渲染精度问题）；点数与 fact 数不符、引用不存在、值不等——
  一律计入审计包新段 `chartChecks`，任何不一致 → 不放行（与数字一致率同级）。
  **对抗单测先行**（纪律 6）：篡改 option 单点值/删点/引用不存在的 fact 逐条被拦。
- **卡点2 展示**：审计包 chartChecks 段进详情页；图表本体在报告预览区随章节渲染（T4）。

---

## 四、任务表（T1→T4 串行为主，T5 合流）

| 任务 | 内容 | 产出物 | 验证方式 |
|---|---|---|---|
| **P4-T1** 图表声明 schema 与校验 | 按契约1：`ChapterDef.charts` + `ChartDef` record；`TemplateValidator` 扩展规则（含 series/dimension 绑定合法性矩阵）；`OutlineStep` 章节拷贝透传（大纲快照含图表声明，口径锁死语义一致） | schema/校验器改动 + 单测正反例 | 单测全绿；启动自检对存量模板零误伤 |
| **P4-T2** 趋势序列取数 | 按契约1：`MetricQuerySpec.PURPOSE_CHART_SERIES` + `SpecResolveStep` 追加序列 spec（本期复用 CURRENT）+ ④ 造 `fact_NNN_sK` 序列 fact + 图表配额上限；周报模板需要的历史周数据核查（现种子 2026-05-04 起有连续周流水，近 6 周够用；不够则增量种子只增不改） | Spec/FactBuild 扩展 + 单测 | 单测：序列窗口迭代/配额触顶 BLOCKED；评测确定性层 100 条不回退（序列 fact 不在期望清单射程——purpose 过滤确认） |
| **P4-T3** ChartBuildStep + ChartAuditor | 按契约2：确定性 option 生成（line/bar/pie 三型模板化组装）+ `report_run.charts_json` 落库迁移 + ⑥ 审计包 `chartChecks` 段 + **对抗单测先行**（篡改/删点/幻引用） | 新 Step + Auditor + 迁移 SQL + 单测 | 单测全绿；E2E：周报 run 落 charts_json，审计包 chartChecks 100% |
| **P4-T4** 前端渲染与编辑器配置 | ECharts vendored 进 `static/`（纪律 10）；`report.html` 章节尾渲染图表（读 charts_json）+ 审计段展示 chartChecks；`template-admin.html` 章节图表配置区（型/绑定下拉，选项从本章 metrics 过滤） | 前端改动 + 静态资源 | 实操：编辑器配一张图 → 干跑校验 → 发布 → 报告页可见图且与事实表数值一致 |
| **P4-T5** 评测与模板扩充 | treasury-weekly 发新版：summary 章加趋势图（series: week_txn_amount_cny×6 周）、by_currency 章加结构图（dimension 绑定）；golden-set 扩：FACTS 补 `CHART_SERIES` 期望值（手写 SQL 逐期直查，纪律 7）；确定性层比对键天然支持（purpose 区分） | 模板新版 + 评测用例 | 两层评测全过且存量 100 条原样通过 |
| **P4-T6** G04 扎口 | Gate 四条逐条验收；README 演示手册补图表演示 + 评测基线更新；验收记录写入本文件 §六；tag `phase04-G04` | 收尾 commit | Gate 全过；`mvn test` 全绿；W26/M06/Q2 基线 E2E 不回退 |

> 全局编辑节点（⑤ 第二遍跨章口吻统一）按拍板项 ③ 默认后置：现每报 4~5 章一次 LLM 调用已保证
> 全文一致性，等 Phase05 归因章节加入、章数变多后再评估收益。

---

## 五、主要风险与缓解

| 风险 | 缓解 |
|---|---|
| 序列取数使 ③ 调用数放大 N 倍（近 6 周 = 6 次查询） | 单图 periods ≤12 收口；仅声明了图表的章节才取；③ 本就逐 spec 串行秒级，演示量级无虞 |
| 序列 fact 撑大 fact 空间、误入 ⑤ prompt | 契约2：图表 fact 不进 WriteStep prompt（按 purpose 过滤）；独立图表配额上限触顶 BLOCKED |
| ECharts option 结构自由度大，审计漏点 | option 由程序模板化组装（三型收口），ChartAuditor 逐点比对 + 点数守恒断言；对抗单测先行 |
| 历史周期种子数据不足使趋势图脏 | T2 先核查数据覆盖，不足则增量种子（纪律 8）；期望值手写 SQL 直查 |
| 与 Phase05 并行改 report.html 冲突 | 分区渲染：P4 只动章节尾图表容器与审计 chartChecks 段，P5 只动归因章与 claim 证据链段；合流前互跑对方 Gate 用例 |
| 评测确定性层被序列 fact 打破对称检查 | T2 验证 purpose 过滤：期望清单外的 CHART_SERIES fact 不计入「多产出即失败」（比对射程限 CURRENT/COMPARE/COMPARE_YOY），或补齐期望值——T5 拍板落地 |
