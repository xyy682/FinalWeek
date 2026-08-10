# FinalWeek

FinalWeek 是面向大学生期末复习的课程资料理解工具。项目按 [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) 串行开发；当前仓库已完成 Phase 1–2（项目基线、登录、课程与权限隔离），其余业务能力会在后续阶段逐项交付，不把尚未实现的功能描述成可用能力。

## 当前可运行内容

- Vue 3.5 + Vite 8 + TypeScript 6 + Element Plus 前端，包含邮箱登录、课程列表、课程四区独立 URL、设置页和手机基础布局。
- Java 21 + Spring Boot 3.5 单体，已实现 Mailpit 邮箱验证码、Redis TTL/限流、Spring Session、CSRF、课程 CRUD/逻辑删除、8 门上限和 ownership 隔离。
- Redis 故障门禁覆盖登录、全部认证接口和未来 AI API 路径，统一返回 `503 SERVICE_REDIS_UNAVAILABLE`；静态落地页及公开 ping 仍可访问。
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
                                          `-- local Lucene (later phase)
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

Phase 2 当前有 12 个后端测试用例和前端组件测试；完整 Playwright 主流程会在 Phase 11 随业务闭环一起验收。目前没有 AI Golden Case 实测结果、性能数字或生产 SLA 声明。

## 配置与密钥

首版所有数量、大小、TTL、限流、TopK、RRF、同步超时、历史条数与模型 ID 都集中在类型化后端配置和 [`.env.example`](.env.example) 中。不要提交 `.env` 或真实 `BAILIAN_API_KEY`。

## 规范

产品范围、技术选型、交互流程、后端约束、前端规范和阶段计划均在 [`docs`](docs) 目录。实现发生冲突时，以六份规范文档的共同约束和 `IMPLEMENTATION_PLAN` 阶段顺序为准。
