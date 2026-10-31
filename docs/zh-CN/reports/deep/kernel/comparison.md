<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../reports/deep/kernel/comparison.md) · [中文文档目录](../../../index.md)
<!-- /ominicat-language-nav -->

# 回调生命周期修复后的历史比较

这些是独立测量，并非受控 A/B 实验。本次运行与限速的 64 个常驻客户端加 4 个连接更替客户端正确性持续测试及开发工作共用主机。两次运行均不能确立服务器或提供方容量。

先前源码指纹：`7e06420ee8e1911f75afc30a4fc687fecdf3c468dc7e805d73ced416c6e030aa`。当前指纹：`7f3b929c919da480b5014b1bd01b9891c1b1aa034787fe8a5facfcb320978017`.

| 基准测试 | 阶段数 | 先前值 | 当前值 | 得分变化 | 单位 | 先前 B/op | 当前 B/op | 分配变化 |
|---|---:|---:|---:|---:|---|---:|---:|---:|
| PipelineBenchmark.dispatch | 1 | 234,421,494.592 | 113,224,138.704 | -51.70% | ops/s | 64.000 | 112.000 | +48.000 B/op |
| PipelineBenchmark.dispatch | 4 | 79,935,482.684 | 40,009,652.744 | -49.95% | ops/s | 136.000 | 328.000 | +192.000 B/op |
| PipelineBenchmark.dispatch | 16 | 25,120,225.657 | 11,615,086.320 | -53.76% | ops/s | 424.000 | 1192.001 | +768.000 B/op |
| SessionBenchmark.roundTrip | 1 | 8.320 | 8.128 | -2.32% | us/op | 215.893 | 272.032 | +56.140 B/op |
| SessionBenchmark.roundTrip | 4 | 8.114 | 8.229 | +1.42% | us/op | 287.893 | 512.014 | +224.122 B/op |
| SessionBenchmark.roundTrip | 16 | 8.805 | 8.575 | -2.61% | us/op | 575.897 | 1472.031 | +896.134 B/op |

对于 `ops/s`，得分越低表示吞吐量越低。对于 `us/op`，得分越高表示往返越慢。分配值是 JMH GC 剖析器对每次操作的估计，其中包含基准测试工作负载。

直接分派基准测试覆盖新增的同步调用保护。会话基准测试还包含排队、工作线程唤醒和输出交接。这些基准测试没有单独覆盖或隔离异步句柄退役或信封回调包装；这些路径有专门的正确性、竞态和可达性测试。

内联与逃逸分析的变化、JVM 行为以及共享主机调度都可能影响这些观测。本次运行未识别各因素的贡献。不能据此推断任何优化结论，也不能声称正确性保护没有成本。

证据：[当前 JMH JSON](../../../../../reports/deep/kernel/jmh.json)、[先前 JMH JSON](../../../../../reports/deep/kernel/previous-jmh.json)、[机器可读比较](../../../../../reports/deep/kernel/comparison.json)、[当前源码清单](../../../../../reports/deep/kernel/source-sha256.json)、[先前源码清单](../../../../../reports/deep/kernel/previous-source-sha256.json)、[完整测量报告](report.md)。
