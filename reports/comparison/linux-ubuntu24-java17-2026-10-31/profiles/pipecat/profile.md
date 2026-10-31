# Pipecat instrumented Python diagnosis

Separate instrumented cProfile diagnosis of the synthetic queued-W1 path; not a headline timing, native CPU stack sample, retained-heap measure or cross-language allocation/B-op comparison. Current event-loop thread only. Imports, startup, warmup and teardown are excluded. Python and exposed C call entries are attributed; native implementation internals are not resolved. Cumulative call times overlap and must not be summed as an exclusive breakdown.

Capture starts after the flushed measure marker and stops before the teardown marker. The measured client loop, numeric oracle, native queues/output callback and small phase-return coordination are included. Profiling changes execution cost; this run is excluded from the five-process headline comparison. Self time excludes subcalls; cumulative time includes them.

Raw profiler data is generated locally and must not be published. The safe [JSON](summary.json) and [CSV](summary.csv) retain normalized code locations only.

| Rank | Function | Self seconds | Cumulative seconds |
| --- | --- | ---: | ---: |
| 1 | `comparison/pipecat/benchmark.py:74 (__setattr__)` | 0.343092 | 0.476141 |
| 2 | `site-packages/pipecat/processors/frame_processor.py:1212 (__internal_push_frame)` | 0.281139 | 1.417983 |
| 3 | `stdlib/asyncio/base_events.py:1922 (_run_once)` | 0.268104 | 4.972169 |
| 4 | `site-packages/pipecat/processors/frame_processor.py:849 (process_frame)` | 0.266903 | 0.596038 |
| 5 | `stdlib/asyncio/queues.py:149 (get)` | 0.170084 | 0.451057 |
| 6 | `[native]:0 (<built-in method builtins.isinstance>)` | 0.161024 | 0.161024 |
| 7 | `[native]:0 (<built-in method builtins.hasattr>)` | 0.133050 | 0.133050 |
| 8 | `site-packages/pipecat/processors/frame_processor.py:1330 (__process_frame)` | 0.124676 | 2.767018 |
| 9 | `site-packages/pipecat/processors/frame_processor.py:124 (__getattribute__)` | 0.121229 | 0.121229 |
| 10 | `site-packages/pipecat/frames/frames.py:108 (__post_init__)` | 0.116041 | 0.405774 |
| 11 | `[native]:0 (<method 'poll' of 'select.epoll' objects>)` | 0.107308 | 0.107308 |
| 12 | `site-packages/pipecat/utils/base_object.py:240 (_call_event_handler)` | 0.104181 | 0.174181 |
| 13 | `comparison/pipecat/benchmark.py:238 (process_frame)` | 0.101203 | 2.049949 |
| 14 | `stdlib/asyncio/queues.py:137 (put_nowait)` | 0.097630 | 0.491844 |
| 15 | `stdlib/asyncio/base_events.py:818 (_call_soon)` | 0.092938 | 0.167847 |

Definitions follow the [CPython cProfile documentation](https://docs.python.org/3.12/library/profile.html).
These instrumented call statistics do not explain Java native CPU stacks or establish a common allocation axis.
