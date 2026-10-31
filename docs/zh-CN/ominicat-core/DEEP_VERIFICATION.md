<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-core/DEEP_VERIFICATION.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 内核对抗性验证

记录于 2026-10-31，环境为 macOS/aarch64、Homebrew OpenJDK 21.0.13.0.3。本报告覆盖无依赖内核、回调适配器和组合处理器，不证明生产容量、云供应商正确性或穷尽的并发安全性。

## 观测结果

| 运行 | 测试数 | 断言失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| 针对原始 `14e6bb0` 内核的新确定性回归 | 16 | 13 | 1 | 0 |
| 更新后的完整内核套件，512 个压力种子 | 80 | 0 | 0 | 0 |
| `jdk.trackAllThreads=false` 下的活动回调可达性 | 1 | 0 | 0 | 0 |

完整运行执行了 **1,572,864 次选定的随机操作**，另有 1,024 个终端资源场景和 512 个随机 fork/join 用例。JUnit 测试用例累计耗时为 2.102 秒，不含 Maven 启动与编译，也不属于性能基准。

所有 Maven 运行均使用独立临时源码快照和独立 `target` 目录，没有清理或写入共享检出目录的构建输出。修复前运行失败是预期结果，已保留作为回归证据。

## 已复现的缺陷与变更

| 已复现行为 | 变更 | 回归证据 |
|---|---|---|
| 处理器保留的同步 `next` 可在会话成功完成后进入下游阶段。 | 原调用返回后拒绝发射，同时拒绝已终止的会话代次。 | `SinkLifetimeTest` |
| 原同步处理器仍活动时，其他线程的回调可调用下游处理器。 | 强制检查调用的线程与生命周期；回调供应商使用 `AsyncProcessor`。 | `SinkLifetimeTest` |
| 捕获的 sink 可在同一 worker 的后续调用中复用。 | 每次调用都获得独立、可失效的 sink 边界。 | `SinkLifetimeTest` |
| 备用处理器活动期间，已失败的主处理器可从其捕获的 sink 发射。 | 分别限定主、备用 sink 的作用域。 | `CompositeLifetimeTest` |
| 捕获的并行分支 sink 可超出有界分支缓冲区的生命周期。 | 将分支发射限定在分支调用期间。 | `CompositeLifetimeTest` |
| 在输出交付期间关闭并行处理器，已进入的 sink 返回后仍可交付更多合并输出。 | 每次后续交付前检查取消；已进入的回调仍依赖协作。 | `CompositeLifetimeTest` |
| 某个子项清理中的 `Error` 会跳过其余自有子项。 | 尝试清理每个子项，保留首个失败及附带的 suppressed 失败，并使回退处理器的关闭保持幂等。 | `CompositeLifetimeTest` |
| 链中重复的处理器身份可能导致同一自有资源关闭两次。 | 拒绝链内重复身份。不同活动管线之间共享处理器仍不受支持。 | `CompositeLifetimeTest` |
| SDK 保留的已退役原始 emitter 通过外围实例继续持有已终止会话/输出对象图。 | 使用以弱引用持有会话的静态回调句柄。 | `RetiredCallbackRetentionTest` |
| 已退役的信封回调继续持有输入载荷、外围适配器/委托或 ID-supplier 对象图。 | 只复制元数据而不复制载荷；解除终态回调引用；也限定同步信封回调的作用域。 | `RetiredCallbackRetentionTest` |

失效的同步 sink 会解除其下游对象图与工作线程引用。信封取消会在会话工作线程、封装器监视器之外关闭供应商句柄，使供应商关闭流程可以与回调线程协作。

## 独立校验模型与调度

| 测试 | 基础种子 | 种子数/模式 | 选定操作或用例 |
|---|---:|---|---:|
| 阻塞 worker 的 FIFO/生命周期校验模型 | `104372206247974` | 512 | 524,288 次操作 |
| 四参与方 submit/interrupt/finish/close 竞态 | `104372206247974` | 512 | 524,288 次操作 |
| 三参与方回调/生命周期竞态 | `2770083878` | 512 × raw/envelope | 524,288 次操作 |
| 保留资源的终态顺序校验模型 | `51814211622` | 512 × raw/envelope | 1,024 个用例 |
| 并行有序合并/资源校验模型 | `775891984422` | 512 | 512 个用例 |

阻塞 worker 测试在暂停处理时，由独立模型预测接受情况、FIFO 内容、输入状态、队列清空、拒绝计数和终端输出；每次选定操作后检查预测。待处理容量在一到八项之间变化。

并发测试独立记录实际接受情况，标识每个已发射帧，拒绝重复或未接受的输出，并断言队列边界。执行中，已接受工作减去已处理、失败、取消及排队工作后，差值必须为零或一；终止后必须为零。测试考虑了这一情况：工作项被归类为取消时，已进入的输出回调仍可完成。

异步测试让发射、完成、失败、中断、排空和关闭相互竞争。原始回调和信封回调使用同一个有界续传队列。测试区分内核拒绝计数和已退役信封适配器内部的拒绝，验证工作线程执行、过期回调拒绝、不可变终态，以及物理资源只释放一次。终止后会故意再次使用保留的回调。

并行测试改变分支数、输出数、失败位置和缓冲区上限。独立校验模型仅在每个分支都成功时才期待按声明顺序输出；溢出或分支失败不得发射已合并的部分结果。各自有子项在活动工作结束后按逆序关闭一次。另一个超时探针会暂时忽略中断，验证清理不会与仍活动的分支重叠。

可达性测试保留无效回调并请求 GC，然后验证正常完成、中断和关闭后，1 MiB 输入/输出、委托和 ID-supplier 对象图可以被回收。另一个测试验证活动回调仍可达存活会话。禁用显式 GC 时，可达性测试会跳过；**记录的运行中没有测试被跳过**。

## 复现

从仓库根目录创建独立快照，并覆盖当前内核源码：

```sh
OMINICAT_AUDIT_DIR=$(mktemp -d /tmp/ominicat-core-audit.XXXXXX)
git archive 14e6bb0 | tar -x -C "$OMINICAT_AUDIT_DIR"
rsync -a --exclude target ominicat-core/ "$OMINICAT_AUDIT_DIR/ominicat-core/"
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -f "$OMINICAT_AUDIT_DIR/ominicat-core/pom.xml" \
  -Dominicat.stress.seeds=512 clean test
```

在其他机器上使用适合该机器的 Java 21 `JAVA_HOME`。记录的 Maven 可执行文件为 `/opt/homebrew/bin/mvn`。常规测试套件中，每个随机测试默认使用 64 个种子；`ominicat.stress.seeds` 可增加重复次数。

关闭虚拟线程跟踪后，检查活动回调可达性：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -f "$OMINICAT_AUDIT_DIR/ominicat-core/pom.xml" \
  '-Dtest=RetiredCallbackRetentionTest#activeCallbackKeepsItsSessionAliveUntilTheOperationTerminates' \
  -DargLine=-Djdk.trackAllThreads=false test
```

复现原始失败时，另建一个 `14e6bb0` 快照，仅将更新后测试目录中的 `SinkLifetimeTest.java`、`CompositeLifetimeTest.java` 和 `RetiredCallbackRetentionTest.java` 复制进去，然后运行：

```sh
mvn -q -f "$OMINICAT_BEFORE_DIR/ominicat-core/pom.xml" \
  -Dtest=SinkLifetimeTest,CompositeLifetimeTest,RetiredCallbackRetentionTest clean test
```

修复前运行应以失败状态退出。Surefire XML 记录了 16 个测试、13 个断言失败和一个错误。更新后完整运行的 Surefire XML 记录了 80 个测试，无断言失败、错误或跳过。

## 限制

种子使操作选择可以重复，不会复现 JVM 线程调度。这些测试覆盖许多已观测交错及具体复现的失败，不覆盖所有可能执行。队列边界按帧计数，不按任意应用载荷字节计数。取消不能撤销已进入输出回调的外部副作用；忽略中断的处理器可能延迟清理。`END` 仍是普通帧；会话的优雅完成才是异步句柄及其已接受续传的排空屏障。
