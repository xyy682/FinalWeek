# FinalWeek 测试报告

更新时间：2026-09-03（Asia/Shanghai）

> 当前项目总览与证据边界见 [`GPT_CONTEXT.md`](GPT_CONTEXT.md)。本报告按日期保留历史运行结果；顶部“最新”行代表最近一次记录，不把不同环境和日期的测试数量相加。

本报告记录 Phase 16 最终实际执行结果和复现入口。数字来自本地求职版 MVP 环境，不代表公网性能或生产 SLA；外部百炼评测与真实 AI 浏览器链路均在用户明确授权后运行。

## 结果摘要

| 层级 | 实际结果 | 说明 |
| --- | --- | --- |
| 最新宿主机后端回归 | 119 tests，0 failures，0 errors，1 skipped | 2026-09-03；覆盖 CAS 执行租约、owner 栅栏、心跳组件、过期 PROCESSING 回收和重新投递；唯一跳过项为未配置真实 XeLaTeX 环境条件测试 |
| MyBatis-Plus 重构基线 | 105 tests，0 failures，0 errors，1 skipped | 2026-08-21 宿主机全量执行；MySQL/Testcontainers 6 项全部通过，仅因未设置 `XELATEX_EXECUTABLE` 跳过 1 项真实模板测试 |
| 真实 XeLaTeX 后端基线 | 105 tests，0 failures，0 errors，0 skipped | 2026-08-21 宿主机启用 Docker 与真实 XeLaTeX；耗时 1 分 59 秒 |
| Docker 后端构建基线 | 105 tests，0 failures，0 errors，7 skipped | 2026-08-21；7 项依赖宿主机 Docker/XeLaTeX 的条件测试跳过；镜像构建成功 |
| MySQL Testcontainers | 11 tests，0 failures，0 errors，0 skipped | 任务租约/CAS 2 项、知识版本 JPA 4 项、MyBatis 持久化与 owner 栅栏 5 项；两个 MySQL 8.4.10 容器均成功执行 15 个 Flyway migration |
| 真实 XeLaTeX 模板 | 1 passed | Windows TeX Live 2026；中文、中英混排、公式、试卷/答案各两遍编译并由 PDFBox 打开 |
| 容器内 XeLaTeX | 通过 | Jammy、非 root `finalweek`、Noto Serif CJK SC、可写独立临时目录、`-no-shell-escape` 双 PDF 编译 |
| 前端 Vitest | 5 passed | 包含 30%/70%/99% 断点续传差集计算 |
| 前端生产构建 | 通过 | 类型检查通过；单 chunk >500 KB 为非阻断警告 |
| Playwright | 5 passed，4 skipped，0 failed | 9 个项目化用例；最终重新运行的真实 AI 主链路 53.9 秒，另 4 个非 AI 场景 4.0 秒；Chrome/Edge/Pixel 7 |
| Golden Cases（历史） | 8/8 执行成功，overall passed | 2026-08-11 `qwen3.7-plus` 真实报告，通过 JSON Schema |
| Golden Cases（扩展） | 14/14 执行成功，overall passed | 2026-08-13 `qwen3.7-plus` 真实报告；覆盖当时的原七类题型；综合题加入后尚未重新运行付费评测 |
| 管理清理 | dry-run 退出码 0 | 未执行真实删除 |
| Docker Compose | 8 个服务 healthy | 最新 frontend/backend 镜像及 MySQL/Redis/RabbitMQ/MinIO/Qdrant/Mailpit；健康接口 `UP` |

Playwright 的 4 个 skipped 是按项目设计：昂贵的真实 AI 全链路只在 Desktop Chromium 执行，移动布局场景只在 Pixel 7 执行；landing smoke 在三项目均执行。

## Phase 11 基线故障覆盖矩阵

| 风险 | 证据 | 结论 |
| --- | --- | --- |
| 缺少分片 | `UploadServiceTest.rejectsCompleteWhenAnyChunkIsMissing` | complete 返回稳定冲突，不合并 |
| 重复资料/complete 幂等 | upload complete 锁、数据库 completion/唯一约束及 `courseHashDuplicate...` | 返回既有资料/任务，不生成第二份业务结果 |
| publisher confirm 失败 | `TaskDispatchServiceTest.publishFailureLeavesRecoverableDatabaseState` | 进入 `PUBLISH_FAILED`，不产生假 `QUEUED` |
| confirm/consumer 状态竞争 | 条件 `queued` 与 MySQL CAS claim 测试 | 后续状态不会被 confirm 倒退 |
| MQ 重投预算 | `TaskConsumerTest.retryableFailureRequeuesWithinBudgetAndRejectsAtBudgetLimit` | 预算内 nack/requeue，到上限 reject 并记失败 |
| 重复/取消/旧轮次消息 | `duplicateCancelledOrOldRoundMessage...` | claim 失败即 ack，不运行 pipeline |
| 租约 owner 栅栏 | `TaskConsumerTest`、`KnowledgeVersionMybatisIntegrationTest` | 错误 owner 不能提交终态；失去租约的旧消费者只 ack，不再 nack/reject 干扰新执行者 |
| PROCESSING 宕机恢复 | `StaleTaskScannerTest`、`TaskStateMySqlIntegrationTest` | 租约过期后只有一个扫描实例回收并重新投递，避免任务永久卡死 |
| consumer/取消抢占与租约回收 | `TaskStateMySqlIntegrationTest` | 两个真实 MySQL 并发 claim 仅一方成功；写入 owner/lease；过期租约只有一次 CAS 回收成功 |
| checkpoint 重复 | `TaskStateMySqlIntegrationTest` | 数据库唯一键拒绝同 task/stage 重复写入 |
| Lucene 同课程并发 | `LuceneCourseIndexTest` | 写入串行、增量文档共存且幂等；修复重建独立验证 |
| 课程执行中删除 | `CourseServiceTest.runningTaskPreventsCourseDeletion...` | 返回冲突且不取消其他任务 |
| 第三方 429/503/400 | `BailianLlmClientFaultTest` | 429/503 各执行 3 次；永久 400 不重试 |
| Redis 不可用 | `RedisAvailabilityFilterTest` | 登录、认证查询和 AI API 均 fail closed 为稳定 503 |
| SSE 断线 | `full-journey.spec.ts` 主动 abort EventSource | 显示重新连接，通过 REST 轮询取得解析终态，刷新后恢复 |
| 计划模型超时 | 后台计划 pipeline/任务失败测试 | HTTP 已返回 `202`；后台失败且旧计划不被覆盖 |
| 问答模型超时 | 后台答疑 pipeline/消息失败测试 | 原问题落为失败且可按原消息 ID 重试 |
| 清理误删 | `AdminCleanupServiceTest` + 真实 dry-run | 活跃 MySQL 白名单不删除；默认 dry-run |

## Phase 12–16 覆盖矩阵

| 风险 | 证据 | 结论 |
| --- | --- | --- |
| 资料确认状态边界 | `KnowledgeVersionServiceTest` | 活动资料任务拒绝确认；失败资料必须显式忽略 |
| 相同资料集合重复确认 | 集合哈希唯一约束、课程锁、`KnowledgeVersionServiceTest`、`KnowledgeVersionMybatisIntegrationTest` 双线程竞争 | 真实 MySQL 下仅创建一个不可变知识版本和一个提纲任务，另一请求返回集合未变化 |
| 版本化提纲迁移 | `KnowledgeVersionMybatisIntegrationTest` | V10–V15 在 MySQL 8.4.10 生效，知识版本、提纲关系和任务租约字段可持久化 |
| 通用任务迁移与取消竞争 | `TaskStateMySqlIntegrationTest`、`TaskStateServiceTargetTest` | 原任务数据迁移；可见性、checkpoint 和 claim/cancel CAS 有数据库证据 |
| 异步计划/答疑离页恢复 | pipeline/coordinator 测试 + `full-journey.spec.ts` | REST 先返回后台任务/`PENDING` 消息，离页后从业务接口恢复终态 |
| 八类题型和数值边界 | `MockExamRequestNormalizerTest`、`MockExamValidatorTest` | 八类 DTO/答案通过；1–50、1000 分、300 分钟、2000 字和整数溢出受控 |
| 严格资料与通用知识 | `MockExamValidatorTest` | 禁止通用知识时缺口整体失败；允许时逐题标记且不得伪造来源 |
| 越权和版本外来源 | `MockExamServiceTest`、`MockExamValidatorTest` | ownership 在读取对象前检查；跨知识版本 segment 被拒绝 |
| 原题复刻与历史近似 | `MockExamQualityCheckerTest` | 源题复刻硬失败；历史近似和范围覆盖不足记录警告 |
| 同课程出卷冲突 | `MockExamCoordinatorTest` | 课程锁下发现活动出卷任务即返回稳定冲突，不创建第二份卷或后台任务 |
| TeX 注入 | `TexEscaperTest`、`MockExamValidatorTest` | 文本统一转义；危险命令、`^^`、注释/参数绕过和非法公式位置被拒绝 |
| 试卷泄露答案 | `MockExamTexRendererTest` + 实际 PDF 目视检查 | 试卷只渲染题干公式，答案公式仅出现在参考答案 |
| 双 PDF 部分失败 | `XeLatexMockExamArtifactGeneratorTest` | 任何部分失败先确认对象清理，不发布单边结果 |
| PDF 可用性 | `XeLatexTemplateIntegrationTest` + 容器内实际编译 | 中文/公式、A4、两遍编译、PDFBox 页数和非空检查通过 |
| 逻辑删除和对象清理 | `MockExamObjectCleanupTest`、`MockExamCleanupServiceTest`、`KnowledgeVersionMybatisIntegrationTest` | 删除立即隐藏；对象清理退避重试；已完成/失败清理记录可在稍后逻辑删除时安全重新排队；课程删除分离来源并登记双 PDF 清理 |
| 真实主流程 | Playwright `full-journey.spec.ts` | 两资料 → 知识版本 → 提纲 → 计划 → 离页答疑 → 2 题模拟卷 → 双 PDF 预览/下载通过 |
| 运行时契约 | /v3/api-docs、Flyway 表、Docker 健康检查 | OpenAPI 含 5 个模拟卷路径；迁移版本 15；真实数据库基线保留 |

## AI 评测

当前真实报告：[`evals/reports/golden-20260813-150557.json`](../evals/reports/golden-20260813-150557.json)；[`evals/reports/latest.json`](../evals/reports/latest.json) 指向同一次成功运行。历史基线报告为 [`evals/reports/golden-20260811-130019.json`](../evals/reports/golden-20260811-130019.json)。

- coverage：`1.0`
- citation correctness：`1.0`
- importance agreement：`0.5`
- ungrounded rate：`0.0`
- structural validity：`1.0`
- source fidelity：`1.0`
- answer consistency：`1.0`
- source replication ratio：`0.0`
- PDF usability：`1.0`
- overall：`passed`

评测目录共 14 条案例，09–14 覆盖真题风格、教师例题、严格资料不足、通用知识补题、原七类题型和公式。报告使用 Schema 2.0，记录实际模型 `qwen3.7-plus`、embedding `text-embedding-v4`、OCR `qwen-vl-ocr-latest`、ASR `paraformer-realtime-v2`，总耗时 128608 ms。重要度一致率仍是观察项且当前没有通过阈值；其余有阈值指标全部通过。

第一次扩展运行 [`golden-20260813-150056.json`](../evals/reports/golden-20260813-150056.json) 如实保留：`sourceFidelity=0.9375`、overall `failed`。失败来自 `mock-general-knowledge-fill` 的固定输入同时给出两个可独立出题的课程事实，却要求至少一道通用知识题，要求互相矛盾。随后把样例缩减为一个课程事实并明确“两题覆盖不同知识点，缺口才使用且必须标记通用知识”，同时在评测提示中明确不得伪造课程来源；阈值与评分逻辑未降低，重新真实调用后得到上述通过报告。

## 复现命令

```powershell
# 后端完整测试（Docker 构建路径）
docker compose build backend

# 宿主机完整测试（真实 XeLaTeX 条件测试）
$env:XELATEX_EXECUTABLE='D:\texlive\2026\bin\windows\xelatex.exe'
Set-Location backend
.\mvnw.cmd -B -ntp -s .mvn\settings.xml test

# 前端单元、构建、E2E
Set-Location ..\frontend
corepack pnpm@10.18.3 test
corepack pnpm@10.18.3 build
.\node_modules\.bin\playwright.CMD test

# 管理清理只读核对
Set-Location ..
docker compose run --rm backend --finalweek.cleanup.enabled=true --finalweek.cleanup.dry-run=true
```

真实 Golden Case 会产生模型费用，运行方法见根 `README.md`。Playwright 也会调用真实模型；需要根目录本地 `.env` 配置有效 `BAILIAN_API_KEY`，该文件禁止提交。

## 运行边界

- 未执行正式压力测试、Safari/Firefox 矩阵、生产安全审计或 WCAG 2.2 AA 审计。
- Testcontainers 当前重点验证 MySQL CAS、计数和唯一约束；其余中间件主要由 Compose E2E、单元/集成测试共同覆盖。
- 管理清理仅实际运行 dry-run；没有获得授权执行真实删除。
- Golden Case 和 Playwright 结果依赖外部模型的当次输出，后续模型版本或服务状态变化时应重新运行，不能视为生产 SLA。

## 2026-08-23 真实课程资料问题修复回归

- 后端全量：113 tests，0 failures，0 errors，1 skipped。
- 定向覆盖：知识提纲上下文/修复、AUTO 总分后端分配、无效来源过滤、原题复制检测、问答 UUID 清理。
- 前端：5 tests passed；TypeScript 检查与 Vite production build 通过。
- `docker compose config --quiet` 通过；真实数据库课程资料的 PPTX、23 分钟录音、提纲和模拟卷质量仍列为镜像重建后的人工复测项。
## 2026-08-24 模拟卷分题型生成与综合题回归

- 宿主机后端全量：115 tests，0 failures，0 errors，1 skipped。
- 新增流水线测试验证两个题型中只有一个批次失败时，模型总调用为 3 次：合格题型 1 次、失败题型 2 次，合格题型不被重写。
- 选择题严格四选项、非选择题冗余 options 归一化、八类题型答案与评分、综合题 PDF 分区均有自动化覆盖。
- 前端 5 tests passed；`vue-tsc -b` 与 Vite production build 通过。
- `docker compose up -d --build backend frontend` 成功；两个容器均为 healthy，后端 `/actuator/health` 为 `UP`，容器前端产物包含“综合题”。
- 本轮未重新调用付费 Golden Eval；历史 Golden 报告仍只代表原七类题型，综合题真实模型质量待数据库课程人工复测。
## 2026-08-25 试卷 PDF 排版回归

- 后端全量测试通过；TeX 渲染定向单元测试通过。
- 使用宿主机 XeLaTeX 对试卷与参考答案各执行两遍真实编译，两个 PDF 均可由 PDFBox 打开并渲染第一页 PNG。
- 首次真实编译发现宿主机缺少 `needspace.sty`，已移除该额外依赖并改用 TeX 内置 `\filbreak`；再次编译通过，验证模板不依赖未安装宏包。
- 人工检查导出第一页：题号、题干、右侧分值、四个选项和题型分区均保持独立，未复现多道判断/填空题拼成连续段落的问题。
- 样例产物位于 `tmp/mock-layout-v2/`，仅作为本地视觉回归产物；真实历史 PDF 不被覆盖，新生成任务使用模板版本 `v2`。
## 2026-08-25 知识提纲树节点布局回归

- 复现根因：自定义节点由单行改为标题/来源两行后，没有覆盖 Element Plus el-tree 的固定 content 高度，导致相邻节点内容纵向叠加；重要度下拉框的 auto 左外边距又把控件推到卡片最右侧。
- 修复后树节点 content 使用 auto 高度，展开箭头与首行对齐；标题、重要度和人工调整状态在同一主行，来源在次级行，删除来源编号和旧的右推布局。
- 前端 5 tests passed；vue-tsc 与 Vite production build 通过。
- Docker 前后端重新构建并均为 healthy；运行中课程页 CSS 分包已验证包含 el-tree-node__content height:auto，且不再包含 importance margin-left:auto。
- Chrome 自动验收连接受当前 Windows sandbox helper 刷新故障影响，未伪装为已完成；仍需在用户浏览器强制刷新后做最终截图确认。
