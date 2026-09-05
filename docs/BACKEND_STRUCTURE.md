# Backend Structure — FinalWeek（求职版 MVP）

> 文档状态：Phase 1–16 已完成。文末“Phase 12–16 扩展架构”对应已交付的迁移、MyBatis-Plus 模型与 Mapper、REST 接口、任务管线和测试要求。
>
> 当前实现总览见 [`GPT_CONTEXT.md`](GPT_CONTEXT.md)。本文件明确标注的 Phase 1–9 数据模型与旧 API 只用于解释迁移来源，不代表当前数据库和接口。

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

本节先保留 Phase 1–9 的基础表模型，便于理解迁移来源；Phase 10 已把其中的 `parse_task` 迁移并扩展为当前 `background_task`。当前知识版本、通用任务、异步计划/答疑和模拟卷表结构以文末“Phase 12–16 扩展架构”为准。

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

### `parse_task`（Phase 1–9 历史模型）

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

后台模型调用超时后的重试复用同一记录；若同一幂等键已成功则返回既有结果版本，不再次调用模型。较旧请求不得覆盖更新版本的当前计划。

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

`POST /materials/{materialId}/retry` 只接受 `FAILED` 任务：复用原 `taskId`、business key 和 checkpoint，增加 `manual_retry_count` 与 `execution_round`，把当前轮次的 `delivery_attempt_count` 重置后重新投递。对应 `failed_task` 变为 `REDELIVERED`；任务最终成功后变为 `RESOLVED`。当前实现不得创建新 material 或第二条 `background_task`。

### Task and SSE

- `GET /tasks/{taskId}`
- `POST /tasks/{taskId}/cancel`
- `POST /tasks/{taskId}/republish`：仅用于 `PUBLISH_FAILED`
- `POST /failed-tasks/{taskId}/redeliver`：管理员/演示用失败队列重投；普通用户从资料页调用 `/materials/{materialId}/retry`
- `GET /tasks/events`：SSE

SSE 事件：`eventId`、`taskId`、`status`、`stage`、`progress`、`message`、`occurredAt`。

### Outline

- `GET /courses/{courseId}/outline`
- `PATCH /outline-nodes/{nodeId}/importance`

提纲不再通过公开的手动 generate 接口创建。当前由 `POST /courses/{courseId}/knowledge-versions` 确认成功资料集合并自动创建提纲任务；`GET /courses/{courseId}/knowledge-version` 返回当前发布版本和确认状态。

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
6. 提交事务后先获取 RabbitMQ 用户级/全局发布保护令牌（默认 30/120 次每分钟）。未通过时只把仍为 `PENDING_PUBLISH` 的任务改为 `PUBLISH_FAILED` 并记录 `AI_RATE_LIMITED`，不回滚 material/上传结果，也不发送消息；接口返回同一 material/task，供用户稍后重新投递。
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
2. 使用 MySQL CAS 将可执行状态推进到 `PROCESSING`，并原子写入 `processing_owner=messageId` 与 `processing_lease_until`；只有更新一行的消费者获得执行权。消费者不再使用职责重叠的 Redisson 任务锁。
3. 查询任务终态；已成功则直接 ack。
4. 查询最近成功 checkpoint，从下一阶段执行。
5. 每阶段先完成幂等结果写入，再在 MySQL 事务中写 checkpoint 和任务阶段；checkpoint 永远最后提交。
6. 全部完成后更新资料、任务状态并 ack。
7. 异常按类型决定外部 API 重试、MQ 重投或永久失败。外部 API 单次调用最多执行 3 次（首次 + 2 次重试），记录 `api_attempt_count`；任务最多执行 3 次（首次消费 + 2 次重投），记录 `delivery_attempt_count`。不可重试错误立即失败，两类预算不得相乘后伪装成同一计数。

任务执行权由 MySQL CAS 租约控制：执行期间心跳续租，成功、失败和重试更新校验 owner；租约过期后扫描器 CAS 回收为 `PENDING_PUBLISH` 并重新投递。checkpoint、唯一键、稳定 ID 和可重复写负责处理已发生的重复副作用。Redisson 仅保留在上传合并、内容哈希和 Lucene 写入等独立临界区。

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
- FFmpeg 为整份媒体提取一个 16 kHz 单声道 WAV，Spring Boot 通过 `paraformer-realtime-v2` Java SDK 本地文件非流式调用 ASR；应用层不再做 60 秒 ASR 切片；不向百炼提供无法公网访问的本地 MinIO URL。
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

- 只用于确认知识版本后自动创建、或失败后按原版本重试的独立提纲任务。
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

计划生成通过 RabbitMQ `GENERATE_PLAN` 后台任务执行并返回 `202`。调用前执行用户级限流；请求携带幂等键并写入 `plan_generation_request`，同时固定知识版本、预期当前计划版本和任务 ID。生成结果与当前计划的版本 CAS 替换在单个数据库事务中完成；超时、失败或版本竞争不修改旧计划。

- 只接受当前提纲节点作为任务内容。
- 高重要度和低掌握程度获得更多时间。
- 总任务分钟数不得超过考试前的总可用分钟数。
- 计划生成失败不覆盖旧计划。
- 首版不动态重排或分析完成质量。

## Chat Rules

课程问答不做 token 流式输出，但模型处理通过隐藏的 `ANSWER_CHAT` RabbitMQ 任务执行。后端在同一事务写 `PENDING` 用户消息和后台任务并返回 `202`；成功时写助手消息并把用户消息改为 `SUCCEEDED`，失败/超时改为 `FAILED`。重试接口以原消息 ID 做幂等状态迁移，不重复写入用户消息；该任务不出现在全局抽屉。

- 输入仅文字。
- 对话历史持久化 MySQL，向模型只发送最近 N 条和当前检索上下文；N 通过配置设置。
- 资料型回答必须引用检索到且提供给模型的 segment。
- 通用知识必须独立分区并设置 `general_knowledge_used=true`。
- 没有课程证据时允许回答“不足以根据资料回答”，不能伪造引用。

## Rate Limiting

- Redisson `RRateLimiter`：RabbitMQ 任务用户级/全局限流（覆盖资料、提纲、计划和模拟卷）、用户级计划/模拟卷生成限流、用户级问答限流。
- 限流必须发生在 RabbitMQ 投递前；手动重投同样重新获取令牌。
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

课程数、文件大小/时长、上传 TTL、RabbitMQ/计划/问答限流、RRF 与各路 TopK、后台计划/问答模型超时、对话历史条数、Golden Case 数量及通过阈值，全部作为“首版默认可配置值”。默认值集中在类型化配置和 `.env.example`，不是不可修改的业务需求；每次评测必须固化当次配置。

百炼首版默认模型：`BAILIAN_ASR_MODEL=paraformer-realtime-v2`、`BAILIAN_OCR_MODEL=qwen-vl-ocr-latest`、`BAILIAN_EMBEDDING_MODEL=text-embedding-v4`、`BAILIAN_LLM_MODEL=qwen3.7-plus`。实际供应商不支持默认 ID 时必须显式修改配置，并在评测报告中记录真实 ID。

## Phase 12–16 扩展架构（已实现）

### Module Changes

- `task` 从面向解析的任务模型演进为通用后台任务，统一驱动资料解析、提纲、计划、模拟卷和答疑；答疑类型对全局任务查询隐藏。
- 新增 `knowledgeversion`（或 `course` 内等价包级边界）管理资料快照、当前发布版本和确认幂等。
- 新增 `mockexam` 管理出卷请求、结构化题目、内部来源、XeLaTeX 渲染、双 PDF 发布、历史与逻辑删除。
- `plan` 保留唯一当前计划与现有业务校验，生成编排迁移到后台任务。
- `chat` 保留消息状态与引用约束，模型处理迁移到后台消费者；它不复用用户可见任务历史。

仍保持单 Spring Boot JVM、单 RabbitMQ 任务基础设施和包级模块，不拆 Python Worker、微服务或独立 PDF 服务。

### Current Data Model

#### `course_knowledge_version`

- `id`, `course_id`, `version`, `status`, `material_set_hash`, `outline_id`, `created_at`, `published_at`, `error_code`。
- 状态为 `GENERATING`、`PUBLISHED`、`SUPERSEDED`、`FAILED`；`(course_id, version)` 唯一，每门课程至多一个当前 `PUBLISHED` 指针。新版发布时旧版转为 `SUPERSEDED`，但不删除其快照和提纲。
- 版本一经创建，其资料集合不可变；失败版本可按同一业务键重试，成功发布以课程锁和预期当前版本 CAS 完成。

#### `course_knowledge_version_material`

- `knowledge_version_id`, `material_id`, `position`，联合唯一约束防止重复资料。
- 只能引用确认瞬间属于当前用户/课程且状态为 `SUCCEEDED` 的资料。
- `material_set_hash` 由排序后的 material ID 与内容哈希规范化计算，用于阻止无新增资料的重复确认。

#### Versioned Outline

- `outline` 改为一对一关联 `course_knowledge_version`，旧提纲和节点内部保留但默认接口只返回当前发布版本。
- `outline_node` 的来源必须属于该知识版本资料快照。人工重要度只修改当前版本节点；新版本不自动继承旧节点调整。
- 发布新版不删除旧节点，确保生成中的模拟卷及历史版本引用稳定。

#### `background_task`

- 以迁移方式保留现有 `parse_task` 数据并规范化名称；字段至少包括现有任务字段以及 `task_type`, `business_id`, `visible_in_global_drawer`, `generation_version`。
- 任务类型：`PARSE_MATERIAL`、`GENERATE_OUTLINE`、`GENERATE_PLAN`、`GENERATE_MOCK_EXAM`、`ANSWER_CHAT`。
- 通用状态继续使用 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED`、`PROCESSING`、`RETRYING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。
- 资料、提纲、计划和模拟卷类型对全局抽屉可见；答疑类型不可见但仍有持久化任务终态、重试预算和幂等业务键。
- `task_checkpoint` 和 `failed_task` 外键迁移到通用任务；不同任务类型使用自己的合法阶段集合，禁止跨类型 checkpoint。

#### Plan Changes

- `plan_generation_request` 增加 `background_task_id` 和提交时 `knowledge_version_id`，继续保留幂等键、请求哈希、预期计划版本和结果版本。
- `study_plan` 关联生成依据的 `knowledge_version_id`。课程当前版本更高时接口返回 `stale=true`，但计划任务仍可修改完成状态。
- 同一课程同一时刻最多一个活动计划生成任务；成功原子替换唯一计划，提纲版本或计划版本变化时失败且保留旧计划。

#### Chat Changes

- `chat_message` 的 `PENDING/SUCCEEDED/FAILED` 语义保持不变；问题创建事务同时创建 `ANSWER_CHAT` 后台任务。
- 任务检索当前全部 `SUCCEEDED` 资料，不绑定知识版本。重试复用原问题和业务键，不创建重复用户消息。

#### `mock_exam`

- 字段至少包含 `id`, `user_id`, `course_id`, `knowledge_version_id`, `task_id`, `retry_of_id`, `display_name`, `pdf_title`, `status`, `request_json`, `request_hash`, `allow_general_knowledge`, `question_count`, `score_sum`, `total_score_requested`, `duration_minutes`, `warnings_json`, `paper_object_key`, `answer_object_key`, `created_at`, `completed_at`, `deleted_at`。
- `request_json` 保存经过服务端规范化的题型数量、范围节点、分值模式、可选总分/时长和补充说明；重试必须新建记录并通过 `retry_of_id` 关联原快照。
- 同一课程通过活动任务唯一约束/事务锁保证同时最多一个未终态模拟卷任务。
- `status` 是业务投影，终态包括 `SUCCEEDED`、`FAILED`、`CANCELLED`；`deleted_at` 非空后所有用户查询视为不存在。

#### `mock_exam_question`

- 字段至少包含 `id`, `mock_exam_id`, `position`, `section_position`, `question_type`, `stem`, `options_json`, `answer_json`, `score`, `uses_general_knowledge`, `formula_metadata_json`。
- 题型枚举固定为 `SINGLE_CHOICE`、`MULTIPLE_CHOICE`、`TRUE_FALSE`、`FILL_BLANK`、`SHORT_ANSWER`、`CALCULATION`、`ESSAY`、`COMPREHENSIVE`。
- `options_json` 只用于选择题；`answer_json` 按题型保存正确选项、按空答案、参考文本或必要计算步骤。位置在同一试卷内唯一。

#### `mock_exam_question_source`

- `question_id`, `segment_id` 联合唯一。课程内题至少一个来源，来源必须属于任务知识版本的资料集合和模型允许上下文。
- 通用知识题不得伪造 segment 来源；其 `uses_general_knowledge=true`，只在参考答案模板中显示标识。

### Current REST API

所有接口继续使用 `/api/v1`、Cookie Session、CSRF、课程 ownership 隔离和统一错误体。

#### Knowledge Version and Active Tasks

- `POST /courses/{courseId}/knowledge-versions`：确认当前成功资料并创建知识版本/提纲任务，返回 `202`、版本摘要和 `taskId`。
- `GET /courses/{courseId}/knowledge-version`：返回当前发布版本、是否有未确认成功资料、活动/最近失败提纲任务及功能可用性。
- `POST /tasks/{taskId}/retry`：按原业务快照开启新的执行轮次；只允许匹配任务类型的失败状态。
- `GET /tasks/active`：返回当前用户所有对抽屉可见的活动任务，包含课程名、跳转目标、阶段和进度。
- `POST /tasks/{taskId}/cancel`：只允许尚未开始的任务；与 consumer claim 使用 CAS 竞争。
- `GET /tasks/events`：继续发送任务进度，并增加计划、模拟卷和答疑消息终态事件；业务结果仍以 REST/MySQL 为准。

#### Plan and Chat

- `POST /courses/{courseId}/plan/generate` 保留幂等键和请求体，当前返回 `202 {taskId, requestId}`，不在 HTTP 请求内等待模型。
- `GET /courses/{courseId}/plan` 继续返回唯一计划，增加 `knowledgeVersion` 和 `stale`。
- `POST /courses/{courseId}/messages` 保存问题和后台任务，返回 `202` 的 `PENDING` 消息；分页和重试接口继续恢复最终结果。

#### Mock Exam

- `POST /courses/{courseId}/mock-exams`：校验并规范化表单，锁定当前知识版本，返回 `202 {mockExamId, taskId}`。
- `GET /courses/{courseId}/mock-exams?page=&size=`：按创建时间倒序返回当前用户未删除记录和分页信息。
- `GET /mock-exams/{mockExamId}`：返回记录摘要、原始规范化参数、错误/警告和可用操作；不向前端暴露内部 segment 来源。
- `POST /mock-exams/{mockExamId}/retry`：接收可修改的新请求，创建关联的新记录和任务；原记录不变。
- `DELETE /mock-exams/{mockExamId}`：只允许终态记录，设置 `deleted_at` 并登记后台对象清理。
- `GET /mock-exams/{mockExamId}/files/{kind}/preview`：`kind=paper|answer`，ownership 校验后返回短时预签名 URL。
- `GET /mock-exams/{mockExamId}/files/{kind}/download`：校验后返回下载地址/响应，并设置安全化的“试卷名_生成时间_试卷/参考答案.pdf”。

所有模拟卷变更请求使用客户端幂等键或唯一业务约束防止重复点击；已删除、越权或课程已删除统一按不存在处理。

### Knowledge Version and Retrieval Rules

- 确认事务必须锁课程并再次查询所有资料状态；前端禁用不能代替服务端校验。
- 模拟卷检索强制按 `userId + courseId + knowledgeVersion.materialIds` 过滤。整课使用完整快照；选节点时使用节点来源和针对该子树的混合检索扩展上下文。
- 答疑检索维持 `userId + courseId + material.status=SUCCEEDED`，不受知识版本限制。
- 提纲发布新版时不取消旧版模拟卷任务；任务从创建起只使用保存的知识版本和节点范围。
- 计划发布继续检查知识版本/提纲和预期计划版本，任何变化都不能让旧结果覆盖当前计划。

### Mock Exam Generation Pipeline

1. `REQUIREMENTS_ANALYZED`：校验八类题型、逐题型题数、1–50 总题数、整数分值/1000 上限、可选 1–300 分钟时长、2000 字说明和结构化字段冲突；构造知识版本上下文。
2. 识别真题信号时同时参考 `MaterialType.PAST_EXAM` 和内容中的历年卷结构；教师例题只从内容结构识别。用户要求优先，真题其次，例题再次，不复刻原题。
3. `QUESTIONS_GENERATED`：按用户选定题型拆分批次，最多两路并发调用 LLM；每个批次独立校验，格式、来源或原题复刻失败时只重试该题型，成功后再合并为固定 JSON Schema。课程资料优先；允许通用知识时仅补足确实缺失的题型/题量，不联网。
4. `PAPER_VALIDATED`：Java 校验题型数量、选择题严格四选项、答案完整性、分值、范围、segment ownership、通用知识标识、受支持公式命令和内部一致性；模型误给非选择题附带的 options 会被确定性清空。
5. 对源真题/例题及本课程历史题做规范化相似度检查。直接复刻属于硬失败；历史近似和范围未完全覆盖记录为警告，允许发布。
6. `PDFS_GENERATED`：从同一份已校验结构化题目渲染试卷与参考答案，防止两个文件答案漂移。
7. 两个 PDF 都通过打开、非空、页数和对象存储校验后，事务写入对象键并标记成功；任何部分失败都删除/登记清理临时对象且不发布。

### XeLaTeX Security and Layout

- 只使用仓库内受版本控制的试卷/参考答案模板和允许的宏；用户、资料和模型文本必须经过 TeX 转义，不得作为命令、路径或模板片段执行。
- 公式字段只允许经过解析校验的常见数学 LaTeX 子集；禁止文件读写、网络、shell、宏定义、包加载和动态 include 命令。
- 以非特权用户在每任务独立临时目录运行 `xelatex -no-shell-escape`，设置编译超时、输出大小限制和进程退出校验；不把 RabbitMQ 文本直接拼成命令行。
- 模板使用 A4、Noto CJK 字体、姓名/学号栏、题型分区、题目分值和答题空间。试卷模板 `v2` 将每题渲染为独立编号块：题干固定正文宽度、分值固定栏右对齐、选项使用嵌套列表，并在结束段落后按题型预留作答空间；无输入总分/时长时不显示卷首字段。
- PDF 不显示生成日期、知识版本或 AI 标识；通用知识题只在参考答案中标明。

### Deletion and Cleanup

- 单套删除先在事务中设置 `deleted_at`，对用户立即不可见；运行中记录拒绝删除，排队任务需先成功取消。
- 对象删除采用独立、不可见的可重试清理记录/队列，不出现在全局任务列表。对象键必须确定且受 mock exam ID 隔离。
- 管理员跨存储核对增加模拟卷白名单和孤儿 PDF 统计。课程逻辑删除后，模拟卷查询和文件授权立即失效；最终清理覆盖记录、题目、来源和两个 PDF。

### Current Error Codes

- `KNOWLEDGE_VERSION_MATERIALS_BUSY`、`KNOWLEDGE_VERSION_EMPTY`、`KNOWLEDGE_VERSION_UNCHANGED`、`KNOWLEDGE_VERSION_STALE`。
- `PLAN_GENERATION_IN_PROGRESS`、`PLAN_KNOWLEDGE_VERSION_CHANGED`。
- `MOCK_EXAM_REQUIRES_OUTLINE`、`MOCK_EXAM_GENERATION_IN_PROGRESS`、`MOCK_EXAM_REQUEST_INVALID`、`MOCK_EXAM_INSTRUCTION_CONFLICT`。
- `MOCK_EXAM_SOURCE_INSUFFICIENT`、`MOCK_EXAM_FORMAT_INVALID`、`MOCK_EXAM_SOURCE_INVALID`、`MOCK_EXAM_FORMULA_UNSAFE`。
- `MOCK_EXAM_PDF_FAILED`、`MOCK_EXAM_NOT_FOUND`、`MOCK_EXAM_NOT_CANCELLABLE`、`MOCK_EXAM_NOT_DELETABLE`、`MOCK_EXAM_FILE_NOT_READY`。

### Current Required Tests

- 资料确认时的服务端状态复查、失败资料忽略、空集合拒绝、相同集合幂等和并发确认。
- 新旧知识版本发布、失败保留旧版、人工重要度不继承、旧版模拟卷继续完成和计划版本变化拒绝发布。
- 活动任务跨课程查询、可见类型过滤、排队取消与 consumer claim 竞争、SSE 断线 REST 恢复。
- 答疑离页后完成、全部成功资料立即可检索、任务不出现在抽屉、原消息重试不重复。
- 八类题型 DTO/Schema、题数 1–50、分值汇总/1000 上限、时长 1–300、补充说明长度及冲突校验。
- 严格资料模式失败、必要通用知识补足与参考答案标识、非法或跨版本来源拒绝。
- 真题/例题风格信号、源题复刻拒绝、历史近似警告、知识点覆盖不足警告。
- TeX 转义、危险命令拒绝、公式白名单、编译超时、中文/公式分页、两个 PDF 内容一致和原子发布。
- 历史分页与 ownership、短时预览/下载、重试关联、逻辑删除立即不可见、后台清理幂等及课程级清理。

上述能力已纳入 Phase 16 自动化与真实 Docker 验收；外部 AI 的 14 条真实报告覆盖当时的原七类题型，后续综合题仅有自动化回归、尚未重新运行付费 Golden Eval。实际结果见 `docs/TEST_REPORT.md`。
