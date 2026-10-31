# Pipecat instrumented Python diagnosis

Separate instrumented cProfile diagnosis of the synthetic queued-W1 path; not a headline timing, native CPU stack sample, retained-heap measure or cross-language allocation/B-op comparison. Current event-loop thread only. Imports, startup, warmup and teardown are excluded. Python and exposed C call entries are attributed; native implementation internals are not resolved. Cumulative call times overlap and must not be summed as an exclusive breakdown.

Capture starts after the flushed measure marker and stops before the teardown marker. The measured client loop, numeric oracle, native queues/output callback and small phase-return coordination are included. Profiling changes execution cost; this run is excluded from the five-process headline comparison. Self time excludes subcalls; cumulative time includes them.

Raw profiler data is generated locally and must not be published. The safe [JSON](summary.json) and [CSV](summary.csv) retain normalized code locations only.

| Rank | Function | Self seconds | Cumulative seconds |
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

Definitions follow the [CPython cProfile documentation](https://docs.python.org/3.12/library/profile.html).
These instrumented call statistics do not explain Java native CPU stacks or establish a common allocation axis.
