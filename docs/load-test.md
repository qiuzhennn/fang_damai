# 抢票压测

使用 Apache JMeter 模拟 **10,000 个独立用户抢购 500 张票**，每人随机购买 1～6 张。同时持续访问登录身份、订单列表和演出列表，观察抢票期间普通查询的表现。

本次在后端重启后执行测试。最终受理 **150 次购买，全部查询到对应订单**，这些购买请求的票数合计为 **500 张**。

## 测试环境

| 项目 | 配置 |
| --- | --- |
| 压测工具 | Apache JMeter 5.6.3，非 GUI 模式；JDK 17，堆上限 2 GiB |
| 后端 | 单实例 Spring Boot 4.0.8；JDK 21，G1，堆上限约 4 GiB |
| Redis 客户端 | Lettuce 6.8.2.RELEASE、Netty 4.2.17.Final |
| 硬件 | 16 个逻辑处理器，物理内存约 16 GiB |
| 部署 | Windows 运行后端、JMeter 和 IDEA；同机 Ubuntu 虚拟机通过 Docker 运行 MySQL、Redis 和 RabbitMQ，经 SSH 转发访问 |
| 诊断工具 | JFR 查看 CPU、GC 和执行热点，抓包查看 TCP 连接 |

项目编译目标为 Java 17，本次使用 JDK 21 运行。后端、压测器和中间件共享同一台电脑的资源。

## 测试方式

- 提前登录 10,000 个账号，将 Cookie 和 CSRF 凭据导出为 JMeter CSV。
- 测试票档准备 500 张库存，完成 Redis 库存预热后启售。
- 购票线程组设置为 10,000 个线程、120 秒 Ramp-up、循环一次。各线程先检查登录身份，再通过集合点集中发起购票。
- 每个用户购买一次，数量随机取 1～6 张，使用独立的 `requestId`。本轮请求实际在约 3.9 秒内发出。
- 异步购票返回受理状态后，每秒按请求标识查询订单，最多查询 30 次。购票请求自动重试次数为 0。
- 另设 10 个普通访问线程，持续 240 秒，通过 500 ms 定时器间隔访问身份、订单列表和演出列表。

## 压测配置

后端 JVM 参数：

```text
-Dserver.tomcat.accept-count=10000
-Dserver.tomcat.max-connections=20000
-Dserver.tomcat.keep-alive-timeout=180s
-Dapp.loadtest.disable-command-expiry=true
```

JMeter 开启连接复用，并设置：

```properties
httpclient4.time_to_live=180000
httpclient4.retrycount=0
```

`app.loadtest.disable-command-expiry` 是排查过程中加入的临时开关，需要配套客户端配置类。它通过 `TimeoutOptions.timeoutCommands(false)` 关闭 Lettuce 的异步命令到期任务调度，同步 Redis 调用仍保留等待超时。

测试期间，用户限流、票档限流、并发名额和积压保护均开启。

## 测试结果

### 购票结果

| 业务结果 | HTTP 状态 | 请求数 | 占购票请求比例 |
| --- | --- | ---: | ---: |
| `SUCCESS`，已受理，等待建单 | 200 | 150 | 1.50% |
| `TICKET_BUSY`，票档限流 | 429 | 6,411 | 64.11% |
| `SYSTEM_BUSY`，系统保护 | 503 | 2,831 | 28.31% |
| `INSUFFICIENT_STOCK`，库存不足 | 409 | 608 | 6.08% |

150 次受理请求全部关联到唯一订单，请求票数合计 500 张。订单按用户 ID 和请求标识查询确认，票数按对应购买请求汇总。

其余 9,242 次请求被限流或过载保护拒绝，608 次返回库存不足。JMeter 将这些 HTTP 4xx/5xx 计入失败，因此总体 Error 为 **39.58%（9,850 / 24,889）**；其中购票请求的拒绝比例为 **98.50%**。

24,889 条样本包括 10,000 次身份预检查、10,000 次购票、368 次订单查询和 4,521 次普通访问。

### 响应耗时

| 请求范围 | 样本数 | P50 | P95 | P99 | 最大值 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 全部购票响应 | 10,000 | 3,521 ms | 3,872 ms | 3,942 ms | 4,016 ms |
| 受理成功响应 | 150 | 1,400 ms | 1,540 ms | 1,581 ms | 1,595 ms |
| 抢票期间的普通访问 | 30 | 2,385 ms | 3,674 ms | 3,687 ms | 3,687 ms |

表中统计从 HTTP 请求发出到响应完成的耗时，分位数按最近秩法计算。受理接口先返回处理状态，建单结果通过后续轮询确认。

抢票期间普通访问出现了约 3.7 秒的短时延迟。最后一笔订单查询确认后的 2,209 次普通访问，P95 为 **49 ms**。

### 运行情况

| 指标 | 结果 |
| --- | --- |
| 登录身份检查 | 10,000 次全部返回 HTTP 200 |
| 购票连接拒绝 / 读取超时 / HTTP 500 | **0 / 0 / 0** |
| 普通访问 | **4,521 次全部返回 HTTP 200**，无读取超时 |
| 抢票至订单确认期间，后端 / 整机 CPU 采样峰值 | 55.4% / 99.8% |
| 同一阶段最长 GC 暂停 | 29.2 ms |

抓包中，10,050 次独立 SYN 均匹配 SYN-ACK，SYN 重传和 RST 记录均为 0。抢票期间复用了开抢前已经建立的连接。

## 调整记录

### Redis 客户端

最初通过 JFR 定位到 Lettuce 命令到期任务调度的热点。开启上述诊断开关后，后续采样中没有再出现这一热点，5,000 用户测试中的购票读取超时从 **1,335 次降为 0**。

### HTTP 连接复用

原先部分连接在用户等待集中开抢时因空闲到期而关闭，开抢后需要集中重建连接。将 Tomcat 空闲连接超时和 JMeter 连接存活时间统一调整为 180 秒后，后续测试复用了已有连接，连接拒绝降为 0。本次重启后的万人复测保持了这一结果。

相关配置说明：[JMeter 属性](https://jmeter.apache.org/usermanual/properties_reference.html)、[Lettuce 命令到期调度](https://github.com/redis/lettuce/blob/6.8.2.RELEASE/src/main/java/io/lettuce/core/protocol/CommandExpiryWriter.java)、[Tomcat HTTP Connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)。
