# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

`AiReportPreResearch` 是 `../nl2mql2sqlDemo`（NL→MQL→SQL「两跳」查询引擎）的 **fork + 上层演进**：在原查询底座之上，叠加 `ideaV2-核心路径.md` 定义的**报告生成 6 步流水线编排层**，做出「锁口径(人) → 结构化取数(引擎) → 程序造事实 → LLM 只照事实写 → 程序核数字 → 人签发」的最小可信闭环。演示场景：司库资金周报 / 资金快报。

**两层同处一个包根 `com.treasury.nl2sql`，但边界清晰**：

| 层 | 包 | 定位 |
|---|---|---|
| 底座查询引擎 | `com.treasury.nl2sql.{ir,compile,validate,llm,embedding,schema,service,glossary,fewshot,eval,guard,store,api}` | 原 demo 原样继承，**代码/端点零改动** |
| 报告流水线 | `com.treasury.nl2sql.report.{domain,asset,pipeline,store,api}` | 本项目新增 |

> **底座层的权威文档是 `../nl2mql2sqlDemo/CLAUDE.md`**——它详述 IR 模型（`ir/Mql.java`）、`MqlValidator` 安全边界、`Nl2SqlService.query()` 链路、口径沉淀等。改底座前先读它。本文件只详述**报告流水线层**与两层的衔接契约。项目自带文档：`README.md`（能力全景 + 演示脚本）、`ideaV2-核心路径.md`（主线设计）、`phase01.md`（Phase01 分解与验收记录）。

## 常用命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 必须 JDK 17+（pom java.version=17）

# 建库（首次/新环境，按编号顺序执行）：
mysql -h127.0.0.1 -P23306 -uroot -p < db/00-init.sql              # 建 reportbi + 23 张业务表 + 种子（DROP 重建，慎用）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/01-report-tables.sql  # 流水线状态表（可清零）
mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/02-asset-tables.sql   # 资产表（CREATE IF NOT EXISTS，版本行不可变，禁 DROP）

mvn -q spring-boot:run            # 端口 8080；看到「报告资产注册表就绪」即就绪
mvn -q clean package

mvn -q test                       # 全部单测
mvn -q test -Dtest=NumberAuditorTest                         # 单类
mvn -q test -Dtest=MqlSqlCompilerTest#nonEquiJoin_rendersComparisonOperator  # 单方法
# 报告流水线纯逻辑单测（无需 DB/LLM）：
mvn -q test -Dtest='PeriodResolverTest,MqlTemplateFillerTest,FactBuildStepTest,NumberAuditorTest,TemplateMatcherTest,MqlParameterizerTest,TemplateValidatorTest,MetricAdminServiceTest,TemplateDraftServiceTest,WriteStepPromptTest'
```

- ⚠️ **启动即反射 `information_schema`（`SchemaService`）+ 全量校验入库报告资产**，DB 不通或某资产坏了都会**启动失败**——`spring-boot:run` 前先备好 `reportbi` 库。
- 演示页（启动后）：`report.html`（报告流水线，双卡点+证据钻取）、`template-admin.html`（模板管理+AI 起草）、`metric-wizard.html`（指标五步向导）；原查询引擎演示页仍在 `/`。

## 配置与密钥

- 本项目连 **`reportbi`** 库（不是底座默认的 `chatbi`），默认 `127.0.0.1:23306`，用 `DB_HOST/DB_PORT/DB_NAME/...` 覆盖。
- 密钥**不入库**：`src/main/resources/application-local.yml`（gitignore，`cp .example` 后填）或环境变量 `LLM_API_KEY`/`EMBEDDING_API_KEY`。切 LLM 供应商只改 `LLM_BASE_URL/LLM_MODEL/LLM_API_KEY`（OpenAI 兼容协议通用）。
- 报告层专属配置（`application.yml` 的 `report`）：`max-rewrite-rounds: 2`（⑥审计违规回灌重写上限）、`match.tau: 0.60`（①模板 embedding 召回阈值，keywords 命中不受此限，召回空即失败关闭）。
- **元数据表隔离**：三张状态表 + 两张资产表已加入 `schema.exclude-tables`——它们不进 NL2SQL 白名单与 prompt schema（否则报告引擎会试图查自己的状态表）。

## 报告流水线架构（新增层）

编排入口 `report/pipeline/ReportPipeline`——无状态机框架，用 if/switch 编排；`report_run` 一行一次运行，每步 I/O 落 `report_step`（重试 attempt 递增只追加），事实落 `report_fact`。

```
用户需求 → ① OutlineStep(LLM,同步)   三段式匹配：TemplateMatcher 程序召回 → LLM 候选内单选 → 服务端把关
        → 🔒 卡点1 人工确认口径（AWAITING_OUTLINE_APPROVAL，口径在此锁死，run 固化 template_version）
        → ② SpecResolveStep(程序)    大纲 → MetricQuerySpec
        → ③ FetchStep(程序,零LLM)    MQL模板填充 → MqlValidator白名单 → MqlSqlCompiler编译 → 只读执行，留 SQL+双哈希
        → ④ FactBuildStep(程序)      结果 → FactRecord；环比/净流入等 DERIVED fact 也由程序算
        → ⑤ WriteStep(LLM)           只写 {{fact_key}} 占位符，禁写任何阿拉伯数字；章节 stylePrompt 注入 user 段
        → ⑥ AuditStep + NumberAuditor(程序双检)  草稿禁数扫描→回灌重写≤2轮；替换后回读核对，一致率必须 100%
        → ✍️ 卡点2 人工审批（AWAITING_PUBLISH_APPROVAL，服务端复核审计包一致率 100% 才放行 → PUBLISHED）
```

②~⑥ 在守护线程异步跑，前端轮询。`POST /resume` 断点续跑：SPEC~FACT 从②整段重跑，WRITE/AUDIT 从库读事实续跑不重取数。

### 四条不可破坏的硬约束（改任一步前必须守住）

1. **③ 是零-LLM 确定性通路**。每个指标语义（`resources/report/metrics.json` / 库中 `report_metric`）内嵌一个**参数化 MQL 模板**（合法 Mql JSON，占位符仅 `{{period_start}}`/`{{period_end}}`）。填模板→残留占位符检测→`MqlValidator` 白名单（**不通过直接 BLOCKED，不回灌自愈**）→编译执行。同输入必同 SQL、`sql_hash` 可复现——这是「数字一致率 100%」硬门禁的地基。**不要给 ③ 引入 LLM 或自愈重写。**
2. **⑤ 的 LLM 禁止写数字**。正文数值只能是 `{{fact_key}}` 占位符（例外：日期、周期标签）；数值由 ⑥ 用 ④ 渲染的 `display_value` 替换并附 `[fact_xxx]` 引用——转录错误在构造上不可能发生。
3. **⑥ 是死代码兜底，数字安全不依赖任何 prompt**。检查1 剥离占位符+白名单后扫描裸数字（违规回灌重写，超限 BLOCKED）；检查2 对终稿逐个把「数值[fact_xxx]」反解析回数值与 fact 比对（容差=展示精度半个末位），100% 才放行。stylePrompt 只能改文风、改不动数字（已固化为注入对抗回归项）。
4. **失败关闭，不猜测补全**。模板/周期识别不了、指标映射不到、MQL 校验失败、质量断言失败、审计重写超限——一律 `BLOCKED` 转人工。`blocked_reason` 前缀 `[POLICY]`=业务性停止 / `[EXCEPTION]`=意外异常。

### 底座与报告层的衔接契约

- **底座的自由生成路径（`Nl2SqlService.query()` / 首页 `/api/query`）不进报告主流水线**，定位为「资产制作期工具」：向导（`MetricWizardService`）用它试查生成 MQL 草稿，人工核验后经确定性参数化（`MqlParameterizer`）沉为指标模板。这保持了底座作为 `EvalService` 评估契约的纯净性——参见 `../nl2mql2sqlDemo/CLAUDE.md` 的「分层是硬约束」。
- 报告层复用底座的 `MqlValidator`（安全边界）/ `MqlSqlCompiler`（确定性编译）/ `EmbeddingClient`（模板匹配召回）。

### 资产模型（库为唯一事实源）

`report/asset/ReportAssetService` 是注册表：**库是唯一事实源**，classpath 种子（`resources/report/metrics.json` + `resources/report/templates/*.json`）**仅对空库幂等增量种入**（库中已存在的 id 不覆盖，含 DEPRECATED——人为下架不会被种子复活）。启动做全量自检（模板引用完整性 + keywords 非空 + 指标 MQL 模板过校验器），坏了就 fail-fast。`reload()` 热刷新。

- **模板/指标皆多版本、行不可变**（`report_template`/`report_metric`）：所有写 = 产生新版本 DRAFT 或流转状态，**绝无 UPDATE `body_json`**。publish 的「旧版下线+新版上线+匹配缓存刷新」在服务层一个事务内完成。
- `TemplateValidator` 在保存/干跑/启动自检**三处共用**同一套校验规则。改校验规则改这一处。
- 页面化 CRUD：`TemplateAdminService`（+ `TemplateDraftService` AI 起草九步后处理链）、`MetricAdminService`（+ `MetricWizardService` 试查/口径反翻译/参数化）。改 JSON 文件对已入库资产无效——改已入库资产走页面/API。

## 代码地图（报告层，包根 `com.treasury.nl2sql.report`）

| 位置 | 内容 |
|---|---|
| `domain/` | 两大契约 `MetricQuerySpec`/`FactRecord` + `Outline`/`AuditResult` + 落库 record（`ReportRun`/`ReportStep`）+ 枚举 `RunStatus`/`Phase` |
| `pipeline/` | 六步 Step + `ReportPipeline` 编排器；辅助 `PeriodResolver`/`MqlTemplateFiller`/`MqlParameterizer`/`MqlTrialExecutor`/`TemplateMatcher`/`NumberAuditor`/`PolicyException` |
| `asset/` | `ReportAssetService` 注册表 + `Template/MetricAdminService` + `TemplateValidator` + `TemplateDraftService` + `MetricWizardService`；`MetricDefinition`/`ReportTemplateDef` |
| `store/` | 三运行状态仓库（`ReportRun/Step/FactRepository`）+ 两资产仓库（`Template/MetricAssetRepository` 继承 `VersionedAssetRepository`）；JdbcTemplate，范式照底座 `store/CaliberRepository` |
| `api/` | `ReportController`（流水线端点 `/api/report`）、`TemplateAdminController`、`MetricAdminController`；非法状态迁移→400 |
| 前端 | `resources/static/{report,template-admin,metric-wizard}.html`，均单文件 vanilla JS |

状态机：`AWAITING_OUTLINE_APPROVAL → RUNNING → AWAITING_PUBLISH_APPROVAL → PUBLISHED`；任一步失败→`BLOCKED`；卡点2 驳回→`REJECTED`。完整 API 表与演示脚本见 `README.md` §四/§三。

## 技术说明 PDF 随包分发

pom 把项目根下 `*lastRelease.pdf` 复制进 `static/`（当前 `智能查数与报告流水线技术说明-lastRelease.pdf`），由 Spring Boot 静态资源直接对外，导航条「📄 技术说明」按钮可浏览器直查。换版只需替换该 `*lastRelease.pdf`。编制/重构技术说明书走 `/tech-spec-authoring` skill。
