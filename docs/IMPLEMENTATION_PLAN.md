# Implementation Plan — FinalWeek（求职版 MVP）

> 文档状态：Phase 1–16 已完成。Phase 12–16 已通过代码、迁移、单元/容器测试、真实 XeLaTeX、浏览器主链路与 14 条真实百炼 Golden Case 验证；实际结果见 `docs/TEST_REPORT.md`。

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

> 本阶段记录首版同步实现；Phase 13 已将生成编排迁移为后台任务，并保留本阶段的幂等、预算和计划版本 CAS 约束。

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

> 本阶段记录首版同步实现；Phase 13 已将模型处理迁移为隐藏后台任务，并保留本阶段的消息幂等、来源白名单和重试约束。

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

## Phase 12 — Course Knowledge Versions and General Task Engine（已完成）

### Goal

把“成功资料集合”固化为不可变课程知识版本，并把现有解析/提纲任务模型扩展为计划、模拟卷和答疑都能复用的通用后台任务。

### Tasks

1. 设计 Flyway 迁移：保留现有任务数据并把 `parse_task` 规范化为 `background_task`，迁移 checkpoint/failed task 外键与持久化模型。
2. 增加 `course_knowledge_version` 和版本资料关联表；以排序后的资料业务键计算集合哈希，建立课程版本唯一约束和当前发布指针。
3. 将提纲关联到知识版本并内部保留旧版；新版发布时旧版转为 `SUPERSEDED`，当前接口仍只展示唯一 `PUBLISHED` 版本。
4. 实现“资料已上传完毕”：服务端锁课程、拒绝活动资料任务和空成功集合、允许明确忽略失败资料、阻止相同集合重复确认。
5. 确认成功后自动创建提纲任务；取消无新增资料时的手动重新生成，失败任务支持原版本重试且不覆盖旧提纲。
6. 扩展任务类型、合法 checkpoint、publisher confirm、manual ack、旧执行轮次、任务限流、排队取消和课程删除锁定逻辑。
7. 增加当前用户活动任务 REST 和 SSE 事件；任务响应携带课程名、跳转目标及 `visibleInGlobalDrawer`。

### Done When

- 一批成功资料只能产生一个知识版本，并发确认不会重复建版本或调用模型。
- 活动、失败和取消资料的确认边界符合目标 PRD；新版本失败时旧版保持当前。
- 旧提纲数据不会因发布新版被删除，人工重要度不跨版本继承。
- 现有资料解析/提纲任务在迁移后行为与测试不回退。
- 只有排队任务能取消；consumer claim 与取消竞争只有一方成功。

## Phase 13 — Async Plan, Background Chat and Global Task UX（已完成）

### Goal

让耗时能力不阻塞页面导航，同时保持计划 CAS 和答疑引用约束。

### Tasks

1. 计划生成改为 RabbitMQ 后台任务，保留幂等请求哈希、唯一当前计划、时间预算校验和计划版本 CAS。
2. `study_plan` 保存知识版本；当前版本更高时返回过期标记，旧计划仍可勾选，新计划成功才替换。
3. 答疑提交事务保存 `PENDING` 消息和隐藏后台任务；检索全部成功资料，结果按原消息 ID 发布/失败/重试。
4. 前端顶部增加任务图标和只含活动任务的抽屉，覆盖资料、提纲、计划和模拟卷；实现跨课程跳转、排队取消与 SSE/REST 恢复。
5. 答疑不进入任务抽屉，但在线终态触发提示；关闭页面或离开路由不取消服务端任务，离线不补发通知。

### Done When

- 计划接口返回 `202 + taskId`，用户离页后仍能完成；提纲或计划版本竞争不会覆盖旧计划。
- 答疑离页后能从历史恢复，立即使用所有成功资料，不产生重复用户消息且不出现在活动任务列表。
- 用户可同时查看不同课程的后台任务；终态从抽屉移除，业务页面保留真实结果。
- 原有计划表单、任务完成/撤销和聊天来源跳转保持不变。

## Phase 14 — Mock Exam Domain and Generation Pipeline（已完成）

### Goal

基于当前课程知识版本异步生成结构化模拟卷，并可靠区分课程依据与必要的模型通用知识。

### Tasks

1. 建立 `mock_exam`、`mock_exam_question`、`mock_exam_question_source`、重试关联和逻辑删除模型。
2. 实现创建、详情、分页历史、修改后重试、排队取消和终态逻辑删除接口；同课程活动出卷任务上限为 1。
3. 定义七类题型 JSON Schema/DTO；校验逐题型题数、1–50 总题数、整数分值/1000 上限、可选 1–300 分钟时长和 2000 字补充说明。
4. 固定任务的知识版本和提纲范围；整课使用版本全部资料，父节点范围展开完整子树。
5. 构造出题上下文：用户要求优先，真题类型+内容识别其次，自动识别教师例题再次；只仿风格，不复刻原题。
6. 默认课程资料优先，仅在用户允许且确有缺口时使用模型内置通用知识；禁止联网，严格模式资料不足则失败并说明缺口。
7. 校验题型、选项、答案、分值、公式、课程来源 ownership 和通用知识标识；对源题复刻做硬拒绝，对历史近似及范围覆盖不足记录警告。
8. 使用独立 checkpoint：`REQUIREMENTS_ANALYZED → QUESTIONS_GENERATED → PAPER_VALIDATED → PDFS_GENERATED → COMPLETED`。

### Done When

- 七类题型均能产生可验证结构化题目和精简参考答案，课程内题逐题具有合法内部来源。
- 用户禁止通用知识时不会静默补题或减少题量；允许时只补缺口并正确标识。
- 生成期间课程发布新版不改变任务输入，成功结果保留原知识版本。
- 失败、取消和修改后重试均保留不可变历史，ownership 和课程隔离覆盖全部接口。

## Phase 15 — Secure XeLaTeX PDFs and Mock Exam UI（已完成）

### Goal

把同一份已校验题目原子渲染为可打印试卷和参考答案，并完成课程第五区域的完整交互。

### Tasks

1. 在固定 Jammy 后端镜像安装锁定的 XeLaTeX、必要宏包与 Noto CJK 字体；运行时不联网下载依赖。
2. 建立受版本控制的 A4 试卷/参考答案模板、统一 TeX 文本转义和数学命令白名单。
3. 以非特权用户、独立临时目录、`-no-shell-escape`、超时和大小限制编译；使用 PDFBox 做打开、页数和基础结果校验。
4. 两个 PDF 使用确定性 MinIO 对象键。只有两者上传并复核成功才原子发布；部分对象进入清理而不对用户可见。
5. 新增 `/courses/:id/mock-exams` 标签和同页表单/分页历史；实现范围树、七类题型数量、分值模式、可选总分/时长、通用知识开关和补充说明。
6. 历史默认显示名称、状态、时间和操作；错误/警告按需展开。预览新标签页打开短时 URL，下载使用安全文件名。
7. 逻辑删除后立即隐藏记录，后台幂等清理对象；管理员 dry-run 增加孤儿模拟卷 PDF 核对。

### Done When

- 中文、中英混排和常见公式在 A4 模板中稳定分页，危险 TeX 输入不能读取文件、执行 shell 或加载任意宏包。
- 试卷和参考答案题号、分值与答案严格一致；任一文件失败时用户看不到部分结果。
- 未填写总分/时长时卷首省略字段；PDF 不显示生成日期、知识版本或 AI 标识。
- 成功记录可预览下载，失败/取消可修改后重试，运行中不可删除，逻辑删除与课程删除均能最终清理文件。
- 手机端表单、历史卡片和任务抽屉基本可用，所有状态有文字标签。

## Phase 16 — Target-State Tests, Evals and Delivery（已完成）

### Goal

为重构后的课程闭环留下可复现证据，并在实现真正通过前保持 README 与对外描述不变。

### Tasks

1. 补齐知识版本、通用任务、计划/答疑异步化、模拟卷领域、TeX 安全和清理的单元与 Testcontainers 测试。
2. 扩展 Playwright：上传多份资料 → 确认 → 自动提纲 → 跨页面任务 → 异步计划 → 离页答疑 → 模拟卷 → 双 PDF 操作。
3. 覆盖并发确认、任务取消/消费竞争、提纲与计划版本竞争、SSE 断线、同课程出卷冲突、双文件部分失败、逻辑删除清理和越权访问。
4. 扩展 Golden Case：真题风格、教师例题、严格资料不足、通用知识补题、七类题型、公式、答案一致性和原题复刻检查。
5. 在真实 Docker Compose 中验证 XeLaTeX 镜像、中文字体、MinIO 预览下载、RabbitMQ 新任务和完整跨课程导航。
6. 只有所有结果真实运行后，才更新 README、测试报告、截图、测试数量和当前可运行能力；如实记录失败指标与边界。

### Done When

- 六份权威文档、代码、OpenAPI、数据库迁移、README 和界面术语一致。
- 主流程和故障流程在 Docker 中可复现，报告能证明权限、幂等、版本快照和双 PDF 原子性。
- AI 评测分别报告结构合法性、课程来源忠实度、答案一致性、原题复刻和 PDF 可用性；未达目标不隐藏。
- README 只在目标态实际完成后把课程四区更新为五区，并明确仍不包含在线考试、评分、联网和图像题。
