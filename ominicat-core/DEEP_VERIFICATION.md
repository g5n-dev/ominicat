# Core adversarial verification

Recorded on 2026-10-31 with Homebrew OpenJDK 21.0.13.0.3 on macOS/aarch64. This report covers the dependency-free kernel, its callback adapters, and its composing processors. It does not establish production capacity, cloud-provider correctness, or exhaustive concurrency safety.

## Observed results

| Run | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| New deterministic regressions against original `14e6bb0` core | 16 | 13 | 1 | 0 |
| Updated complete core suite, 512 stress seeds | 80 | 0 | 0 | 0 |
| Active-callback reachability with `jdk.trackAllThreads=false` | 1 | 0 | 0 | 0 |

The complete run executed **1,572,864 selected randomized operations**, plus 1,024 terminal-resource scenarios and 512 randomized fork/join cases. JUnit's summed test-case time was 2.102 seconds; that excludes Maven startup and compilation and is not a performance benchmark.

All Maven runs used separate temporary source snapshots and separate `target` directories. They did not clean or write the shared checkout's build outputs. A failing before-run was expected and retained as regression evidence.

## Reproduced defects and changes

| Reproduced behavior | Change | Regression evidence |
|---|---|---|
| A synchronous `next` retained by a processor could enter downstream stages after successful session completion. | Reject emissions after the originating invocation returns; also reject terminal session generations. | `SinkLifetimeTest` |
| A callback on another thread could invoke downstream processors while the original synchronous processor was still active. | Enforce the invocation's thread and lifetime; callback providers use `AsyncProcessor`. | `SinkLifetimeTest` |
| A captured sink could be reused in a later invocation on the same worker. | Every invocation gets its own invalidated sink boundary. | `SinkLifetimeTest` |
| A failed primary could emit from its captured sink while the secondary was active. | Independently scope primary and secondary sinks. | `CompositeLifetimeTest` |
| A captured parallel-branch sink could outlive its bounded branch buffer. | Scope branch emission to the branch invocation. | `CompositeLifetimeTest` |
| Closing a parallel processor during output delivery allowed additional joined output after the already-entered sink returned. | Check cancellation before each subsequent delivery. A callback already entered is still cooperative. | `CompositeLifetimeTest` |
| An `Error` during one child's cleanup skipped the remaining owned children. | Attempt every child cleanup, preserve the first failure and suppressed failures; make fallback close idempotent. | `CompositeLifetimeTest` |
| A duplicated processor identity in a chain could close the same owned resource twice. | Reject duplicate identities within a chain. Sharing a processor across different active pipelines remains unsupported. | `CompositeLifetimeTest` |
| An SDK-retained retired raw emitter kept the terminated session/output graph alive through its enclosing instance. | Use a static callback handle with weak session ownership. | `RetiredCallbackRetentionTest` |
| A retired envelope callback retained its input payload, enclosing adapter/delegate, or ID-supplier graph. | Copy metadata without the payload; detach terminal callback references; scope synchronous envelope callbacks too. | `RetiredCallbackRetentionTest` |

Invalidated synchronous sinks detach their downstream graph and worker reference. Envelope cancellation closes the provider handle on the session worker, outside the wrapper monitor, so provider shutdown can coordinate with callback threads.

## Independent oracles and schedules

| Test | Base seed | Seeds/modes | Selected operations or cases |
|---|---:|---|---:|
| Blocked-worker FIFO/lifecycle oracle | `104372206247974` | 512 | 524,288 operations |
| Four-actor submit/interrupt/finish/close races | `104372206247974` | 512 | 524,288 operations |
| Three-actor callback/lifecycle races | `2770083878` | 512 × raw/envelope | 524,288 operations |
| Retained-resource terminal-order oracle | `51814211622` | 512 × raw/envelope | 1,024 cases |
| Parallel ordered-join/resource oracle | `775891984422` | 512 | 512 cases |

The blocked-worker test keeps processing suspended while an independent model predicts acceptance, FIFO contents, intake state, queue clearing, rejection counts, and terminal output. It checks those predictions after each selected operation. Capacities vary from one to eight pending items.

The concurrent tests record actual acceptance independently, identify every emitted frame, reject duplicate/unaccepted outputs, and assert queue bounds. During execution, accepted work minus processed, failed, cancelled, and queued work must be zero or one; after termination it must be zero. These tests account for the fact that an already-entered output callback can finish while its work item is classified as cancelled.

Asynchronous tests race emission, completion, failure, interruption, draining, and close. Raw callbacks and envelope callbacks use the same bounded continuation queue. The tests distinguish kernel rejection counts from rejection inside an already-retired envelope adapter. They verify worker-thread execution, stale callback rejection, immutable terminal outcomes, and one physical resource release. Retained callbacks are deliberately reused after termination.

Parallel tests vary branch counts, output counts, failure positions, and buffer limits. An independent oracle expects declaration-order output only when every branch succeeds. Overflow or branch failure must emit no joined partial result. Every owned child closes once in reverse order after its active work ends. A separate timeout probe temporarily ignores interruption and verifies cleanup does not overlap its still-active branch.

The reachability tests retain invalid callbacks while requesting GC, then verify that 1 MiB input/output, delegate, and ID-supplier graphs become collectible after normal completion, interruption, and close. A separate test verifies an active callback still reaches a live session. Reachability tests are skipped if explicit GC is disabled; **none were skipped in the recorded run**.

## Reproduction

From the repository root, create an isolated snapshot and overlay the current core sources:

```sh
OMINICAT_AUDIT_DIR=$(mktemp -d /tmp/ominicat-core-audit.XXXXXX)
git archive 14e6bb0 | tar -x -C "$OMINICAT_AUDIT_DIR"
rsync -a --exclude target ominicat-core/ "$OMINICAT_AUDIT_DIR/ominicat-core/"
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -f "$OMINICAT_AUDIT_DIR/ominicat-core/pom.xml" \
  -Dominicat.stress.seeds=512 clean test
```

Use an appropriate Java 21 `JAVA_HOME` on another machine. The recorded Maven executable was `/opt/homebrew/bin/mvn`. The ordinary test suite defaults to 64 seeds per randomized test; `ominicat.stress.seeds` raises the repetition count.

To check active callback reachability with virtual-thread tracking disabled:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -f "$OMINICAT_AUDIT_DIR/ominicat-core/pom.xml" \
  '-Dtest=RetiredCallbackRetentionTest#activeCallbackKeepsItsSessionAliveUntilTheOperationTerminates' \
  -DargLine=-Djdk.trackAllThreads=false test
```

To reproduce the original failures, create another `14e6bb0` snapshot, copy only `SinkLifetimeTest.java`, `CompositeLifetimeTest.java`, and `RetiredCallbackRetentionTest.java` from the updated test directory into that snapshot, then run:

```sh
mvn -q -f "$OMINICAT_BEFORE_DIR/ominicat-core/pom.xml" \
  -Dtest=SinkLifetimeTest,CompositeLifetimeTest,RetiredCallbackRetentionTest clean test
```

That before-run is expected to exit unsuccessfully. Surefire XML records 16 tests, 13 failures, and one error. The updated full run's Surefire XML records 80 tests and no failures, errors, or skips.

## Limits

Seeds make operation selection repeatable; they do not reproduce a JVM thread schedule. These tests cover many observed interleavings and specific reproduced failures, not every possible execution. Queue bounds count frames rather than arbitrary application payload bytes. Cancellation cannot revoke external effects of an output callback already entered, and a processor that ignores interruption can delay cleanup. `END` remains an ordinary frame; graceful session completion is the drain barrier for asynchronous handles and their accepted continuations.
