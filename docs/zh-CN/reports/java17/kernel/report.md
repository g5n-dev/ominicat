<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../reports/java17/kernel/report.md) · [中文文档目录](../../../index.md)
<!-- /ominicat-language-nav -->

# 本地内核性能证据

记录时间：2026-10-31T07:21:53.715391Z。这些测量仅覆盖内存中的 Java 内核，不测量模型推理、语音准确率、网络、WebRTC 或智能体端到端延迟。

运行时/源码指纹：`83c64c086b23e3513e81e1bf571a52329e0dab5e51d17b529782368c9b7f49f9`。[逐文件 SHA-256 清单](../../../../../reports/java17/kernel/source-sha256.json)。

## 环境

- Java：Homebrew 17.0.21.0.3
- 平台：Mac OS X / aarch64；10 个可用处理器
- 最大堆：512 MiB
- 主机同时承担开发工作，并并发运行 120 秒的按节奏发流/原生 Java 17 兼容性验证。这不是独占主机基准测试、受控 A/B 实验，也不是服务器/提供方容量测试。

- 工作线程后端：**platform**，即 Java 17 上有名称的守护工作线程；构建后的类使用主版本号 **61**。[工作线程/执行器观测探针](../../../../../reports/java17/kernel/worker-backend.json)。

## JMH 分派与调度

每个分派阶段都会转换一个持续变化的数值载荷。Blackhole 消费转换后的结果。分派包含帧分配；会话测试包含一个尚未完成的帧、有界入口、工作线程调度和输出队列交接。

| 基准测试 | 阶段数 | 得分 | JMH 误差（99.9% 置信区间） | 单位 | 分配量 B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 111,304,843.502 | ± 3,796,904.383 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 40,536,488.832 | ± 1,222,608.090 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 11,447,645.104 | ± 79,358.318 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 6.862 | ± 0.093 | us/op | 248.036 |
| SessionBenchmark.roundTrip | 4 | 7.104 | ± 0.939 | us/op | 488.028 |
| SessionBenchmark.roundTrip | 16 | 7.062 | ± 0.315 | us/op | 1,448.021 |

配置：2 个 fork，3 次预热迭代 × 1 s，5 次测量迭代 × 1 s；1 个 JMH 线程。已启用 GC 分配剖析器。

## 并发且在途数量有界的负载

16 个会话 × 4 个阶段，每个会话最多有 32 个尚未完成的帧。先预热 100,000 帧，再测量 25,000,000 帧；拒绝帧数为 0。

| 已接受帧吞吐量 | p50 | p95 | p99 | 最大值 |
|---:|---:|---:|---:|---:|
| 2,706,282 frames/s | 57.250 µs | 256.875 µs | 1,436.041 µs | 108,764.167 µs |

延迟从提交前一刻测量到输出回调，包含入口队列等待时间，不包含等待生成器在途许可的时间。这是闭环测试；它不校正协调遗漏，也不预测独立到达的过载下的延迟。百分位数计算未丢弃任何帧，并在完整的实测样本数组上使用最近秩法。

## 性能剖析

- `jdk.ExecutionSample`：524 条已记录事件。
- `jdk.ObjectAllocationSample`：2814 条已记录事件。
- `jdk.GarbageCollection`：33 条已记录事件。
- `jdk.JavaMonitorEnter`：0 条已记录事件。
- `jdk.VirtualThreadPinned`：此运行时不提供。

CPU 采样叶方法（整个负载进程，包含预热和百分位数排序）：

| 方法 | 样本数 |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 272 |
| `java.util.DualPivotQuicksort.sort` | 71 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 53 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire` | 46 |
| `io.ominicat.core.Pipeline.lambda$dispatchSession$2` | 30 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.signalNextIfShared` | 21 |
| `io.ominicat.core.PipelineSession.run` | 10 |
| `io.ominicat.benchmarks.CoreLoad.lambda$runPhase$4` | 9 |

分配采样权重（估计值，并非每种类型的精确分配总量）：

| 类型 | 估计值 MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda$93+0x000000700100fd60.2065602256` | 2,778.293 |
| `io.ominicat.core.SynchronousSink` | 2,467.815 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,022.432 |
| `io.ominicat.core.PipelineSession$Pending` | 843.538 |
| `io.ominicat.core.PipelineSession$$Lambda$86+0x000000700100f6e8.1407567661` | 600.434 |
| `[J` | 209.197 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 156.540 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 38.244 |

事件计数是本次录制中的观测结果。未记录到固定（pinning）事件并不能证明其他工作负载中不存在固定；这些结果受 JFR 阈值和采样机制影响。

原始证据：[JMH JSON](../../../../../reports/java17/kernel/jmh.json)、[JMH 输出](../../../../../reports/java17/kernel/jmh-output.txt)、[负载 JSON](../../../../../reports/java17/kernel/load.json)、[GC 日志](../../../../../reports/java17/kernel/gc.txt)、[JFR 摘要](../../../../../reports/java17/kernel/jfr-summary.txt)、[精简 JFR 热点](../../../../../reports/java17/kernel/jfr-hotspots.json)。二进制 JFR 录制（`kernel.jfr`）和未压缩的选定事件导出在本地生成，均排除在版本控制之外。

在仓库根目录使用 Java 17+ 复现：`scripts/profile.sh quick reports/latest`。使用 `full` 进行更长时间的预热和测量。在 JDK Mission Control 中打开 JFR 录制，可分析栈、分配、锁和 GC。

比较修订版本时，应使用同一台空闲机器、相同的 JVM、堆大小、阶段数和剖析器设置。更广泛的结论需要更长时间的运行、重复测量、按到达率施加的负载测试，以及真实提供方和传输测量。

[CPU/分配火焰图与 GC 摘要](profiles.md) · [测量与产物上下文](../../../../../reports/java17/kernel/measurement-context.json) · [剖析清理验证](../../../../../reports/java17/kernel/tooling-verification.json)。

本次 Java 17 测量与历史 Java 21 报告相互独立。它没有隔离运行时版本、工作线程后端或其他变化的成本。同步 JMH 用例没有单独对回调退役或真实模型服务进行基准测试。
