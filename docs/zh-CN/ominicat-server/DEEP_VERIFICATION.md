<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-server/DEEP_VERIFICATION.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 原生传输与资源验证

运行日期：**2026-10-31，Asia/Shanghai**。这些是使用真实 TCP socket 的本地合成传输测试，没有使用商业模型 API 或外部网络服务。

服务端变更在独立源码快照中检查，避免并发模块构建修改运行中测试进程使用的类。归档基线为提交 `14e6bb0`，其上覆盖本次审查的服务端模块。[源码哈希](../../../ominicat-server/validation/source-sha256.json) 标识了下文资源运行测量的生产文件，当时服务端 `src/main` 聚合 SHA-256 为 `148b6ee26fb50e5b996aa66a2fff4e2ae0f3d3d6abacced0ecdbdc2c77bbd8b0`。后续 Host 头加固另有下文验证；全仓库验证和更长的最终发行包持续运行属于独立证据。

## 先复现失败，再修正

最初四项原生 socket 测试产生了**三个失败**。[原始失败日志](../../../ominicat-server/validation/regressions-before-fixes.log) 已保留。

| 复现方式 | 观测到的失败 | 修正与回归 |
| --- | --- | --- |
| 故意不配合取消的处理器仍阻塞时断开连接 | 重连收到 HTTP `101`，即使配置为 `maxSessions: 1`；socket 更替可累积孤儿工作线程 | 只有 socket 关闭与处理器清理均完成才释放准入；夹具释放前，重连收到 `503` |
| 请求大量突发输出后停止读取 TCP | 关闭帧被无法刷出的数据挡住；三秒后，另一个连接仍收到 `503` | 立即取消管线；两秒期限到达后强制关闭无法刷出的 socket；准入恢复 |
| 发送 50,000 个掩码 Ping 帧，同时从不读取 TCP 输出 | Pong 写入绕过 JSON 输出预算，四秒后会话仍占用槽位 | Pong 和数据写入共享有限输出预算及可写性检查；压力触发关闭，准入恢复 |

进一步审查发现，原始 HTTP/未完成握手 socket 不受 WebSocket 准入控制。现在 `maxConnections` 在分配 HTTP handler 前限制这些连接，默认为 `max(32, 2 * maxSessions)`。双连接测试验证拒绝、精确资源计数，以及关闭一条原始 socket 后的恢复。

工厂资源清理还需覆盖 `Error`，而不只覆盖 `Exception`/`RuntimeException`。现在测试会在较晚的启动清理中注入 `AssertionError`，并模拟会话工厂失败后清理再次出错。此前已返回的资源仍会全部关闭，致命启动错误得到保留；每连接初始化失败会生成不含敏感细节的 `1011`，并释放准入。

## 验证运行

扩展套件完成 **52 个测试，零失败**，其中包括 180 秒持续运行。另一次 60 秒运行完成 **11 个测试，零失败**，并增加工作事件循环任务/计时器采样。常规服务端套件含 51 个测试；`ServerSoakIT` 需要显式启用，Surefire 默认测试命名规则不会选中它。

| 条件 | 180 秒运行 | 60 秒任务采样运行 |
| --- | ---: | ---: |
| 实际持续测量时间 | 180.441 s | 60.473 s |
| 并发原生客户端 | 16 | 16 |
| 新连接总速率上限 | 100/s | 100/s |
| 会话容量 / 原始连接容量 | 64 / 128 | 64 / 128 |
| 待处理输入帧 / 最大 JSON 字节数 | 8 / 32,768 | 8 / 32,768 |
| 成功的连接 → 交换 → 断开循环 | 17,248 | 5,744 |
| Ping/Pong 交换 | 2,464 | 816 |
| 故意不读取的 TCP 消费者 | 60 | 20 |
| 意外失败 / 准入拒绝 | 0 / 0 | 0 / 0 |
| Netty 泄漏检测器 | PARANOID | PARANOID |
| 报告的 Netty 泄漏警告 | 0 | 0 |
| JVM 堆上限 | 512 MiB | 512 MiB |

生成器周期性发送字段类型错误的 JSON，并验证同一会话随后仍可交换有效帧。一半正常循环显式发送 `end`，其余直接关闭 TCP 连接。慢消费者夹具读完 `101` 握手后，**不再读取任何 TCP 字节**；这不是仅仅不再请求消息的 JDK WebSocket 监听器。

公开日志副本将本地临时构建目录替换为 `[isolated-workspace]`，保留源码哈希、测试结果和数值测量。[构件校验和](../../../ominicat-server/validation/artifacts-sha256.json) 覆盖这些公开副本。

原始构件：

- [180 秒样本与计数](../../../ominicat-server/validation/native-soak-180s.json)及[测试日志](../../../ominicat-server/validation/native-soak-180s.log)
- [60 秒任务/资源样本](../../../ominicat-server/validation/native-soak-tasks-60s.json)及[测试日志](../../../ominicat-server/validation/native-soak-tasks-60s.log)

环境：OpenJDK `21.0.13.0.3`、macOS，JVM 可使用十个处理器。这是工作站测量，不是部署容量估算。

## 资源观测

除另有说明外，下列数据取自 180 秒运行的前十秒之后。

| 观测项 | 结果 |
| --- | --- |
| Netty 池化直接内存预留 | 每个样本均恰为 16 MiB |
| 测试进程打开的文件描述符 | 58–92；最终样本为 58 |
| 平台线程 | 31–35；最终样本为 31 |
| 采样堆用量 | 11.5–32.6 MB |
| 整个运行的准入会话 / 存活管线工作线程峰值 | 17 / 16 |
| 待输出载荷字节峰值 | 128,499 |
| 排空后的连接、活动会话、准入槽位、工作线程和待输出 | 全部为零 |

单独的任务采样运行观测到 **0–5 个排队工作事件循环任务**和 **0–15 个已调度工作事件循环任务**，排空后均回到零；其 Netty 直接内存预留始终恰为 16 MiB。队列/计时器检查只用于测试，使用固定 Netty 版本的内部实现，并在其所属事件循环上采样。一项 250 连接回归验证连接更替后没有残留的超时/关闭计时器。

60 秒运行的采样堆范围为 16.6 到 289.2 MB，结束时未强制回收。原始堆样本包含尚未回收的临时对象，不是保留堆测量。同样，Netty 池化直接内存预留包括可复用分配器容量。资源指标为零、检测器无警告，是这些有限负载的证据，不能证明所有可能的自定义插件都无泄漏。

## 其他对抗性用例

- **空闲连接：**一秒入站空闲超时关闭原生对端，并释放准入。
- **停滞的供应商响应体：**本地 HTTP 夹具发送 SSE 响应头后停滞响应体。一秒供应商截止时间使会话在四秒内以 `1011` 关闭；另一个会话随后收到成功响应。
- **输入压力：**显式阻塞的阶段填满单帧队列。溢出会报告，已接受帧会保留。被拒绝的 `end` 不关闭输入，排空后重试可成功。
- **无效/认证输入：**常规套件覆盖 bearer 头、浏览器 bearer 子协议、无关来源、无效 base64/元数据/类型、重复字段、多个 JSON 根、嵌套信封及超大/分片消息。
- **已配置语音路径：**真实 WebSocket → PCM 缓冲 → 本地 HTTP 转写 → 转写门控 → 提示模板 → SSE 对话 → 本地 HTTP 语音合成 → PCM 输出。联合检查载荷、流式文本、合成音频、重复终稿抑制和清理。
- **不配合取消的处理器下关闭：**传输层关闭在三秒内返回，并关闭所有 socket。直到显式释放夹具前，阻塞工作线程及其保留的准入仍可见。Java 无法安全强制终止忽略中断的任意回调；测试不宣称回调退出前已完成清理。

## 测试中发现的生成器限制

最初不限速的生成器在约五秒内完成 **31,930 条连接**，随后耗尽工作站临时客户端端口，报出 `BindException: Can't assign requested address`。后续本地客户端短暂受到 TIME_WAIT 影响。尽管如此，服务端自有指标仍排空到零，池化直接内存预留保持 16 MiB。该运行不计为通过的持续运行或服务端吞吐结果。成功运行改以低于已观测主机端口预算的总计 100 连接/s 速率重新执行。

## 后续回环主机与端口校验加固

独立原生探针可使用攻击者控制的 `Host` 和匹配的 `Origin` 连接未认证的回环监听器。**这并未被演示为浏览器漏洞利用**。浏览器本地网络限制不会免除服务自身的防护责任，[WICG 本地网络访问安全考虑](https://wicg.github.io/local-network-access/#dns-rebinding) 对此有说明。

绑定回环地址且未配置认证的监听器，现在会在路由或 WebSocket 准入前，以 HTTP `403` 拒绝非回环的主机与端口部分（authority）。检查使用实际监听通道绑定的地址，不做 DNS 查询。它接受 `localhost`、规范点分 `127/8` 字面量和方括号包裹的 `::1`，并允许附带有效十进制端口。配置令牌或绑定通配/公网地址的监听器保持现有主机与端口处理行为，其认证/部署策略仍由运营方负责。

**33 个测试通过**，包括六个新增原生回归、九个资源对抗性测试和十八个现有服务端集成测试。新增测试覆盖每条公开 HTTP 路由、匹配来源的 WebSocket 尝试、规范本地主机与端口、无效/相似主机与端口、重复 Host 头、认证后的外部代理主机与端口、通配绑定和真实 IPv6 回环监听器。测试主机上没有跳过任何 IPv6 测试。参见[测试日志](../../../ominicat-server/validation/loopback-host-guard.log)和[源码哈希](../../../ominicat-server/validation/host-guard-source-sha256.json)。

新的服务端 `src/main` 聚合 SHA-256 为 `4405542a7fa8d5157b729c03f415e29cc48e9ca5613f35d3ad80162f91d8a24c`。较早的资源运行仍标注其实际被测源码。最终发行包验证必须在此变更后重新构建并重启长时间持续运行。

## 复现

使用 JDK 21+ 和支持 tar 提取过滤器的 Python 3：

```sh
./scripts/server_soak.sh 180 16 /tmp/ominicat-server-validation 100
```

参数依次为持续秒数、并发客户端数、构件目录和每秒总新连接数。脚本归档 `HEAD`，覆盖当前服务端模块，并在临时目录中构建。它启用 PARANOID Netty 泄漏检测，堆初始值为 128 MiB、最大值为 512 MiB，并保留源码快照和报告供检查。

对于手动准备的独立检出目录：

```sh
./mvnw -B -pl ominicat-server -am test \
  -Dtest=ServerAdversarialTest,StalledProviderRecoveryTest,ServerSoakIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dominicat.soak.seconds=180 -Dominicat.soak.clients=16 \
  -Dominicat.soak.connectsPerSecond=100 \
  -Dominicat.soak.output=/tmp/ominicat-server-validation.json \
  '-DargLine=-Xms128m -Xmx512m -Dio.netty.leakDetection.level=paranoid'
```

连接更替、内核分发、模型响应延迟和用户感知音频延迟属于不同负载。这些测量不宣称优于其他运行时，也不测量供应商质量、互联网抖动、跨机器时钟、WebRTC 或声学处理。
