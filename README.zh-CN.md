<p align="center"><a href="docs/assets/banner.svg"><img src="docs/assets/previews/banner.png" alt="OminiCat：Java 17 多模态会话微内核" width="100%"></a></p>

<p align="center"><a href="README.md">English</a> · <a href="#linux-框架性能实测">Linux 实测</a> · <a href="#高性能从哪里来">执行机制</a> · <a href="#先运行起来">快速开始</a> · <a href="docs/configuration.md">配置</a> · <a href="docs/plugins.md">插件开发</a> · <a href="docs/studio.md">Studio</a></p>

**OminiCat 是 Java 17+ 多模态会话微内核。** 用统一帧连接音频、文本、图像、转写、模型与会话处理器；通过配置组合插件，可嵌入现有 Java 应用，也可独立运行为 WebSocket 服务容器。

内核仅依赖 JDK，负责有界调度、取消、排空与生命周期，媒体、模型和应用策略由插件提供。当前为 **0.1 开发预览版**；[验证报告](reports/verification.md)记录了 **297 项通过测试、512 个随机种子**及本地协议故障验证。真实服务兼容性与部署容量仍需在目标环境验证。

[![OminiCat 模块与接入边界](docs/assets/previews/architecture-overview.png)](docs/assets/architecture-overview.svg)

## Linux 框架性能实测

在 **Ubuntu 24.04 原生运行器**和 **CentOS Stream 9 容器用户态**分别完成 OminiCat、Pipecat 1.15.0、Apache Camel 4.22.2 LTS 的无模型排队路径对比。两条作业共 **90 个独立进程、17,406,385 个测量输出**，全部通过逐帧输出、计数、拒绝与资源清理校验。

[![Ubuntu 24.04：单帧在途的框架执行对比](docs/assets/previews/ubuntu-comparison.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/overview.svg)

下表为**四阶段、单帧在途（W=1）**时，五个独立进程各项指标的中位数：

| 独立 Linux 作业 | 框架 | 处理速率 · 帧/秒 | 终点 p99 · µs | 采样进程峰值 RSS · MiB |
| --- | --- | ---: | ---: | ---: |
| Ubuntu 24.04 | **OminiCat** | **49,084** | **17.30** | 144.1 |
| Ubuntu 24.04 | Pipecat 1.15.0 | 5,300 | 220.35 | 93.5 |
| Ubuntu 24.04 | Apache Camel 4.22.2 LTS | 42,756 | 20.41 | 188.7 |
| CentOS Stream 9 用户态 | **OminiCat** | **66,567** | **12.44** | 145.3 |
| CentOS Stream 9 用户态 | Pipecat 1.15.0 | 10,019 | 130.88 | 88.0 |
| CentOS Stream 9 用户态 | Apache Camel 4.22.2 LTS | 59,527 | 13.78 | 188.9 |

每个平台测量 **3 套框架 × 3 种阶段数（1／4／16）× 5 个新进程 = 45 轮**，每进程预热 3 秒、测量 5 秒。相同数值变换使用不可变帧，每阶段创建新值。OminiCat 使用 `PipelineSession`，Pipecat 使用原生排队处理器，Camel 使用单消费者 SEDA；各自的对象、运行时、调度与公开 API 成本均计入。模型、网络、编解码及启动和清理不在计时区间内。

处理速率覆盖完整调用循环；终点延迟在输出校验之后、调用方确认之前结束。**W=1 速率不代表饱和吞吐或生产容量。** RSS 每 50 ms 采样，包含运行时、依赖和共同的 16 MiB 测量缓冲区；它不是框架独占内存、存活堆或 B/op，短暂峰值可能被漏采。Java 使用 `-Xms128m -Xmx512m`，Python 没有对应的受管堆上限。

| 环境 | Ubuntu 作业 | CentOS 作业 |
| --- | --- | --- |
| 用户态 | Ubuntu 24.04.5 LTS · x86_64 | CentOS Stream 9 · x86_64，固定摘要的官方容器 |
| CPU 型号／可用逻辑处理器 | AMD EPYC 9V74／4 | AMD EPYC 9V45／4 |
| Java | Temurin 17.0.21 | Red Hat OpenJDK 17.0.20.1 |
| Python | CPython 3.12.15 | CPython 3.12.15 |
| 内核边界 | Ubuntu 运行器内核 | **共享 Ubuntu 运行器内核**，验证 CentOS 用户态 |

同一作业内三套框架顺序执行并轮换次序；两条作业使用独立共享主机，没有独占 CPU。**仅比较同一作业内的结果，不据此给操作系统排名**；运行时、原生调度和对象模型的差异也不能归因于某一项内核设计。完整报告保留五轮最小—最大范围，不将其当作置信区间。

<details>
<summary>查看 CentOS Stream 9 用户态的完整对比图</summary>

[![CentOS Stream 9 用户态：单帧在途的框架执行对比](docs/assets/previews/centos-comparison.png)](reports/comparison/linux-centos9-java17-2026-10-31/overview.svg)

</details>

[Ubuntu 完整报告与逐轮数据](reports/comparison/linux-ubuntu24-java17-2026-10-31/README.md) · [CentOS 完整报告与逐轮数据](reports/comparison/linux-centos9-java17-2026-10-31/README.md) · [协议与复跑步骤](comparison/README.md) · [已完成的 CI 运行](https://github.com/g5n-dev/ominicat/actions/runs/41222330745) · [自动采集工作流](.github/workflows/framework-comparison.yml)。

## 高性能从哪里来

OminiCat 将同步调用、会话排队和外部异步回调分开，减少不必要的阶段间转换，并把资源与取消边界放在明确的位置。这些机制可以直接从源码核验；上面的对比保留了整个运行时成本，未单独测定各机制带来的加速比例。

[![同步分发、会话工作线程与异步续接路径](docs/assets/previews/execution-path.png)](docs/assets/execution-path.svg)

| 机制 | 实际执行方式与成本边界 |
| --- | --- |
| **帧直接传递** | [Pipeline](ominicat-core/src/main/java/io/ominicat/core/Pipeline.java) 将类型化 `Frame` 直接传给下一同步阶段，阶段间无需 JSON 或独立队列。同步嵌入调用无需会话调度；每次调用仍有 sink 生命周期保护和 continuation 分配，调用方负责串行访问与清理。 |
| **会话串行所有权** | [PipelineSession](ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java) 由一个工作线程推进线性阶段，使用 `ReentrantLock` 与 `ArrayDeque` 管理待处理项。异步输出重新入队，从下一阶段续接；显式并行分支另有受限工作线程。 |
| **有界接纳与过期隔离** | 输入和异步续接共用有界队列；活跃异步句柄另有独立上限。中断提升 generation、清理排队项并拒绝旧回调，避免过期工作继续向后发射。已发生的外部作用不能撤销，阻塞处理器仍须配合中断。 |
| **线程与传输边界** | [WorkerThreads](ominicat-core/src/main/java/io/ominicat/core/WorkerThreads.java) 在 Java 17 使用平台线程，21+ 自动选择虚拟线程；线程工厂探测在初始化时完成。服务器的处理器工作在会话线程运行；[帧协议编解码](ominicat-server/src/main/java/io/ominicat/server/FrameCodec.java)和网络收发仍有各自成本。 |
| **不可变媒体快照** | [Audio／Image](ominicat-core/src/main/java/io/ominicat/core/Frame.java) 构造时防御性复制输入，读取时提供独立只读视图，已构造帧可按引用经过各阶段。媒体复制、协议 JSON／Base64 和模型调用都有实际成本，数值帧对比不覆盖它们。 |

[![队列、异步句柄、分支与网络的独立资源边界](docs/assets/previews/bounded-runtime.png)](docs/assets/bounded-runtime.svg)

各层上限有不同单位：队列与分支缓冲约束待处理项或帧引用，网络出口约束待发送字节，它们不能合并解释为总内存预算。过载、取消与排空由[生命周期契约](docs/architecture.md)和[配置](docs/configuration.md)明确处理；虚拟线程也不会自动提高 CPU 计算性能。

## Linux CPU、分配与 GC

以下来自 **Ubuntu 作业的 OminiCat 四阶段独立 JFR 诊断**。整段记录包含 JVM／框架启动、预热、5 秒 W=1 测量、测量程序及清理，共有 **41 个 CPU 栈采样、445 个分配采样**。它与前述未插桩的 45 轮计时分开，不能用来替换主表或推导精确 B/op。

[![Ubuntu OminiCat：CPU 叶节点采样分布](docs/assets/previews/ubuntu-cpu-hotspots.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/cpu-hotspots.svg)

[![Ubuntu OminiCat：按类型统计的分配采样权重](docs/assets/previews/ubuntu-allocation-hotspots.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/allocation-hotspots.svg)

分配权重反映采样分布，包含测量程序的延迟数组；它不是存活堆或精确对象数量。CPU 采样也包含初始化和排序，少量样本不能证明某条路径没有成本。

[![Ubuntu OminiCat：整段 JFR 记录中的 GC 暂停合计](docs/assets/previews/ubuntu-gc-pauses.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/gc-pauses.svg)

这份记录包含 **3 次 GC**，累计暂停 **4.876 ms**，单次暂停合计最大 **2.580 ms**；时间位置相对首个选定事件，包含预热和测量程序。

<details>
<summary>展开完整 CPU 与分配调用栈</summary>

图中保留导出数据提供的全部正权重栈帧，并标明源记录截断或不可用的栈。点击预览打开 SVG 原图，可放大并查看方法提示。

[![Ubuntu OminiCat：CPU 采样完整调用栈](docs/assets/previews/ubuntu-cpu-flamegraph.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/cpu-flamegraph.svg)

[![Ubuntu OminiCat：分配采样完整调用栈](docs/assets/previews/ubuntu-allocation-flamegraph.png)](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/allocation-flamegraph.svg)

</details>

[Ubuntu 诊断与采集范围](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/README.md) · [OminiCat 详细诊断](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/profiles.md) · [CentOS 诊断](reports/comparison/linux-centos9-java17-2026-10-31/profiles/README.md)。Java JFR 与 Python cProfile 的采集区间和指标不同，不作跨语言分配或 CPU 百分比排名。

## 先运行起来

最低支持 **JDK 17**；同一 Java 17 字节码在 17／21／25 上验证，21+ 自动选择虚拟线程。仓库自带 Maven Wrapper，详见[版本兼容](docs/java-compatibility.md)。

```bash
git clone https://github.com/g5n-dev/ominicat.git
cd ominicat
./mvnw -B verify
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

打开 [OminiCat Studio](http://127.0.0.1:8080)。默认 `echo` 配置不需要模型密钥；大盘按输入、实际处理阶段和产出分列，点击任务查看内容与保留的事件，再进入独立调试页连接、发送文字、麦克风录音、图片或转写。

![OminiCat Studio 动态运行大盘](docs/assets/studio-overview.jpg)

动态交接回放来自可选的活动记录，刻意放慢快速事件，不能当作逐节点实时耗时或任务提交证明。Studio 保留有界内容预览；活动 API 是运行时级操作视图，部署时应设置鉴权和访问范围。普通连接默认不记录阶段活动。

[大盘与调试使用说明](docs/studio.md) · [三阶段离线示例](config/studio.yaml) · [浏览器与资源验证](reports/studio/README.md)。

## 小内核，可组合的能力

| 层 | 提供的机制 |
|---|---|
| 微内核 | 零第三方运行依赖、统一帧、有界队列、跨版本工作线程、取消栅栏、排空与资源回收 |
| 流式扩展 | 同步处理器和独立的异步回调 SPI；回调重新进入有界调度队列 |
| 编排 | 命名分支与确定顺序的汇合、分支截止时间、输出前降级、独立异步出口 |
| 会话 | 稳定片段 ID、修订与最终提交、角色和说话人分离、时间区间、有界窗口与快照 |
| 可选媒体引用 | 来源、轨道、时间基准、生产者作用域和片段版本；明确区间/点/未知位置、显式帧映射与有界导入导出 |
| 可配置策略 | 带版本和证据的结构化评估、提示模板、可寻址步骤与条件路由 |
| 容器 | Netty WebSocket、YAML 配置、SPI 发现、鉴权、容量限制、Prometheus 指标 |

```yaml
host: 127.0.0.1
port: 8080
queueCapacity: 256
maxSessions: 256
stages:
  - type: transcript-gate
  - type: conversation-evaluator
    options:
      speaker: agent
      requiredPhrases: "hello,thank you"
      policyVersion: "example-v1"
```

每个会话创建独立处理器实例。新能力实现 `ProcessorFactory`，在 `META-INF/services/io.ominicat.core.spi.ProcessorFactory` 注册，再由 YAML 选择；插件依赖核心，内核无需依赖具体厂商或应用策略。构建并安装本仓库后即可使用，当前尚未发布到 Maven Central。详见[插件开发](docs/plugins.md)。

嵌入应用可用 `Pipeline.process` 同步处理，或用 `openSession` 获得排队、取消与排空语义；同步调用方自行管理处理器生命周期。`FrameEnvelope` 为帧携带会话、轮次、关联、因果和时间信息，`EnvelopeProcessor.wrap` 保留同步与异步阶段的元数据。

音频为 PCM16LE，带流标识和每通道采样位置；转写带稳定片段标识、修订号、说话人、角色、起止时间及 final 标记。持久化、跨进程恢复身份和外部作用的提交点由应用管理。

## 适配器与示例

- [离线回显](config/demo.yaml)：文字、音频、图像和转写的传输验证。
- [语音管线](config/voice.yaml)：分段转写 → 提示模板 → 流式文本生成 → 语音合成。
- [图像输入](config/vision.yaml)：编码图像进入兼容模型接口。
- [独立音轨](config/dual-stream.yaml)：声道拆分、各流隔离及可配置归属。
- [会话策略](config/conversation.yaml)、[步骤流程](config/steps.yaml)和[确定性回放](examples/README.md)。

现有兼容 HTTP 转写适配器处理有界语音片段，语音合成按轮次缓冲；文本模型通过 SSE 输出。厂商原生双向语音流可通过 `AsyncProcessor` 接入，具体适配器需要独立实现和验证。WebRTC、自动说话人分离、回声消除、持久化消息投递与自动重连不包含在当前发行物中。

## 复跑与验证

```bash
# 测试、HTML 覆盖率报告、CycloneDX 依赖清单
./mvnw -B -Pcoverage,sbom clean verify

# 独立 JMH、有界并发负载、JFR 与 GC，输出留在本地
./scripts/profile.sh quick reports/local/run

# 对默认 echo 服务做真实 WebSocket 往返测量
java scripts/WebSocketLoad.java ws://127.0.0.1:8080/ws 32 5000
```

完整三框架对比还需 CPython 3.12，按[对比复跑步骤](comparison/README.md)构建独立测试程序并运行 45 轮。相关 PR／main 变更自动触发 [Linux 工作流](.github/workflows/framework-comparison.yml)，也可手动触发；成功产物保留 30 天，包含逐轮 JSON、来源清单、校验和及安全诊断图，排除原始记录与私有日志。

本地 `profile.sh` 生成 `report.md`、`profiles.md`、CPU／分配分布图、完整栈图、GC 时间图及原始诊断。保留选定事件后可用 `python3 scripts/summarize_profiles.py reports/local/run` 重生成图；`full` 增加 JMH 预热与测量时间。发布前仅打包经过隐私检查的白名单文件。[测量方法](docs/performance.md) · [持续资源验证复跑](validation/README.md)。

## 继续阅读

- [架构、所有权与生命周期](docs/architecture.md)
- [配置、鉴权与部署](docs/configuration.md)
- [帧协议](docs/protocol.md)与[插件开发](docs/plugins.md)
- [通用场景覆盖矩阵](docs/scenario-coverage.md)
- [不可变媒体引用与导入导出 API](docs/media-references.md)
- [Studio 运行大盘与独立流程调试](docs/studio.md)
- [迭代计划与验收标准](docs/iteration-plan.md)
- [跨项目接入契约提案](docs/integration-contracts.md)
- [时间区间、分段与注释契约提案](docs/temporal-annotations.md)
- [会话扩展](ominicat-conversation/README.md)与[适配器能力](ominicat-providers/README.md)

采用 [Apache License 2.0](LICENSE)。OminiCat 是独立 Java 实现，采用自身的 API 和线协议。
