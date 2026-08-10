# Implementation Plan — FinalWeek（求职版 MVP）

## Implementation Rule

每一阶段都必须形成可运行结果和对应测试。不要同时铺开所有模块，也不要在核心闭环未完成前加入 Outbox、微服务、复杂 Agent、生产级监控等扩展设计。

## Phase 1 — Project Scaffold and Docker

### Goal

建立 Vue + Spring Boot 单体和可一键启动的完整本地应用。

### Tasks

1. 初始化 Vue 3/Vite/TypeScript/Element Plus 前端。
2. 初始化 Java 21/Spring Boot 3.5/Maven 后端。
3. 按 auth、course、upload、material、task、knowledge、outline、plan、chat、ai 建立包结构。
4. 配置 Docker Compose：Vue 前端、单实例 Spring Boot 后端、MySQL、Redis、RabbitMQ、MinIO、Qdrant、Mailpit；Web API 与 consumer 在同一后端进程。
5. 配置 Flyway、结构化日志、Actuator、统一错误响应和 `.env.example`。
6. 建立 JUnit、Testcontainers、Vitest 和 Playwright 基础。

### Done When

- 干净环境执行 `docker compose up` 可启动前端、后端及全部依赖；另行记录仅启动中间件的开发模式。
- Spring Boot health 成功，前端能调用测试 API。
- 所有镜像和依赖使用固定版本，无密钥提交。

## Phase 2 — Login, Course and Authorization

### Goal

完成真实登录和用户隔离，为后续所有资源建立权限基线。

### Tasks

1. 实现邮箱验证码发送、Redis TTL、请求限流和 Mailpit 测试。
2. 实现 Spring Session、HttpOnly Cookie、CSRF 和退出登录。
3. 实现课程创建、列表、详情、重命名、逻辑删除和 8 门上限。
4. 建立课程四区域路由和桌面/手机基础布局。
5. 为课程资源实现统一 ownership 校验。
6. 实现 Redis 故障门禁：登录和全部需要认证的接口统一返回 HTTP 503，静态落地页与无需认证健康检查仍可访问。

### Done When

- 用户可通过 Mailpit 验证码登录并创建课程。
- 用户 A 无法访问用户 B 的课程。
- 登录错误、验证码过期、限流和 Session 失效测试通过。
- Redis 不可用测试覆盖登录、普通已认证查询和 AI 接口，三者均返回稳定 `SERVICE_REDIS_UNAVAILABLE`。
- 课程删除接口预留任务状态检查；Phase 4 接入任务引擎后补齐“取消未开始任务、执行中返回 409”的事务语义。

## Phase 3 — Chunk Upload and Resume

### Goal

实现与 DoVideoAI 同等级的 GB 级分片上传、弱网恢复和合并幂等。

### Tasks

1. 实现上传初始化、Redis meta/Set/completed key；TTL 使用首版可配置默认值 24 小时。
2. 实现 MinIO 临时分片写入，遵守“先 MinIO 成功、再记录 Redis”。
3. 实现上传状态查询和前端差集续传。
4. 实现 Redisson 合并锁、顺序合并、完整哈希和重复 complete。
5. 实现 PDF、PPTX、TXT/MD、MP3、MP4 类型与大小校验。
6. 实现同课程哈希重复检测。
7. 实现过期上传和孤立临时分片清理任务。
8. 实现资料删除状态约束：成功资料禁止单独删除；待投递、投递失败、排队、失败、取消可删除，其中排队状态先 CAS 取消；处理中/重试中拒绝删除。

### Done When

- 上传在 30%、70%、99% 中断后只补传缺失分片。
- 重复分片和重复 complete 不产生重复文件。
- 两个并发 complete 只生成一条资料记录。
- 最终文件哈希与原文件一致。
- 成功资料单独删除返回 `409 MATERIAL_DELETE_FORBIDDEN`；整门课程删除在 Phase 4 接入任务状态检查后按统一事务规则执行。

## Phase 4 — RabbitMQ Task Engine and SSE

### Goal

把长耗时解析移出 HTTP 请求，并完成状态、重试、幂等和可观察闭环。

### Tasks

1. 建立 parse_task、task_checkpoint、failed_task 表和状态机，分别保存 `publish_attempt_count`、`delivery_attempt_count`、`api_attempt_count`，并增加 `manual_retry_count`、`execution_round`。
2. 配置 RabbitMQ durable exchange/queue、confirm、manual ack、重投和失败队列。
3. 上传完成后先写 `PENDING_PUBLISH` 任务，再用 publisher confirm 条件推进为 `QUEUED`；失败条件推进为 `PUBLISH_FAILED`，实现显式重新投递和滞留任务扫描补偿。confirm 回调不得倒退已进入处理或终态的任务。
4. 消费者通过 MySQL CAS、Redisson 锁、business_key 和终态检查处理重复消息；允许 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED`、`RETRYING` 抢占为 `PROCESSING`，覆盖消息早于 confirm 回写或 confirm 超时后延迟到达的竞态；结果幂等写入后最后提交 checkpoint。
5. 外部 API 调用最多执行 3 次（首次 + 2 次重试），任务每个 `execution_round` 最多执行 3 次（首次消费 + 2 次重投）；`PUBLISH_FAILED` 重新投递复用当前轮次并以 CAS 回到 `PENDING_PUBLISH`，普通用户对终态 `FAILED` 手动重试则复用原任务/checkpoint、开启新轮次预算并增加手动重试计数，旧轮次消息不得消耗新预算。
6. 实现 Redis 任务进度和 SSE；断线后 REST 恢复终态。
7. 实现 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED` 状态的 CAS 取消，并覆盖取消与消费抢占并发。
8. 在所有 RabbitMQ 投递前实现用户级和全局 RRateLimiter，覆盖资料解析、提纲生成、重新投递与手动重试。
9. 完成课程删除与任务状态联动：在同一事务中先检查执行中任务，存在 `PROCESSING`/`RETRYING` 时返回 `409 COURSE_TASK_RUNNING` 并回滚；否则取消未开始任务并逻辑删除课程。

### Done When

- 请求无需等待解析即可返回任务 ID。
- 重复消息不生成重复业务结果。
- SSE 失败不影响任务，重连后能恢复最终状态。
- 三次失败进入失败表/队列，可手动重新投递。
- publisher confirm 失败不会产生“实际无消息但显示排队”的任务；重新投递沿用原任务。
- 取消、重新投递和 consumer 抢占只有一个 CAS 成功，已取消消息只 ack 不执行。
- confirm 回调与早到消费者并发时状态不倒退；手动重试沿用原 taskId/checkpoint 且旧轮次消息不会污染新预算。
- 资料解析和提纲生成超过 RabbitMQ 限流时均不投递消息。
- 上传 complete 遇到限流时保留 material/task 并进入可重投的 `PUBLISH_FAILED`；提纲生成遇到限流时返回 429 且不创建任务。

## Phase 5 — PDF/PPT/Text/Audio/Video Parsing

### Goal

把全部支持格式转换为带来源位置的 `CourseSegment`。

### Tasks

1. 定义统一 CourseContext/CourseSegment 数据结构。
2. PDFBox 提取 PDF 文本和页码；扫描页渲染并调用 OCR。
3. POI 提取 PPTX 文本和幻灯片号；需要时使用 LibreOffice 渲染后 OCR。
4. TXT/MD 按段落提取。
5. FFmpeg 提取并切分音频，通过 `paraformer-realtime-v2` Java 本地文件/音频流接口调用 ASR 获取时间戳；验证整个流程不依赖公网 MinIO URL。
6. MP4 执行场景变化检测、固定间隔保底抽帧、感知哈希去重和 OCR。
7. 按时间轴合并 ASR 与 OCR；单路失败时保留另一条并记录警告。
8. 完成 `CONTENT_EXTRACTED` 阶段 checkpoint。
9. 为 PDF/PPTX 生成确定性对象键的归一化预览 PDF；为 MP3/MP4 验证 MinIO Range 读取。
10. 实现 `GET /materials/{materialId}/preview` 与 `GET /segments/{segmentId}` 的权限校验、短期 URL 和来源定位。

### Done When

- 五种格式均有真实样例和失败样例。
- PDF/PPT 引用能返回页码/幻灯片，音视频引用能返回时间点。
- ASR/OCR 单路失败不会丢失另一条有效结果。
- PDF/PPTX 来源可以定位到归一化预览页，音视频来源可以通过 Range 跳转到时间点。
- 不把复杂视觉内容包装成已支持能力。

## Phase 6 — Chunking, Embedding and Hybrid Retrieval

### Goal

建立课程级 RAG，并把关键词 + 向量混合检索作为必做能力。

### Tasks

1. 实现语义优先、固定上限和少量 overlap 的分块，以 `(material_id, chunk_no)` upsert，完成 `CHUNKED` checkpoint。
2. 实现百炼 Embedding 批量调用和确定性 Qdrant point ID。
3. 实现用户、课程、资料过滤字段和越权测试。
4. 使用 Lucene 建立课程级 BM25 索引：正常解析获取 `lucene-index:{courseId}` Redisson 锁，以确定性 document ID 对本资料全部 chunk 增量 bulk upsert、commit 后释放；同课程并发资料串行提交。另行实现启动核对、MySQL 全量重建和课程删除清理，完整重建只用于初始化/故障修复且同样持锁；明确只支持单后端实例。
5. 实现可配置的向量 TopK、BM25 TopK、RRF_K（首版默认 60）融合和 segment 去重。
6. 实现单路检索不可用时的降级。
7. 全部索引成功后完成 `EMBEDDING_COMPLETED` checkpoint，再原子推进任务/资料为 `COMPLETED`/`SUCCEEDED`。
8. 只有资料状态为 `SUCCEEDED` 后才允许检索其 segment，验证索引阶段失败或 checkpoint 与终态之间的窗口不会暴露部分结果。

### Done When

- 同义表达能通过向量召回，精确术语能通过 BM25 召回。
- RRF 结果可解释并回到 MySQL 获取原文和位置。
- 用户/课程隔离测试通过。
- 重复消费只覆盖同一 Qdrant point 和 Lucene document。
- 同课程资料 A/B 并发解析后 Lucene 同时包含两者，不出现旧快照覆盖；普通解析测试不触发全量重建。
- 首次启动或索引损坏后可从 MySQL 全量重建，且 Compose 始终只有一个后端实例。

## Phase 7 — Knowledge Outline

### Goal

生成带高/中/低重要度和精确来源的树状课程提纲，并允许人工调整重要度。

### Tasks

1. 定义提纲 JSON Schema 和 Java 校验 DTO。
2. 提示词纳入学生重点说明、教师强调语句和真题/题库信号。
3. 使用混合检索构造有来源的课程上下文。
4. 实现异步提纲任务、一次格式修复、引用归属校验和按生成版本 CAS 原子替换；通过 MySQL 活动任务 guard 保证每课最多一个活动提纲任务，重复请求返回现有任务；使用独立 `CONTEXT_RETRIEVED → OUTLINE_GENERATED → COMPLETED` checkpoint，不复用资料解析阶段。
5. 实现可折叠树、来源侧栏和重要度人工调整。
6. 再生成前提示覆盖当前提纲和人工重要度。
7. 通过 SSE 展示提纲任务进度；验证资料解析 `COMPLETED` 不会自动创建提纲。

### Done When

- 每个生成知识点至少有一个有效来源。
- 用户可调整重要度，刷新后仍保留。
- 失败生成不覆盖当前提纲。
- 并发点击生成只创建一个活动任务，旧生成版本不能覆盖较新版本。
- 首版没有结构编辑或智能合并入口。

## Phase 8 — Simple Daily Study Plan

### Goal

把原“有时间再做”的简单计划纳入必做，但严格控制功能范围。

### Tasks

1. 实现考试日期、每日分钟数、掌握程度和目标成绩表单。
2. 基于当前提纲和重要度生成每日任务 JSON。
3. 校验总预计时间不超过可用时间。
4. 建立 `plan_generation_request` 并要求客户端幂等键；以当前计划唯一约束和版本 CAS 保存 study_plan、plan_task，重复同一幂等键不再次调用模型。
5. 实现按日期展示、完成和撤销完成。
6. 再生成前提示覆盖，失败时保留旧计划。
7. 使用同步 REST 和可配置 `PLAN_REQUEST_TIMEOUT_SECONDS`；调用前执行 `PLAN_USER_RATE_PER_MINUTE` 用户级限流；不投递 RabbitMQ、不创建异步任务、不发送 SSE；超时/失败不覆盖旧计划，重试前重新获取当前计划并复用同一幂等键。

### Done When

- 可以生成并执行完整的每日计划。
- 没有提纲、考试已过、时间为零时不能提交。
- 不存在自动重排、任务拖动、跳过或差异预览。
- 超时后重新获取当前计划，重复提交不会产生多份“当前计划”。
- 并发不同请求使用计划版本 CAS，较旧请求不会覆盖较新的当前计划；触发计划限流时不调用模型。

## Phase 9 — Course Text Q&A and Rate Limiting

### Goal

提供基于混合检索的课程文字问答，并把 Redis 限流作为必做成本保护。

### Tasks

1. 实现每门课程一条连续聊天历史和游标分页。
2. 实现用户级问答 RRateLimiter；RabbitMQ 任务限流已在 Phase 4 完成，计划限流已在 Phase 8 完成。
3. 在模型调用前执行课程混合检索。
4. 生成区分课程资料和通用知识补充的结构化回答。
5. 校验引用确实属于当前上下文、用户和课程。
6. 实现失败消息重试和来源跳转。
7. 问答使用非流式同步 REST 和可配置 `CHAT_REQUEST_TIMEOUT_SECONDS`；不投递 RabbitMQ、不发送 SSE；以 `PENDING → SUCCEEDED/FAILED` 消息状态及基于原消息 ID 的重试防止重复用户消息。
8. 测试 Redis 不可用时全部需要认证的接口 fail closed。

### Done When

- 课程问题能返回带来源回答。
- 没有证据时明确说明，不伪造引用。
- 通用知识单独标记。
- 限流后的问答请求不进入模型 API；回归验证 Phase 4 的资料解析/提纲任务不投递 RabbitMQ，以及 Phase 8 的计划生成不进入模型 API。

## Phase 10 — AI Golden Cases

### Goal

把简单 AI 评测集作为正式交付，而不是只设计不运行。

### Tasks

1. 在 `evals/cases` 准备首版默认至少 8 个固定案例：PDF、扫描 PDF、PPT、音频、视频互补、真题重点、可回答问题、不可回答问题；案例数是可配置默认值。
2. 在 `evals/annotations` 标注核心知识点、期望重要度和正确来源，在 `evals/fixtures` 保存可复现输入清单/哈希。
3. 提供 `./mvnw -Pgolden-eval verify`（Windows：`mvnw.cmd -Pgolden-eval verify`）运行提纲和问答，在 `evals/reports` 输出 JSON；`evals/report.schema.json` 至少约束运行时间、提交版本、案例/输入版本、实际模型 ID、配置快照、逐案例输出与评分、汇总指标和失败原因。
4. 计算覆盖率、引用正确率、重要度一致率和无依据陈述比例。
5. 把实际结果和失败案例写入 README；未达目标时保留真实数据并分析原因。

### Done When

- 8 个案例均可重复执行。
- 有通过 schema 校验的 JSON 评测报告，可选生成 Markdown 摘要。
- 首版默认阈值为覆盖 ≥80%、引用正确 ≥90%、无依据且未标记陈述 ≤10%；阈值可配置且必须写入报告，未达标时如实记录结果与修复过程。

## Phase 11 — Integration, Fault Tests and Portfolio Delivery

### Goal

形成能运行、能演示、能被面试追问的完成项目。

### Tasks

1. 完成核心单元测试和 Testcontainers 集成测试。
2. 完成登录 → 建课 → 上传 → 解析 → 提纲 → 计划 → 问答的 Playwright E2E。
3. 注入分片失败、并发 complete、publisher confirm/消费者竞态、MQ 重投/重复消息/旧执行轮次、取消抢占、同课程 Lucene 并发写入、课程删除遇到执行中任务、第三方 429/5xx、Redis 不可用、SSE 断线和同步 REST 超时。
4. 记录任务提交耗时、各解析阶段耗时、恢复跳过阶段和模型调用次数。
5. 完成桌面 Chrome/Edge 演示检查和手机基本可用检查。
6. 编写 README：架构图、系统流程、截图、运行说明、真实指标、已知边界。
7. 编写独立面试问答，覆盖技术选型、异常流程、当前不足和 AI Coding 个人贡献。
8. 提供支持 dry-run 的管理员清理命令，清理已删课程及孤儿 MinIO 对象、Qdrant point、Lucene document，并输出处理统计。

### Done When

- Docker 环境可一键启动，主流程可现场演示。
- 故障测试证明断点续传、MQ 幂等、checkpoint 和 SSE 恢复有效。
- 清理命令可重复执行，且不会删除仍归属于有效课程的对象或索引。
- 所有写进简历的数字都能由测试脚本复现。
- README 明确当前没有公网流量、生产 SLA、复杂 Agent 和生产级数据清除。
- 六份文档、代码、README 和面试话术不存在能力夸大或历史方案不一致。
