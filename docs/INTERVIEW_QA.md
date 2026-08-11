# FinalWeek 面试问答草稿

> 这是基于仓库事实整理的面试表达模板。使用者必须按自己的真实参与过程、理解深度和实际贡献修改；不要照稿捏造个人经历、团队规模或线上指标。

## 30 秒项目介绍

FinalWeek 是一个 Java 后端求职向的课程资料理解 MVP。用户通过邮箱验证码登录，分片上传 PDF、PPTX、TXT/MD、音视频；Spring Boot 用 RabbitMQ 异步完成抽取、OCR/ASR、分块和 Qdrant + Lucene 混合索引，再生成带来源提纲，并提供同步复习计划和课程问答。项目重点不是聊天 UI，而是大文件恢复、异步状态机、checkpoint 幂等、课程隔离、引用校验和可复现测试。

## 为什么是 Spring Boot 单体，而不是微服务或 Python Worker？

MVP 的目标是把一条链路做完整并能解释。Web、consumer 和编排同 JVM 能减少部署与分布式一致性面；Java 直接使用 PDFBox、POI、FFmpeg 进程和供应商客户端足够完成需求。本地 Lucene 也天然要求单实例。代价是不能横向扩容，README 已明确这是部署边界，而不是生产架构承诺。

## 为什么上传要分片并把 Redis、MinIO、MySQL 分工？

MinIO 保存真实字节，Redis 保存有 TTL 的上传会话和已完成分片集合，MySQL 保存归属及最终业务记录。分片先写 MinIO，成功后才记 Redis；恢复时取集合差集。complete 用 upload 锁、确定性对象键、完整 SHA-256 和数据库唯一约束处理重复/并发调用。Redis 丢失不会成为最终资料真相源。

## 不用 Outbox，怎样处理 MySQL 与 RabbitMQ 的间隙？

先在数据库创建 `PENDING_PUBLISH`，提交后获取限流令牌并发布；publisher confirm 成功才条件改为 `QUEUED`，失败变为 `PUBLISH_FAILED`，允许显式重投。消费者可从 `PENDING_PUBLISH`、`PUBLISH_FAILED`、`QUEUED`、`RETRYING` CAS 到 `PROCESSING`，因为合法消息到达本身就是已投递证据。confirm 的条件更新不能倒退后续状态。这个方案比直接写 `QUEUED` 诚实，但仍不是 Outbox 的强投递保证，适合当前单体 MVP。

## MQ 重复投递与重试如何避免重复结果？

Redisson 锁只减少并发，最终保障来自 MySQL 状态 CAS、task/stage 唯一键、`(material_id, chunk_no)` 唯一键，以及 Qdrant/Lucene 稳定 ID upsert。消息携带 `executionRound`；旧轮次消息 claim 失败后直接 ack。外部 API 重试次数与 MQ delivery 次数分开记录，避免把两个预算混成一个含糊数字。

## checkpoint 为什么只做到阶段级？

需求只需要在内容提取、分块、索引等昂贵阶段恢复。阶段结果先幂等落地，checkpoint 最后提交；失败重投从最近成功阶段继续。通用页面级/分块级 checkpoint 引擎会显著扩大状态组合和失效传播问题，超出求职 MVP 范围。

## 为什么 Qdrant 和 Lucene 都要用？

向量检索擅长同义表达，BM25 擅长公式、缩写和精确术语。两路各取 TopK 后用 RRF 融合，算法简单、可解释且不依赖分数校准。返回前根据 segment ID 回 MySQL 取原文与位置；Qdrant/Lucene 都可重建，不承担业务真相。单路故障可降级，双路失败则拒绝让模型脱离课程资料生成资料型回答。

## 如何防止“有引用但引用是假的”？

向量查询强制过滤 userId/courseId，BM25 使用课程级索引；模型只看到检索得到的 segment。结构化回答返回 segment ID，后端再次校验 ID 属于当前用户、课程且确实在本次上下文白名单中，最后前端通过授权预览接口定位页码、幻灯片、段落或时间点。

## 为什么提纲异步，而计划和问答同步？

提纲需要面向整门课检索、组织树并校验大量来源，耗时长且适合任务进度与 checkpoint。计划和单次问答的用户预期更接近一次请求，首版采用可配置超时的同步 REST。计划另有幂等键、请求记录和版本 CAS；问答先落 PENDING 用户消息，超时转 FAILED 并按原消息 ID 重试。

## Redis 挂了为什么全部认证接口返回 503？

Session、锁和限流都依赖 Redis。继续处理认证请求可能造成身份状态与成本保护不一致，所以选择 fail closed；静态落地页和公开健康接口仍可读。测试同时覆盖登录、普通认证查询和 AI API，不只测一个入口。

## 删除为什么不是立即跨存储强一致？

课程先在 MySQL 事务中检查运行任务并逻辑删除；MinIO、Qdrant、Lucene 没有共同事务。管理员命令以 MySQL 活跃数据为白名单做可重复核对，默认 dry-run。当前方案不提供回收站，也不承诺生产级合规删除；如果走向生产，应增加审计、保留策略、备份与更可靠的异步清理作业。

## 哪些测试最能说明工程能力？

- MySQL Testcontainers 用两个线程竞争同一 CAS，证明只有一个 consumer 能 claim，并验证随后取消失败、delivery count 为 1、checkpoint 唯一键生效。
- Playwright 主动断开 SSE，仍通过 REST 获得解析终态，刷新恢复后继续提纲、计划、问答和来源抽屉。
- consumer 测试验证可重试异常在预算内 nack/requeue，到上限 reject；重复、取消和旧轮次消息只 ack。
- 第三方故障测试验证 429/503 执行三次预算，永久 400 不重试。
- Golden Cases 固定输入哈希、模型 ID、配置快照和 JSON Schema，避免只展示挑选后的成功截图。

## 当前最明显的缺陷是什么？

真实 Golden Case 的 coverage、引用正确率和无依据陈述比例通过，但重要度一致率只有 `33.3%`。模型容易把顶层概念整体判高。合理改进方向是明确重要度 rubric、增加负例和信号消融，而不是修改标注或隐藏结果。另一个结构性边界是本地 Lucene 导致单实例，公网部署前需要重新评估共享检索设施与一致性方案。

## 如果再做一周，优先做什么？

先扩大真实资料集并改进重要度评测与提示词；再补 Redis/RabbitMQ/MinIO/Qdrant 多容器故障集成测试和性能基线；最后才考虑共享搜索、异步清理审计和部署安全。不会先加入复杂 Agent，因为当前瓶颈有明确证据指向重要度质量和运行可靠性。

## AI Coding 与个人贡献应怎样讲？

可以如实说明 AI 用于需求对齐、代码草拟、测试枚举或文档整理，但必须能独立解释最终状态机、事务边界、失败路径和测试证据。建议按真实情况补充：你亲自做了哪些决策、如何审查生成代码、遇到过什么失败、怎样用日志/测试定位，以及哪些建议被你否决。不要声称所有代码都手写，也不要把 AI 输出本身当作个人技术判断。

## 容易被追问的数字

- 支持 5 类扩展名：PDF、PPTX、TXT/MD、MP3、MP4（TXT/MD 是同一文本解析分支）。
- 外部 API 默认最多 3 次执行；MQ 每个 execution round 默认最多 3 次 delivery，两者分开计数。
- Golden Case 8 个；coverage `100%`、citation correctness `100%`、ungrounded `0%`、importance agreement `33.3%`。
- Playwright 9 个项目化用例：5 passed、4 designed skips、0 failed，22.4 秒。
- 这些是本地本次可复现结果，不是线上吞吐、并发量或 SLA。
