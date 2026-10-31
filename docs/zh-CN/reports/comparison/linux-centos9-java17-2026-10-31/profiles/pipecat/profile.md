<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../../reports/comparison/linux-centos9-java17-2026-10-31/profiles/pipecat/profile.md) · [中文文档目录](../../../../../index.md)
<!-- /ominicat-language-nav -->

# Pipecat 插桩 Python 诊断

合成排队 W1 路径的独立 cProfile 插桩诊断；不是主计时、原生 CPU 栈采样、存活堆测量或跨语言分配／B/op 对比。仅覆盖当前事件循环线程。不包含导入、启动、预热和清理。对 Python 及暴露的 C 调用入口归因，不解析原生实现内部。累计调用时间会重叠，不能相加成互斥的成本分解。

采集在 measure 标记刷出后开始，在 teardown 标记前停止。包含被测客户端循环、数值校验器、原生队列／输出回调和少量阶段返回协调。剖析会改变执行成本；此运行不纳入五进程主对比。自身时间不含子调用，累计时间包含子调用。

原始剖析数据在本地生成，不得发布。安全的 [JSON](../../../../../../../reports/comparison/linux-centos9-java17-2026-10-31/profiles/pipecat/summary.json) 和 [CSV](../../../../../../../reports/comparison/linux-centos9-java17-2026-10-31/profiles/pipecat/summary.csv) 只保留归一化代码位置。

| 排名 | 函数 | 自身时间（秒） | 累计时间（秒） |
| --- | --- | ---: | ---: |
| 1 | `comparison/pipecat/benchmark.py:74 (__setattr__)` | 0.329014 | 0.443019 |
| 2 | `site-packages/pipecat/processors/frame_processor.py:1212 (__internal_push_frame)` | 0.273558 | 1.418368 |
| 3 | `site-packages/pipecat/processors/frame_processor.py:849 (process_frame)` | 0.267618 | 0.591330 |
| 4 | `stdlib/asyncio/base_events.py:1922 (_run_once)` | 0.262478 | 4.973428 |
| 5 | `stdlib/asyncio/queues.py:149 (get)` | 0.178166 | 0.473829 |
| 6 | `[native]:0 (<built-in method builtins.isinstance>)` | 0.139836 | 0.139836 |
| 7 | `site-packages/pipecat/processors/frame_processor.py:1330 (__process_frame)` | 0.122218 | 2.737456 |
| 8 | `site-packages/pipecat/frames/frames.py:108 (__post_init__)` | 0.118095 | 0.397868 |
| 9 | `[native]:0 (<built-in method builtins.hasattr>)` | 0.114006 | 0.114006 |
| 10 | `stdlib/asyncio/queues.py:137 (put_nowait)` | 0.106211 | 0.509904 |
| 11 | `site-packages/pipecat/processors/frame_processor.py:124 (__getattribute__)` | 0.104684 | 0.104684 |
| 12 | `[native]:0 (<method 'poll' of 'select.epoll' objects>)` | 0.103968 | 0.103968 |
| 13 | `site-packages/pipecat/utils/base_object.py:240 (_call_event_handler)` | 0.102291 | 0.174775 |
| 14 | `comparison/pipecat/benchmark.py:238 (process_frame)` | 0.097996 | 2.026570 |
| 15 | `stdlib/asyncio/base_events.py:818 (_call_soon)` | 0.090490 | 0.171616 |

定义遵循 [CPython cProfile 文档](https://docs.python.org/3.12/library/profile.html).
这些插桩调用统计不能解释 Java 原生 CPU 栈，也不能建立共用的分配坐标。
