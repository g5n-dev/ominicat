<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../reports/deep/kernel/report.md) · [中文文档目录](../../../index.md)
<!-- /ominicat-language-nav -->

# 本地内核性能证据

记录时间：2026-10-31T06:13:49.096290Z。这些测量仅覆盖内存中的 Java 内核，不测量模型推理、语音准确率、网络、WebRTC 或智能体端到端延迟。

运行时/源码指纹：`7f3b929c919da480b5014b1bd01b9891c1b1aa034787fe8a5facfcb320978017`。[逐文件 SHA-256 清单](../../../../../reports/deep/kernel/source-sha256.json)。

## 环境

- Java：Homebrew 21.0.13.0.3
- 平台：Mac OS X / aarch64；10 个可用处理器
- 最大堆：512 MiB
- 主机同时承担开发工作，并并发运行限速的正确性持续测试（64 个常驻客户端加 4 个连接更替客户端）。这不是服务器容量测试，也不是独占主机基准测试。

## JMH 分派与调度

每个分派阶段都会转换一个持续变化的数值载荷。Blackhole 消费转换后的结果。分派包含帧分配；会话测试包含一个尚未完成的帧、有界入口、工作线程调度和输出队列交接。

| 基准测试 | 阶段数 | 得分 | JMH 误差（99.9% 置信区间） | 单位 | 分配量 B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 113,224,138.704 | ± 1,559,095.817 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 40,009,652.744 | ± 263,268.497 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 11,615,086.320 | ± 225,706.073 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 8.128 | ± 0.261 | us/op | 272.032 |
| SessionBenchmark.roundTrip | 4 | 8.229 | ± 0.189 | us/op | 512.014 |
| SessionBenchmark.roundTrip | 16 | 8.575 | ± 0.202 | us/op | 1,472.031 |

配置：2 个 fork，3 次预热迭代 × 1 s，5 次测量迭代 × 1 s；1 个 JMH 线程。已启用 GC 分配剖析器。

## 并发且在途数量有界的负载

16 个会话 × 4 个阶段，每个会话最多有 32 个尚未完成的帧。先预热 100,000 帧，再测量 25,000,000 帧；拒绝帧数为 0。

| 已接受帧吞吐量 | p50 | p95 | p99 | 最大值 |
|---:|---:|---:|---:|---:|
| 3,345,082 frames/s | 32.375 µs | 69.459 µs | 272.458 µs | 31,027.834 µs |

延迟从提交前一刻测量到输出回调，包含入口队列等待时间，不包含等待生成器在途许可的时间。这是闭环测试；它不校正协调遗漏，也不预测独立到达的过载下的延迟。百分位数计算未丢弃任何帧，并在完整的实测样本数组上使用最近秩法。

## 性能剖析

- `jdk.ExecutionSample`：791 条已记录事件。
- `jdk.ObjectAllocationSample`：2267 条已记录事件。
- `jdk.GarbageCollection`：30 条已记录事件。
- `jdk.JavaMonitorEnter`：0 条已记录事件。
- `jdk.VirtualThreadPinned`：0 条已记录事件。

CPU 采样叶方法（整个负载进程，包含预热和百分位数排序）：

| 方法 | 样本数 |
|---|---:|
| `io.ominicat.core.Pipeline.dispatchSession` | 166 |
| `java.lang.System$2.unparkVirtualThread` | 122 |
| `io.ominicat.core.PipelineSession.valid` | 106 |
| `jdk.internal.misc.Unsafe.getAndBitwiseAndInt` | 87 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 81 |
| `java.util.DualPivotQuicksort.sort` | 58 |
| `java.lang.VirtualThread.parkNanos` | 29 |
| `java.util.concurrent.locks.ReentrantLock$Sync.lock` | 26 |

分配采样权重（估计值，并非每种类型的精确分配总量）：

| 类型 | 估计值 MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda.0x000000d80100cac8` | 2,902.082 |
| `io.ominicat.core.SynchronousSink` | 2,139.987 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,192.092 |
| `io.ominicat.core.PipelineSession$Pending` | 783.803 |
| `io.ominicat.core.PipelineSession$$Lambda.0x000000d80100b958` | 575.023 |
| `[J` | 211.192 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 202.383 |
| `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask` | 68.579 |

事件计数是本次录制中的观测结果。未记录到固定（pinning）事件并不能证明其他工作负载中不存在固定；这些结果受 JFR 阈值和采样机制影响。

原始证据：[JMH JSON](../../../../../reports/deep/kernel/jmh.json)、[JMH 输出](../../../../../reports/deep/kernel/jmh-output.txt)、[负载 JSON](../../../../../reports/deep/kernel/load.json)、[GC 日志](../../../../../reports/deep/kernel/gc.txt)、[JFR 摘要](../../../../../reports/deep/kernel/jfr-summary.txt)、[精简 JFR 热点](../../../../../reports/deep/kernel/jfr-hotspots.json)。二进制 JFR 录制（`kernel.jfr`）和未压缩的选定事件导出在本地生成，均排除在版本控制之外。

在仓库根目录使用 Java 21+ 复现：`scripts/profile.sh quick reports/latest`。使用 `full` 进行更长时间的预热和测量。在 JDK Mission Control 中打开 JFR 录制，可分析栈、分配、锁和 GC。

比较修订版本时，应使用同一台空闲机器、相同的 JVM、堆大小、阶段数和剖析器设置。更广泛的结论需要更长时间的运行、重复测量、按到达率施加的负载测试，以及真实提供方和传输测量。

[历史比较与观测到的分配变化](comparison.md)。[测量与产物上下文](../../../../../reports/deep/kernel/measurement-context.json)。[CPU/分配火焰图与 GC 摘要](profiles.md)。
