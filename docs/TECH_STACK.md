# Tech Stack — FinalWeek（求职版 MVP）

> 文档状态：Phase 1–16 已完成。文末“Phase 12–16 技术栈增量”对应已交付的知识版本、通用任务、模拟卷、XeLaTeX 和评测实现。
>
> 当前技术事实与历史方案排除清单见 [`GPT_CONTEXT.md`](GPT_CONTEXT.md)。下文 Phase 12–16 增量均为当前已实现能力，不再表示未来目标。

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
- Vue Router：登录、课程、资料、提纲、计划、问答、模拟卷页面。
- Pinia：登录用户、活动任务抽屉和少量界面状态；服务端数据以 API 返回为准。
- Element Plus：表单、上传、表格、树、进度、弹窗和反馈。
- 原生 `fetch` 项目封装：统一 CSRF、错误码和请求 ID。
- SSE：推送资料、提纲、计划、模拟卷进度和答疑消息终态；断线后以活动任务/业务历史 REST 恢复，不引入 WebSocket。
- Markdown：聊天回答只渲染经过清洗的 Markdown 子集。

## Spring Boot Application

### Required Dependencies

- Spring Web MVC：REST 与 SSE。
- Spring Security：Cookie Session、授权和 CSRF。
- Spring Session Data Redis：服务端 Session。
- MyBatis-Plus：业务数据访问、分页、条件查询与显式 CAS SQL；实体关系使用标量外键，复杂查询由 Mapper SQL 明确表达。
- Flyway：数据库迁移；禁止 `ddl-auto=update`。
- Spring AMQP：RabbitMQ 生产、消费、确认和失败队列。
- Redisson：上传合并锁、内容哈希锁、课程级 Lucene 写锁和令牌桶限流；RabbitMQ 任务执行权由 MySQL CAS 租约控制。
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

- 保存资料解析、提纲、复习计划、模拟卷和隐藏答疑等长耗时后台任务。
- durable exchange/queue、persistent message、publisher confirm、manual ack。
- 数据库先创建 `PENDING_PUBLISH` 任务，publisher confirm 成功后进入 `QUEUED`；投递失败进入 `PUBLISH_FAILED`，允许显式重新投递。
- 初次投递或重新投递均先获取 RabbitMQ 用户级/全局限流令牌。上传 complete 已落库但限流未通过时，任务条件进入 `PUBLISH_FAILED` 并记录 `AI_RATE_LIMITED`，上传结果不回滚且不发送消息；提纲生成限流未通过时返回 429 且不创建任务。
- 消费者可能先于 confirm 回写收到消息，因此使用 MySQL CAS 推进到 `PROCESSING` 并原子写入 owner/lease；心跳续租，超时由扫描器 CAS 回收重投，成功/失败/重试更新校验 owner。confirm 只能条件更新，不能倒退后续状态。
- 单个任务最多执行 3 次（首次消费 + 2 次重投）；单次外部 API 调用最多执行 3 次（首次 + 2 次重试），两类计数分开保存。
- 普通用户对 `FAILED` 资料手动重试时复用原任务、business key 与 checkpoint，开启新的任务级投递预算并记录手动重试轮次；不创建第二个解析任务。
- 消息只传 `taskId`、`materialId`、`courseId`、`executionRound` 等标识，不传文件或全文；旧轮次消息不得执行或消耗新轮次预算。

### MinIO

- 保存上传分片、最终课程文件、视频关键帧、PDF/PPTX 归一化预览 PDF 和较大的解析中间文件。
- 文件访问必须经过 Spring Boot 权限校验或短期预签名 URL。
- 音视频预览支持 HTTP Range；预签名 URL 仅在课程归属校验后签发。
- 原始文件名只作为展示信息，不直接作为对象键。
- 本地 MinIO 不需要暴露公网访问；ASR 由 Spring Boot 读取本地对象、生成整份 WAV 后通过 SDK 本地文件非流式接口提交。

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
- 计划：`POST /api/v1/courses/{courseId}/plan/generate` 接收客户端幂等键，创建用户可见后台任务并返回 `202 Accepted`；服务端保留请求记录、知识版本快照和计划版本 CAS。问答：`POST /api/v1/courses/{courseId}/messages` 在同一事务中保存 `PENDING` 消息与隐藏后台任务并返回 `202 Accepted`。两者都通过 RabbitMQ 离页执行，并分别使用可配置模型超时和用户级限流。
- 课程删除遇到 `PROCESSING`/`RETRYING` 任务返回 `409 COURSE_TASK_RUNNING`；提纲生成重复请求返回当前活动任务。

## Testing

- Java 单元测试：JUnit 5、AssertJ、Mockito。
- 集成测试：Testcontainers 当前重点启动 MySQL，验证迁移、CAS、计数和唯一约束；Redis、RabbitMQ、MinIO、Qdrant 主要由单元/集成测试及真实 Docker Compose 主链路共同覆盖。
- API：MockMvc 或 REST Assured。
- 前端：Vitest + Vue Test Utils。
- 关键 E2E：Playwright，覆盖登录、多资料上传、解析、知识版本确认、自动提纲、异步计划、离页答疑、模拟卷和双 PDF 操作。
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

文档中的数量、大小、时长、TTL、限流、TopK、RRF、后台模型调用超时、历史条数、案例数和阈值均是首版默认可配置值，不是不可修改的业务需求；实施时统一写入类型化配置并在 `.env.example` 说明，不得散落为魔法数字。至少包括：

- 上传会话 TTL：`UPLOAD_TTL_HOURS=24`。
- RabbitMQ 任务限流（覆盖资料解析与提纲生成）：`PARSE_USER_RATE_PER_MINUTE=30`、`PARSE_GLOBAL_RATE_PER_MINUTE=120`。变量名为兼容首版配置沿用 `PARSE_*`，语义是所有用户触发的 RabbitMQ AI 任务。
- 用户计划生成限流：`PLAN_USER_RATE_PER_MINUTE=5`。
- 用户问答限流：`CHAT_USER_RATE_PER_MINUTE=20`。
- 混合检索：`RRF_K=60`，向量、BM25 和最终 TopK 分别由 `VECTOR_TOP_K`、`BM25_TOP_K`、`FINAL_TOP_K` 配置。
- 后台计划/答疑模型调用：`PLAN_REQUEST_TIMEOUT_SECONDS`、`CHAT_REQUEST_TIMEOUT_SECONDS`（变量名为兼容首版沿用）。
- AI 评测案例数、通过阈值和单项评分标准由评测配置文件管理；修改默认值必须在报告中留下配置快照。

## Phase 12–16 技术栈增量（已实现）

### Frontend and Task Delivery

- Vue Router 已包含 `/courses/:id/mock-exams`；课程导航当前为资料、知识提纲、复习计划、课程问答、模拟卷五区。
- 继续使用 Pinia 保存少量全局界面状态，增加只包含活动任务的任务抽屉状态；任务终态和业务结果仍以 REST/MySQL 为准。
- 复用 `GET /api/v1/tasks/events` 推送资料、提纲、计划、模拟卷进度及答疑消息终态。断线恢复必须先调用活动任务/业务历史 REST，不引入 WebSocket。
- 计划和答疑不再依赖浏览器同步等待模型：计划进入用户可见后台任务；答疑进入隐藏后台任务并通过消息历史恢复。

### General Background Tasks

- Spring AMQP 继续作为全部长耗时工作的单一队列基础设施，新增计划、模拟卷和答疑任务类型；消息只携带任务及业务标识，不携带题目、全文或 PDF。
- 现有任务状态、publisher confirm、manual ack、任务级/外部 API 重试、旧执行轮次防护和 MySQL checkpoint 规则扩展到新任务类型。
- Redis 令牌桶增加模拟卷限流；计划从同步限流迁移到投递前限流。答疑仍执行用户级成本保护，但后台受理不得绕过限流。
- 全局任务 REST 只返回 `visibleInGlobalDrawer=true` 的活动任务；答疑任务不可见，终态在线事件仍可提示。

### Course Knowledge Versions

- MySQL 增加不可变课程知识版本和版本资料关联表；当前发布版本是计划与模拟卷的输入真相源。
- Qdrant 与 Lucene 无需复制每个知识版本的数据。模拟卷检索在现有用户/课程过滤上追加版本 material ID 白名单；答疑继续检索全部成功资料。
- 旧提纲在 MySQL 内部保留以支持历史版本和生成中任务；前端默认只读取当前发布版本。

### XeLaTeX PDF Generation

- 标准试卷和参考答案使用受控 XeLaTeX 模板生成，不使用 PDFBox 进行复杂版面创作。PDFBox 继续负责读取/渲染已有资料，并可用于生成结果的打开、页数和基本结构校验。
- 后端 Jammy 运行镜像已安装固定发行版的 XeLaTeX、必要的基础宏包及 Noto CJK 字体；不得使用 `latest` 或运行时联网下载宏包。
- XeLaTeX 以非特权 `finalweek` 用户运行，每个任务使用独立临时目录、`-no-shell-escape`、编译超时、文件大小限制和退出码检查。
- 仓库内只维护两套受版本控制模板：试卷与参考答案。模板负责 A4、分页、题型标题、姓名/学号栏、分值、答题空间及页眉页脚。
- 业务文本进入模板前统一 TeX 转义。数学内容使用单独字段和受支持命令白名单，不允许模型或用户注入宏定义、包加载、文件读写、网络或 include。
- 两个 PDF 必须从同一份 Java 已校验 DTO 渲染；任一编译或校验失败都不得发布部分结果。

### Storage

- MinIO 增加按用户/课程/mockExam ID 隔离的试卷与参考答案对象键；原始试卷名仅用于展示和下载文件名。
- 预览继续采用 ownership 校验后的短时预签名 URL，新标签页使用浏览器原生 PDF 查看器。
- 单套逻辑删除后写入内部可重试清理记录；清理任务不进入用户任务抽屉。管理员 dry-run 核对增加孤儿模拟卷 PDF。

### AI Structured Output and Validation

- `LlmClient` 继续使用固定 JSON Schema；模拟卷 schema 覆盖八类题型、选项、答案、分值、课程来源 ID、通用知识标识和公式字段。
- 普通文本、公式、来源 ID 分字段返回，禁止把整份 TeX 或 PDF 交给模型生成。
- Java Validator 强制执行题型题量、总题数 1–50、整数分值/汇总不超过 1000、可选时长 1–300、补充说明 2000 字、答案完整性和来源 ownership。
- 不新增联网搜索、Agent、多模型路由或图片生成。通用知识仅使用当前 LLM 内置知识，并在课程资料不足且用户允许时补足。

### Current Configuration

新增配置必须进入类型化配置和 `.env.example`，至少包括：

- 模拟卷用户级限流、生成模型超时、同课程活动任务上限（固定业务语义为 1）。
- 最大题数默认/硬上限 50、分值汇总上限 1000、时长上限 300 分钟、补充说明上限 2000 字。
- XeLaTeX 可执行文件、编译超时、最大 PDF 大小、临时目录、模板版本和允许公式命令集版本。
- 模拟卷分页大小、预签名 URL TTL、逻辑删除对象清理重试与扫描间隔。
- 历史题相似度策略和警告阈值应可版本化并写入生成记录/评测快照，不得散落在提示词中。

### Current Testing Stack

- 后端单元测试增加知识版本、模拟卷 schema/validator、TeX 转义、公式白名单、模板渲染和双文件原子发布。
- Testcontainers/Compose 集成测试覆盖迁移后的通用任务表、RabbitMQ 新任务类型、MinIO 双 PDF、逻辑删除清理和用户隔离。
- Playwright 目标主流程扩展为资料确认、自动提纲、跨页面任务抽屉、异步计划、离页答疑、模拟卷生成历史及 PDF 操作。
- Golden Case 增加真题风格、教师例题、严格资料不足、通用知识补题、题型和公式案例；评测报告区分结构校验、来源忠实度、答案一致性、原题复刻与 PDF 可用性。
- 本地、容器和浏览器验证均已实际运行；14 条真实百炼 Golden Case 覆盖当时的原七类题型。后续综合题已加入代码和自动化测试，但尚未重新运行付费 Golden Eval。结果与报告路径见 `docs/TEST_REPORT.md`。

### Current Constraints

- 不新增 Python Worker、独立渲染服务或第二套消息系统。
- 不允许模型直接输出可执行 TeX 模板；不启用 shell escape；不运行用户提供的 TeX。
- 不支持依赖图片、图表或示意图的题目，不承诺复杂视觉公式还原。
- 不建设在线考试、自动评分、错题本、分享或编辑器。
- 不因新增知识版本复制 Qdrant/Lucene 数据，也不把历史 PDF 设为公开永久 URL。
