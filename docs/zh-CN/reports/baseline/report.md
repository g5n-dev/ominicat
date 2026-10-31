<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../reports/baseline/report.md) · [中文文档目录](../../index.md)
<!-- /ominicat-language-nav -->

# 本地内核性能证据

记录时间：2026-10-31T05:07:24.283691Z。这些测量仅覆盖内存中的 Java 内核，不测量模型推理、语音准确率、网络、WebRTC 或智能体端到端延迟。

运行时/源码指纹：`7e06420ee8e1911f75afc30a4fc687fecdf3c468dc7e805d73ced416c6e030aa`。[逐文件 SHA-256 清单](../../../../reports/baseline/source-sha256.json)。

## 环境

- Java：Homebrew 21.0.13.0.3
- 平台：Mac OS X / aarch64；10 个可用处理器
- 最大堆：512 MiB
- 该机器同时承担开发任务。结果仅为初始基线，并非容量保证。

## JMH 分派与调度

每个分派阶段都会转换一个持续变化的数值载荷。Blackhole 消费转换后的结果。分派包含帧分配；会话测试包含一个尚未完成的帧、有界入口、工作线程调度和输出队列交接。

| 基准测试 | 阶段数 | 得分 | JMH 误差（99.9% 置信区间） | 单位 | 分配量 B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 234,421,494.592 | ± 5,062,946.652 | ops/s | 64.000 |
| PipelineBenchmark.dispatch | 4 | 79,935,482.684 | ± 1,869,628.437 | ops/s | 136.000 |
| PipelineBenchmark.dispatch | 16 | 25,120,225.657 | ± 1,270,195.551 | ops/s | 424.000 |
| SessionBenchmark.roundTrip | 1 | 8.320 | ± 0.516 | us/op | 215.893 |
| SessionBenchmark.roundTrip | 4 | 8.114 | ± 0.268 | us/op | 287.893 |
| SessionBenchmark.roundTrip | 16 | 8.805 | ± 0.298 | us/op | 575.897 |

配置：2 个 fork，3 次预热迭代 × 1 s，5 次测量迭代 × 1 s；1 个 JMH 线程。已启用 GC 分配剖析器。

## 并发且在途数量有界的负载

16 个会话 × 4 个阶段，每个会话最多有 32 个尚未完成的帧。先预热 100,000 帧，再测量 25,000,000 帧；拒绝帧数为 0。

| 已接受帧吞吐量 | p50 | p95 | p99 | 最大值 |
|---:|---:|---:|---:|---:|
| 3,391,487 frames/s | 32.334 µs | 72.958 µs | 257.958 µs | 20,786.459 µs |

延迟从提交前一刻测量到输出回调，包含入口队列等待时间，不包含等待生成器在途许可的时间。这是闭环测试；它不校正协调遗漏，也不预测独立到达的过载下的延迟。百分位数计算未丢弃任何帧，并在完整的实测样本数组上使用最近秩法。

## 性能剖析

- `jdk.ExecutionSample`：889 条已记录事件。
- `jdk.ObjectAllocationSample`：2191 条已记录事件。
- `jdk.GarbageCollection`：12 条已记录事件。
- `jdk.JavaMonitorEnter`：0 条已记录事件。
- `jdk.VirtualThreadPinned`：0 条已记录事件。

CPU 采样叶方法（整个负载进程，包含预热和百分位数排序）：

| 方法 | 样本数 |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 515 |
| `jdk.internal.misc.Unsafe.getAndBitwiseAndInt` | 143 |
| `java.util.DualPivotQuicksort.sort` | 67 |
| `java.lang.VirtualThread.parkNanos` | 51 |
| `io.ominicat.core.PipelineSession.run` | 16 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 16 |
| `io.ominicat.benchmarks.CoreLoad.lambda$runPhase$4` | 11 |
| `java.lang.System$2.unparkVirtualThread` | 10 |

分配采样权重（估计值，并非每种类型的精确分配总量）：

| 类型 | 估计值 MiB |
|---|---:|
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,224.031 |
| `io.ominicat.core.PipelineSession$Pending` | 732.665 |
| `io.ominicat.core.PipelineSession$$Lambda.0x000000e80100bba8` | 528.602 |
| `[J` | 211.193 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 93.189 |
| `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask` | 57.338 |
| `java.util.concurrent.ForkJoinTask$AdaptedRunnableAction` | 44.071 |
| `java.lang.VirtualThread$$Lambda.0x000000e80103e8c8` | 36.146 |

事件计数是本次录制中的观测结果。未记录到固定（pinning）事件并不能证明其他工作负载中不存在固定；这些结果受 JFR 阈值和采样机制影响。

原始证据：[JMH JSON](../../../../reports/baseline/jmh.json)、[JMH 输出](../../../../reports/baseline/jmh.log)、[负载 JSON](../../../../reports/baseline/load.json)、[GC 日志](../../../../reports/baseline/gc.log)、[JFR 摘要](../../../../reports/baseline/jfr-summary.txt)、[精简 JFR 热点](../../../../reports/baseline/jfr-hotspots.json)。二进制 JFR 录制（`kernel.jfr`）和未压缩的选定事件导出在本地生成，均排除在版本控制之外。

在仓库根目录使用 Java 21+ 复现：`scripts/profile.sh quick reports/latest`。使用 `full` 进行更长时间的预热和测量。在 JDK Mission Control 中打开 JFR 录制，可分析栈、分配、锁和 GC。

比较修订版本时，应使用同一台空闲机器、相同的 JVM、堆大小、阶段数和剖析器设置。更广泛的结论需要更长时间的运行、重复测量、按到达率施加的负载测试，以及真实提供方和传输测量。

其他证据：[CPU/分配火焰图与 GC 分析](profiles.md)、[真实 WebSocket 回显结果](../../../../reports/baseline/websocket.json)和[测试/覆盖率验证](../verification.md)。
