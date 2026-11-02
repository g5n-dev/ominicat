<!-- ominicat-language-nav -->
**简体中文** · [English](../scenario-coverage.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 场景覆盖

OminiCat 已提供可配置的**录制一句话 → 转写 → 生成文本 → 合成 → 播放回复**链路，也提供模型文本流式输出、有界会话原语与手动中断。当前**尚未提供**持续收听、增量 ASR／分块 TTS、自动插话打断的一体化语音助手。

判断覆盖情况时，需要同时看两个层面：使用者能完成什么，以及哪些传输、处理器与生命周期机制使交互成立。帧类型或扩展接口不能证明完整交互已经实现。下文**已实现**表示随项目提供代码及所列本地夹具；**可组合**表示仍需额外接入策略或适配器。本地提供方夹具不代表真实服务或声学验证。

## 实际交互覆盖矩阵

| 交互需求 | 当前实际能力 | 配置／可执行证据 | 缺失工作或外部边界 |
| --- | --- | --- | --- |
| 说一句话并听到生成的回复 | **已实现，按轮次处理。** 缓冲 PCM → 最终转写 → 修订门控 → 保留上下文的提示 → SSE 模型文本 → 完整合成 PCM。 | [语音配置](../../config/voice.yaml)；[真实 WebSocket 与本地 HTTP 管线夹具](../../ominicat-server/src/test/java/io/ominicat/server/ConfiguredVoicePipelineTest.java)。 | 配置可用端点、模型与凭据环境变量名称。该夹具验证组装链路和载荷，不验证真实识别效果、自然度或响应延迟。 |
| 输入问题，边生成边阅读回答 | **已实现。** `openai-chat` 输出 SSE 文本块，最后发出 `TURN_END`。每个文本／最终转写输入都会创建独立请求。 | [聊天夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/ChatFactoryTest.java)；[保持打开的流夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/SseOpenStreamTest.java)。 | 应发送完整文本请求；连续输入文本块不是对同一提示的增量更新。需要明确提供保留的对话上下文。 |
| 把录完的一段音频转换为最终文本 | **已实现。** `openai-transcribe` 按流缓冲有界 PCM，仅在边界处提交 multipart WAV；输出最终转写，不输出实时中间结果。 | [转写夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionFactoryTest.java)；[时间线 oracle](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionTimelineOracleTest.java)。 | 采集／转换可用 PCM，选择边界，并在需要时提供来源偏移。没有内置录音解码器或词级对齐。 |
| 还在说话时就看到不断修订的识别文本 | **缺少适配器。** `Transcript` 能承载中间／最终修订；`transcript-gate` 可转发接纳的中间结果。`AsyncProcessor` 能将回调结果送入有界续接队列。 | [门控夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptGateTest.java)；[能力与脚本化中间结果夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionDescriptionTest.java)。 | 内置 HTTP ASR 明确拒绝边界前中间结果要求。仍需提供方原生增量适配器及其端点检测／取消行为；脚本化夹具不是随项目提供的实时 ASR 连接。 |
| 把模型文本读出来 | **已实现，但有缓冲。** `openai-speech` 立即转发文本，累积至 `TURN_END` 或 `END`，读取完整语音响应后再输出一个 PCM `Audio`。 | [语音合成夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/SpeechFactoryTest.java)；[语音配置](../../config/voice.yaml)。 | 没有随附按句子／标点分段的处理器或提供方 PCM 分块流式适配器。可配置 `model` 与 `voice`；没有公开语速、风格或 SSML 选项。 |
| 连续进行多轮对话 | **可组合。** `prompt-template` 有界保留进入该阶段的最终转写，并渲染历史。聊天适配器本身仍无状态。 | [提示夹具](../../ominicat-conversation/src/test/java/io/ominicat/conversation/PromptTemplateTest.java)；[窗口夹具](../../ominicat-conversation/src/test/java/io/ominicat/conversation/ConversationWindowTest.java)；[语音配置](../../config/voice.yaml)。 | 下游助手回复不会自动回流。因此示例不包含完整用户／助手消息历史。应用负责回复提交、被打断轮次的策略、持久化和恢复。 |
| 点击中断，停止本地声音后提交下一轮 | **已实现，采用合作式取消。** Studio 停止本地播放／采集；WebSocket `interrupt` 隔离当前会话代次，并中断处理器中阻塞的 I/O。 | [响应体保持打开时的断开／下一代次夹具](../../ominicat-server/src/test/java/io/ominicat/server/HttpBodyCancellationTest.java)；[过期输出夹具](../../ominicat-server/src/test/java/io/ominicat/server/OminiCatServerTest.java)；[浏览器控制夹具](../../scripts/test_console.cjs)。 | 无法召回已经播放／发送的音频或远端副作用。浏览器必须停止自身已排队的声音。忽略中断的插件无法被强制终止。 |
| 使用者一开口，自动打断正在播放的回复 | **尚未提供完整交互。** 能量事件与代次取消分别可用。 | [RMS 事件夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/OfflineProvidersTest.java)；[Studio 采集／播放实现](../../ominicat-server/src/main/resources/web/console.js)。 | 必须一起接入并测试持续双向采集、语音／回声区分、打断决策与本地播放取消。向浏览器请求回声消除，不代表保证声音立即停止或保证声学质量。 |
| 免手动结束录音，让静音决定一句话结束 | **可组合。** `rms-vad` 产生基于能量的语音状态变化；ASR 可在停止事件处刷新。Studio 麦克风采用录制片段、停止后发送的方式。 | [离线 Studio 配置](../../config/studio.yaml)；[RMS 夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/OfflineProvidersTest.java)；[转写边界夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionFactoryTest.java)。 | 没有随附开箱即用的持续麦克风语音配置。需要实时 PCM 驱动与端点策略。RMS 检测不是神经语音分类、降噪或自动播放打断。 |
| 发送图片并获得模型回答 | **已实现单张图片交互。** 图片转换为兼容聊天接口的数据 URL，模型回答以文本流返回。 | [视觉配置](../../config/vision.yaml)；[图片请求夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/ChatFactoryTest.java)；[协议往返](../../ominicat-server/src/test/java/io/ominicat/server/FrameCodecTest.java)。 | 所选模型必须支持图片。没有提供视频解码器、音视频同步会话或持久共享多模态历史。 |
| 处理独立采集的说话人或声道 | **已实现声道／音轨处理。** 声道拆分保留 PCM 采样位置；有界的每流 ASR 缓冲使用配置的说话人／角色映射。 | [双流配置](../../config/dual-stream.yaml)；[声道夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/AudioChannelSplitTest.java)；[音轨夹具](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionFactoryTest.java)。 | 声道拆分不等于说话人识别分离，也不等于从混合单声道语音中分离人声。驱动负责采集时钟映射、重采样与跨设备对齐。 |
| 提供方卡住或客户端过慢时恢复，避免无界保留工作 | **已实现资源／失败边界。** 有限 HTTP 截止时间、有界入口／会话／输出资源、取消与明确过载行为。 | [提供方故障矩阵](../../ominicat-providers/src/test/java/io/ominicat/providers/ProviderFaultMatrixTest.java)；[提供方卡住后的恢复](../../ominicat-server/src/test/java/io/ominicat/server/StalledProviderRecoveryTest.java)；[原生过载／清理夹具](../../ominicat-server/src/test/java/io/ominicat/server/ServerAdversarialTest.java)。 | 这些上限不提供自动重试、持久交付、重连续接、每租户配额或远端回滚。目标服务限制仍需部署验证。 |

## 运行随项目提供的语音链路

[离线 Studio 示例](../../config/studio.yaml)无需模型服务，能够验证 PCM 能量事件、转写修订与回显。在 Studio 中手动输入转写是提供文本，不是测试 ASR。[语音示例](../../config/voice.yaml)配置真实的五阶段提供方链路。调用外部服务前，应配置端点、模型和凭据环境变量引用；账号／模型可用性与工厂预检是两回事。

```sh
./mvnw -Pcoverage,sbom verify
java -jar ominicat-server/target/ominicat-server.jar --config config/voice.yaml
```

打开 Studio，进入调试视图，主动连接，录制一句话，然后停止并发送。浏览器把完整录音转换为单声道 16 kHz PCM，并发送 `turn_end`；录音过程中不会上传麦克风块。采集上限为 15 秒。回复文本可逐步到达，但合成音频要等完整文本轮次结束。参见[配置](configuration.md)、[帧协议](protocol.md)和[Studio 操作](studio.md)。

普通验证命令运行本地夹具，不需要提供方凭据。其中 `ConfiguredVoicePipelineTest` 使用真实套接字与三个本地 HTTP 端点，验证 WAV 输入、提示内容、SSE 文本、PCM 输出、重复最终结果门控与清理。它不调用付费提供方，也不能认证真实麦克风和扬声器的听感。

## 流式与中断在这里的具体含义

### 转写与轮次边界

`TURN_END`、`SPEECH_STOPPED` 或 `END` 刷新待处理的 ASR 音轨；刷新前不会产生中间转写。每个非空流输出带会话内稳定 ID 的最终片段，修订为零；没有置信度时用 `-1`。边界及其他未被消费的帧随后传向下游。音轨格式、采样游标与归属，不等于说话人识别。

`Transcript` 和 `transcript-gate` 支持合适适配器提供的可修订中间结果；能承载这种数据不等于已实现适配器。随附声明将 `TranscriptionFactory.UTTERANCE_FINALS` 标为支持，将 `TranscriptionFactory.PRE_BOUNDARY_PARTIALS` 标为不支持。[显式能力检查](plugin-capabilities.md)可在创建前拒绝此类失配；默认 YAML／Studio 启动不会自动启用要求检查。

聊天在有效 SSE 完成标记后发出自己的 `TURN_END`。上游边界也可能继续传递，因此可能出现多个边界；空的语音缓冲不会重复合成同一回复。如果应用需要唯一的语义轮次完成事件，应明确该边界，而不是统计原始控制帧。

### 合成与播放

语音输出为有符号 PCM16 小端、单声道 24 kHz。当前 `Audio` 使用默认 `streamId` 和未知的 `startSample`，并不是带时间位置的合成分块序列。流式 TTS 接入仍需明确文本分段、输出块顺序／位置、缓冲与取消规则。提供方响应分几次 HTTP 读取，不代表应用能够收到分块音频。

Studio 通过 Web Audio 调度收到的 PCM，限制为排队播放 15 秒和 32 个活动音源；超过任一上限都会停止本地播放。这些演示上限不会取消远端效果，也不能认证可听见的自动插话打断。较长合成响应还可能超过 WebSocket 消息／base64 预算。需要对齐提供方、消息和播放上限，或提供有界分块输出适配器；HTTP 请求成功本身不保证音频能交付并播放。

### 会话状态与取消

`prompt-template` 只记住进入该阶段的转写；线性管线不会把助手文本回送给它。中断会清除其历史；`transcript-gate` 则在有界保留窗口内保留已提交最终结果的身份。接入方必须决定中断后哪些用户和助手事实仍算已提交。快照 API 不等于自动重连恢复或持久会话存储。

会话代次隔离会拒绝过期下游输出；不能撤回已经交给传输的输出，也不能撤销提供方请求已完成的作用。带作用域的 `Cancel` 帧是应用信号，不是自动的请求／轮次所有权执行。适当场景应使用传输已实现的 `interrupt` 行为，同时取消客户端播放并明确外部幂等性。

`END` 是应用标记，不是独立延迟回调的屏障。对于嵌入式异步处理，`finish()` 后的 `completion()` 才是排空屏障。提供方必须使用有限截止时间，处理拒绝／取消并释放所拥有资源。合作式取消无法保证任意 Java 代码终止。

## 支撑抽象与扩展入口

这些机制服务于具体交互，不要求所有接入采用同一个任务状态机或同一条全局媒体时间线。

| 机制 | 随项目提供的证据 | 剩余边界 |
| --- | --- | --- |
| 配置式或嵌入式管线 | [YAML／容器配置](configuration.md)、ServiceLoader 工厂、每会话处理器所有权；类型化直接与队列 API。 | 自定义媒体／提供方适配器必须声明并验证自身格式、截止时间、状态与资源上限。 |
| 有界转写评估与步骤 | [评估配置](../../config/conversation.yaml)、[步骤配置](../../config/steps.yaml)、[步骤夹具](../../ominicat-conversation/src/test/java/io/ominicat/conversation/flow/StepFlowProcessorTest.java)、[评估夹具](../../ominicat-conversation/src/test/java/io/ominicat/conversation/ConversationEvaluatorTest.java)。 | 字面量／标点规则不是语义模型验证。步骤定义、策略含义、存储和访问控制由应用负责。 |
| 并行工作与较慢目的端 | [并行夹具](../../ominicat-core/src/test/java/io/ominicat/core/ParallelProcessorTest.java)、[outlet 夹具](../../ominicat-core/src/test/java/io/ominicat/core/AsyncOutletTest.java)：有界、有序的同步分支及独立有界交付。 | 当前分支／fallback 组合器拒绝异步子处理器。异步分支需要各自拥有的会话；持久确认、重试和幂等性仍属外部职责。 |
| 回放与状态恢复 | [JSONL 回放示例](examples/README.md)、经过验证的会话／步骤快照与确定性 ID／时钟。 | 示例回放转写而非长音频；解码、语音片段划分、文件／采样游标和作业调度需要驱动实现。 |
| 可选来源／注释契约 | [已实现的 T1a 引用](media-references.md)；[时间语义提案](temporal-annotations.md)与[接入提案](integration-contracts.md)。 | T1b 编辑、T2/T3 注释／生命周期及剩余 I1/I2/I3 契约仍在计划中。它们不是普通原始帧语音管线的先决条件。 |

只有在操作需要比较媒体区间时，这些区间才需要共同时间基准。`startSample` 仅对来源流有效；运行时不推断设备偏移或漂移。当前评估摘录保留整轮区间，不是词级音频对齐。可选来源引用承载接入方提供的已验证身份和映射，不提供定位器、解码器或更正服务。

## 验证与剩余工作

具体本地证据覆盖 ASR／聊天／语音合成组装链路、中间／最终数据规则、多音轨缓冲、SSE 边界、完整响应 PCM、阻塞响应体取消、代次竞态、浏览器媒体清理及有界过载。浏览器控制夹具使用合成 DOM／媒体环境；真实设备与浏览器的声学行为仍需目标环境测试。[合成性能报告](performance.md)不测识别准确度、语音自然度、首段音频延迟、端到端插话打断或生产语音容量。

以下顺序优先补齐可用的对话闭环，再处理免手动输入与实时字幕。这些是接入候选，不是本次文档更新交付的功能，也不是自动排期工作。应选择目标交互所需的最小项目；没有任何一项要求通用状态机、注释套件或分布式平台。

| 优先级／交互 | 最小可选接入 | 最小验收 |
| --- | --- | --- |
| 1 — 完整模型轮次结束前就听到回复 | 有界文本分段器，加上明确 PCM 分块／流契约的语音适配器。 | 本地模型夹具保持未完成、语音夹具保持响应体打开时，在两者完成前交付首个有效音频块。检查分块顺序／位置、有界文本／音频缓冲、接纳输出后的完成边界，以及取消后不接纳过期音频。该夹具不产生可听延迟保证。 |
| 2 — 记住对话双方的内容 | 明确的助手轮次记忆适配器或驱动，具备有界消息保留与被打断回复的提交策略。 | 第二次请求准确包含获准保留的用户和助手轮次，各一次。失败／被中断的部分回复遵循声明的策略；重复与截断保持有界。纯文本记忆不应要求虚构媒体时间戳或共用媒体时间线。 |
| 3 — 不按停止按钮也能结束一句话 | 持续 PCM 驱动，加上可选的每流端点／轮次检测器；复用现有 ASR 边界。 | 脚本化语音／静音产生声明的边界，不丢失或重排 PCM，也不反复刷新同一语音片段；测试上限与中断重置。自动插话打断还需要单独验证回声／播放决策与客户端停止链路。 |
| 4 — 说话时阅读字幕 | 使用现有中间／最终修订边界的提供方原生增量 ASR 适配器。 | 在显式边界前输出非最终转写，继续接纳后续 PCM，并正确完成稳定片段。用本地协议夹具测试修订、拒绝／截止时间／取消及流隔离，再验证明确配置的目标服务。 |
