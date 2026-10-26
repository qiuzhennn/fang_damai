# 仿大麦

基于 Spring Boot、MySQL、Redis 和 RabbitMQ 的票务后端项目，包含用户登录、演出管理、场次管理、票档库存和订单等功能。

目前支持同步下单和异步抢票，仍在持续开发中。

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 开发语言 |
| Spring Boot | 4.0.8 | 后端框架 |
| Spring Security | 随 Spring Boot 管理 | Session 登录、角色权限、CSRF 防护 |
| MyBatis-Plus | 3.5.17 | 数据访问与分页 |
| MySQL | 8.4 | 业务数据、订单与库存事务 |
| Redis | 7.4 | 库存预扣、请求记录、购买信息缓存、Stream 任务 |
| Lua | — | Redis 库存与请求状态操作 |
| RabbitMQ | — | 异步建单、消息确认与异常消息处理 |
| Maven | 3.9+ | 构建与依赖管理 |
| JUnit、Mockito | 随 Spring Boot 管理 | 单元测试与集成测试 |

## 已实现功能

- [x] **用户与权限**：用户注册、登录、退出，管理员登录，用户与管理员接口权限隔离。
- [x] **演出管理**：演出创建、分类、上架与下架，演出列表与详情查询。
- [x] **场次与票档**：场次创建、启售与停售，票档创建、查询和库存预热。
- [x] **购票下单**：同步下单、异步下单、重复请求识别、按请求标识查询订单。
- [x] **订单管理**：本人订单列表、订单详情、取消订单、模拟支付、超时关单。
- [x] **库存处理**：Redis Lua 预扣、MySQL 库存锁定、创建失败补偿、关单后的库存释放与重试。
- [x] **消息处理**：Redis Stream 任务登记与转发、RabbitMQ 发布确认、手动 ACK、异常消息恢复和暂存。

## 下单流程

### 同步下单

校验购买条件后，通过 Redis Lua 预扣库存，再在 MySQL 事务中锁定库存、创建待支付订单。订单创建成功后，将订单 ID 回写 Redis；确认创建失败后，退回预扣库存。

### 异步下单

1. 校验购买条件，通过 Lua 预扣库存，同时记录购买请求并写入 Redis Stream。
2. Relay 读取 Stream 任务，发布到 RabbitMQ，收到发布确认后确认 Stream 消息。
3. 消费者通过 MySQL Guard 协调建单与补偿，在事务中锁定库存、创建订单。
4. 回写 Redis 请求状态，处理完成后手动 ACK。

异步接口返回 `PROCESSING` 时，订单可能仍在创建，需要通过 `requestId` 查询结果。异常消息通过死信队列进入恢复流程，暂时无法确认结果的消息转入暂存队列。

每次购买使用一个小写 UUID 格式的 `requestId`，同一次购买重试时复用原值。数据库提交结果未知时保留预扣库存，确认结果后再处理。

待支付订单通常保留 15 分钟；如果演出更早开始，则以演出开始时间为支付截止时间。取消或超时关单时，在 MySQL 事务中释放库存并登记 Redis 库存释放任务，由定时任务完成回补。

## 项目结构

```text
fang-damai/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/damai/
    │   │   ├── common/           # 配置、认证、异常处理、统一响应
    │   │   └── modules/
    │   │       ├── admin/        # 管理员
    │   │       ├── user/         # 用户
    │   │       ├── program/      # 演出、分类、场次
    │   │       ├── ticket/       # 票档、缓存、库存
    │   │       └── order/        # 订单、消息、补偿
    │   └── resources/
    │       ├── application.yml
    │       └── lua/
    └── test/
```

## 本地启动

启动前需要安装 JDK 17、Maven，并准备 MySQL、Redis 和 RabbitMQ。数据库初始化 SQL 和 Docker Compose 配置暂未添加。

### 配置连接

连接配置位于 [application.yml](src/main/resources/application.yml)，通过环境变量设置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` | `127.0.0.1` | MySQL 地址 |
| `DB_PORT` | `3306` | MySQL 端口 |
| `DB_NAME` | `damai` | 数据库名称 |
| `DB_USERNAME` | `root` | MySQL 用户名 |
| `DB_PASSWORD` | 必填 | MySQL 密码 |
| `REDIS_HOST` | `127.0.0.1` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `RABBITMQ_HOST` | `127.0.0.1` | RabbitMQ 地址 |
| `RABBITMQ_PORT` | `5672` | AMQP 端口 |
| `RABBITMQ_USER` | `damai` | RabbitMQ 用户名 |
| `RABBITMQ_PASSWORD` | 必填 | RabbitMQ 密码 |
| `SERVER_PORT` | `8080` | 应用端口 |

Windows PowerShell：

```powershell
$env:DB_PASSWORD = "your-mysql-password"
$env:RABBITMQ_PASSWORD = "your-rabbitmq-password"
mvn spring-boot:run
```

Linux / macOS：

```bash
export DB_PASSWORD='your-mysql-password'
export RABBITMQ_PASSWORD='your-rabbitmq-password'
mvn spring-boot:run
```

使用 IDEA 时，也可以在运行配置中填写环境变量。如果中间件部署在虚拟机或其他主机上，需要修改对应的连接地址。

### 管理员初始化

首次创建管理员时，设置以下环境变量后启动应用：

```text
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=your-admin-password
```

用户名为 4～30 位小写字母、数字或下划线；密码为 12～64 个字符且不超过 72 字节。初始化完成后，将 `ADMIN_BOOTSTRAP_ENABLED` 设为 `false`。

### RabbitMQ 配置

`RABBITMQ_INIT_ON_STARTUP=true` 可在启动时显式声明交换机、队列和绑定。

正常建单队列 `damai.order.create.queue` 需要配置死信策略，死信交换机为 `damai.order.dlx`，路由键为 `order.create.failed`。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ORDER_RELAY_ENABLED` | `true` | Stream 任务转发 |
| `ORDER_CONSUMER_ENABLED` | `true` | 建单消费者 |
| `ORDER_RECOVERY_ENABLED` | `false` | 异常消息恢复消费者 |

### 接口调用

通过 `GET /auth/csrf` 获取 CSRF token。注册、登录及修改数据的请求需要携带 token 和对应的 Session Cookie。

HTTP 请求示例位于 `src/main/java/com/example/damai/common/web/`。

抢票前先创建演出、场次和票档，预热库存后再启售。库存预热接口为：

```text
POST /admin/ticket-categories/{id}/stock/warmup
```

预热时，场次需要处于待开售或停售状态，且没有锁定库存或未完成的库存释放任务。

## 测试与打包

集成测试需要连接 MySQL、Redis 或 RabbitMQ，运行前请准备独立的测试环境。

```bash
# 测试
mvn test

# 打包
mvn clean package

# 启动
java -jar target/fang-damai-0.0.1-SNAPSHOT.jar
```

## 后续计划

- [ ] 完善抢票限流和热点票档保护。
- [ ] 优化 Redis 缓存与库存预扣。
- [ ] 优化异步建单的消息转发和消费效率。
- [ ] 完善订单幂等、库存补偿和故障恢复。
- [ ] 优化高并发下的数据库锁竞争与事务处理。
- [ ] 支持多实例运行，完善任务协调。
