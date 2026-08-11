# FinalWeek

FinalWeek 是面向大学生期末复习的课程资料理解工具。项目按 [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) 串行开发；当前仓库已完成 Phase 1–10（项目基线、完整学习闭环和 AI Golden Cases），Phase 11 的集成故障测试与作品集交付仍在进行，不把尚未完成的交付描述成可用能力。

## 当前可运行内容

- Vue 3.5 + Vite 8 + TypeScript 6 + Element Plus 前端，包含邮箱登录、课程列表、课程四区独立 URL、设置页和手机基础布局。
- Java 21 + Spring Boot 3.5 单体，已实现 Mailpit 邮箱验证码、Redis TTL/限流、Spring Session、CSRF、课程 CRUD/逻辑删除、8 门上限和 ownership 隔离。
- Redis 故障门禁覆盖登录、全部认证接口和未来 AI API 路径，统一返回 `503 SERVICE_REDIS_UNAVAILABLE`；静态落地页及公开 ping 仍可访问。
- 分片上传使用 Redis 元数据/完成分片集合/完成标记、MinIO 确定性临时对象、Redisson complete 锁和 MySQL 唯一约束；支持断点差集续传、完整 SHA-256、格式/大小校验、同课程去重与过期临时分片清理。
- 上传完成会在同一数据库事务创建资料与 `PENDING_PUBLISH` 解析任务；RabbitMQ 使用 durable 队列、publisher confirm、manual ack、每轮三次投递预算和 DLQ，Redis/SSE 推送进度，MySQL REST 状态负责断线恢复。
- PDF 使用 PDFBox 按页提取并对低文字密度页 OCR；PPTX 使用 POI 按幻灯片提取、LibreOffice 生成确定性 PDF 预览，必要时逐页 OCR；TXT/MD 保留段落号。
- MP3/MP4 使用 FFmpeg/ffprobe 校验时长和切分音轨，`paraformer-realtime-v2` 以本地 WAV 字节流识别并保存句级时间戳；MP4 按场景和最长间隔抽帧、感知哈希去重、OCR，并按时间线合并 ASR/OCR。单路失败时保留另一条有效内容并记录警告。
- `CourseSegment` 保存页码、幻灯片号、段落号或起止毫秒；语义优先的固定 token 上限分块保留来源位置，并以 `(material, chunk)` 生成稳定 UUID。任务依次持久化 `UPLOADED → CONTENT_EXTRACTED → CHUNKED → EMBEDDING_COMPLETED → COMPLETED` checkpoint，完整建索引后才原子标记资料成功。
- 百炼 `text-embedding-v4` 批量向量化后按稳定 segment UUID upsert Qdrant，并携带用户、课程、资料和 segment 隔离字段；Lucene 10 为每门课程维护 BM25 增量索引，同课程写入由本地锁和 Redisson 锁串行化，启动时可按 MySQL 成功资料核对并重建。
- 混合检索分别执行向量召回与 BM25，使用可配置 RRF（默认 `K=60`）融合、去重并回到 MySQL 解析原文；只返回 `SUCCEEDED` 资料，单路故障自动降级、双路故障明确返回不可用。
- 知识大纲以独立 RabbitMQ 任务执行，使用混合检索上下文和百炼 JSON 模式生成最多四层、100 节点的树；JSON Schema/Java 双重约束、一次格式修复、来源归属校验和生成版本 CAS 保证失败或旧任务不会覆盖当前大纲。每课最多一个活动生成任务，重复请求返回原任务，限流发生在建任务前。
- 大纲默认展开前两层，知识点展示高/中/低重要度并可即时保存人工调整；来源抽屉按 PDF 页、幻灯片页、文本段落或音视频时间点定位。重新生成前明确确认，并在成功原子替换时覆盖旧结构及人工重要度。
- 简单复习计划使用考试日期、每日分钟数、整体掌握程度和目标成绩同步生成按日期排列的知识点任务；服务端校验节点属于当前提纲、单日与总时间预算，支持完成和撤销完成。客户端幂等键、请求记录和当前计划版本 CAS 保证重试不重复调用已成功请求、旧请求不能覆盖新版本，生成失败保留旧计划。
- 每门课程维护一条可游标翻页的连续文字问答历史。提问先按用户做 Redis 限流，再执行课程混合检索，最后以非流式同步 REST 调用模型；回答正文、课程来源与可选通用知识补充结构化分离，来源必须同时属于本次上下文、当前用户和课程。问题先以 `PENDING` 持久化，成功或失败均落状态，失败重试复用原消息 ID，客户端取消只中止本地等待并重新同步服务端历史。
- PDF/PPTX 预览统一为短时授权 PDF URL；TXT/MD/MP3/MP4 使用原文件短时 URL，MinIO 支持浏览器 Range 请求。来源片段接口同时校验用户、课程和资料归属。
- 单个 Compose 项目启动前端、后端、MySQL、Redis、RabbitMQ、MinIO、Qdrant 和 Mailpit；Web API 与未来的 MQ consumer 保持同一后端进程。
- JUnit、Testcontainers、Vitest 和 Playwright 测试基础。

## 架构基线

```text
Vue 3 -- REST / SSE / chunk upload --> Spring Boot (single JVM)
                                          |-- MySQL
                                          |-- Redis
                                          |-- RabbitMQ
                                          |-- MinIO
                                          |-- Qdrant
                                          `-- local Lucene (course BM25)
```

## 一键启动

前置要求：Docker Desktop 与 Docker Compose v2。

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

默认入口：

- 应用：http://localhost:5173
- 后端健康检查：http://localhost:8080/actuator/health
- OpenAPI UI：http://localhost:8080/swagger-ui.html
- RabbitMQ 管理页：http://localhost:15672
- MinIO 控制台：http://localhost:9001
- Mailpit：http://localhost:8025
- Qdrant：http://localhost:6333/dashboard

本地登录流程：打开应用后进入“邮箱验证码登录”，填写任意合法邮箱并发送验证码，再到 Mailpit 查看 6 位验证码。验证码默认 10 分钟有效，同一邮箱 60 秒内不可重复发送。

如果宿主机已有 MySQL 或 Redis，在 `.env` 中修改 `MYSQL_HOST_PORT`、`REDIS_HOST_PORT` 即可；容器间连接仍使用标准内部端口。

停止服务：

```powershell
docker compose down
```

该命令不会删除命名卷。只有明确需要丢弃本地数据时才使用 `docker compose down -v`。

## 开发模式

开发时可以只启动中间件，再在宿主机运行前后端。这是开发便利模式，不替代完整 Compose 交付。

```powershell
docker compose up -d mysql redis rabbitmq minio qdrant mailpit

Set-Location backend
.\mvnw.cmd spring-boot:run

Set-Location ..\frontend
corepack pnpm@10.18.3 install --frozen-lockfile
corepack pnpm@10.18.3 dev
```

宿主机后端连接非默认映射端口时，需要相应设置 `MYSQL_URL` 与 `REDIS_PORT`。

## 测试

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp -s .mvn\settings.xml test

Set-Location ..\frontend
corepack pnpm@10.18.3 test
corepack pnpm@10.18.3 build
```

当前构建实际执行后端 59 项 JUnit 与前端 5 项 Vitest，并完成类型检查和生产构建。自动化测试覆盖登录、权限、Redis 门禁、分片写入顺序、缺片、顺序合并、哈希、重复资料、过期清理、任务投递失败补偿、旧执行轮次去重、原生/扫描 PDF、文本分段、音视频双路合并、固定 overlap 分块、稳定 segment ID、Lucene 并发/幂等/重建、RRF 去重与检索降级、大纲格式修复、非法来源、活动任务去重、限流不建任务、旧生成版本拒绝覆盖、计划节点与时间预算、计划限流不调用模型、幂等重放及计划版本 CAS、问答结构和引用白名单、无证据不调用模型、问答限流不检索/不调用模型、失败消息原位重试，以及前端 30%/70%/99% 差集计算。

Phase 5 真实验证使用五种格式逐一走完登录、建课、分片上传、RabbitMQ 消费和来源读取：PDF/PPTX/TXT 生成正确页/幻灯片/段落位置，MP3 生成句级时间戳，MP4 以一次 ASR 和一次 OCR 合并音轨与画面文字；五种预览均验证 Range `206`，来源接口验证所属用户 `200`、其他用户 `404`、匿名 `401`。完整 Playwright 主流程会在 Phase 11 随业务闭环一起验收。目前没有 AI Golden Case 评测结果、性能数字或生产 SLA 声明。

Phase 6 真实验证使用无仓库信息的合成文本走完上传、分块、百炼 embedding、Qdrant 与 Lucene：任务以一次 API 调用成功，五个 checkpoint 顺序完整，Qdrant point ID 与 segment ID 相同且隔离 payload 完整，课程 Lucene 索引落盘。混合排序、单路降级、所有权过滤、重复 upsert、同课程并发写入和故障重建由自动化测试覆盖。

Phase 7 真实验证在同一合成课程上使用百炼 `qwen3.7-plus` 生成大纲：并发请求返回同一活动任务，任务一次调用成功并依次完成 `CONTEXT_RETRIEVED → OUTLINE_GENERATED → COMPLETED`；7 个节点均带有效来源。人工重要度跨刷新保留，其他用户修改返回 404；故意触发的持久化失败完整回滚并保留旧版本，修复后版本 3 原子替换版本 1，失败的版本 2 未覆盖数据，最终仅保留一份当前大纲且无活动任务。

Phase 8 真实验证使用当前提纲和百炼 `qwen3.7-plus` 同步生成计划：首次生成 10 项、总计 300 分钟且单日不超过 60 分钟，同一幂等键重放直接返回版本 1；完成与撤销均持久化，其他用户修改返回 404。再次生成以 3 项、单日最多 45 分钟的版本 2 原子替换版本 1，旧完成状态被覆盖，数据库始终只有一份当前计划。

Phase 9 真实验证使用同一合成课程走完混合检索和百炼 `qwen3.7-plus` 问答：问题与回答均为 `SUCCEEDED`，回答返回 1 个有效课程来源且未使用通用知识；历史接口连续返回 2 条消息，按原用户消息 ID 重试直接重放既有回答且不产生重复记录。来源归属读取为 `200`，另一账号读取课程历史和重试原消息均为 `404`；Redis fail-closed 路径及限流后不检索、不调用模型由自动化测试覆盖。

Phase 10 提供 8 个固定 Golden Case，覆盖原生 PDF、扫描 PDF、PPTX、音频、音视频互补、真题重点、可回答问题和不可回答问题。固定输入是 Phase 5 抽取结果的版本化快照，启动评测前逐一校验 SHA-256；案例、人工标注、输入清单和报告 Schema 分别位于 `evals/cases`、`evals/annotations`、`evals/fixtures` 和 `evals/report.schema.json`。

2026-08-11 使用真实 `qwen3.7-plus` 运行的首份报告为 [`evals/reports/golden-20260811-130019.json`](evals/reports/golden-20260811-130019.json)：8/8 案例无执行失败，核心知识点覆盖率 `100%`、引用正确率 `100%`、未标记无依据陈述比例 `0%`，达到默认 `80% / 90% / 10%` 阈值。重要度一致率只有 `33.3%`：模型把四个没有教师强调或真题信号的顶层概念判为高，而人工标注为中；这项当前没有通过阈值，报告保留原始输出，后续应通过更明确的重要度评分规则而不是修改结果来掩盖差异。

运行真实评测（会读取仓库根目录忽略提交的 `.env`，调用模型并覆盖 `latest.json`）：

```powershell
Set-Location backend
.\mvnw.cmd -Pgolden-eval verify
```

## 配置与密钥

首版所有数量、大小、TTL、限流、TopK、RRF、同步超时、历史条数与模型 ID 都集中在类型化后端配置和 [`.env.example`](.env.example) 中。不要提交 `.env` 或真实 `BAILIAN_API_KEY`。

## 规范

产品范围、技术选型、交互流程、后端约束、前端规范和阶段计划均在 [`docs`](docs) 目录。实现发生冲突时，以六份规范文档的共同约束和 `IMPLEMENTATION_PLAN` 阶段顺序为准。
