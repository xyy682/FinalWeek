# FinalWeek 开发交接（2026-08-11 22:00，Asia/Shanghai）

## 当前结论

FinalWeek 已按 `IMPLEMENTATION_PLAN.md` 完成 Phase 1～11。六份权威约束仍为：

- `PRD.md`
- `TECH_STACK.md`
- `BACKEND_STRUCTURE.md`
- `FRONTEND_GUIDELINES.md`
- `APP_FLOW.md`
- `IMPLEMENTATION_PLAN.md`

仓库：`https://github.com/xyy682/FinalWeek.git`

本地工作区：`D:\学习项目\FinalWeek`

Windows 命令优先使用 PowerShell 7：`D:\PowerShell\7\pwsh.exe`。

根目录本地 `.env` 包含真实 `BAILIAN_API_KEY` 并已被忽略。绝对不要打印、提交或复制密钥。管理员清理只实际执行过 dry-run；没有授权真实删除数据。

## 阶段提交

| 阶段 | 提交 | 内容 |
| --- | --- | --- |
| Phase 1 | `92d71a5` | 项目骨架 |
| Phase 2 | `d7adcce` | 邮箱验证码登录、课程 |
| Phase 3 | `34cdc1d` | 可恢复分片上传 |
| Phase 4 | `8a377ac` | RabbitMQ 任务引擎 |
| Phase 5 | `1144d7f` | 文档/媒体内容提取 |
| Phase 6 | `839e3a4` | Qdrant + Lucene 混合检索 |
| Phase 7 | `94757d3` | 知识提纲 |
| Phase 8 | `7640372` | 复习计划 |
| Phase 9 | `d88c65f` | 有来源约束的课程问答 |
| Phase 10 | `0186a5a` | 黄金集与真实模型评测 |
| Phase 11 | 见包含本文的最新提交 | 集成、故障测试、可观测性、清理与作品集交付 |

## Phase 11 交付

- 管理员清理：`AdminCleanupService` + 条件启用 runner，默认 dry-run；以 MySQL 活跃课程/资料/segment 为白名单核对已删课程、MinIO、Qdrant 和 Lucene。runner 最后执行并优雅退出。
- 可观测性：任务提交、consumer 总耗时/终态/delivery/API 次数、解析 extraction/chunking/indexing、提纲 context/generation 均记录结构化阶段日志及 checkpoint skip。
- 故障测试：第三方 429/503/400、publisher confirm、MQ 重投预算/重复/旧轮次、consumer/取消 CAS、课程删除运行任务、Lucene 并发、Redis fail-closed、SSE 断开、计划/问答同步超时。
- Testcontainers：真实 MySQL 8.4.10 + 9 个 Flyway migration，验证并发 claim、取消竞争、delivery count 与 checkpoint 唯一键。
- Playwright：登录 → 建课 → TXT 上传 → 主动断开 SSE → REST 恢复 → 刷新 → 提纲 → 计划 → 问答 → 来源抽屉；另有 Chrome/Edge landing 和 Pixel 7 移动检查。
- 作品集：根 `README.md`、`docs/TEST_REPORT.md`、`docs/INTERVIEW_QA.md` 与两张真实 E2E 截图。

## 最终实测

- 后端 Docker 构建：67 tests，0 failures，0 errors，1 skipped。skipped 是普通 Docker 构建无 Docker socket 的 Testcontainers；宿主机该测试已真实通过（1/1，约 2 分 40 秒）。
- 前端 Vitest：5 passed。
- 前端生产构建：通过；单 chunk >500 KB 是非阻断警告。
- Playwright：9 个项目化用例，5 passed、4 designed skips、0 failed，总耗时 22.4 秒。
- Docker Compose：8 个服务启动；backend health `UP`，公开 ping `ok`，最终 `docker compose ps` 应保持全部 healthy。
- 管理清理 dry-run：退出码 0，最后统计：

```text
FINALWEEK_CLEANUP_STATS={"dryRun":true,"deletedCourses":2,"orphanMinioObjects":0,"orphanQdrantPoints":0,"orphanLuceneCourses":0,"orphanLuceneDocuments":0,"rebuiltActiveCourses":0}
```

- 截图：`docs/images/finalweek-course-chat.png`、`docs/images/finalweek-mobile-login.png`。

## 真实 AI 评测

`evals/cases` 有 8 个固定案例，fixture 运行前校验 SHA-256，报告通过 JSON Schema。使用 `qwen3.7-plus` 的真实结果：

- coverage：`1.0`
- citation correctness：`1.0`
- importance agreement：`0.333333`（明确薄弱项）
- ungrounded rate：`0.0`
- overall：passed

报告：`evals/reports/golden-20260811-130019.json` 与 `evals/reports/latest.json`。

## 重要边界

- 本项目是本地求职 MVP，没有公网流量、生产 SLA、压测结论或生产级合规清除。
- Spring Boot Web 与 consumer 同 JVM，只支持单后端实例；Lucene 是本地可重建索引。
- 不支持复杂 Agent、多模型路由、联网搜索、Cross-Encoder、生产监控栈或自动备份。
- OCR/ASR 不承诺复杂公式/图表、潦草手写或纯视觉动作。
- Golden Case 重要度一致率只有 33.3%，后续应改善 rubric/提示词与案例，而不是隐藏结果。

## Git 与磁盘

- `.env`、`.m2-it-cache/`、`.playwright-browsers/`、`frontend/test-results/` 和 Playwright HTML report 均被忽略。
- Playwright Chromium 缓存位于项目 D 盘 `.playwright-browsers/`，避免新增 C 盘浏览器缓存。
- Docker 数据位于 `D:\DockerData`；不要未经许可清理用户 C 盘缓存或 Docker 卷。
- 若后续推送仍遇到网络 reset，应报告真实错误，不要伪称已经推送。
