# FinalWeek 量化测试结果

测试日期：2026-08-22（Asia/Shanghai）

## 结论

- RabbitMQ 重复消息测试达到需求规模：5 份资料均分别接受 1、5、10、20 次重复投递，共 180 条；重复业务结果 0，MySQL Segment/Checkpoint、Qdrant point、Lucene 哈希、任务终态和 API 调用次数均未变化。推荐写入简历，但必须限定为“已完成任务的重复投递场景”。
- 异步 HTTP 的 100 次正式样本测到的是幂等重放路径，不是 100 个新任务。资料、计划、模拟卷重放 P95 分别为 16.891 ms、19.462 ms、24.493 ms，均为 100/100 返回 202；只建议作为面试补充。
- 冷创建每接口只有 1 个最终成功样本：资料 81.394 ms、提纲 60.184 ms、计划 107.569 ms、模拟卷 119.829 ms，不能据此计算 P95。
- 先前一次提纲冷创建返回 HTTP 500，但后台任务实际创建并成功；坏样本已保留，因此当前不能宣称提纲受理稳定。
- Checkpoint 缺少可控故障注入开关；Hybrid RAG 缺少至少 50 条人工标注的 Segment ground truth。本轮不以代码推测冒充指标。

## 测试环境

| 项目 | 值 |
|---|---|
| CPU | Intel Core i7-13650HX，14 核 / 20 线程 |
| 主机内存 | 23.8 GiB；测试时可用约 3.38 GiB |
| Docker VM 内存 | 约 11.57 GiB |
| 操作系统 | Windows 11 家庭中文版 64 位，10.0.26200 |
| Docker | Docker Desktop Client/Server 29.7.2，WSL2 kernel 6.18.33.2 |
| JDK | Eclipse Temurin OpenJDK 21.0.8+9 LTS |
| MySQL / Redis / RabbitMQ | 8.4.10 / 8.4.5 / 4.2.9 |
| Qdrant | 1.18.1，`finalweek_segments`，1024 维 Cosine |
| 模型 | LLM `qwen3.7-plus`；Embedding `text-embedding-v4`；OCR `qwen-vl-ocr-latest`；ASR `paraformer-realtime-v2` |
| 模型 API | 阿里云百炼；API key 未写入报告 |
| 网络 | HTTP 压测走 localhost；初始化资料、Embedding 与提纲会访问外部模型 API |

结果受 Docker Desktop、JVM 热身、后台模型任务和主机负载影响。

## P0-1：异步任务接口响应时间

### 真实入口与方法

| 业务 | HTTP 入口 | 说明 |
|---|---|---|
| 资料解析 | `POST /api/v1/uploads/{uploadId}/complete` | 完成分片合并后创建解析任务 |
| 提纲生成 | `POST /api/v1/courses/{courseId}/knowledge-versions` | 确认资料集合时创建任务，不存在单独 outline generate 接口 |
| 计划生成 | `POST /api/v1/courses/{courseId}/plan/generate` | 支持 Idempotency-Key |
| 模拟卷生成 | `POST /api/v1/courses/{courseId}/mock-exams` | 支持 Idempotency-Key |

脚本创建隔离账号/课程，上传固定 TXT，并真实等待资料解析、Embedding 和提纲。每个接口记录 1 次冷创建；资料完成、计划和模拟卷再以相同 uploadId/Idempotency-Key 执行 10 次预热、100 次正式重放。统计只覆盖发出 HTTP 请求到收到 202 + taskId，不含后台完成时间。所有异常值均保留。

提纲确认不支持相同资料集合重放，重复确认会返回 `KNOWLEDGE_VERSION_UNCHANGED`，因此没有制造 100 个虚假的提纲成功样本。

### 冷创建原始结果

每项 n=1，不能计算分位数。

| 接口 | HTTP | 延迟 |
|---|---:|---:|
| 资料解析 | 202 | 81.394 ms |
| 提纲生成 | 202 | 60.184 ms |
| 计划生成 | 202 | 107.569 ms |
| 模拟卷生成 | 202 | 119.829 ms |

### 幂等重放正式结果

| 接口 | 202 / 总数 | Average | P50 | P90 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|
| 资料完成 | 100 / 100 | 12.547 ms | 11.676 ms | 14.641 ms | 16.891 ms | 20.697 ms | 52.878 ms |
| 计划生成 | 100 / 100 | 12.730 ms | 11.342 ms | 17.441 ms | 19.462 ms | 29.247 ms | 30.569 ms |
| 模拟卷生成 | 100 / 100 | 15.558 ms | 14.139 ms | 22.428 ms | 24.493 ms | 32.401 ms | 39.661 ms |

已独立从 CSV 重算 P95、最大值和 202 数量，结果一致。

### 保留的失败样本

2026-08-22 11:45 左右，账号 `finalweek-metrics-20260822-114437@example.com` 的提纲确认请求返回 HTTP 500，requestId `ee9b2911-6020-4935-9aef-be7f3b08fc1b`。数据库中的 `GENERATE_OUTLINE` 任务仍被创建并最终为 `SUCCEEDED`，说明错误发生在受理事务之后或响应阶段；现有日志没有异常栈，不能断言根因。下一次完整运行返回 202/60.184 ms。

因此不能写“异步接口受理 P95 控制在 XX ms”。分类：B（重放数据可作面试补充，冷创建数据不建议写简历）。若引用，必须明确：

> 在本机 Docker 幂等重放测试中，资料完成、计划与模拟卷接口各 100 次均返回 202，P95 分别为 16.9 ms、19.5 ms 和 24.5 ms。

复现：

```powershell
D:\PowerShell\7\pwsh.exe -NoProfile -File .\resume-metrics\scripts\benchmark-async-acceptance.ps1
```

## P0-2：RabbitMQ 重复消息幂等

### 方法

从真实数据库选择最近 5 个 `PARSE_MATERIAL/SUCCEEDED` 且有 Segment 的任务。每个任务使用完全相同的 taskId、executionRound 和 messageId，分别重复发布 1、5、10、20 次；每份资料 36 条，总计 180 条。消息通过 `finalweek.tasks` exchange / `parse` routing key 发布。

前后比较任务终态、delivery/API attempt、Checkpoint 数、MySQL Segment 数、按 materialId 过滤的 Qdrant point 数、课程 Lucene 文件内容哈希和 DLQ 数量。

### 结果

| 指标 | 结果 |
|---|---:|
| 测试资料数 | 5 |
| 每份资料重复负载 | 1、5、10、20 |
| 重复投递总数 | 180 |
| 无业务状态变化的资料 | 5 / 5 |
| 重复业务结果 | 0 / 5 |
| 本次成功拦截的重复投递 | 180 / 180 |
| DLQ | 15 → 15 |

5 份资料前后均保持：任务 `SUCCEEDED`；delivery/API attempt 未增加；Checkpoint 数均为 5；Segment 数 1、1、2、3、2；Qdrant point 数 1、1、2、3、2；Lucene 哈希不变。重复消息没有进入解析/模型调用流水线。

限制：只覆盖任务已成功后的重复消息，不覆盖首条消息仍在 PROCESSING 时的并发重复、消费者崩溃窗口或 RabbitMQ 重启重投。“180/180”只描述本次样本，不泛化为所有场景 100% 幂等。

分类：A（推荐写入简历）。推荐表述：

> 基于业务键、MySQL CAS 与确定性向量/索引写入处理重复消息；在 5 份资料、共 180 次重复投递（单阶段最高连续 20 次）测试中，未产生重复 Segment、Qdrant point 或 Lucene 索引变更。

复现：

```powershell
D:\PowerShell\7\pwsh.exe -NoProfile -File .\resume-metrics\scripts\test-rabbitmq-idempotency.ps1
```

## 未形成指标的项目

### P0-3 Checkpoint 故障恢复

代码存在 `CONTENT_EXTRACTED`、`CHUNKED`、`EMBEDDING_COMPLETED`、`COMPLETED` Checkpoint，完成资料通常有 5 条阶段记录；但无测试专用故障注入开关。杀容器无法稳定控制三个阶段，也不能满足每阶段至少 10 次。应新增仅 benchmark profile 启用的 stage failure hook 后再测。分类：C。

### P0-4 Hybrid RAG

现有 14 条 Golden Case 评估生成质量，不是 Vector Only / BM25 Only / RRF 共用的 Segment 排名 ground truth。当前没有至少 50 条人工标注问题，不能推测 Hit@5。分类：C。

### P1-5 断点续传

本轮只用小 TXT 验证上传链路，没有执行 100 MB / 500 MB、30% / 70% / 99% 中断矩阵，不能形成断点续传指标。分类：C。

## 最终汇总

| 指标 | Baseline | 当前方案 | 提升 | 样本量 | 是否建议写简历 |
|---|---:|---:|---:|---:|---|
| 已完成任务重复消息业务结果 | - | 0 个重复结果 | - | 5 份 / 180 次 | 是，需限定场景 |
| 资料完成重放 P95 | - | 16.891 ms | - | 100 | 否，仅面试补充 |
| 计划生成重放 P95 | - | 19.462 ms | - | 100 | 否，仅面试补充 |
| 模拟卷生成重放 P95 | - | 24.493 ms | - | 100 | 否，仅面试补充 |
| 冷创建受理 P95 | - | 未形成 | - | 每接口 1 | 否 |
| Checkpoint 恢复率 / Hybrid Hit@5 | - | 未测试 | - | 0 | 否 |

## 推荐简历数据

1. RabbitMQ：5 份资料、180 次重复消息、重复业务结果 0。推荐表述见 P0-2。

当前只有这一项同时满足样本规模、可复现性和面试可解释性。

## 原始数据

- `raw/async-acceptance-20260822-114700.csv`
- `raw/async-acceptance-cold-20260822-114700.csv`
- `raw/async-acceptance-20260822-114700.json`
- `raw/rabbitmq-idempotency-deliveries-20260822-115034.csv`
- `raw/rabbitmq-idempotency-20260822-115034.json`