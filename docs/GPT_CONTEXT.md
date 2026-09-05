# FinalWeek 当前实现上下文（供 GPT / 面试学习使用）

> 更新时间：2026-08-29。
>
> 本文只描述当前仓库已经实现的状态，不保留被后续阶段替代的历史方案。把项目资料提供给 GPT 时，应优先提供本文；其他文档若与本文冲突，以当前源码、配置、数据库迁移和测试证据为最终依据。

## 1. 文档阅读优先级

发生冲突时按以下顺序判断：

1. 当前源码、`application.yml`、`.env.example`、`compose.yaml` 和 Flyway migration。
2. 本文 `docs/GPT_CONTEXT.md`。
3. 根目录 `README.md`、`docs/USER_TEST_FIXES_2026-08-23.md` 和 `docs/TEST_REPORT.md` 的最新日期记录。
4. `PRD.md`、`APP_FLOW.md`、`BACKEND_STRUCTURE.md`、`TECH_STACK.md`、`FRONTEND_GUIDELINES.md` 中标为“当前实现”的内容。
5. `IMPLEMENTATION_PLAN.md` 以及其他文档中明确标为“历史实现”的内容只用于解释演进过程，不代表当前运行方式。

不要把以下历史方案当成当前实现：

- 课程只有四个功能区；当前是五个功能区。
- 用户可以在没有新增资料时手动反复生成提纲；当前由“资料已上传完毕”创建知识版本并自动生成提纲。
- 计划和问答在 HTTP 请求内同步等待模型；当前均通过 RabbitMQ 后台执行。
- 应用把音频按 60 秒切成多个 ASR 会话；当前为整份媒体生成一个 WAV，再通过 SDK 本地文件非流式识别。
- RabbitMQ 发布保护默认值为用户/全局每分钟 `5/30`；当前默认值为 `30/120`。
- 模拟卷只有七类题型；当前代码增加了综合题，共八类。

## 2. 项目定位与边界

FinalWeek 是面向大学生期末复习的本地求职版 MVP。用户上传 PDF、PPTX、TXT/MD、MP3 或 MP4 课程资料，系统异步解析并构建带来源位置的课程知识库，随后提供知识提纲、复习计划、课程问答和模拟卷。

项目目标是完成一个可本地运行、可演示、可测试、能深入解释技术取舍的 Java 后端项目，不宣称是生产级 SaaS。

当前明确边界：

- 单个 Spring Boot 实例；Web API 与 RabbitMQ consumer 在同一 JVM。
- 本地 Lucene 索引可从 MySQL 重建，因此当前不支持后端横向扩容。
- 只接入百炼，不包含多模型路由、联网搜索或复杂自主 Agent。
- 没有公网生产流量、生产 SLA、正式压测结论、自动备份和生产告警。
- 不承诺识别潦草手写、复杂图表、复杂视觉公式或纯视觉动作。
- AI 限流、TopK、超时等数字是本地可配置默认值，不是生产最优参数。

## 3. 当前整体架构

```text
Vue 3 浏览器端
  ├─ REST / Cookie Session / CSRF
  ├─ SSE 任务状态通知
  └─ 分片上传
          │
          v
Java 21 + Spring Boot 3.5 单体
  ├─ MySQL：用户、课程、资料、任务终态、checkpoint、知识版本和业务结果
  ├─ Redis：Session、验证码、上传临时状态、Redisson 锁、限流和短期进度
  ├─ RabbitMQ：资料解析、提纲、计划、模拟卷和隐藏答疑后台任务
  ├─ MinIO：上传分片、原始资料、预览文件、中间产物和模拟卷双 PDF
  ├─ Qdrant：课程片段向量索引
  ├─ Lucene：课程级 BM25 索引，可从 MySQL 重建
  ├─ 百炼：ASR、OCR、Embedding、LLM
  └─ XeLaTeX：从已校验 DTO 生成试卷和参考答案 PDF
```

MySQL 是任务终态、checkpoint 和业务结果的真相源。Redis、Qdrant、Lucene 和 SSE 都不能单独决定业务终态。

## 4. 当前端到端主链路

### 4.1 上传与资料发布

1. 前端向后端初始化上传，获得 `uploadId`、分片大小和总数。
2. 浏览器最多并行处理 3 个文件；每个文件内部按当前协议顺序上传分片。
3. 分片先写入 MinIO，成功后才把分片序号加入 Redis Set。
4. 断线恢复时查询 Redis 中已完成分片，只上传差集。
5. 前端确认全部分片成功后主动调用 complete。
6. 后端获取 `upload-complete:{uploadId}` Redisson 锁，校验归属和完整性，合并并计算完整文件 SHA-256。
7. 同课程内容哈希重复时复用已有资料；数据库唯一约束是最终并发防线。
8. 后端创建资料和 `PENDING_PUBLISH` 后台任务，再执行 RabbitMQ 发布限流与投递。
9. publisher confirm 成功后任务条件更新为 `QUEUED`；限流、发送或 confirm 失败进入可恢复的 `PUBLISH_FAILED`，不会把已成功上传的文件回滚掉。

### 4.2 RabbitMQ 后台任务

通用任务状态为：

```text
PENDING_PUBLISH → QUEUED → PROCESSING → SUCCEEDED
       │             │          │
       v             v          v
PUBLISH_FAILED   CANCELLED   RETRYING → FAILED
```

- 消费者以 MySQL CAS 同时抢占状态和执行租约，写入消息 ID owner；运行期间心跳续租，过期后扫描器 CAS 回收并重新投递。消费者不再使用冗余的 Redisson 任务锁。
- publisher confirm 回调只能条件更新，不能把已经进入处理或终态的任务倒退为 `QUEUED`。
- 消息携带任务和业务标识，不携带文件或课程全文。
- 外部 API 单次调用和任务级重新投递分别记录预算，永久错误不重试。
- 手动重试复用原 `taskId`、business key 和已有 checkpoint，并通过 `executionRound` 隔离旧轮次消息。
- manual ack 只在业务成功或确认无需执行时发生；预算内可重试错误 nack/requeue，耗尽后拒绝并记录失败。

### 4.3 资料解析与索引

资料解析 checkpoint：

```text
UPLOADED
→ CONTENT_EXTRACTED
→ CHUNKED
→ EMBEDDING_COMPLETED
→ COMPLETED
```

- PDF 使用 PDFBox 提取；低文字密度页 OCR。
- PPTX 使用 POI 提取；必要时 LibreOffice 转换为 PDF 后 OCR。
- TXT/MD 保留段落来源。
- MP3/MP4 使用 FFmpeg 为整份媒体生成一个 16 kHz 单声道 WAV，通过 `paraformer-realtime-v2` Java SDK 本地文件非流式识别并读取句级时间戳；应用层不再按 60 秒切 ASR 会话。
- MP4 同时按场景变化和最长间隔抽帧、感知哈希去重并 OCR；ASR/OCR 按时间线合并，单路失败时保留另一条有效内容并记录警告。
- 内容按语义边界和 token 上限分块，保存页码、幻灯片、段落或起止时间。
- Embedding 写入 Qdrant；同课程 Lucene 增量写入由 `lucene-index:{courseId}` 锁串行提交。
- 只有资料原子进入 `SUCCEEDED` 后，其片段才允许被检索，避免暴露半完成索引。

### 4.4 混合检索

1. Qdrant 按用户、课程和必要的资料白名单执行向量召回。
2. Lucene 执行 BM25 关键词召回。
3. 使用 RRF 融合两路排名，默认 `RRF_K=60`。
4. 按稳定 segment ID 去重，再回到 MySQL 获取原文与来源位置。
5. 单路故障时降级到另一路；双路都失败时明确返回不可用，不让模型脱离资料生成资料型答案。

### 4.5 知识版本与提纲

1. 用户上传并解析多份资料后点击“资料已上传完毕”。
2. 服务端在课程锁内重新检查资料状态，快照当时全部成功资料，创建不可变知识版本。
3. 相同资料集合由集合哈希阻止重复确认；失败/取消资料必须由用户明确忽略。
4. 确认成功后自动创建异步提纲任务；没有新增成功资料时不提供手动重复生成。
5. 提纲只使用该知识版本的资料，输出经过 JSON Schema、Java Validator 和来源 ownership 校验。
6. 新版成功前旧知识版本和旧提纲继续可用；失败不能覆盖旧版。
7. 人工重要度只修改当前版本，不自动继承到新版本。

### 4.6 计划、答疑和模拟卷

- 计划：固定提交时知识版本，通过可见 RabbitMQ 后台任务生成；新计划成功才原子替换旧计划，失败或版本竞争保留旧计划。
- 答疑：任一资料成功后即可使用，检索当前全部成功资料；问题与隐藏后台任务同事务创建，返回 `202`，离页后仍处理。回答正文、课程来源和通用知识补充结构化分离。
- 模拟卷：固定当前知识版本和可选提纲子树，支持单选、多选、判断、填空、简答、计算、论述、综合八类题型。结构化题目经服务端校验后，从同一 DTO 生成试卷和参考答案；两份 PDF 都成功上传并复核后才原子发布。

## 5. 当前限流与并发默认值

- RabbitMQ 发布保护：单用户每分钟 `30`，全局每分钟 `120`。
- 计划生成：单用户每分钟 `5`。
- 问答：单用户每分钟 `20`。
- 模拟卷：单用户每分钟 `3`。
- RabbitMQ consumer：默认并发 `2`、最大并发 `4`、prefetch `1`。

这些值均来自当前配置，是本地演示默认值，可通过环境变量修改。

## 6. 幂等与一致性边界

不要把“用了 Redisson”直接等同于完整幂等。当前防线分层如下：

- MySQL CAS 执行租约：决定任务唯一执行者；owner 栅栏阻止租约过期后的旧消费者提交终态，心跳与扫描器负责宕机恢复。
- Redisson 锁：仅用于上传合并、内容哈希和 Lucene 写入等独立临界区，不用于 RabbitMQ 任务消费。
- MySQL CAS：控制任务状态只能合法前进。
- 唯一约束：阻止重复资料、重复 checkpoint 和重复业务记录。
- 稳定 ID 与确定性对象键：重复写 Qdrant、Lucene、MinIO 时覆盖同一结果。
- 任务终态检查和 `executionRound`：过滤重复消息、取消消息和旧轮次消息。
- 补偿与清理：处理 MinIO、MySQL、RabbitMQ 之间没有分布式事务产生的残留窗口。

项目没有使用 Outbox、Saga 或分布式事务框架，也不宣称 MinIO、MySQL、RabbitMQ 之间强一致。

## 7. AI 输出约束与评测证据

- LLM 使用固定 JSON Schema；Java 再校验结构、数量、来源归属和业务规则。
- 模型只能引用本次提供且属于当前用户/课程/知识版本的 segment ID。
- 通用知识必须单独标记，不能伪造课程来源。
- 当前真实 Golden 报告包含 14 条案例，历史运行覆盖原七类模拟卷题型；综合题后续已加入代码和自动化测试，但尚未重新执行付费 Golden Eval，因此不能宣称综合题已通过真实模型评测。
- 最新记录的宿主机后端回归为 115 tests、0 failures、0 errors、1 skipped；不同日期和不同运行环境的测试数不能相加。
- Testcontainers 当前重点验证 MySQL CAS、计数、迁移和唯一约束；其他中间件主要由单元/集成测试及真实 Docker Compose 主链路共同验证。

详细证据以 `docs/TEST_REPORT.md` 的最新日期记录和 `evals/reports` 中的不可变 JSON 报告为准。

## 8. 当前五个课程功能区

```text
/courses/:id/materials
/courses/:id/outline
/courses/:id/plan
/courses/:id/chat
/courses/:id/mock-exams
```

它们分别对应资料、知识提纲、复习计划、课程问答和模拟卷。

## 9. 建议给 GPT 的开场要求

可以把下面这段和本文一起发送给 GPT：

> 请只按 `GPT_CONTEXT.md` 描述的当前实现帮助我学习 FinalWeek。其他文档中的历史 Phase 方案只能用于解释演进，不得覆盖当前实现。回答时区分“代码已实现”“自动化测试验证”“真实外部模型验证”和“生产环境尚未验证”四种证据等级。遇到文档与源码冲突时先指出冲突，不要自行拼接两套方案。
