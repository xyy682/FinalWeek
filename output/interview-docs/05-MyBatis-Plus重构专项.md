# FinalWeek MyBatis-Plus 重构专项

## 1. 重构后的准确结论

FinalWeek 已从 Spring Data JPA/Hibernate 改为 MyBatis-Plus 3.5.17。业务服务层仍使用 `CourseRepository`、`BackgroundTaskRepository` 等名称，但这些接口已经是 MyBatis Mapper/持久化门面，不再是 Spring Data Repository。

```text
Service / Coordinator / Pipeline
  -> 领域内 *Repository
      -> BaseRepository<T>
          -> MyBatis-Plus BaseMapper<T>
      -> @Select / @Update / @Delete 显式 SQL
  -> Spring @Transactional
  -> MySQL + Flyway schema
```

重构没有改变 MySQL 是业务真相源、任务状态机或领域流程，主要改变的是 Java 对象与 SQL 之间的映射方式，以及关联、更新和锁的表达方式。

## 2. 关键实现

### Mapper 扫描

`MybatisPlusConfiguration` 使用 `@MapperScan(basePackages="com.finalweek", markerInterface=RepositoryMarker.class)`。只有带持久化标记的接口会成为 Mapper，避免扫描整个领域包时把普通接口误注册。

### 自定义 BaseRepository

`BaseRepository<T>` 继承 `BaseMapper<T>`，并提供 `findById`、`save`、`saveAndFlush`、`saveAll`、`delete` 等兼容方法。目的不是模拟完整 JPA，而是让原 Service 层在迁移期间少改动。

`save` 的实际逻辑：

1. 从 MyBatis-Plus `TableInfo` 获取主键属性。
2. 主键为空或 `selectById` 查不到时视为 insert。
3. 否则视为 update。
4. 调用自定义生命周期回调。
5. UUID 主键为空时生成 UUID。
6. 执行 `insert` 或 `updateById`。

这一兼容实现简单，但每次保存已有 ID 的新对象可能多一次 `selectById`，批量写入也逐条执行。它适合当前 MVP 迁移，不应包装成最佳性能方案。

### `saveAndFlush` 与 `flush`

MyBatis Mapper 调用通常立即执行 SQL，没有 JPA 一级缓存和持久化上下文。因此：

- `saveAndFlush` 等价于当前项目的 `save`；
- `flush()` 是空操作；
- 修改实体字段后必须显式调用保存或 Mapper 更新；
- 事务提交前 SQL 已执行，但是否最终持久化仍由事务 commit/rollback 决定。

### 标量外键

实体不再持有 JPA `@ManyToOne/@OneToMany` 关系，而是保存 UUID：

```text
Course.userId
Material.courseId
BackgroundTask.userId/courseId/materialId/businessId
MockExam.knowledgeVersionId/retryOfId
```

构造函数有时仍接收领域对象以维持业务 API，但内部只提取 ID。查询关联数据时使用显式 join、批量查询或 Service 编排。

### UUID 与枚举

- MySQL 主键和外键使用 `BINARY(16)`。
- `BinaryUuidTypeHandler` 负责 UUID 与 16 字节互转。
- MyBatis 开启下划线转驼峰。
- 枚举使用 `EnumTypeHandler` 按名称存储。

### 生命周期回调

项目自定义 `@BeforeInsert` 和 `@BeforeUpdate`，`BaseRepository.save` 通过反射调用实体方法，继续统一设置 `createdAt/updatedAt`。

这不等同于 JPA Entity Listener：只有经过自定义 `save` 的对象会触发；直接显式 `@Update` SQL 需要在 SQL 中自己维护 `updated_at=current_timestamp`。

### 分页和更新策略

- `PaginationInnerInterceptor(DbType.MYSQL)` 提供分页，maxLimit=100。
- `update-strategy=always` 允许把 error、retry reference 等字段更新为 NULL。

`always` 的风险是 `updateById` 可能覆盖更多列。因此并发热点状态不使用读取实体后普通保存，而使用精确条件更新。

## 3. CAS、行锁与事务如何实现

### 任务状态 CAS

以任务 claim 为例，条件同时包含：

- task id；
- execution round；
- delivery attempt 上限；
- 允许的旧状态集合。

只有 SQL 受影响行数为 1，Consumer 才获得执行权。取消、发布失败、重投、成功和失败同样采用显式旧状态条件。

这比通用 `@Version` 乐观锁更适合任务状态机，因为竞争条件本身就是业务规则。

### 悲观锁

课程确认知识版本、模拟卷清理等需要串行业务聚合时，Mapper 使用 `SELECT ... FOR UPDATE`。调用必须处于 `@Transactional` 中，否则锁会随单条语句自动提交而失去保护范围。

### Spring 事务

从 JPA 改为 MyBatis-Plus 后，`@Transactional` 仍管理同一 DataSource 上的 JDBC 事务：

- 多个 Mapper 写可以一起 commit/rollback；
- 运行时异常触发回滚；
- 外部模型、MinIO、RabbitMQ 仍不在 MySQL 本地事务内；
- 不能因为用了 `@Transactional` 就宣称跨系统原子性。

## 4. 为什么这次重构适合 FinalWeek

适配点：

- 后台任务状态迁移需要精确 `UPDATE ... WHERE old_status`。
- ownership 查询大量携带 user/course/deleted 条件。
- 知识版本和删除流程需要明确 `FOR UPDATE`。
- CourseSegment、来源表和清理表存在显式 join/batch delete。
- 项目本来就由 Flyway 管理 schema，不需要 Hibernate 自动建表。

付出的代价：

- 需要维护更多 SQL；
- 没有自动关联装配、脏检查和级联；
- Mapper SQL 与字段迁移必须同步；
- 通用 `save` 兼容层存在额外查询和整行更新风险；
- 复杂 SQL 需要 MySQL 集成测试，而不能只 Mock Repository。

## 5. 为什么不应该回答“因为 MyBatis 更快”

性能取决于 SQL、索引、结果集、网络和对象映射，不由框架名字单独决定。JPA 也能写原生 SQL和批量更新，MyBatis 也能写出慢 SQL。

更准确的回答：

> 我重构的主要目标是让 SQL、锁条件和受影响行数显式化，减少 ORM 对象关系对状态机业务的干扰；性能收益必须通过具体查询计划和基准测试验证，不能只根据框架下结论。

## 6. 重构验证

### 单元测试

Service、Pipeline 和 Validator 继续 Mock Repository，确认领域行为没有改变。

### MyBatis Slice 集成测试

`KnowledgeVersionMybatisIntegrationTest` 使用：

- `@MybatisPlusTest`；
- `MybatisPlusConfiguration`；
- MySQL 8.4.10 Testcontainers；
- 真实 Flyway schema。

覆盖：

- 知识版本和提纲映射；
- UUID BINARY(16)；
- 模拟卷 retry 外键置空；
- 题目外键级联删除；
- 清理记录重新入队；
- 两线程确认知识版本只产生一个成功版本。

### 真实 MySQL 状态竞争

`TaskStateMySqlIntegrationTest` 直接用两个线程竞争条件更新，验证只有一个 claim 成功、取消不能覆盖 PROCESSING，以及 checkpoint 唯一约束生效。

## 7. 高频面试题速答

### JPA 和 MyBatis-Plus 的核心区别是什么？

> JPA 以对象状态和 persistence context 为中心，支持脏检查、关联和级联；MyBatis 以 Mapper SQL 为中心，SQL执行更显式。FinalWeek 当前选择后者，是因为状态机和锁条件比对象图导航更核心。

### MyBatis-Plus 相比原生 MyBatis 提供了什么？

> BaseMapper 的单表 CRUD、元数据、分页拦截器和条件构造能力。项目没有让它隐藏关键并发 SQL，任务 CAS 和行锁仍显式书写。

### 为什么不用 MyBatis-Plus 乐观锁插件？

> 任务迁移条件包含状态、轮次和预算，不只是 version 相等。显式 SQL 能直接表达领域条件；知识版本发布也需要业务版本和当前指针判断。

### `FOR UPDATE` 在什么隔离级别下使用？

> MySQL 默认 InnoDB REPEATABLE READ。项目锁定按主键/索引命中的课程或任务记录，并把读取和后续写入放在同一事务。面试还应说明范围查询可能涉及 next-key lock，索引设计会影响锁范围。

### 会不会 SQL 注入？

> Mapper 参数使用 `#{}` 绑定为 PreparedStatement 参数；动态 IN 使用 foreach 绑定值。不能把用户输入通过 `${}` 拼接到 SQL。排序字段如需动态化，应使用枚举白名单。

### 如何排查慢 SQL？

> 先从结构化日志/指标定位 Mapper 和耗时，再使用 EXPLAIN ANALYZE 查看执行计划、扫描行数、索引和回表；重点检查 user/course/status/created_at 等组合条件。不能只靠把 JPA 换成 MyBatis 解决慢 SQL。

## 8. 面试表述边界

- 可以说“关键 SQL 和锁条件更显式”。
- 可以说“移除了 JPA 对象关联、懒加载和脏检查依赖”。
- 可以说“Spring 事务和 Flyway 迁移仍然保留”。
- 不要说“Repository 还是 JPA，只是底层换了实现”。它已经是 Mapper 接口。
- 不要说“MyBatis-Plus 自动保证并发安全”。并发正确性来自条件 SQL、行锁、事务和唯一约束。
- 不要说“重构后一定性能更高”。目前验证重点是行为等价和并发语义。

## 9. 代码入口

- [pom.xml](D:/学习项目/FinalWeek/backend/pom.xml)
- [MybatisPlusConfiguration.java](D:/学习项目/FinalWeek/backend/src/main/java/com/finalweek/common/persistence/MybatisPlusConfiguration.java)
- [BaseRepository.java](D:/学习项目/FinalWeek/backend/src/main/java/com/finalweek/common/persistence/BaseRepository.java)
- [BinaryUuidTypeHandler.java](D:/学习项目/FinalWeek/backend/src/main/java/com/finalweek/common/persistence/BinaryUuidTypeHandler.java)
- [BackgroundTaskRepository.java](D:/学习项目/FinalWeek/backend/src/main/java/com/finalweek/task/BackgroundTaskRepository.java)
- [CourseRepository.java](D:/学习项目/FinalWeek/backend/src/main/java/com/finalweek/course/CourseRepository.java)
- [KnowledgeVersionMybatisIntegrationTest.java](D:/学习项目/FinalWeek/backend/src/test/java/com/finalweek/knowledgeversion/KnowledgeVersionMybatisIntegrationTest.java)
