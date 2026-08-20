# FinalWeek 测试报告

更新时间：2026-08-13（Asia/Shanghai）

本报告记录 Phase 16 最终实际执行结果和复现入口。数字来自本地求职版 MVP 环境，不代表公网性能或生产 SLA；外部百炼评测与真实 AI 浏览器链路均在用户明确授权后运行。

## 结果摘要

| 层级 | 实际结果 | 说明 |
| --- | --- | --- |
| 后端 JUnit | 105 tests，0 failures，0 errors，0 skipped | 宿主机启用 Docker 与真实 XeLaTeX；最终回归耗时 1 分 59 秒 |
| Docker 后端构建 | 105 tests，0 failures，0 errors，7 skipped | 7 项依赖宿主机 Docker/XeLaTeX 的条件测试跳过；镜像构建成功 |
| MySQL Testcontainers | 6 tests，0 failures，0 errors，0 skipped | 任务 CAS 2 项 + 知识版本并发/持久化/最终级联及清理再排队 4 项；MySQL 8.4.10 与 14 个 Flyway migration |
| 真实 XeLaTeX 模板 | 1 passed | Windows TeX Live 2026；中文、中英混排、公式、试卷/答案各两遍编译并由 PDFBox 打开 |
| 容器内 XeLaTeX | 通过 | Jammy、非 root `finalweek`、Noto Serif CJK SC、可写独立临时目录、`-no-shell-escape` 双 PDF 编译 |
| 前端 Vitest | 5 passed | 包含 30%/70%/99% 断点续传差集计算 |
| 前端生产构建 | 通过 | 类型检查通过；单 chunk >500 KB 为非阻断警告 |
| Playwright | 5 passed，4 skipped，0 failed | 9 个项目化用例；最终重新运行的真实 AI 主链路 53.9 秒，另 4 个非 AI 场景 4.0 秒；Chrome/Edge/Pixel 7 |
| Golden Cases（历史） | 8/8 执行成功，overall passed | 2026-08-11 `qwen3.7-plus` 真实报告，通过 JSON Schema |
| Golden Cases（扩展） | 14/14 执行成功，overall passed | 2026-08-13 `qwen3.7-plus` 真实报告；Schema 2.0 与全部阈值通过 |
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
| consumer/取消抢占 | `TaskStateMySqlIntegrationTest` | 两个真实 MySQL 并发 claim 仅一方成功，随后取消失败 |
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
| 相同资料集合重复确认 | 集合哈希唯一约束、课程锁、`KnowledgeVersionServiceTest`、`KnowledgeVersionJpaIntegrationTest` 双线程竞争 | 真实 MySQL 下仅创建一个不可变知识版本和一个提纲任务，另一请求返回集合未变化 |
| 版本化提纲迁移 | `KnowledgeVersionJpaIntegrationTest` | V10–V14 在 MySQL 8.4.10 生效，知识版本与提纲关系可持久化 |
| 通用任务迁移与取消竞争 | `TaskStateMySqlIntegrationTest`、`TaskStateServiceTargetTest` | 原任务数据迁移；可见性、checkpoint 和 claim/cancel CAS 有数据库证据 |
| 异步计划/答疑离页恢复 | pipeline/coordinator 测试 + `full-journey.spec.ts` | REST 先返回后台任务/`PENDING` 消息，离页后从业务接口恢复终态 |
| 七类题型和数值边界 | `MockExamRequestNormalizerTest`、`MockExamValidatorTest` | 七类 DTO/答案通过；1–50、1000 分、300 分钟、2000 字和整数溢出受控 |
| 严格资料与通用知识 | `MockExamValidatorTest` | 禁止通用知识时缺口整体失败；允许时逐题标记且不得伪造来源 |
| 越权和版本外来源 | `MockExamServiceTest`、`MockExamValidatorTest` | ownership 在读取对象前检查；跨知识版本 segment 被拒绝 |
| 原题复刻与历史近似 | `MockExamQualityCheckerTest` | 源题复刻硬失败；历史近似和范围覆盖不足记录警告 |
| 同课程出卷冲突 | `MockExamCoordinatorTest` | 课程锁下发现活动出卷任务即返回稳定冲突，不创建第二份卷或后台任务 |
| TeX 注入 | `TexEscaperTest`、`MockExamValidatorTest` | 文本统一转义；危险命令、`^^`、注释/参数绕过和非法公式位置被拒绝 |
| 试卷泄露答案 | `MockExamTexRendererTest` + 实际 PDF 目视检查 | 试卷只渲染题干公式，答案公式仅出现在参考答案 |
| 双 PDF 部分失败 | `XeLatexMockExamArtifactGeneratorTest` | 任何部分失败先确认对象清理，不发布单边结果 |
| PDF 可用性 | `XeLatexTemplateIntegrationTest` + 容器内实际编译 | 中文/公式、A4、两遍编译、PDFBox 页数和非空检查通过 |
| 逻辑删除和对象清理 | `MockExamObjectCleanupTest`、`MockExamCleanupServiceTest`、`KnowledgeVersionJpaIntegrationTest` | 删除立即隐藏；对象清理退避重试；已完成/失败清理记录可在稍后逻辑删除时安全重新排队；课程删除分离来源并登记双 PDF 清理 |
| 真实主流程 | Playwright `full-journey.spec.ts` | 两资料 → 知识版本 → 提纲 → 计划 → 离页答疑 → 2 题模拟卷 → 双 PDF 预览/下载通过 |
| 运行时契约 | `/v3/api-docs`、Flyway 表、Docker 健康检查 | OpenAPI 含 5 个模拟卷路径；迁移版本 14；真实数据库有 1 套成功卷和 2 道题 |

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

评测目录共 14 条案例，09–14 覆盖真题风格、教师例题、严格资料不足、通用知识补题、七类题型和公式。报告使用 Schema 2.0，记录实际模型 `qwen3.7-plus`、embedding `text-embedding-v4`、OCR `qwen-vl-ocr-latest`、ASR `paraformer-realtime-v2`，总耗时 128608 ms。重要度一致率仍是观察项且当前没有通过阈值；其余有阈值指标全部通过。

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
