# Backend Structure — FinalWeek（求职版 MVP）

## Architecture

```text
Vue 3
  ├─ REST ───────────────> Spring Boot
  ├─ SSE  <─────────────── task progress
  └─ chunk upload ───────> upload controller
                              │
Spring Boot                   ├─ MySQL     业务数据与任务真相
  ├─ RabbitMQ producer       ├─ Redis     Session/上传/锁/限流/进度
  ├─ RabbitMQ consumer       ├─ MinIO     分片/课程文件/关键帧
  ├─ parser + FFmpeg         ├─ Qdrant    课程向量
  ├─ RAG service             └─ Lucene    可重建 BM25 索引
  └─ Bailian clients
```

Spring Boot 是唯一应用服务和权限入口。求职版 MVP 中 RabbitMQ 消费者与 Web API 必须在同一个 Spring Boot 进程/JVM 内启动，Docker Compose 只运行一个后端实例；这是本地 Lucene 一致性的部署约束，不支持将 consumer 独立部署或横向扩容。

## Modules

| Module | Responsibility |
|---|---|
| `auth` | 邮箱验证码、Session、当前用户 |
| `course` | 课程 CRUD、用户隔离、逻辑删除 |
| `upload` | 分片初始化、上传、查询、合并、临时清理 |
| `material` | 资料元数据、文件校验、内容解析 |
| `task` | RabbitMQ、投递补偿、状态、阶段 checkpoint、取消、重试、失败表 |
| `knowledge` | CourseSegment、chunk、Embedding、Qdrant、Lucene、RRF |
| `outline` | 提纲生成、来源验证、重要度调整 |
| `plan` | 简单计划生成、任务完成状态 |
| `chat` | 课程文字历史、检索、回答和引用 |
| `ai` | ASR/OCR/Embedding/LLM 适配器 |

## Data Model

### `user_account`

- `id`
- `email`：唯一
- `status`
- `created_at`, `updated_at`

### `course`

- `id`, `user_id`
- `name`
- `deleted`
- `created_at`, `updated_at`

查询课程时必须同时限定 `id + user_id + deleted = false`。

### `material`

- `id`, `user_id`, `course_id`
- `name`, `material_type`, `file_type`
- `note`
- `object_key`, `preview_object_key`, `content_hash`, `size_bytes`, `duration_ms`
- `parse_status`, `parse_warning`
- `created_at`, `updated_at`

同一课程对 `content_hash` 建唯一业务约束，防止重复解析。

### `upload_task`

- `id`（uploadId）、`user_id`, `course_id`
- `original_name`, `file_type`, `expected_size`
- `chunk_size`, `total_chunks`
- `status`, `expire_at`
- `material_id`
- timestamps

已上传分片序号主要保存在 Redis Set；MySQL 保存任务归属、总量和最终状态，不为每个分片建业务表。

### `parse_task`

- `id`, `user_id`, `course_id`, `material_id`
- `task_type`：`PARSE_MATERIAL`、`GENERATE_OUTLINE`
- `status`
- `current_stage`
- `publish_attempt_count`、`delivery_attempt_count`、`api_attempt_count`
- `manual_retry_count`、`execution_round`：手动重试轮次与当前任务级预算轮次
- `business_key`：唯一，用于重复消息幂等
- `error_code`, `error_message`
- `started_at`, `finished_at`, timestamps

### `task_checkpoint`

- `id`, `task_id`
- `stage`
- `result_object_key` 或简短 `result_json`
- `status`
- `completed_at`

同一任务同一阶段唯一。资料解析 checkpoint 只能使用 `UPLOADED → CONTENT_EXTRACTED → CHUNKED → EMBEDDING_COMPLETED → COMPLETED`；提纲任务只能使用 `CONTEXT_RETRIEVED → OUTLINE_GENERATED → COMPLETED`。两套阶段不得混写。首版只记录阶段完成，不实现通用页面/分块 checkpoint 引擎。

### `failed_task`

- `id`, `task_id`
- `message_id`
- `failure_stage`, `failure_reason`
- `redeliver_count`
- `status`：`PENDING`、`REDELIVERED`、`RESOLVED`
- timestamps

### `course_segment`

- `id`, `user_id`, `course_id`, `material_id`
- `content`
- `source_type`
- `page_number`, `slide_number`, `paragraph_number`
- `start_time_ms`, `end_time_ms`
- `asr_text`, `ocr_text`
- `chunk_no`
- `created_at`

数据库唯一约束为 `(material_id, chunk_no)`。Qdrant point 和 Lucene document 都引用 `course_segment.id`；Qdrant point ID、Lucene document ID 和派生对象键由稳定业务键确定，重复执行使用 upsert/覆盖而不是新增。

### `outline`

- `id`, `course_id`：每课只保留当前一条
- `generated_at`, `updated_at`

### `outline_node`

- `id`, `outline_id`, `parent_id`
- `title`, `importance`, `position`
- `source_refs_json`
- `importance_manually_adjusted`

`source_refs_json` 只保存 segment ID 和展示位置；发布提纲前必须验证引用归属。

### `study_plan`

- `id`, `course_id`：每课只保留当前一条
- `exam_date`
- `daily_minutes`
- `mastery_level`
- `target_score`
- `generated_at`
- `version`：每次成功替换递增，用于并发生成 CAS

### `plan_generation_request`

- `id`, `user_id`, `course_id`
- `idempotency_key`：同一用户和课程内唯一
- `status`：`PENDING`、`SUCCEEDED`、`FAILED`
- `result_plan_version`
- timestamps

同步超时重试复用同一记录；若同一幂等键已成功则返回既有结果版本，不再次调用模型。较旧请求不得覆盖更新版本的当前计划。

### `plan_task`

- `id`, `plan_id`, `outline_node_id`
- `planned_date`
- `estimated_minutes`
- `completed`, `completed_at`
- `position`

### `chat_message`

- `id`, `user_id`, `course_id`
- `role`：`USER`、`ASSISTANT`
- `content`
- `source_refs_json`
- `general_knowledge_used`
- `status`
- `created_at`

每门课程按时间形成一条连续历史，不建立 conversation 表。

## REST API

统一前缀 `/api/v1`，资源接口必须校验当前用户归属。

### Auth

- `POST /auth/code`：发送邮箱验证码。
- `POST /auth/login`：验证验证码并创建 Session。
- `POST /auth/logout`。
- `GET /me`。

### Course

- `GET /courses`
- `POST /courses`
- `GET /courses/{courseId}`
- `PATCH /courses/{courseId}`
- `DELETE /courses/{courseId}`：逻辑删除

删除课程时在同一事务中锁定课程及相关任务；若存在 `PROCESSING`/`RETRYING` 任务则返回 `409 COURSE_TASK_RUNNING` 并回滚，不取消其他任务。否则在事务内取消尚未开始的任务并逻辑删除课程。

### Upload and Material

- `POST /courses/{courseId}/uploads/init`
- `GET /uploads/{uploadId}`：返回已上传分片和状态
- `PUT /uploads/{uploadId}/chunks/{chunkIndex}`
- `POST /uploads/{uploadId}/complete`
- `GET /courses/{courseId}/materials`
- `GET /materials/{materialId}`
- `DELETE /materials/{materialId}`
- `POST /materials/{materialId}/retry`
- `GET /materials/{materialId}/preview`：返回媒体类型、定位能力和经授权的短期 URL
- `GET /segments/{segmentId}`：返回经课程归属校验的原文片段与来源位置

成功解析的资料禁止单独删除，`DELETE /materials/{materialId}` 返回 `409 MATERIAL_DELETE_FORBIDDEN`。待投递、投递失败、排队、失败或已取消的资料可按状态校验后单独删除；排队资料必须在同一事务中先 CAS 为 `CANCELLED`。处理中/重试中返回 `409 TASK_NOT_CANCELLABLE`，成功资料只能随整门课程删除。

`POST /materials/{materialId}/retry` 只接受 `FAILED` 任务：复用原 `taskId`、business key 和 checkpoint，增加 `manual_retry_count` 与 `execution_round`，把当前轮次的 `delivery_attempt_count` 重置后重新投递。对应 `failed_task` 变为 `REDELIVERED`；任务最终成功后变为 `RESOLVED`。该操作不得创建新 material 或第二条 parse_task。

### Task and SSE

- `GET /tasks/{taskId}`
- `POST /tasks/{taskId}/cancel`
- `POST /tasks/{taskId}/republish`：仅用于 `PUBLISH_FAILED`
- `POST /failed-tasks/{taskId}/redeliver`：管理员/演示用失败队列重投；普通用户从资料页调用 `/materials/{materialId}/retry`
- `GET /tasks/events`：SSE

SSE 事件：`eventId`、`taskId`、`status`、`stage`、`progress`、`message`、`occurredAt`。

### Outline

- `GET /courses/{courseId}/outline`
- `POST /courses/{courseId}/outline/generate`
- `PATCH /outline-nodes/{nodeId}/importance`

### Plan

- `GET /courses/{courseId}/plan`
- `POST /courses/{courseId}/plan/generate`
- `PATCH /plan-tasks/{taskId}/completed`

### Chat

- `GET /courses/{courseId}/messages?cursor=...`
- `POST /courses/{courseId}/messages`
- `POST /chat-messages/{messageId}/retry`

## Upload Design

### Redis Keys

- `fw:upload:{uploadId}:meta`：用户、课程、文件名、总分片、过期时间。
- `fw:upload:{uploadId}:chunks`：已成功分片序号 Set。
- `fw:upload:{uploadId}:completed`：最终 materialId，防止重复 complete。
- TTL 默认 24 小时。

### Chunk Write Order

1. 校验 uploadId 归属、chunkIndex 和大小。
2. 将分片写入 MinIO 临时前缀。
3. MinIO 写成功后才向 Redis Set 添加分片号。
4. 重复上传相同 chunkIndex 覆盖确定性对象键，Set 不重复计数。

### Complete Idempotency and Publication

1. 获取 `uploadId` Redisson 锁。
2. 若 completed key 或 MySQL 最终记录存在，返回相同结果。
3. 校验 0..N-1 分片均存在。
4. 按序合并到临时文件并计算完整哈希。
5. 上传最终对象，在同一 MySQL 事务中写入 material 与状态为 `PENDING_PUBLISH` 的 parse_task。
6. 提交事务后先获取 RabbitMQ 用户级/全局限流令牌。未通过时只把仍为 `PENDING_PUBLISH` 的任务改为 `PUBLISH_FAILED` 并记录 `AI_RATE_LIMITED`，不回滚 material/上传结果，也不发送消息；接口返回同一 material/task，供用户稍后重新投递。
7. 限流通过后发布 RabbitMQ persistent message 并等待 publisher confirm：成功则以条件更新将仍为 `PENDING_PUBLISH` 的任务改为 `QUEUED`；失败/超时只把仍为 `PENDING_PUBLISH` 的任务改为 `PUBLISH_FAILED`。若消费者已先把状态推进到 `PROCESSING` 或其他后续状态，confirm 回调不得倒退状态。
8. 设置 completed key，清理临时分片。重新调用 complete 返回同一 material/task；`PUBLISH_FAILED` 由重新投递接口继续，不创建第二个任务。

MinIO 与 MySQL 没有分布式事务。使用确定性对象键、唯一业务键和定时孤儿清理作为补偿，不宣称强一致。

## RabbitMQ and Idempotency

本项目不引入 Outbox。以 `PENDING_PUBLISH` + publisher confirm + `PUBLISH_FAILED` 显式重投补偿数据库与 RabbitMQ 的提交间隙；定时扫描长期停留在 `PENDING_PUBLISH` 的任务并将其标记为 `PUBLISH_FAILED`，不得把它们显示为已排队。

### Message

```json
{
  "taskId": "uuid",
  "taskType": "PARSE_MATERIAL",
  "materialId": "uuid",
  "courseId": "uuid",
  "executionRound": 0
}
```

### Consumer Flow

1. 校验消息字段和任务归属。
2. 使用 MySQL compare-and-set 将 `PENDING_PUBLISH`/`PUBLISH_FAILED`/`QUEUED`/`RETRYING` 推进到 `PROCESSING`；消息合法到达本身证明已经投递，可安全处理 confirm 回调尚未回写或 confirm 超时后延迟到达的消息。若任务已 `CANCELLED`、`SUCCEEDED` 或 `FAILED`，直接 ack。Redisson 任务锁只减少并发，不替代此状态检查。
3. 查询任务终态；已成功则直接 ack。
4. 查询最近成功 checkpoint，从下一阶段执行。
5. 每阶段先完成幂等结果写入，再在 MySQL 事务中写 checkpoint 和任务阶段；checkpoint 永远最后提交。
6. 全部完成后更新资料、任务状态并 ack。
7. 异常按类型决定外部 API 重试、MQ 重投或永久失败。外部 API 单次调用最多执行 3 次（首次 + 2 次重试），记录 `api_attempt_count`；任务最多执行 3 次（首次消费 + 2 次重投），记录 `delivery_attempt_count`。不可重试错误立即失败，两类预算不得相乘后伪装成同一计数。

锁用于减少并发执行；MySQL 唯一键、状态检查和可重复写才是最终幂等保障。

### Cancellation

- 只有 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED` 可通过 compare-and-set 变为 `CANCELLED`。
- consumer 抢到 `PENDING_PUBLISH/PUBLISH_FAILED/QUEUED/RETRYING → PROCESSING` 后，取消或重新投递接口返回 `409 TASK_NOT_CANCELLABLE`。
- 已取消任务若仍收到消息，consumer 只 ack，不执行任何阶段。

### Manual Retry

- `PUBLISH_FAILED` 重新投递不是新执行轮次：先获取限流令牌，再将 `PUBLISH_FAILED` CAS 为 `PENDING_PUBLISH`、增加 `publish_attempt_count` 并发布同一 `execution_round` 的消息。若延迟原消息已把状态抢占为 `PROCESSING`，CAS 失败并返回当前任务，不再发送第二条消息。
- `FAILED` 手动重试复用原任务和 checkpoint，开启新的 `execution_round`，当前轮次任务级投递次数从零重新计算，外部 API 次数仍按每次调用单独记录。
- 手动重试投递前重新执行 RabbitMQ 用户级和全局限流；限流失败时任务保持 `FAILED`，不改变轮次和失败记录。
- `PUBLISH_FAILED` 重新投递限流未通过时保持 `PUBLISH_FAILED`、更新安全错误摘要并返回 429，不发送消息。
- 旧轮次的延迟或重复消息通过 `execution_round` 与消息中的轮次号识别并 ack，不得消耗新轮次预算。

## Parsing Pipeline

### `CONTENT_EXTRACTED`

- PDFBox 提取 PDF 原生文字；低文字密度页渲染后 OCR。
- POI 提取 PPTX 文本；必要时 LibreOffice 转 PDF 后 OCR。
- TXT/MD 按段落读取。
- FFmpeg 提取音频并分段，Spring Boot 从 MinIO 读取分段后通过 `paraformer-realtime-v2` Java 本地文件/音频流接口调用 ASR；不向百炼提供无法公网访问的本地 MinIO URL。
- MP4 抽取关键帧、感知哈希去重并 OCR。
- 将结果标准化为带位置的临时 CourseContext。
- PDF/PPTX 同时生成确定性对象键的归一化预览 PDF；重复执行覆盖同一对象。

### `CHUNKED`

- 优先按标题、段落和时间边界切分。
- 超长内容按固定 token 上限及少量 overlap 继续拆分。
- 将 chunk 保存为 `course_segment`。
- 以 `(material_id, chunk_no)` upsert，整阶段成功后才写 `CHUNKED` checkpoint。

### `EMBEDDING_COMPLETED`

- 批量调用 Embedding。
- 以确定性 point ID upsert Qdrant。
- 获取课程级 Redisson 锁 `lucene-index:{courseId}`，以确定性 document ID 对本资料全部 chunk 增量 bulk upsert Lucene BM25 索引，commit 成功后释放锁；正常解析不得全量重建课程索引。
- 检索只允许使用 MySQL 中资料状态为 `SUCCEEDED` 的 segment，避免阶段中途失败时暴露部分向量或 Lucene document。

### `OUTLINE_GENERATED`

- 只用于用户主动生成提纲的独立任务。
- 检索相关课程片段，调用 LLM 返回 JSON 树。
- 验证引用、层级和重要度后替换当前提纲。

资料解析成功写 `COMPLETED` 时只代表文件、segment 与两类索引可用，不自动生成提纲。提纲任务使用独立的 `CONTEXT_RETRIEVED`、`OUTLINE_GENERATED`、`COMPLETED` checkpoint。

## Hybrid Retrieval

1. 校验用户和课程。
2. 向量分支从 Qdrant 召回。
3. 关键词分支从 Lucene BM25 召回。
4. RRF：`score(d) = Σ 1 / (RRF_K + rank_i(d))`，首版默认 `RRF_K=60`。
5. 按 segment ID 去重，取最终 TopK。
6. 回到 MySQL 获取原文与位置，避免直接信任向量 payload 中的展示内容。

Lucene 索引损坏、丢失或首次初始化时可由 `course_segment` 全量重建；这是故障修复/初始化能力，不是每份资料正常入库流程。重建必须获取同一课程级写锁，在锁内基于 MySQL 成功资料构建新版本、校验后原子切换。Qdrant 不可用时允许 BM25 降级；Lucene 不可用时允许向量降级；降级必须记录日志。由于 Lucene 是本地索引，首版仅支持单后端实例。

## Source Preview

- `GET /materials/{materialId}/preview` 先校验当前用户、课程和资料状态，再返回短期预签名 URL、媒体类型、过期时间及是否支持时间定位。
- PDF 与 PPTX 均预览解析阶段生成的归一化 PDF；来源页码/幻灯片由 segment 元数据映射。原 PDF 仍作为原始资料保留，但不作为来源定位的默认预览对象。
- MP3/MP4 对象支持 HTTP Range，前端根据 `start_time_ms` 设置播放器位置。
- `GET /segments/{segmentId}` 只返回经授权的短原文和位置，不暴露对象键、其他课程内容或永久下载地址。

## Outline Generation Rules

- 学生资料说明中明确重点的内容优先判为高。
- 真题/题库命中的考点提高重要度。
- 教师强调语句通过提示词识别，但不宣称绝对准确。
- 每个生成节点必须引用至少一个当前课程 segment。
- 发布前验证 segment 属于当前用户和课程。
- 用户只能修改重要度；重新生成覆盖所有节点和人工重要度。
- 创建提纲任务时通过课程行/活动任务 guard 的 MySQL CAS 保证每课最多一个活动任务；重复请求返回现有任务。发布结果时校验生成版本，旧任务不得覆盖较新版本。
- 提纲生成在创建任务前获取 RabbitMQ 用户级/全局限流令牌；未通过返回 429 且不创建 guard 或任务记录。

## Study Plan Rules

计划生成是同步 REST，不进入 RabbitMQ、不创建异步 parse_task、也不发送 SSE。请求超时由 `PLAN_REQUEST_TIMEOUT_SECONDS` 配置；调用前执行用户级计划限流。请求必须携带幂等键并写入 `plan_generation_request`，生成结果与当前计划的版本 CAS 替换必须在单个数据库事务中完成。超时/失败不修改旧计划，客户端必须先重新读取当前计划并以同一幂等键重试。

- 只接受当前提纲节点作为任务内容。
- 高重要度和低掌握程度获得更多时间。
- 总任务分钟数不得超过考试前的总可用分钟数。
- 计划生成失败不覆盖旧计划。
- 首版不动态重排或分析完成质量。

## Chat Rules

课程问答是非流式同步 REST，不进入 RabbitMQ、不发送 SSE。请求超时由 `CHAT_REQUEST_TIMEOUT_SECONDS` 配置。后端先写一条 `PENDING` 用户消息；成功时在同一事务写助手消息并把用户消息改为 `SUCCEEDED`，失败/超时改为 `FAILED`。重试接口以原消息 ID 做幂等状态迁移，不重复写入用户消息。

- 输入仅文字。
- 对话历史持久化 MySQL，向模型只发送最近 N 条和当前检索上下文；N 通过配置设置。
- 资料型回答必须引用检索到且提供给模型的 segment。
- 通用知识必须独立分区并设置 `general_knowledge_used=true`。
- 没有课程证据时允许回答“不足以根据资料回答”，不能伪造引用。

## Rate Limiting

- Redisson `RRateLimiter`：RabbitMQ 任务用户级/全局限流（覆盖资料解析和提纲生成）、用户级计划生成限流、用户级问答限流。
- 限流必须发生在 RabbitMQ 投递或同步 LLM 调用前；手动重投同样重新获取令牌。
- Redis 不可用时，登录以及全部需要认证的后端请求统一 fail closed，返回 HTTP 503 和稳定错误 `SERVICE_REDIS_UNAVAILABLE`；静态落地页与无需认证的健康检查不受影响。
- 默认阈值是本地 Demo 保护参数，README 必须如实说明未经过生产流量验证。

## Error Handling

### Retryable

- 网络超时、连接中断、429、第三方 5xx。

### Non-retryable

- 鉴权失败、参数错误、不支持格式、文件损坏、模型不存在、权限错误。

### Stable Codes

- `AUTH_CODE_INVALID`
- `COURSE_LIMIT_REACHED`
- `UPLOAD_EXPIRED`
- `CHUNK_MISSING`
- `FILE_TYPE_UNSUPPORTED`
- `DUPLICATE_MATERIAL`
- `TASK_NOT_CANCELLABLE`
- `TASK_PUBLISH_FAILED`
- `MATERIAL_DELETE_FORBIDDEN`
- `COURSE_TASK_RUNNING`
- `SERVICE_REDIS_UNAVAILABLE`
- `AI_RATE_LIMITED`
- `AI_PROVIDER_TEMPORARY`
- `MODEL_OUTPUT_INVALID`
- `COURSE_SOURCE_NOT_FOUND`

客户端只接收安全中文错误；第三方响应体和堆栈不直接返回。

## Deletion

- 课程使用逻辑删除，查询默认排除。删除课程时在同一事务中锁定课程及任务：存在处理中/重试中任务则返回 `COURSE_TASK_RUNNING` 并回滚，不取消其他任务；否则 CAS 取消尚未开始的任务并逻辑删除课程。
- 成功资料不提供单独删除。未成功资料删除必须校验任务状态：排队任务先 CAS 取消，处理中/重试中拒绝删除；其余允许状态幂等清理 MinIO 最终原文件、归一化预览和中间对象，以及 CourseSegment、Qdrant point 和 Lucene document。Lucene 删除必须持有课程级写锁。删除数据库记录或设置可重传标记时必须释放同课程内容哈希唯一约束，使同文件可再次上传。
- 删除课程后禁止新上传、检索、提纲和问答。
- 提供可重复执行的管理员清理命令：扫描并删除已删课程或无数据库归属的 MinIO 对象、Qdrant point、Lucene document，并报告处理/跳过/失败数量；支持 dry-run。
- 首版不提供回收站，不承诺立即完成跨存储合规清除。

## Observability

- JSON 结构化日志：`requestId`、`taskId`、`courseId`、阶段、耗时和错误码。
- 不记录验证码、Cookie、API Key、邮箱全文、课程全文或模型完整 Prompt。
- Actuator 提供 health 和基础 metrics。
- RabbitMQ 管理页用于演示队列、消费和失败消息。
- 不搭建 Prometheus、Grafana 或分布式追踪。

## Required Tests

- 用户 A 无法访问用户 B 的课程、文件、任务和向量。
- 30%、70%、99% 中断后只上传缺失分片，最终文件哈希一致。
- 两个 complete 并发只产生一条 material 和 parse_task。
- 同一 RabbitMQ 消息重复投递不产生重复片段或向量。
- 在内容提取、Embedding、提纲阶段制造失败，重试从最近成功 checkpoint 开始。
- Redis 不可用时登录与全部已认证接口返回 503；静态落地页和无需认证健康检查仍可读。
- SSE 断开重连后读取最终任务状态。
- publisher confirm 失败不会留下假 `QUEUED`，重新投递不创建第二个任务。
- 取消、重新投递与 consumer 抢占并发时只有一个 CAS 成功；consumer 可从 `PENDING_PUBLISH` 或 `PUBLISH_FAILED` 抢占，已取消消息被 ack 且不执行。
- confirm 回调晚于消费开始时不会把 `PROCESSING` 倒退为 `QUEUED`；旧手动重试轮次消息不会消耗新轮次预算。
- 同一课程两份资料并行完成 Embedding 时，Lucene 在课程锁内分别增量提交且最终同时包含两份资料；全量重建只在修复测试中触发。
- 课程存在处理中任务时删除返回 `COURSE_TASK_RUNNING`；失败资料删除会清理部分派生内容并允许重新上传同一哈希。
- 成功资料单独删除返回 `MATERIAL_DELETE_FORBIDDEN`，删除整门课程后管理员清理可覆盖三类存储。
- PDF/PPTX 来源能打开归一化预览并定位，MP3/MP4 能通过 Range 跳转时间点。
- 混合检索能正确限制用户和课程，并返回来源位置。
- 首版默认至少 8 个 Golden Case 产生真实评测报告；案例数和阈值均为可配置默认值，报告必须记录实际模型 ID 与配置快照。

## Configuration Policy

课程数、文件大小/时长、上传 TTL、RabbitMQ/计划/问答限流、RRF 与各路 TopK、同步计划/问答超时、对话历史条数、Golden Case 数量及通过阈值，全部作为“首版默认可配置值”。默认值集中在类型化配置和 `.env.example`，不是不可修改的业务需求；每次评测必须固化当次配置。

百炼首版默认模型：`BAILIAN_ASR_MODEL=paraformer-realtime-v2`、`BAILIAN_OCR_MODEL=qwen-vl-ocr-latest`、`BAILIAN_EMBEDDING_MODEL=text-embedding-v4`、`BAILIAN_LLM_MODEL=qwen3.7-plus`。实际供应商不支持默认 ID 时必须显式修改配置，并在评测报告中记录真实 ID。
