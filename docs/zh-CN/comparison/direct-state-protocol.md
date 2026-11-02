<!-- ominicat-language-nav -->
**简体中文** · [English](../../../comparison/direct-state-protocol.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 直接调用状态：结构变化的配对实验

本预先登记的协议定义范围受限的结构实验，实测结果应记录在绑定源码的结果报告中。这是在 OminiCat 内部比较因果关系，不是另一份框架排名。完整 Linux 实验与生命周期检查通过前不认定收益。负面或不确定结果也应保留，但不能据此发布更差的运行时。

## 唯一受控改动

基线直接分发先求值捕获式 continuation，再用新的同步 sink 包装。候选用一个私有静态、每次新建的对象同时表示 continuation 和调用生命周期。它仍按顺序调用各处理器，支持过滤及多次输出，检查调用线程与有效期，拒绝空输出帧，并在 `finally` 中清除所属线程、流水线和输出引用。它不池化、不重新激活已退休句柄。

生产内核源码只允许 `Pipeline.java` 不同。会话分发、异步续接、generation 栅栏、共享同步 sink 与媒体构造器保持不变。少一个分配表达式本身不能证明堆字节数或速度改善：内联和逃逸分析可能消除或改变分配。必须用同一可执行探针比较实际 JAR。

## 固定行为范围

七个场景通过公开 API 执行，逐阶段及最终结果均校验：

| 场景 | 工作及边界 |
| --- | --- |
| `raw-direct-1` | 一个数值阶段；公开直接调用及同步返回。 |
| `raw-direct-4` | 四个数值阶段；预先登记的主要分配用例。 |
| `raw-direct-16` | 十六个数值阶段；检查阶段数量敏感性。 |
| `envelope-direct-4` | 四个真实信封适配器；确定性的标识符和时钟供应器，精确校验元数据与因果关系，其工作计入时间。 |
| `raw-session-4` | 未改的真实会话路径，容量为一且单请求在途。包含最终确认及等待成功处理计数；对照单独报告，不用于修正其他结果。 |
| `audio-direct-4` | 四个透传阶段，循环使用 64 个预建、不可变的 640 字节、16 kHz 单声道 PCM 帧；精确校验帧身份及阶段顺序。 |
| `image-direct-4` | 四个透传阶段，循环使用 64 个预建、不可变的 64 KiB 合成图像载荷；精确校验帧身份及阶段顺序。 |

数值输入与转换帧在计时中创建，并使用轮换的独立结果校验。媒体创建、字节快照验证及只读检查在准备阶段完成；不计媒体构造器或 codec 成本。重用预建媒体不等于测量音频／图像创建、解码或生产媒体容量。准备、产物校验及资源清理在基准方法之外；会话校验和等待成本属于该对照自身的测量范围。

## 配对与溯源

使用 Ubuntu 24.04、明确选择的 Java 17 JVM、一个基准线程与固定 256 MiB 堆。构建基线和候选 core JAR，再构建一份探针 JAR。每个全新 JVM 将所选 core 放在 classpath 首位，使用完全相同的探针字节。准备阶段按必填 SHA-256 属性检查公开内核类、流水线嵌套类和探针实际来自的 JAR。公开证据不打印路径。

固定计划包含三个 ABBA 块。每个场景依次运行基线、候选、候选、基线，每次为全新、单 fork 的 JMH JVM；中间块反转场景顺序。完整计划共 84 个 JVM，每侧每场景六个进程。每个进程进行三次一秒预热和五次一秒测量，报告 AverageTime `ns/op` 与 GC profiler 分配 `B/op`。保留每侧每场景全部 30 次测量迭代、六个进程均值及源码／产物／环境指纹。

每块将两个候选进程均值的算术平均，与两个基线进程均值的算术平均比较。报告三个配对比值、观察范围及算术平均。独立重复单位为进程和块；同一进程的迭代不能算作独立重复。观察范围不是置信区间；共享 runner 波动与三个块不能建立通用性能保证。

## 预先登记的验收与反例

观察目标为：主要用例每块分配至少降低 10%；所有改动所影响的直接调用用例，每块耗时增加不超过 5%，分配增加不超过 1%。未改的会话对照不能挽救失败目标，也不能用于事后修正噪声。采集器将技术完整性与目标分开归档：一致满足为 `observed-targets-met`；点估计满足但块范围不一致为 `uncertain`；否则为 `targets-not-met`。这些是实验决策，不是 CI 性能阈值门禁或统计显著性声明。分配基线与候选都为零时比值为 1；零基线上出现正分配时保留 null 比值并判目标失败，不生成 Infinity，也不把合法观察当成技术采集失败。

发布还要求 Java 17 coverage／SBOM 校验、Java 17/21/25 兼容与 512 个运行时压力种子。行为 fixture 必须保留过滤、有序多输出、嵌套直接调用、空值／错误行为、错误线程拒绝和返回后拒绝。插件持有的退休句柄不得保留流水线、处理器捕获状态、输出状态、输入载荷或所属线程。先输出再抛异常仍是失败调用。取消与 generation 测试继续保护未改的会话／异步行为。

证据不完整、混用、变更、非有限或来源绑定错误时，采集必须失败，不能丢弃用例。不支持或不确定时，保留证据并保持生产行为不变。本结果不能解释旧 Camel SEDA 差距的某个百分比：原比较还有不同 API、元数据及确认机制，而且 Camel 入口队列之后本就使用同步路由。

## 独立诊断

分别记录基线与候选的 `raw-direct-4`、`envelope-direct-4`，共四个独立插桩 JVM。JMH 不再派生进程，进行两次一秒预热及两次一秒测量。请求 10 ms 执行采样、每秒最多 50 个分配采样事件，以及 64 层记录／导出栈。选定事件 JSON 的 128 MiB 上限保持严格；超限、不完整或缺失时失败，不截断证据。

CPU／分配／GC 图包含启动、准备、预热、测量程序及清理，不是额外的正式计时样本。总样本数、估计分配权重和 GC 次数未按操作归一化；更快的循环在同一时长内可能完成更多工作。逐操作分配使用正式 JMH `B/op`。只发布选定白名单事件及派生诊断，不发布原始 JFR、私有配置、凭据或未经审查的工具日志。

## 复现

```sh
# In each clean baseline/candidate checkout, with the same Java 17 JAVA_HOME:
./mvnw -B -ntp -pl ominicat-core -am package -DskipTests
# Build the one shared probe in the candidate checkout:
./mvnw -B -ntp -pl ominicat-benchmarks -am package -DskipTests
python3 comparison/direct_state_evidence.py collect --baseline BASELINE_CHECKOUT --candidate . --output reports/local/direct-state/evidence/measurements
python3 comparison/direct_state_profiles.py collect --baseline BASELINE_CHECKOUT --candidate . --output reports/local/direct-state
python3 comparison/direct_state_evidence.py check reports/local/direct-state/evidence/measurements
python3 comparison/direct_state_profiles.py check reports/local/direct-state/evidence
```
