<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../reports/java17/http-cancellation/README.md) · [中文文档目录](../../../index.md)
<!-- /ominicat-language-nav -->

# Java 17 上可中断的 HTTP 响应体

记录于 2026-10-31。保持打开的 HTTP 响应可能在取消后仍使 Java 17 会话工作线程阻塞。修复以公共 HTTP/Flow API 上的可中断桥接器替换响应体读取器。干净的 Java 17 仓库构建通过了 **256 项测试**，失败、错误和跳过数均为零。下述所有服务输入均为生成的本地夹具。

## 失败与复现

所检查的 OpenJDK 17 响应体读取器实现在中断后会重新尝试队列等待。在 [Java 17 对照](../../../../../reports/java17/http-cancellation/probe-java17-before.txt)中，确认工作线程已进入响应体读取，500 ms 后仍在等待，且中断标志已不再设置。释放对端后，它才得以退出。对应的 [Java 21 对照](../../../../../reports/java17/http-cancellation/probe-java21-before.txt)在响应体仍保持打开时就退出了。这些对照比较的是取消行为，并非运行时性能。

早先的测试等待响应头后才中断。这使中断可能在线程进入阻塞的响应体读取之前到达，因此测试可能在未触发缺陷的情况下通过。替代回归在中断前确认工作线程已阻塞在响应体队列读取中；对端保持响应体打开，且不依赖工作线程完成断言。

针对原始实现：

- [文本流和二进制语音对照](../../../../../reports/java17/http-cancellation/before-provider-java17.txt)均超时：两项测试，**两个错误**。
- [原生 WebSocket 对照](../../../../../reports/java17/http-cancellation/native-before-java17.json)产生了**一个失败和一个错误**。它们覆盖中断后在同一套接字上处理下一代次，以及在提供方响应体仍保持打开时，断开连接后释放工作线程/准入占用。

实现参考为 [OpenJDK 17 的响应订阅者源码](https://github.com/openjdk/jdk/blob/jdk-17%2B35/src/java.net.http/share/classes/jdk/internal/net/http/ResponseSubscribers.java)。链接源码用于诊断；本报告没有复制任何 JDK 实现。

## 修复与边界

`CompatibleClient` 使用 `BodyHandlers.ofPublisher()` 和私有 `PublisherInputStream` 桥接器：

- 一次上游请求允许进入一个完整的 `List<ByteBuffer>` 批次。只有整个批次排空后的后续读取才会发起下一次请求。双槽队列为一个数据批次和一个终止通知预留空间。这限制的是批次数量，并非任意载荷字节数；现有原始字节预算仍然生效。
- 阻塞中或已被中断的读取会取消上游、恢复中断标志并抛出 `InterruptedIOException`。它不会将取消变成 EOF，也不会让回退请求将取消误认为普通提供方错误。
- 关闭会标记流已关闭、丢弃缓冲引用并在本地唤醒读取器。迟到数据被忽略。EOF 和上游错误仍是终止状态；此前已接受的数据保持其顺序。
- 队列等待和订阅的 `request/cancel` 调用都发生在短时状态锁之外。桥接器**不创建额外的响应读取线程、轮询循环或中断监视线程**。现有共享截止时间调度器和原始响应限制保持完整。

[Java 17 响应体订阅者契约](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html)将已交付缓冲区转交给订阅者，允许保留缓冲区而不复制其内容。[订阅契约](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Subscription.html)允许取消后发生迟到回调，且不保证随后出现终止回调；因此取消不能依靠上游唤醒读取器。取消尚未完成的 HTTP 响应体也可能阻止连接复用。

桥接器只有一个读取器，与适配器的使用方式一致。对于其他处理器工作，取消仍然是协作式的，无法撤销已经进入执行的外部效果。

## 已完成验证

| 证据 | 范围 | 结果 |
| --- | --- | --- |
| [干净 Java 17 构建](../../../../../reports/java17/http-cancellation/build-java17.txt)、[计数](../../../../../reports/java17/http-cancellation/verification.json) | 内核 89 + 对话 40 + 提供方 67 + 服务器 60 | **256 项通过**，无失败/错误/跳过 |
| [隔离的 Java 17 和 21 模块验证](../../../../../reports/java17/http-cancellation/domain-verification.json) | 每个 JDK：内核 89 + 对话 40 + 提供方 67 | **每个 JDK 196 项通过**，无失败/错误/跳过 |
| [隔离的 Java 17 和 21 服务器套件](../../../../../reports/java17/http-cancellation/server-full-suites.json) | 每个 JDK 上的完整服务器模块 | **每个 JDK 60 项通过**，无失败/错误/跳过 |
| [Java 17 原生回归](../../../../../reports/java17/http-cancellation/native-after-java17.json) | 上述两个保持响应体打开的套接字用例 | **2 项通过** |
| [Java 21 原生回归](../../../../../reports/java17/http-cancellation/native-after-java21.json) | 相同的两个套接字用例 | **2 项通过** |
| 提供方桥接语义，计入提供方总数 | 九个方法：整批需求、不预取、有序数据/EOF/错误、重复关闭、中断、迟到/重复订阅、需求失败、超量生产和跨线程订阅回调 | **每个 JDK 9 项通过** |
| [Java 17 持续 SSE 夹具](../../../../../reports/java17/http-cancellation/sse-streaming.json) | 生成 100 chunks/s，保持响应体打开 | **6,000 个有序 Unicode 块，耗时 60.071 s**；每块都在下一次写入前被观测到；DONE 在对端 EOF 前完成 |

这些范围彼此不同：完整 Java 17 构建、隔离模块检查、聚焦的原生回归，以及按节奏发流的本地 HTTP 流。60 秒流不是完整软件包稳定性或容量测试。未声称任何真实模型或外部提供方结果。

## 精确产物的混合媒体资源运行

下方标识的修复后发行包完成了一次独立、按节奏发流的本地回显运行：**预热 10 秒后实测 120.127 秒**，使用 64 个常驻客户端加四个连接更替工作线程、256 MiB 堆限制和 128 MiB 直接内存限制。它验证了 **138,239 个混合媒体帧**和 **4,377 个新连接**，意外失败数为零。预先声明的十一项检查全部通过；每个客户端均有进展。见[摘要](../../../../../reports/java17/http-cancellation/soak-summary.json)、[标准](../../../../../reports/java17/http-cancellation/soak-criteria.json)、[数值样本](../../../../../reports/java17/http-cancellation/soak-samples.jsonl)和[资源时间线](../../../../assets/zh-CN/reports/java17/http-cancellation/soak-resources.svg)。

| 观测 | 预热后 | 排空后 |
| --- | ---: | ---: |
| 请求完整 GC 后的已用堆 | 13,303,592 bytes | 8,173,240 bytes |
| Netty 直接内存保留量 | 16 MiB | 16 MiB |
| 打开的文件描述符 | 96 | 32 |
| 平台线程 | 80 | 16 |

活动会话、保留的准入占用、存活流水线工作线程和待输出字节在排空后全部归零。指标请求本身解释了剩余的传输连接。[诊断](../../../../../reports/java17/http-cancellation/resource-diagnostics.json)记录了零个 Netty 泄漏警告，以及 496,290 KiB NMT 已提交内存；后者包含 JVM/原生/剖析成本，既不是 RSS，也不是整个进程只占 256 MiB 的主张。[JFR 摘要](../../../../../reports/java17/http-cancellation/soak-jfr-summary.txt)覆盖整个进程 303 秒的执行，并非 303 秒的实测流量。

这一生成的回显工作负载覆盖文本、PCM 音频、转录、图像和信封；它是资源/正确性探针，并非饱和基准测试或真实兼容服务检查。受影响的 HTTP 响应体路径由上述提供方套件、保持响应体打开的原生回归和 60 秒 SSE 夹具单独覆盖。两分钟运行和选定资源检查不能确立多日稳定性、不存在所有泄漏或生产 SLA。

## 产物来源与复现

[产物差异清单](../../../../../reports/java17/http-cancellation/artifact-delta.json)标识修复后的发行包：

```text
9601f5104aef55dae06bcbf7e09a0284d0362455f3894eddeaedc9763d39c9ba
```

先前发行包为 `370e4e056d2336526ffe5f4975470671b195235eeb43623efed873bc2e32883e`。恰好三个现有的 `CompatibleClient` 类文件条目发生更改，并新增了 `PublisherInputStream` 及其 `Item` 记录。全部 **73 个内核/对话/服务器类**逐字节保持不变。内核基准测试源码未更改；[现有内核测量](../kernel/report.md)保留其原始范围，并不测量这一 HTTP 桥接器、网络延迟或模型推理。

上述 120.127 秒回显资源运行适用于这一精确产物。其他二进制文件的历史运行不会转移到它。[清单](../../../../../reports/java17/http-cancellation/artifact-delta.json)与独立测试/资源证据保留了这些边界；未声称外部提供方成功结果。

完整 Java 17 验证应通过 `JAVA_HOME` 选择真实 JDK 17，并在隔离检出目录中运行：

```sh
./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify
```

[隔离模块证据](../../../../../reports/java17/http-cancellation/domain-verification.json)保留聚焦命令、运行时版本和源码哈希。Java 21 检查确立了在该受测运行时上观测到的取消行为；独立资源运行的范围仍限于所标识的 Java 17 产物。
