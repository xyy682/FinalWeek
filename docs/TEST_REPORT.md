# FinalWeek 测试报告

更新时间：2026-08-11（Asia/Shanghai）

本报告记录 Phase 11 实际执行结果和复现入口。数字来自本地求职版 MVP 环境，不代表公网性能或生产 SLA。

## 结果摘要

| 层级 | 实际结果 | 说明 |
| --- | --- | --- |
| 后端 JUnit | 67 tests，0 failures，0 errors | 普通 Docker 构建跳过 1 项无 Docker socket 的 Testcontainers 测试 |
| MySQL Testcontainers | 1 test，0 failures，0 errors，0 skipped | 宿主机 Docker Desktop + MySQL 8.4.10，9 个 Flyway migration，约 2 分 40 秒 |
| 前端 Vitest | 5 passed | 包含 30%/70%/99% 断点续传差集计算 |
| 前端生产构建 | 通过 | 类型检查通过；单 chunk >500 KB 为非阻断警告 |
| Playwright | 5 passed，4 skipped，0 failed | 9 个项目化用例，22.4 秒；Chrome/Edge/Pixel 7 |
| Golden Cases | 8/8 执行成功，overall passed | `qwen3.7-plus` 真实调用，报告通过 JSON Schema |
| 管理清理 | dry-run 退出码 0 | 未执行真实删除 |
| Docker Compose | 8 个服务 healthy | frontend/backend/MySQL/Redis/RabbitMQ/MinIO/Qdrant/Mailpit |

Playwright 的 4 个 skipped 是按项目设计：昂贵的真实 AI 全链路只在 Desktop Chromium 执行，移动布局场景只在 Pixel 7 执行；landing smoke 在三项目均执行。

## Phase 11 故障覆盖矩阵

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
| 同步计划超时 | `PlanServiceTest.synchronousTimeout...` | HTTP 504 语义，请求失败且旧计划不被覆盖 |
| 同步问答超时 | `ChatServiceTest.synchronousTimeout...` | HTTP 504 语义，原问题落为失败且可重试 |
| 清理误删 | `AdminCleanupServiceTest` + 真实 dry-run | 活跃 MySQL 白名单不删除；默认 dry-run |

## AI 评测

真实报告：`evals/reports/golden-20260811-130019.json` 与 `evals/reports/latest.json`。

- coverage：`1.0`
- citation correctness：`1.0`
- importance agreement：`0.333333`
- ungrounded rate：`0.0`
- overall：`passed`

重要度一致率是当前明确薄弱项：部分无教师强调/真题信号的顶层概念被模型判为高，而人工标注为中。结果未被人工修改或包装。

## 复现命令

```powershell
# 后端完整测试（Docker 构建路径）
docker compose build backend

# 单独运行宿主机 Testcontainers，Maven 缓存留在 D 盘项目内
$env:MAVEN_OPTS='-Dmaven.repo.local=D:\学习项目\FinalWeek\.m2-it-cache'
Set-Location backend
.\mvnw.cmd -B -ntp -s .mvn\settings.xml '-Dtest=TaskStateMySqlIntegrationTest' test

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
