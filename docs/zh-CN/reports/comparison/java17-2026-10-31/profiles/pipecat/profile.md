<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../../reports/comparison/java17-2026-10-31/profiles/pipecat/profile.md) · [中文文档目录](../../../../../index.md)
<!-- /ominicat-language-nav -->

# Pipecat 插桩 Python 诊断

合成排队 W1 路径的独立 cProfile 插桩诊断；不是主计时、原生 CPU 栈采样、存活堆测量或跨语言分配／B/op 对比。仅覆盖当前事件循环线程。不包含导入、启动、预热和清理。对 Python 及暴露的 C 调用入口归因，不解析原生实现内部。累计调用时间会重叠，不能相加成互斥的成本分解。

采集在 measure 标记刷出后开始，在 teardown 标记前停止。包含被测客户端循环、数值校验器、原生队列／输出回调和少量阶段返回协调。剖析会改变执行成本；此运行不纳入五进程主对比。自身时间不含子调用，累计时间包含子调用。

原始剖析数据在本地生成，不得发布。安全的 [JSON](../../../../../../../reports/comparison/java17-2026-10-31/profiles/pipecat/summary.json) 和 [CSV](../../../../../../../reports/comparison/java17-2026-10-31/profiles/pipecat/summary.csv) 只保留归一化代码位置。

| 排名 | 函数 | 自身时间（秒） | 累计时间（秒） |
| --- | --- | ---: | ---: |
| 1 | `[native]:0 (<method 'control' of 'select.kqueue' objects>)` | 1.553723 | 1.553723 |
| 2 | `site-packages/pipecat/processors/frame_processor.py:1212 (__internal_push_frame)` | 0.201949 | 0.977841 |
| 3 | `stdlib/asyncio/base_events.py:1908 (_run_once)` | 0.200154 | 4.980287 |
| 4 | `comparison/pipecat/benchmark.py:74 (__setattr__)` | 0.198314 | 0.282687 |
| 5 | `site-packages/pipecat/processors/frame_processor.py:849 (process_frame)` | 0.186368 | 0.408261 |
| 6 | `stdlib/asyncio/queues.py:149 (get)` | 0.129854 | 0.336429 |
| 7 | `[native]:0 (<built-in method builtins.isinstance>)` | 0.097695 | 0.097695 |
| 8 | `site-packages/pipecat/frames/frames.py:108 (__post_init__)` | 0.089811 | 0.269817 |
| 9 | `site-packages/pipecat/processors/frame_processor.py:1330 (__process_frame)` | 0.085871 | 1.896875 |
| 10 | `[native]:0 (<built-in method builtins.hasattr>)` | 0.084373 | 0.084373 |
| 11 | `stdlib/asyncio/queues.py:137 (put_nowait)` | 0.079878 | 0.359511 |
| 12 | `site-packages/pipecat/utils/base_object.py:240 (_call_event_handler)` | 0.075767 | 0.128993 |
| 13 | `comparison/pipecat/benchmark.py:238 (process_frame)` | 0.073409 | 1.405394 |
| 14 | `site-packages/pipecat/processors/frame_processor.py:124 (__getattribute__)` | 0.067513 | 0.067513 |
| 15 | `stdlib/asyncio/base_events.py:812 (_call_soon)` | 0.063420 | 0.117751 |

定义遵循 [CPython cProfile 文档](https://docs.python.org/3.12/library/profile.html).
这些插桩调用统计不能解释 Java 原生 CPU 栈，也不能建立共用的分配坐标。
