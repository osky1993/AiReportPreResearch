# API 说明（`/api/report`）

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
| POST | `/runs/{id}/export?format=pdf\|docx` | 已签发报告导出（仅 PUBLISHED）：正文/事实/图表取库中终稿，body 可选上送图表 PNG（缺图降级数据表）|
| GET | `/runs/{id}/lineage` | 血缘导出（P6，只读）：run 级血缘单文档——`{job, run, inputs, outputs, graph:{nodes,edges}}`，对齐 OpenLineage 概念不引框架。节点 11 类（需求/模板@版本/指标@快照版本/spec/SQL/fact/图表/事件/归因/审批/报告），边 8 类（`MATCHED_TO/RESOLVED_TO/COMPILED_TO/PRODUCED/DERIVED_FROM/BOUND_TO/EVIDENCED_BY/APPROVED_BY`）；业务表输入从指标版本快照的 mqlTemplate 结构化提取（不解析 SQL）。字段全部同源库中留痕：断链（有引用无实体）→ 400 失败关闭；老 run 天然缺失的产物标 `absent` |
| GET | `/stats?from=&to=` | 运行看板统计（P6，只读，演示页 `dashboard.html`）：run 状态分布与 BLOCKED 原因 TopN、各步成功率/重试率/阻断率与 P50/P95 耗时、LLM 调用与 token 用量（usage 段随 OUTLINE/WRITE/AUDIT 步落痕，未计量不冒充 0；成本估算按 `report.observe.price-per-1k-*` 配置，缺省只显示 token）。缺省近 30 天，`from=all` 全量。观测层绝对只读——观测宕掉，报告照常出 |
| GET | `/assets` | 支撑资产：全部 PUBLISHED 模板（templates 数组）+ 指标语义定义 |
| GET | `/calibers` | 口径资产列表（ACTIVE，升格入口用；`index.html` 一键带 MQL 跳指标向导第 ③ 步）|
| POST | `/eval/run?layer=deterministic\|llm` | 回归评测（见上「评测基线」节；只读）|

状态机（无框架，编排器 if/switch）：`AWAITING_OUTLINE_APPROVAL → RUNNING →
AWAITING_PUBLISH_APPROVAL → PUBLISHED`；任一步失败 → `BLOCKED`；卡点2 驳回 → `REJECTED`。

## 模板管理 API（P2 契约，前缀 `/api/report`）

资源模型：一个模板资产 = `templateId` + 多版本行（`report_template` 表，行不可变）。
所有写操作 = 产生新版本或流转状态，绝无 UPDATE `body_json`。演示页：`template-admin.html`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/templates` | 列表（按 templateId 聚合：name/latestVersion/publishedVersion/latestStatus/source/updatedAt）|
| POST | `/templates` | 新建：校验通过才写 **v1 DRAFT**，返回 201 |
| GET | `/templates/{id}` | 详情：versions 版本历史 + published 定义（可 null）+ latest 定义 |
| GET | `/templates/{id}/versions/{v}` | 指定版本完整定义（版本历史只读查看）|
| PUT | `/templates/{id}` | 保存 = 校验通过才写**新版本 DRAFT**（body 内 templateId 须与路径一致）|
| POST | `/templates/{id}/publish` | `{version}`：DRAFT→PUBLISHED；旧 PUBLISHED 自动置 DEPRECATED；刷新注册表与匹配向量缓存 |
| POST | `/templates/{id}/deprecate` | `{version}`：DRAFT/PUBLISHED→DEPRECATED；下线即从运行匹配摘除（在跑 run 用快照不受影响）|
| POST | `/templates/validate` | 干跑校验（不写库），前端保存前预检 |
| GET | `/metrics/{id}/references` | 反向检查：指标被哪些 PUBLISHED 模板引用（指标下架保护）|
| POST | `/templates/draft` | AI 起草：场景描述 → 模板草案 + unresolved（草案不落库，进编辑器；空泛/无关 400 拒绝）|

## 指标管理与制作向导 API（P5 契约，前缀 `/api/report/metrics`，演示页 `metric-wizard.html`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/try` | 试查：包装引擎自由生成路径，返回 mql/sql/rows/columns/warnings（失败 200+success=false）|
| POST | `/explain` | 口径反翻译：确定性预检（校验通过才调 LLM）→ {explanation, caveats}；失败 400 |
| POST | `/parameterize` | 确定性扫描日期条件建议（JSON Pointer path）；带 apply 服务端替换生成 mqlTemplate（安全闸：path 须命中建议）|
| POST | `/`（保存） | 五重校验（STRUCTURE 含 timeBound 双向一致性 → PLACEHOLDER → MQL_VALIDATION → TRIAL_EXECUTION → RESULT_SHAPE）全过写 v1 DRAFT |
| POST | `/{id}/publish`、`/{id}/deprecate` | 状态流转（守卫同模板）；**被 PUBLISHED 模板引用的指标不可下架** |
| GET | `/`、`/{id}` | 管理列表 / 详情+版本历史 |

统一错误结构（400=校验失败/非法流转，404=不存在）：
`{"error": "总述", "details": [{"location": "chapters[1].metrics[0]", "message": "引用了不存在的指标: xxx"}]}`

校验规则（保存与 validate 共用）：templateId 匹配 `^[a-z][a-z0-9-]{2,63}$`；name 非空；
keywords 非空数组且无空串；chapters 非空、chapterId 唯一、**每章至少 1 个指标**；
metricId 必须存在于 PUBLISHED 指标；comparison 只允许 null/week_over_week；
guidance/stylePrompt 可空、单条 ≤1000 字。

状态流转守卫：`DRAFT→PUBLISHED`、`DRAFT→DEPRECATED`、`PUBLISHED→DEPRECATED`，其余 400；
publish 的「旧版下线+新版上线+缓存刷新」在服务层一个事务内完成。
