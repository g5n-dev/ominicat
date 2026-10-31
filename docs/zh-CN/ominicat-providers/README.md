<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-providers/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 处理适配器

故障矩阵、确定性种子、已复现缺陷和隔离构建命令见[对抗性验证](DEEP_VERIFICATION.md)。

本模块通过 Java `ServiceLoader` 注册可选的 `ProcessorFactory` 实现。每个工厂创建独立的会话状态。HTTP 适配器使用 JDK 连接池；内核不依赖 HTTP 客户端、JSON 库或模型厂商。

| 类型 | 输入 | 输出 |
| --- | --- | --- |
| `echo` | 任意帧 | 同一帧 |
| `audio-channel-split` | 交错 PCM | 带独立标识的单声道流 |
| `rms-vad` | 一个逻辑流的 PCM | 语音状态转换控制帧和未修改的 PCM |
| `transcript-gate` | 转写修订 | 每个保留片段的一份已提交最终结果；可选的中间修订 |
| `openai-transcribe` | PCM，随后是边界控制帧 | 带归属信息的最终转写，随后是边界帧 |
| `openai-chat` | 文本、最终转写或图像 | 流式文本块，随后是 `TURN_END` |
| `openai-speech` | 文本块，随后是边界帧 | 原始文本；在边界帧之前输出合成 PCM |

兼容 API 支持表示对应的 Chat Completions、音频转写或语音端点能够接受这些请求格式，不代表每个服务提供方都支持所有端点或参数。云端凭据和网络访问是可选的；`echo`、声道拆分、转写提交门控与能量检测都可在本地运行。

## HTTP 设置

三个 HTTP 工厂都接受以下选项。模型名称必须显式配置。

| 选项 | 默认值 | 范围或行为 |
| --- | --- | --- |
| `model` | 必填 | 非空的服务提供方模型标识 |
| `baseUrl` | `https://api.openai.com/v1` | 使用 HTTPS；本地服务可使用回环 HTTP |
| `apiKeyEnv` | `OPENAI_API_KEY` | 环境变量名；仅回环 URL 允许为空 |
| `timeoutSeconds` | `30` | 1–300；请求超时与有限的响应体截止时间 |
| `maxResponseBytes` | `8388608` | 1024–67108864；原始响应字节预算，包括 SSE |

拒绝包含凭据、查询字符串或片段的 URL，禁用重定向。非成功 HTTP 响应只暴露状态码，不包含响应体或凭据。`ProviderException.retryable()` 标识 429 和 5xx 状态；适配器不会自动重试。

流式聊天还设置了保守的字符预算：所有行的字符总数不得超过 `maxResponseBytes` 的四分之一，每一行或事件最多 65536 个字符。原始字节在解码前计数，包括被忽略的字段和回车符。无效 UTF-8 会失败，而不是修改文本。支持 LF、CRLF、CR 换行和开头的 UTF-8 BOM。这同时限制解码后的 UTF-8 内存与响应数据。截止时间也覆盖响应头到达后停滞的响应体；关闭流之前先标记超时，已完成请求会立即移除已取消的截止时间任务。会话中断会取消配合中断的 HTTP 工作，并阻止过期结果继续向下游发射。

## 独立音频流

```yaml
stages:
  - type: audio-channel-split
    options:
      streamIds: "left,right"
  - type: openai-transcribe
    options:
      model: "your-transcription-model"
      speakers: "left=person-a,right=person-b"
      roles: "left=participant,right=assistant"
      maxStreams: "8"
      maxAudioBytes: "4194304"
  - type: transcript-gate
    options:
      maxSegments: "256"
```

输入 `Audio` 是有符号 16 位小端 PCM，按声道交错排列。`audio-channel-split` 按声道顺序为每个声道输出一个单声道帧，保留采样率和每通道 `startSample`，并使用配置的唯一流名称。未配置 `streamIds` 时，名称依次为 `<input streamId>/ch-0`、`<input streamId>/ch-1` 等。配置的名称数量必须与输入声道数一致。此阶段拆分已有声道，不会从混合单声道录音中识别或分离说话人。

`openai-transcribe` 独立缓冲各个流，在 `TURN_END`、`SPEECH_STOPPED` 或 `END` 时为每个非空流发起一次 multipart WAV 请求。提交顺序与首次遇到各流的顺序一致。音频帧被消费；生成转写后再透传边界控制帧。此文件式适配器未实现云端中间转写。

| 选项 | 默认值 | 行为 |
| --- | --- | --- |
| `maxAudioBytes` | `4194304` | 所有流待处理 PCM 字节总数；2–16777216 |
| `maxStreams` | `8` | 会话中不同流标识的数量上限；1–32 |
| `speaker` | `user` | 默认说话人标识 |
| `speakers` | 空 | 以逗号分隔的 `stream=speaker` 映射 |
| `roles` | 空 | 以逗号分隔的 `stream=role` 映射；默认采用已归属的说话人 |

流、说话人和角色标签最多 128 个字符。映射项必须唯一且非空。映射配置中的逗号和等号作为分隔符。

每个流都有自己的累计采样游标。已知的首个 `startSample` 为时间线提供锚点；位置未知时使用游标。同一句话内，已知偏移必须有序且严格连续。不同话语之间允许向前间隔，拒绝向后偏移。同一个流在整个会话中保持固定采样率与声道数。时间戳从采样偏移推导，仅在输出时向下取整，因此短话语不会逐句累积舍入漂移。这些是媒体位置，不是墙上时钟时间戳。

每份输出转写都有确定性的会话内片段 ID `<streamId>:<utterance sequence>`、零修订号、独立的说话人／角色归属，以及表示置信度不可用的 `-1`。序列号在发起请求前分配，因此失败或中断可能留下序号缺口。中断会丢弃缓冲 PCM，但保留已接纳的采样游标、流格式和序列计数器。因此，已接纳后被丢弃的媒体仍会推进时间线。提交或中断时释放缓冲数组，不会将一条音轨混入另一条。

## 转写修订

将 `transcript-gate` 放在会根据转写触发外部工作的处理器之前。默认选项为 `maxSegments: "256"`、`maxTextChars: "65536"` 和 `passPartials: "false"`。

门控跟踪稳定的片段 ID 和单调修订号。忽略较旧的未提交修订，拒绝同一修订号下相互冲突的事实。文本和区间不变时，中间结果可在同一修订号变为最终结果。说话人和角色在修订间保持稳定。最终结果的说话人、角色、文本与区间不可变：完全相同的重复最终结果不产生输出，冲突的最终结果会被拒绝。修改置信度或修订元数据不会替换已经提交的最终结果。

设置 `passPartials: "true"` 后，每个已接纳的中间修订只透传一次，不会将中间结果自动变成最终转写。中断会移除未提交的中间状态，并保留已提交最终结果的标识。保留范围受 `maxSegments`（1–10000）限制，最旧的片段标识优先淘汰，淘汰后重放可能再次输出。这是有界的会话去重，不提供持久化投递保证。

## 文本与图像

`openai-chat` 将每个输入作为独立请求：一条 system 消息加一条 user 消息。它消费 `Text`、最终 `Transcript` 和 `Image` 输入；中间转写和其他帧类型直接透传。用 `prompt-template` 显式保留会话上下文，用 `transcript-gate` 抑制重复的输入最终结果。模板只保留进入该阶段的转写，不会自动保留下游模型回复；因此默认语音管线不维护完整的用户／助手消息历史。

附加选项包括 `systemPrompt`（默认 `Be concise and helpful.`）、`imagePrompt`（默认 `Describe this image.`）、`maxOutputTokens`（默认 1024，范围 1–16384）和 `maxInputChars`（默认 65536，范围 1–1000000）。请求使用 `max_completion_tokens`。图像以内联 data URL 发送，大小必须符合该适配器采用的响应字节预算。支持 SSE 注释与多行事件。缺少 `[DONE]` 标记即视为响应失败，因此截断输出不会产生表示成功的轮次边界。

`openai-speech` 将文本累积到 `TURN_END` 或 `END`，随后一次性请求 PCM，输出 24 kHz 单声道有符号 16 位小端音频。原始文本块立即透传，音频则按整轮缓冲。选项为 `voice`（默认 `alloy`）和 `maxTextChars`（默认 4096，范围 1–4096）。拒绝空 PCM 响应或奇数字节长度。空白轮次不发起请求。中断会清空等待合成的文本。

## 能量状态转换

`rms-vad` 将归一化 PCM RMS 与 `threshold` 比较（默认 `0.02`，范围大于零且不超过一）。在首个活跃帧之前发出 `SPEECH_STARTED`，在累计 `silenceMillis` 的非活跃采样后发出 `SPEECH_STOPPED`（默认 300，范围 1–60000）。采样时长同时考虑采样率和声道数。`END` 会发出尚未输出的停止转换并重置状态；中断也会重置状态。

能量检测器的每个阶段只有一个逻辑发声状态，应输入单一逻辑流，不能把独立音轨合并到该状态中。它是确定性的能量阈值，不包含神经语音分类、自动话语语义或说话人识别。

HTTP 响应消费使用公开 JDK Flow 背压，每次只请求一个数据批次，并保留一个终止信号槽位。在 Java 17+ 上，阻塞读取被中断时会取消订阅并保留线程中断标志，无需轮询，也不为每个响应增加读取线程。一个批次可包含多个字节缓冲；配置的原始响应字节预算仍是独立限制。见[取消验证](../reports/java17/http-cancellation/README.md)。
