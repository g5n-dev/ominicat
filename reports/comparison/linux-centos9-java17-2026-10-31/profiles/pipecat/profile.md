# Pipecat instrumented Python diagnosis

Separate instrumented cProfile diagnosis of the synthetic queued-W1 path; not a headline timing, native CPU stack sample, retained-heap measure or cross-language allocation/B-op comparison. Current event-loop thread only. Imports, startup, warmup and teardown are excluded. Python and exposed C call entries are attributed; native implementation internals are not resolved. Cumulative call times overlap and must not be summed as an exclusive breakdown.

Capture starts after the flushed measure marker and stops before the teardown marker. The measured client loop, numeric oracle, native queues/output callback and small phase-return coordination are included. Profiling changes execution cost; this run is excluded from the five-process headline comparison. Self time excludes subcalls; cumulative time includes them.

Raw profiler data is generated locally and must not be published. The safe [JSON](summary.json) and [CSV](summary.csv) retain normalized code locations only.

| Rank | Function | Self seconds | Cumulative seconds |
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

Definitions follow the [CPython cProfile documentation](https://docs.python.org/3.12/library/profile.html).
These instrumented call statistics do not explain Java native CPU stacks or establish a common allocation axis.
