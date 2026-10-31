<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../reports/baseline/profiles.md) · [中文文档目录](../../index.md)
<!-- /ominicat-language-nav -->

# CPU、分配与 GC 性能剖析

根据有界内核负载中选取的 JFR 事件生成。样本包含负载测试驱动和预热；它们不是模型或网络性能剖析。

[![CPU 采样栈](../../../assets/previews/reports--baseline--cpu-flamegraph-zh-CN.png)](../../../assets/zh-CN/reports/baseline/cpu-flamegraph.svg)

## CPU 叶方法采样

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
| `jdk.internal.vm.Continuation.yield` | 8 |
| `java.util.concurrent.locks.ReentrantLock$Sync.lockInterruptibly` | 8 |
| `java.lang.VirtualThread.unpark` | 7 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireInterruptibly` | 6 |

## 分配采样权重

权重估计的是采样得到的分配分布，而非保留堆大小或精确对象数量。测试驱动保留完整的延迟数组；其中的 `long[]` 分配会有意体现在结果中。每次操作的分配估计值应使用 JMH `gc.alloc.rate.norm`。

| 类 | 采样权重（MiB） |
|---|---:|
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,224.03 |
| `io.ominicat.core.PipelineSession$Pending` | 732.66 |
| `io.ominicat.core.PipelineSession$$Lambda.0x000000e80100bba8` | 528.60 |
| `[J` | 211.19 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 93.19 |
| `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask` | 57.34 |
| `java.util.concurrent.ForkJoinTask$AdaptedRunnableAction` | 44.07 |
| `java.lang.VirtualThread$$Lambda.0x000000e80103e8c8` | 36.15 |
| `java.util.concurrent.ForkJoinTask$RunnableExecuteAction` | 30.36 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 26.30 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode` | 17.52 |
| `io.ominicat.core.Pipeline$$Lambda.0x000000e80100c8a0` | 4.15 |

[![分配采样栈](../../../assets/previews/reports--baseline--allocation-flamegraph-zh-CN.png)](../../../assets/zh-CN/reports/baseline/allocation-flamegraph.svg)

## GC 与争用

- 已记录的垃圾收集次数：12。
- 已记录的 GC 暂停时间之和：20.688 ms；单次收集的最大暂停时间之和：2.197 ms。
- 监视器争用事件：0；虚拟线程固定事件：0。
- 采样事件数为零并不能证明事件不存在。这些观测受采样、事件阈值和工作负载时长的限制。

证据：[选定事件，gzip JSON](../../../../reports/baseline/jfr-events.json.gz)、[GC 日志](../../../../reports/baseline/gc.log)、[测量报告](report.md)。
原始 JFR 在本地生成，并有意排除在 Git 之外；面向公开性能剖析时，不要录制真实凭据或对话载荷。
