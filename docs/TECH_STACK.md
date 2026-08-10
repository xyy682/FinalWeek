# Tech Stack — FinalWeek（求职版 MVP）

## Technical Goal

技术栈以 Java 后端求职为中心，保持和 DoVideoAI 相近的学习难度：一个 Vue 前端、一个 Spring Boot 应用，加上本地 Docker 中间件。所有解析、MQ 消费和 AI 编排均由 Spring Boot 完成，不拆 Python Worker 或微服务。

## Version Baseline

- Java 21 LTS。
- Spring Boot 3.5.x，Maven 3.9.x。
- Node.js 24 LTS，pnpm 10.x。
- Vue 3.5.x、Vite 8.x、TypeScript 6.0.x。
- Element Plus 2.14.x。
- MySQL 8.4 LTS。
- Redis 8.x。
- RabbitMQ 4.2.x。
- Qdrant 1.x 稳定版本。
- Docker Compose v2。

初始化时锁定精确补丁版本并提交锁文件；禁止容器使用 `latest` 标签。

## Frontend

- Vue 3 + Composition API + `<script setup>`。
- Vite + TypeScript strict mode。
- Vue Router：登录、课程、资料、提纲、计划、问答页面。
- Pinia：登录用户和少量界面状态；服务端数据以 API 返回为准。
- Element Plus：表单、上传、表格、树、进度、弹窗和反馈。
- 原生 `fetch` 项目封装：统一 CSRF、错误码和请求 ID。
- SSE：只推送资料解析和提纲生成进度；计划生成与课程问答使用同步 REST，不引入 WebSocket。
- Markdown：聊天回答只渲染经过清洗的 Markdown 子集。

## Spring Boot Application

### Required Dependencies

- Spring Web MVC：REST 与 SSE。
- Spring Security：Cookie Session、授权和 CSRF。
- Spring Session Data Redis：服务端 Session。
- Spring Data JPA：业务数据访问。
- Flyway：数据库迁移；禁止 `ddl-auto=update`。
- Spring AMQP：RabbitMQ 生产、消费、确认和失败队列。
- Redisson：上传合并锁、任务执行锁、课程级 Lucene 写锁和令牌桶限流。
- Spring Validation：接口参数校验。
- Spring Boot Actuator + Micrometer：健康检查与基础指标。
- springdoc-openapi：REST API 文档。

### Module Boundaries

- `auth`：邮箱验证码与 Session。
- `course`：课程和用户隔离。
- `upload`：分片上传、Redis 状态、MinIO 合并。
- `material`：资料元数据和内容提取。
- `task`：RabbitMQ、投递补偿、状态、重试、取消和按任务类型区分的阶段 checkpoint。
- `knowledge`：CourseSegment、分块、BM25、Embedding、Qdrant。
- `outline`：提纲生成、来源和重要度调整。
- `plan`：简单每日计划与完成状态。
- `chat`：课程文字问答和连续历史。
- `ai`：百炼 ASR、OCR、Embedding、LLM 适配器。

模块保持包级边界，不引入 Spring Modulith、微服务或远程内部调用。

## Document and Media Processing

- FFmpeg：音轨提取、音频切分、媒体信息读取和视频关键帧抽取。
- Apache PDFBox：PDF 原生文本、页码和扫描页渲染。
- Apache POI：PPTX 原生文本与幻灯片结构。
- LibreOffice headless：需要渲染 PPTX 页面时转换为 PDF。
- Java ImageIO/Thumbnailator：图片缩放、格式处理。
- 感知哈希库：关键帧去重；若引入必须限制为单一轻量实现。
- 不处理 DOC/DOCX，不引入 Apache Tika 作为全格式解析框架。

## Alibaba Cloud Bailian

通过统一 Java 接口封装供应商调用：

- `AsrClient`：通过支持本地文件/音频流输入的 Java 接口识别 FFmpeg 分段结果，默认 `paraformer-realtime-v2`；不要求百炼从公网 URL 回源本地 MinIO。
- `OcrClient`：扫描页、PPT 渲染页和视频关键帧 OCR。
- `EmbeddingClient`：默认 `text-embedding-v4`，初始 1024 维。
- `LlmClient`：首版默认 `qwen3.7-plus`，完成提纲、计划和问答。

要求：

- 模型 ID、API Key、端点、超时和重试次数均从环境配置读取。首版默认值为 `BAILIAN_ASR_MODEL=paraformer-realtime-v2`、`BAILIAN_OCR_MODEL=qwen-vl-ocr-latest`、`BAILIAN_EMBEDDING_MODEL=text-embedding-v4`、`BAILIAN_LLM_MODEL=qwen3.7-plus`；若百炼控制台不再支持某默认 ID，实施者必须在配置与评测报告中记录实际使用 ID，不得在代码中静默替换。
- 不在业务代码中散落百炼 SDK 调用。
- LLM 输出使用固定 JSON Schema 并由 Java DTO/Validator 校验。
- 普通自动化测试使用 Fake/Mock Client，不调用真实付费 API。
- 首版只接一家供应商，不实现多模型路由。

## Data and Middleware

### MySQL

- 保存用户、课程、资料、上传完成记录、解析任务、阶段 checkpoint、课程片段、提纲、计划、聊天和失败任务。
- MySQL 是任务终态和 checkpoint 真相源。
- 使用唯一索引、确定性对象键/向量 ID 和业务状态检查实现最终幂等。

### Redis

- 邮箱验证码与 Spring Session。
- 上传任务元数据、已完成分片 Set、上传 completed 标记。
- Redisson 锁和令牌桶限流，包括 `lucene-index:{courseId}` 课程级 Lucene 写锁。
- SSE 实时进度与短期缓存。
- Redis 不保存唯一业务结果；任务终态和提纲不能只存在 Redis。
- Redis 不可用时，登录及全部需要认证的后端请求返回 HTTP 503；静态落地页和无需认证的健康检查仍可访问。

### RabbitMQ

- 保存长耗时解析和提纲任务。
- durable exchange/queue、persistent message、publisher confirm、manual ack。
- 数据库先创建 `PENDING_PUBLISH` 任务，publisher confirm 成功后进入 `QUEUED`；投递失败进入 `PUBLISH_FAILED`，允许显式重新投递。
- 初次投递或重新投递均先获取 RabbitMQ 用户级/全局限流令牌。上传 complete 已落库但限流未通过时，任务条件进入 `PUBLISH_FAILED` 并记录 `AI_RATE_LIMITED`，上传结果不回滚且不发送消息；提纲生成限流未通过时返回 429 且不创建任务。
- 消费者可能先于 confirm 状态回写收到消息，或在 confirm 超时后收到延迟消息，因此可用 CAS 将 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED` 或 `RETRYING` 推进到 `PROCESSING`；confirm 回调只能条件更新，不能倒退已进入处理或终态的任务。
- 单个任务最多执行 3 次（首次消费 + 2 次重投）；单次外部 API 调用最多执行 3 次（首次 + 2 次重试），两类计数分开保存。
- 普通用户对 `FAILED` 资料手动重试时复用原任务、business key 与 checkpoint，开启新的任务级投递预算并记录手动重试轮次；不创建第二个解析任务。
- 消息只传 `taskId`、`materialId`、`courseId`、`executionRound` 等标识，不传文件或全文；旧轮次消息不得执行或消耗新轮次预算。

### MinIO

- 保存上传分片、最终课程文件、视频关键帧、PDF/PPTX 归一化预览 PDF 和较大的解析中间文件。
- 文件访问必须经过 Spring Boot 权限校验或短期预签名 URL。
- 音视频预览支持 HTTP Range；预签名 URL 仅在课程归属校验后签发。
- 原始文件名只作为展示信息，不直接作为对象键。
- 本地 MinIO 不需要暴露公网访问；ASR 由 Spring Boot 读取本地对象后通过支持本地文件/音频流的接口提交。

### Qdrant

- 保存课程 chunk 向量。
- payload 包含用户、课程、资料和 segment 标识。
- 所有查询必须按用户和课程过滤。
- point ID 由 chunk 业务键确定，重复写入覆盖同一 point。

### Mailpit

- 本地捕获邮箱验证码。
- 生产 SMTP 不属于首版交付。

## Retrieval

### Vector Branch

- 使用 `EmbeddingClient` 编码 query。
- Qdrant 按用户和课程过滤召回候选。

### Keyword Branch

- 使用 Apache Lucene 10.x 在本地建立课程级轻量 BM25 索引。
- 正常解析采用增量写入：获取课程级 Redisson 锁 `lucene-index:{courseId}`，以确定性 document ID 对本资料全部 chunk 执行 bulk upsert，commit 成功后释放锁。并发解析同一课程的资料必须串行提交 Lucene 写入。
- 索引可由 MySQL 中的课程 chunk 重建，不作为业务真相源。
- 从 MySQL 全量重建仅用于首次初始化、索引缺失/损坏或管理员修复，不是每份资料正常解析的必经路径；重建同样持有课程级写锁并在版本校验后切换。
- 求职版 MVP 只允许一个 Spring Boot 应用实例，Web API 与 RabbitMQ consumer 在同一 JVM 内运行，确保本地 Lucene 索引一致；不支持横向扩容。
- 不引入 Elasticsearch。

### Fusion

- 两路各取 TopK，使用 RRF 融合排名。
- 合并后去重并取最终上下文。
- 首版不使用 Cross-Encoder 重排和 Query Rewrite。

## Authentication and API

- REST 前缀：`/api/v1`。
- 邮箱验证码登录，HttpOnly Cookie + Spring Session Redis。
- 变更接口开启 CSRF 防护。
- 所有课程资源查询必须包含当前用户 ID。
- 错误响应统一包含稳定 `code`、中文 `message` 和 `requestId`。
- SSE：`GET /api/v1/tasks/events`，断线后通过 REST 查询当前任务状态。
- 计划：同步 `POST /api/v1/courses/{courseId}/plan/generate`，要求客户端幂等键并使用服务端请求记录和计划版本 CAS；问答：同步 `POST /api/v1/courses/{courseId}/messages`。两者分别使用可配置请求超时和用户级限流。
- 课程删除遇到 `PROCESSING`/`RETRYING` 任务返回 `409 COURSE_TASK_RUNNING`；提纲生成重复请求返回当前活动任务。

## Testing

- Java 单元测试：JUnit 5、AssertJ、Mockito。
- 集成测试：Testcontainers 启动 MySQL、Redis、RabbitMQ、MinIO、Qdrant。
- API：MockMvc 或 REST Assured。
- 前端：Vitest + Vue Test Utils。
- 关键 E2E：Playwright，覆盖登录、上传、解析完成、提纲和问答。
- AI Golden Case：固定目录为 `evals/cases`、`evals/annotations`、`evals/fixtures`、`evals/reports`，schema 为 `evals/report.schema.json`；由 `./mvnw -Pgolden-eval verify`（Windows：`mvnw.cmd -Pgolden-eval verify`）运行真实百炼配置，输出含运行时间、实际模型 ID、配置快照、逐案例评分和汇总指标的 JSON 报告，可附 Markdown 摘要。
- 故障测试：重复 complete、MQ 重复消费、第三方 429/5xx、Redis 暂时不可用、SSE 重连。

## Local Docker

完整演示模式下，`docker compose up` 必须一次启动：

- Vue 前端
- 单实例 Spring Boot 后端（Web API 与 RabbitMQ consumer 同进程）
- MySQL
- Redis
- RabbitMQ（含管理页）
- MinIO
- Qdrant
- Mailpit

开发模式可只启动中间件，并在宿主机分别运行前后端；这不是“一键启动”验收路径。

不引入 Prometheus、Grafana、Jaeger、Kubernetes 或云资源。应用通过 Actuator、结构化日志和 RabbitMQ 管理页完成求职版演示所需的可观测性。

## Constraints

- 不建立 Python Worker。
- 不建立微服务、Outbox、Saga 或分布式事务框架。
- 不实现生产级月度配额账本。
- 不把聊天消息只存 Redis。
- 不把文件或完整正文放入 RabbitMQ。
- 不让 Qdrant 或 Lucene 成为不可重建的业务真相源。
- 不实现通用分块 checkpoint、自动版本传播或智能提纲合并。
- 不把 Spring Boot Web 与 consumer 拆成不同实例，也不以本地 Lucene 支持多实例部署。
- 不在未实测前把性能目标写成已取得结果。

## First-Version Configurable Defaults

文档中的数量、大小、时长、TTL、限流、TopK、RRF、同步超时、历史条数、案例数和阈值均是首版默认可配置值，不是不可修改的业务需求；实施时统一写入类型化配置并在 `.env.example` 说明，不得散落为魔法数字。至少包括：

- 上传会话 TTL：`UPLOAD_TTL_HOURS=24`。
- RabbitMQ 任务限流（覆盖资料解析与提纲生成）：`PARSE_USER_RATE_PER_MINUTE=5`、`PARSE_GLOBAL_RATE_PER_MINUTE=30`。变量名为兼容首版配置沿用 `PARSE_*`，语义是所有用户触发的 RabbitMQ AI 任务。
- 用户计划生成限流：`PLAN_USER_RATE_PER_MINUTE=5`。
- 用户问答限流：`CHAT_USER_RATE_PER_MINUTE=20`。
- 混合检索：`RRF_K=60`，向量、BM25 和最终 TopK 分别由 `VECTOR_TOP_K`、`BM25_TOP_K`、`FINAL_TOP_K` 配置。
- 同步接口：`PLAN_REQUEST_TIMEOUT_SECONDS`、`CHAT_REQUEST_TIMEOUT_SECONDS`。
- AI 评测案例数、通过阈值和单项评分标准由评测配置文件管理；修改默认值必须在报告中留下配置快照。
