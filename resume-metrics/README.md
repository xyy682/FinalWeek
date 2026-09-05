# Resume Metrics

该目录保存 FinalWeek 的简历量化测试脚本、原始数据与报告。测试脚本只创建带 `finalweek-metrics-*` 标识的隔离测试账号与课程，不修改核心业务实现。

## 当前脚本

在仓库根目录使用 PowerShell 7：

```powershell
D:\PowerShell\7\pwsh.exe -File .\resume-metrics\scripts\benchmark-async-acceptance.ps1
```

脚本会真实走完 Mailpit 登录、建课、分片上传、资料解析、知识版本确认/提纲生成，然后对资料完成、计划生成、模拟卷生成的幂等重放受理路径执行 10 次预热和 100 次正式测试。原始 CSV/JSON 写入 `raw/`。

注意：正式 100 次样本是幂等重放路径，不等于 100 个全新后台任务。报告必须保留这一区别，不能把结果泛化成所有冷创建请求的 P95。

## RabbitMQ 重复投递测试

```powershell
D:\PowerShell\7\pwsh.exe -NoProfile -File .\resume-metrics\scripts\test-rabbitmq-idempotency.ps1
```

脚本选择 5 个已完成资料任务，对每个任务分别发布 1、5、10、20 条重复消息，并比较 MySQL、Qdrant、Lucene 和 DLQ 的前后状态。完整口径与限制见 `FINALWEEK_METRICS.md`。