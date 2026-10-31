<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../reports/performance-audit/linux-java17-2026-11-01/report.md) · [中文文档目录](../../../index.md)
<!-- /ominicat-language-nav -->

# 本地内核性能证据

记录时间：2026-10-31T20:11:05.862261985Z。这些测量仅覆盖内存中的 Java 内核，不测量模型推理、语音准确率、网络、WebRTC 或端到端智能体延迟。

[运行清单与被测基准 JAR SHA-256](../../../../../reports/performance-audit/linux-java17-2026-11-01/run-manifest.json).

证据校验：严格清单模式；已检查完整基准／参数矩阵与负载计数。

运行时／源码指纹： `521ea6ea3ef70373aebd78c194f1fe464867dc734d244b11debc4416eeb8f809`. [逐文件 SHA-256 清单](../../../../../reports/performance-audit/linux-java17-2026-11-01/source-sha256.json).

## 环境

- Java：Eclipse Adoptium 17.0.21
- 平台：Linux / amd64；4 个可用处理器
- 最大堆：512 MiB
- 该主机与开发任务共享。结果是初步基线，不是容量保证。

## JMH 分发与调度

每个分发阶段都变换持续变化的数值载荷，由 Blackhole 消费变换结果。分发包含帧分配；会话测试包含一个在途帧、有界入口、工作线程调度及输出队列交接。

| 基准 | 阶段数 | 结果 | JMH 误差（99.9% 置信区间） | 单位 | 分配 B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 59,090,636.005 | ± 1,174,249.131 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 22,189,603.035 | ± 239,393.999 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 5,514,447.752 | ± 21,234.574 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 23.226 | ± 1.595 | us/op | 248.042 |
| SessionBenchmark.roundTrip | 4 | 22.363 | ± 1.472 | us/op | 488.033 |
| SessionBenchmark.roundTrip | 16 | 23.365 | ± 1.441 | us/op | 1,448.037 |

配置：3 个独立进程，5 次预热迭代 × 2 s，8 次测量迭代 × 2 s；1 个 JMH 线程。启用 GC 分配剖析器。

## 并发有界在途负载

16 个会话 × 4 个阶段，每个会话最多 32 个在途帧。100,000 个预热帧后测量 50,000,000 个帧；拒绝帧数为 0。

| 已接纳吞吐 | p50 | p95 | p99 | 最大值 |
|---:|---:|---:|---:|---:|
| 3,872,063 帧/秒 | 69.127 µs | 145.855 µs | 417.647 µs | 11,367.096 µs |

延迟从紧邻提交之前计至输出回调，包含入口队列等待。不包含等待生成器在途许可的时间。这是闭环测试，不校正协调遗漏，也不预测独立到达的过载下的延迟。百分位计算不丢弃任何帧，使用精确测量样本数组上的最近秩方法。

## 剖析

- `jdk.ExecutionSample`: 1200 个已记录事件。
- `jdk.ObjectAllocationSample`: 3918 个已记录事件。
- `jdk.GarbageCollection`: 344 个已记录事件。
- `jdk.JavaMonitorEnter`: 0 个已记录事件。
- `jdk.VirtualThreadPinned`: 该运行时不可用。

CPU 叶方法采样（整个负载进程，包括预热与百分位排序）：

| 方法 | 样本数 |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 637 |
| `java.util.DualPivotQuicksort.sort` | 361 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire` | 81 |
| `io.ominicat.core.Pipeline.lambda$dispatchSession$2` | 24 |
| `io.ominicat.core.PipelineSession.run` | 21 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 13 |
| `java.util.concurrent.locks.ReentrantLock.lock` | 13 |
| `io.ominicat.core.PipelineSession.take` | 8 |

分配采样权重（估计值，不是各类型的精确分配总量）：

| 类型 | 估计 MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda$101+0x00007b312c014000.320929598` | 5,611.436 |
| `io.ominicat.core.SynchronousSink` | 4,846.964 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 2,337.570 |
| `io.ominicat.core.PipelineSession$Pending` | 1,457.075 |
| `io.ominicat.core.PipelineSession$$Lambda$98+0x00007b312c00fa10.342683394` | 1,323.162 |
| `[J` | 381.605 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 73.290 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode` | 56.938 |

事件计数是本次记录的观测值。记录到零个固定事件不证明其他负载中不存在固定；仍受 JFR 阈值与采样影响。

原始证据： [JMH JSON](../../../../../reports/performance-audit/linux-java17-2026-11-01/jmh.json), [JMH 输出](../../../../../reports/performance-audit/linux-java17-2026-11-01/jmh.log), [负载 JSON](../../../../../reports/performance-audit/linux-java17-2026-11-01/load.json), [GC 日志](../../../../../reports/performance-audit/linux-java17-2026-11-01/gc.log), [JFR 摘要](../../../../../reports/performance-audit/linux-java17-2026-11-01/jfr-summary.txt), [紧凑 JFR 热点](../../../../../reports/performance-audit/linux-java17-2026-11-01/jfr-hotspots.json)。二进制 JFR 记录（`kernel.jfr`）和未压缩选定事件导出在本地生成，不纳入版本控制。

使用 Java 17+ 从仓库根目录复现：`scripts/profile.sh quick reports/latest`。使用 `full` 可延长预热和测量。在 JDK Mission Control 打开 JFR 记录，分析调用栈、分配、锁和 GC。

比较修订时，应使用同一空闲主机、JVM、堆、阶段数与剖析设置。更广泛的声明需要更长运行、重复测量、按到达速率驱动的负载测试，以及真实提供方和传输测量。
