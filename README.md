# AiQueryReportDemo — 智能问数与报告生成示例工程（ideaV2 核心路径演示系统）

在 `nl2mql2sqlDemo`（NL→MQL→SQL 查询引擎）之上叠加 `plan/ideaV2-核心路径.md` 定义的
**报告生成 6 步流水线编排层**，做出「锁口径(人) → 结构化取数(引擎) → 程序造事实 →
LLM 只照着事实写 → 程序核数字 → 人签发」的最小可信闭环。

- 演示场景：**司库资金周报** + **资金快报**（多模板按需求自动匹配；reportbi 库，业务数据覆盖 2024-01-01 ~ 2026-08-16、约 1600 笔流水 5 个币种，推荐报告期 2026-W26，当期周可用 2026-W33）
- 演示页：启动后打开 **http://localhost:8080/report.html**（原查询引擎演示页仍在 `/`）

## 流水线与两条底线

```
用户需求 → ① 大纲生成(LLM,同步) → 🔒卡点1 人工确认口径（口径在此锁死）
        → ② 语义解析(程序: 大纲→MetricQuerySpec)
        → ③ 安全取数(程序: MQL模板填充→白名单校验→jOOQ编译→只读执行, 零LLM, 留SQL+结果哈希)
        → ④ 事实构建(程序: 结果→FactRecord; 环比/净流入也由程序算成 DERIVED fact)
        → ⑤ 章节撰写(LLM: 只写 {{fact_key}} 占位符, 禁写任何阿拉伯数字)
        → ⑥ 证据审计(程序双检: 草稿禁数扫描→违规回灌重写≤2轮; 替换后回读核对, 一致率必须100%)
        → ✍️卡点2 人工审批签发（服务端复核审计包才放行）
```

- **③ 是零-LLM 的确定性通路**：指标语义内嵌参数化 MQL 模板（占位符仅 `{{period_start}}`/`{{period_end}}`），
  填模板 → 残留占位符检测 → `MqlValidator` 白名单（不过直接 BLOCKED，不回灌自愈）→ `MqlSqlCompiler`
  编译执行。同输入必同 SQL、sql_hash 可复现——「数字一致率 100%」硬门禁的地基。
- **⑤ 的 LLM 禁止写数字**：正文数值只能是 `{{fact_key}}` 占位符（例外：日期、周期标签），由 ⑥ 替换为
  ④ 统一渲染的 `display_value` 并附 `[fact_xxx]` 引用——转录错误在构造上不可能发生。⑥ 双检
  （草稿禁数扫描 + 终稿逐数反解析比对，含中文数词射程）是死代码兜底，数字安全不依赖任何提示词。
- **失败关闭**：模板/周期识别不了、指标映射不到、MQL 校验失败、质量断言失败、审计重写超限——
  一律 `BLOCKED` 转人工，不猜测补全（`[POLICY]`=业务性停止 / `[EXCEPTION]`=意外异常）。
- **最小运行状态**：`report_run` 一行一次运行，每步 I/O 落 `report_step`（attempt 递增只追加），
  事实落 `report_fact`；支持断点续跑（`POST /resume`）与双卡点审批等待。

## 快速开始

前置与 `nl2mql2sqlDemo` 完全一致：JDK 17+（建议 21）、MySQL（默认 `127.0.0.1:23306`）、
LLM 与 embedding 密钥在 `src/main/resources/application-local.yml`（已 gitignore）。
本项目连 **reportbi** 库（见 `application.yml` 的 `DB_NAME:reportbi`）。

```bash
# 0) 建业务库（首次/新环境；建库 reportbi 并灌入 23 张业务表与演示种子数据。
#    ⚠️ 可重复执行=DROP 重建，会清空业务表与 caliber_asset 口径沉淀，已有环境慎重）
mysql -h127.0.0.1 -P23306 -uroot -p < db/00-init.sql

# 1) 建流水线状态表（可重复执行；DROP 重建=清空全部运行记录）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/01-report-tables.sql

# 1b) 建资产表（可重复执行；CREATE IF NOT EXISTS，禁止 DROP 重建——版本行不可变）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/02-asset-tables.sql

# 1c) 业务数据扩容 v2（把演示库铺满到「像一家公司的真账」：2024-01 ~ 2026-08 逐月、
#     5 币种、26 个账户；⚠️ 纯增量且用显式主键，只能在 00-init.sql 之后跑一次）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/04-business-data-v2.sql

# 2) 启动（空库自动种入 classpath 种子并全量自检，资产坏了启动即报错）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -q spring-boot:run          # 看到「报告资产注册表就绪」即就绪；再次启动不重复种入

# 3) 单测（流水线相关均为纯逻辑，无需 DB/LLM）
mvn -q test -Dtest='PeriodResolverTest,MqlTemplateFillerTest,FactBuildStepTest,NumberAuditorTest,TemplateMatcherTest'
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/演示脚本.md](docs/演示脚本.md) | 评审演示脚本：A 基础流水线 / B 资产自助闭环 / C 对抗与失败关闭演练（含各轮实录）+ curl 版 + 评测基线 |
| [docs/API说明.md](docs/API说明.md) | `/api/report` 全部端点：流水线、模板管理、指标管理与制作向导；校验规则与状态机 |
| [docs/代码地图与扩展.md](docs/代码地图与扩展.md) | 报告层代码地图（`com.treasury.nl2sql.report`）+ 扩展指标/模板的路径 |
| [docs/部署指南.md](docs/部署指南.md) | 从零到部署：获取源码 → 编译打包 → Linux 测试环境部署 → 常见问题速查 |
| `plan/` | 设计文档：`ideaV2-核心路径.md`（主线）、`ideaV2.md`（全量）、`ideaV2-业务版说明.md`（业务版）；阶段计划 `roadmap.md` + `phase01~06.md` |
| `docs/` | 归档材料：技术说明 PDF 各版本 + 架构图 PNG（图源 `docs/arch-src/`）+ [评估集多轮抖动分析方法论](docs/评估集多轮抖动分析方法论.md) |
| `CLAUDE.md` | 面向 AI 协作的工程速查（架构硬约束、两层衔接契约、常用命令） |
