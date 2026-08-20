# FinalWeek 面试准备文档

这组文档按照简历第三版中 FinalWeek 的项目描述整理，目标不是背诵名词，而是把每个技术点讲成完整的工程决策：解决了什么问题、代码如何实现、为什么选择当前方案、替代方案有什么取舍、系统边界在哪里。

## 阅读顺序

1. [01-总体架构与设计边界.md](01-总体架构与设计边界.md)：先建立全局心智模型。
2. [02-核心流程链路.md](02-核心流程链路.md)：按一次真实请求理解数据和状态如何流动。
3. [03-简历逐行亮点与面试拷打.md](03-简历逐行亮点与面试拷打.md)：对应简历六条亮点逐项准备。
4. [04-综合面试题与口述模板.md](04-综合面试题与口述模板.md)：练习开场介绍、架构追问、故障场景和 AI Coding 真实性问题。

## 一句话定位

FinalWeek 是面向大学生期末复习的多模态课程资料 RAG 系统：将 PDF、PPT、文本、音频和视频统一解析为带来源位置的知识片段，通过 Qdrant 向量召回与 Lucene BM25 召回构建课程知识库，再基于版本化知识快照异步生成提纲、计划、课程回答和模拟卷双 PDF。

## 使用提醒

- 面试时先讲业务问题，再讲中间件；不要从“我用了八个组件”开始。
- 所有“可靠”“一致”“原子”等词都要限定范围。项目没有实现 MySQL、RabbitMQ、MinIO、Qdrant 之间的分布式强一致。
- 不要把项目说成微服务。当前是一个 Spring Boot 模块化单体，Web API 与 RabbitMQ Consumer 在同一 JVM，且只支持单后端实例。
- 不要声称做过生产压测、生产 SLA、集群高可用或海量用户验证。README 已明确这些属于项目边界。
- 回答问题时优先使用“状态机、唯一约束、CAS、稳定 ID、checkpoint、补偿”这些具体机制，少用“保证高可用”一类空泛表述。

## 代码与设计入口

- 项目总览：[README.md](D:/学习项目/FinalWeek/README.md)
- 应用流程：[APP_FLOW.md](D:/学习项目/FinalWeek/docs/APP_FLOW.md)
- 后端设计：[BACKEND_STRUCTURE.md](D:/学习项目/FinalWeek/docs/BACKEND_STRUCTURE.md)
- 技术选型：[TECH_STACK.md](D:/学习项目/FinalWeek/docs/TECH_STACK.md)
- 测试报告：[TEST_REPORT.md](D:/学习项目/FinalWeek/docs/TEST_REPORT.md)

