# AiReportPreResearch — 智能报告生成流水线（ideaV2 核心路径演示系统）

在 `nl2mql2sqlDemo`（NL→MQL→SQL 查询引擎）之上叠加 `ideaV2-核心路径.md` 定义的
**报告生成 6 步流水线编排层**，做出「锁口径(人) → 结构化取数(引擎) → 程序造事实 →
LLM 只照着事实写 → 程序核数字 → 人签发」的最小可信闭环。

- 演示场景：**司库资金周报**（reportbi 库，种子数据覆盖 2026-05-12 ~ 06-30，推荐报告期 2026-W26）
- 演示页：启动后打开 **http://localhost:8080/report.html**（原查询引擎演示页仍在 `/`）
- 设计文档：`ideaV2-核心路径.md`（主线）、`ideaV2.md`（全量）、`ideaV2-业务版说明.md`（业务版）

## 一、流水线与两条底线

```
用户需求 → ① 大纲生成(LLM,同步) → 🔒卡点1 人工确认口径（口径在此锁死）
        → ② 语义解析(程序: 大纲→MetricQuerySpec)
        → ③ 安全取数(程序: MQL模板填充→白名单校验→jOOQ编译→只读执行, 零LLM, 留SQL+结果哈希)
        → ④ 事实构建(程序: 结果→FactRecord; 环比/净流入也由程序算成 DERIVED fact)
        → ⑤ 章节撰写(LLM: 只写 {{fact_key}} 占位符, 禁写任何阿拉伯数字)
        → ⑥ 证据审计(程序双检: 草稿禁数扫描→违规回灌重写≤2轮; 替换后回读核对, 一致率必须100%)
        → ✍️卡点2 人工审批签发（服务端复核审计包才放行）
```

- **失败关闭**：模板/周期识别不了、指标映射不到、MQL 校验失败、质量断言失败（如余额为负、
  取数为 NULL 且 nullPolicy=BLOCK）、审计重写超限——一律 `BLOCKED` 转人工，**不猜测补全**。
  `blocked_reason` 前缀 `[POLICY]`=业务性停止 / `[EXCEPTION]`=意外异常。
- **最小运行状态**：每次运行有 `report_run` 行（run_id 即 report_run_id），每步输入输出落
  `report_step`（重试/重跑 attempt 递增只追加），事实落 `report_fact`。支持断点续跑
  （`POST /resume`：SPEC~FACT 从②整段重跑；WRITE/AUDIT 从库读事实续跑，不重取数）与审批等待。

### 为什么 ③ 是"零 LLM"的确定性通路

每个指标语义定义（`resources/report/metrics.json`）内嵌一个**参数化 MQL 模板**（合法 Mql JSON，
占位符仅 `{{period_start}}`/`{{period_end}}`）。②③ 全是程序：填模板 → 残留占位符检测 →
`MqlValidator` 白名单校验（不通过直接 BLOCKED，**不回灌自愈**）→ `MqlSqlCompiler` 编译执行。
同输入必同 SQL、sql_hash 可复现——这是「数字一致率 100%」硬门禁的地基。
LLM 的自由生成路径（`Nl2SqlService.query()` / 首页演示）不进报告主流水线，
定位为**资产制作期工具**：新增指标时先用 `/api/query` 生成 MQL 草稿，人工核验后沉入 metrics.json
（启动自检会用哨兵日期填充模板过一遍校验器，资产坏了启动即报错）。

### 为什么 ⑤ 的 LLM 禁止写数字

LLM 正文里所有数值只能写 `{{fact_key}}` 占位符（例外：日期、周期标签），数值由程序在 ⑥ 替换为
④ 统一渲染的 `display_value` 并带上 `[fact_xxx]` 引用标记——**转录错误在构造上不可能发生**。
⑥ 双检：检查1 剥离占位符与白名单后扫描任何裸数字（违规回灌重写，超限 BLOCKED）；
检查2 对替换后终稿逐个把「数值[fact_xxx]」反解析回数值与 fact 比对（容差=展示精度半个末位），
一致率 100% 才进卡点2。已接受局限：中文数词（"三成"）不在审计射程，prompt 禁用 + 卡点2 人工兜底。

## 二、快速开始

前置与 `nl2mql2sqlDemo` 完全一致：JDK 17+（建议 21）、MySQL（默认 `127.0.0.1:23306`）、
LLM 与 embedding 密钥在 `src/main/resources/application-local.yml`（已 gitignore）。
本项目连 **reportbi** 库（chatbi 的副本，见 `application.yml` 的 `DB_NAME:reportbi`）。

```bash
# 1) 建流水线状态表（可重复执行；DROP 重建=清空全部运行记录）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/report-tables.sql

# 2) 启动（启动即做资产自检：模板引用完整性 + 16 个指标 MQL 模板全量过校验器）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -q spring-boot:run          # 看到「报告资产已加载并自检通过」即就绪

# 3) 单测（流水线相关均为纯逻辑，无需 DB/LLM）
mvn -q test -Dtest='PeriodResolverTest,MqlTemplateFillerTest,FactBuildStepTest,NumberAuditorTest'
```

## 三、演示脚本（评审用）

打开 http://localhost:8080/report.html：

1. **发起**：输入「生成 2026 年第 26 周的司库资金周报，和上周对比」→ ① 同步生成大纲（5~20 秒）。
2. **卡点1**：确认页展示 4 章及其指标（可勾掉不要的指标）、报告期 2026-W26 / 对比期 W25；
   映射不上的需求表述红字提示（软失败，不阻断）。点「确认口径」→ ②~⑥ 异步执行（页面 1.5s 轮询；
   ⑤ 为 LLM 调用约 30~90 秒）。
3. **卡点2**：审计条显示「核对数字 N 个 / 一致率 100% / 重写 X 轮」；报告正文里每个数字点击
   `[fact_xxx]` 跳到证据表，展开可见该数字的 **SQL、sql_hash、result_hash、MetricQuerySpec**；
   核对无误后「审批发布」。
4. **失败关闭演练**：
   - 输入「生成 2026 年 6 月的资金月报」→ ① 即 BLOCKED（`[POLICY]`，当前仅支持周报）；
     在 BLOCKED 面板填打回意见「改为第 25 周的周报」→ 重新生成大纲（attempt 留痕）。
   - 把 `metrics.json` 某占位符改错（如 `{{period_strat}}`）再启动 → **启动即失败**（资产自检兜底）。
   - 断点续跑：`UPDATE report_run SET status='BLOCKED', phase='WRITE' WHERE run_id=X;`
     （模拟撰写期 LLM 中断）→ 详情页「断点续跑」→ 仅重跑 ⑤⑥（事实不重取，attempt+1）。

curl 版（无界面）：

```bash
curl -s localhost:8080/api/report/runs -H 'Content-Type: application/json' \
  -d '{"requestText":"生成 2026 年第 26 周的司库资金周报，和上周对比"}' | jq '.run.status'
curl -s localhost:8080/api/report/runs/1/outline/approve -H 'Content-Type: application/json' \
  -d '{"approver":"demo"}' | jq '.run.status'
watch -n2 'curl -s localhost:8080/api/report/runs/1 | jq ".run.status, .run.phase"'
curl -s localhost:8080/api/report/runs/1/publish/approve -H 'Content-Type: application/json' \
  -d '{"approver":"demo"}' | jq '.run.status'
```

## 四、API 一览（`/api/report`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/runs` | 发起：同步跑 ①，返回 AWAITING_OUTLINE_APPROVAL（或 BLOCKED） |
| GET | `/runs` / `/runs/{id}` | 列表 / 详情（run + steps 留痕 + facts） |
| POST | `/runs/{id}/outline/approve` | 卡点1 确认（可回传人工调整后的大纲，以人确认版为准）|
| POST | `/runs/{id}/outline/regenerate` | 卡点1 打回：带修改意见重跑 ① |
| POST | `/runs/{id}/publish/approve` | 卡点2 签发（服务端复核审计包一致率 100% 才放行）|
| POST | `/runs/{id}/publish/reject` | 卡点2 驳回（终态 REJECTED）|
| POST | `/runs/{id}/resume` | 断点续跑（仅 BLOCKED 或停摆超 2 分钟的 RUNNING）|
| GET | `/runs/{id}/facts` | 证据视图：全部 FactRecord（含 SQL/双哈希/规约快照）|
| GET | `/assets` | 支撑资产：模板 + 指标语义定义 |

状态机（无框架，编排器 if/switch）：`AWAITING_OUTLINE_APPROVAL → RUNNING →
AWAITING_PUBLISH_APPROVAL → PUBLISHED`；任一步失败 → `BLOCKED`；卡点2 驳回 → `REJECTED`。

## 五、代码地图（新增部分，包根 `com.treasury.nl2sql.report`）

| 位置 | 内容 |
|---|---|
| `domain/` | 两大契约 `MetricQuerySpec`/`FactRecord` + `Outline`/`AuditResult`/落库行 record + 状态枚举 |
| `asset/` | `ReportAssetService`：加载 `resources/report/{metrics,template-treasury-weekly}.json` + 启动自检 |
| `pipeline/` | 六步：`OutlineStep`(①LLM) `SpecResolveStep`(②) `FetchStep`(③) `FactBuildStep`(④) `WriteStep`(⑤LLM) `AuditStep`+`NumberAuditor`(⑥)；`ReportPipeline` 编排器（①同步、②~⑥守护线程异步、resume）；`PeriodResolver`/`MqlTemplateFiller`/`PolicyException` |
| `store/` | 三仓库（JdbcTemplate，范式照 `CaliberRepository`）；建表脚本 `db/report-tables.sql` |
| `api/` | `ReportController`（上表端点；非法状态迁移→400）|
| 前端 | `resources/static/report.html`（单文件 vanilla JS，双卡点 + 进度 + 证据钻取）|

配置：`application.yml` 新增 `report.max-rewrite-rounds: 2`；三张状态表已加入
`schema.exclude-tables`（元数据表不进 NL2SQL 白名单）。**原查询引擎的代码与端点零改动**。

## 六、扩展指标 / 模板的路径

1. 用首页 `/`（`/api/query`）以自然语言试出正确查询，人工核验其 MQL；
2. 把 MQL 沉入 `metrics.json`（日期条件换成 `{{period_start}}/{{period_end}}` 占位符），
   填 `valueColumn`（结果须恰 1 行 1 值）、`unit`、`timeBound`/`comparable`、`nullPolicy`、`qualityChecks`；
3. 在 `template-treasury-weekly.json` 章节里挂上 metricId；
4. 重启——启动自检不过会直接报错指出坏在哪个指标。
派生指标（如净流入）不写 MQL，用 `"derived": {"op":"subtract","left":"...","right":"..."}`，由 ④ 程序计算。
