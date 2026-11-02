<!-- ominicat-language-nav -->
**简体中文** · [English](../../../comparison/mechanism-protocol.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# Java 机制受控实验

本测试检验性能解释背后的本地机制，与[协议 1](protocol.md)的原生排队比较分开。它不改变生产运行时，也不宣称模型、网络、媒体容量或功能等价性。

## 问题与控制变量

路径实验有四格：OminiCat 与 Camel，分别由基准线程直接调用，或通过同一外部交接执行。两者执行四次不可变数值变换，使用同一输出 oracle。OminiCat 调用真实 `Pipeline.process` API。Camel 4.22.2 调用真实 `direct:` InOut 路由，明确配置 `synchronous=true`、`block=false` 和 `failIfNoConsumers=true`。Camel 的两格都关闭 JMX、消息历史与流缓存。

交接只有一份实现：一个容量为一的输入 `ArrayBlockingQueue`、一个持续存活的平台工作线程和一个容量为一的确认队列。每次最多一个请求在途。同一框架内仅切换交接变量。每个阶段及终点都检查预期执行线程；输出校验检查序号、值、计数与顺序。只有同步后端返回后才发布成功确认：即使后端先输出正确值再抛错，实验仍须失败。

这个交接不使用 `PipelineSession` 或 SEDA。同框架的差值衡量整套外部队列／工作线程／确认机制，包括其分配与等待。跨框架的比较衡量同一交接条件下各自的公开同步路径，不能将它解释为某条指令、某把锁或通用路由的独立成本。

## 关联 Exchange 复制

另外两格共用一个预先构建的源 Exchange：返回源对象本身，或调用 `ExchangeHelper.createCorrelatedCopy(source, false, true)`。这与固定版本 SEDA 请求应答路径的参数一致。源构造及源／消息 ID 的惰性初始化均在计时前完成。两种变体消费并校验相同不可变 body 和已准备元数据；复制结果必须是独立 Exchange，并保留正确关联及消息身份。测试记录具体源对象配置，不代表所有 Exchange 形态。

这个组件实验不包含源 Exchange 构造、SEDA 排队、完成 latch 等待、结果回拷与路由执行。不能把其成本与路径差值相加，用来解释旧 SEDA 比较：JIT、对象逃逸、缓存、分配和 GC 存在相互作用。

## 测量协议

- Ubuntu 24.04 上的 Java 17，同一主机／JVM／设置与同一已校验探针 JAR；`-Xms256m -Xmx256m`。
- JMH 1.37 AverageTime，单位 `ns/op`，一个基准线程。每次路径操作包含输入构造、变换、结果检查、同步返回，以及选定时的完整交接／确认；复制操作使用上文的预备源对象。
- 六格先正序、再逆序：共十二个全新基准 JVM，每格两个独立进程。每个进程预热三次一秒，测量五次一秒。
- 保留全部原始迭代数据、两个进程均值与观测迭代范围。范围是观测值，不是置信区间。共享运行器噪声不作为性能阈值或生产容量估计。
- JMH GC profiling 提供参与 JVM 线程上的 `B/op`；交接工作线程在整个 trial 中保持存活。分配包含测量程序、框架和运行时，不代表框架独占字节或保留内存。
- 独立内部 profiler 将整个进程的 CPU 纳秒数，除以 profiler 迭代区间内 JMH 的全部操作计数。该区间包含迭代 hook，以及工作线程、JVM、GC 与测量程序工作；它不是墙钟延迟或单方法归因。
- 不插入逐次延迟数组或额外时钟。本实验报告均值，不报告 p99；也不将 JMH 启动器的采样称为基准进程 RSS。

## 正确性与失败边界

测试必须拒绝错误、重复或缺失输出、错误执行线程、饱和、输出后异常及缺失确认。关闭时排空自有工作、停止 Camel context 并等待外部工作线程结束。重试、丢弃用例、部分矩阵或只选最好进程都不能形成成功报告。每个全新进程准备阶段根据预期 SHA-256 核对实际执行 JAR 的来源。

采集器拒绝源文件／产物变更、意外设置或用例、缺失迭代、无效／非有限指标与未经审查的诊断内容。它记录源码、依赖及可执行产物哈希、主机／JVM 范围、执行顺序和计时边界；原始文件逐字节保留。

## 独立诊断与发布

独立、明确插桩的运行记录 CPU／分配栈和 GC 事件。它们使用不再派生进程的 JMH，使被记录 JVM 就是所选 JVM；其计时不进入主对比。采样包含初始化、预热、测量程序及清理。没有采样不等于不存在，分配采样权重也不是精确 `B/op`。

只发布选定事件诊断白名单及派生图。原始 JFR、配置和未审查工具输出保留在发布目录之外。源码／运行时与文件哈希绑定完整测量及诊断集合；现有原生排队结果与归档保持不变。

## 复现

```sh
./mvnw -B -ntp -pl ominicat-core -am install -DskipTests
./mvnw -B -ntp -f comparison/mechanisms/pom.xml verify
python3 comparison/mechanism_evidence.py collect --root . --output reports/local/mechanism-capture/evidence/measurements
python3 comparison/mechanism_profiles.py collect --root . --output reports/local/mechanism-capture
python3 comparison/mechanism_evidence.py check reports/local/mechanism-capture/evidence/measurements
python3 comparison/mechanism_profiles.py check reports/local/mechanism-capture/evidence
```

显式配置 Java 17 的 `JAVA_HOME`。专用 Linux 工作流串行执行这些步骤。编译与正确性测试同时在 Java 17、21、25 上运行，测量结果仍限定为 Java 17。
