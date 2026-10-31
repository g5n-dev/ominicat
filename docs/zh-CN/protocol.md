<!-- ominicat-language-nav -->
**简体中文** · [English](../protocol.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 帧协议

独立传输服务在 `GET /ws` 上接收 JSON **文本**消息，每个 JSON 对象表示一帧。在配置的完整消息大小上限内支持 WebSocket 分片；拒绝二进制 WebSocket 消息。不使用多态 Java 类反序列化。

默认管线原样回显帧。其他阶段可以消费、转换或扩展帧。除非配置的组合节点显式改变行为，否则每个会话按顺序处理。客户端提供的标识和时间戳是数据，不是授权边界或服务端签发的凭据。

## 内容帧

### 文本

```json
{"type":"text","text":"Hello, OminiCat."}
```

每个文本帧是一段内容，允许空文本。流式聊天会输出多个文本帧，最后输出 `turn_end`。

### 音频

```json
{"type":"audio","data":"AAA=","sampleRate":16000,"channels":1,"streamId":"microphone-1","startSample":0}
```

`data` 是交错排列的**有符号 PCM16 小端音频**的 base64 编码，不含 WAV 文件头。载荷必须非空且包含完整采样帧：字节长度必须能被 `2 * channels` 整除。`sampleRate` 范围为 `1..384000` Hz，`channels` 范围为 `1..32`。插件可以要求更窄的格式范围。

`streamId` 是非空白的逻辑音轨标识，默认值为 `default`。`startSample` 是该音轨内按通道计的采样偏移；`-1` 表示未知，也是默认值。它不是字节偏移。内核不会自动对音轨重新排序、重采样、混音或同步。

浏览器调试页发送独立的单声道 16 kHz 语音片段，不是持续传输的麦克风音轨。内置语音输出为单声道 24 kHz PCM。

### 图像

```json
{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}
```

请对真实的编码图像做 base64 编码；上面的缩略示例仅演示封装形式。载荷必须非空，`mimeType` 必须是 `image/*` 媒体类型。帧构造函数不校验文件签名，也不解码像素。图像校验及支持的格式由提供方和应用适配器负责。

### 转写

```json
{"type":"transcript","text":"Hello there","speaker":"speaker-1","startMillis":0,"endMillis":700,"finalResult":true,"confidence":0.98,"segmentId":"segment-7","revision":2,"role":"user"}
```

说话人身份与应用角色相互独立。`speaker` 和 `role` 均不能是空白。时间戳满足 `0 <= startMillis <= endMillis`。`confidence` 为 `0..1`，不可用时为 `-1`。`finalResult` 区分中间文本和最终文本。`segmentId` 在修订间保持稳定，`revision` 为非负数。

为兼容旧客户端，`segmentId` 默认是 `speaker:startMillis-endMillis`，`revision` 默认为零，`role` 默认为 `speaker`。处理可修订 ASR 片段的适配器应显式提供 ID。内核承载修订信息；提交、去重与冲突行为由会话插件定义。内核不执行声学说话人分离。

## 关联信封

任何受支持的帧都可以封装一层信封：

```json
{"type":"envelope","id":"event-1","sessionId":"session-1","turnId":"turn-1","correlationId":"trace-1","causationId":"","originTimeNanos":100,"ingestTimeNanos":110,"emitTimeNanos":120,"frame":{"type":"text","text":"Hello"}}
```

标识长度为 `1..256` 个字符；`causationId` 可以为空，默认也为空。`emitTimeNanos` 默认等于 `ingestTimeNanos`。时间顺序必须满足 `originTimeNanos <= ingestTimeNanos <= emitTimeNanos`。拒绝嵌套信封。JSON 解析会拒绝重复属性名、超过 16 层的嵌套以及过长的数字字面量。

服务器为配置中的处理器添加包装以保留信封元数据。每个输出帧获得新的事件 ID，将其 `causationId` 指向该阶段输入的 ID，并保留会话、轮次、关联和原始时间值。裸帧输入仍输出裸帧。中断确认不经过处理阶段，因此直接保留提交的信封。

纳秒值描述单调时钟域，不是 Unix 时间戳。本协议不负责同步客户端与服务器时钟。不要用独立时钟的时间戳相减来声称端到端延迟。跨系统追踪应使用调用方管理的关联标识，以及显式处理时钟域的追踪系统。

## 控制、取消与失败事件

```json
{"type":"turn_end"}
{"type":"start"}
{"type":"interrupt"}
{"type":"end"}
{"type":"cancel","reason":"user_requested","scope":"turn","targetId":"turn-1"}
{"type":"failure","code":"provider_timeout","retryable":true,"scope":"request","nodeId":"speech-1","message":"Provider timed out"}
```

| 输入 | 传输行为 |
| --- | --- |
| `turn_end` | 作为普通 `Control(TURN_END)` 入队；浏览器在表单提交或录音之后发送 |
| `start` | 作为显式 `Control(START)` 入队；浏览器在建立连接时发送一次 |
| `interrupt` | 丢弃排队工作，隔离当前 generation 的输出，中断阶段中的阻塞 I/O，确认 `interrupt`；会话仍可继续使用 |
| `end` | 将 `Control(END)` 入队，停止接收输入，排空已接纳工作，清理处理器，再正常关闭 |
| `cancel` | 转发应用取消事件；作用域和目标匹配由应用／驱动负责 |
| `failure` | 转发分类后的应用事件；不会自动重试或终止会话 |

传输层不自动生成 `turn_end`。请显式发送应用需要的边界。已接纳的 `end` 会经过各阶段，让阶段在清理前输出报告或刷新缓冲内容。若输入队列已满，服务器返回 `overloaded` 并保持入口开放；读取待输出内容后重试 `end`。不会为了给它腾空间而替换已接纳帧。

`end` 信号不是独立异步提供方回调的屏障：它可能先于延迟的回调结果到达下游阶段。会话的优雅结束会等待已接纳异步操作及其排队的续接。需要完整轮次聚合的阶段，必须先协调自身操作完成，再输出边界；传输层无法推断这种关系。

取消作用域包括 `request`、`turn` 和 `session`。线性 WebSocket 驱动不维护由它管理的请求／轮次索引，也不会把带作用域的 `cancel` 扩大为无条件中断整个会话。要使用传输层已实现的中断行为，请发送 `interrupt`。身份字段不允许取消另一条连接的工作。

中断是合作式的。阶段必须响应 Java 线程中断，并在中断回调中清理轮次局部缓冲。过期 generation 的输出会被隔离，但此前已传输的数据及外部副作用无法收回。客户端须自行停止已安排的音频播放。随附浏览器的 Interrupt 按钮会在本地停止播放和待完成录音；它没有实现自动打断。

控制输出使用小写名称 `start`、`end`、`interrupt`、`turn_end`、`speech_started` 和 `speech_stopped`。独立服务的解码器仅接收列出的客户端控制项；语音活动信号可以由插件产生，或通过 Java 提交。

`Failure` 字段是应用提供的分类。`code` 与 `scope` 长度为 `1..128` 个字符，`nodeId` 为 `1..256`，`message` 为 `1..2048`。`Cancel.reason` 与 `targetId` 长度为 `1..256`。输出适配器必须自行清理失败消息中的敏感信息。收到的失败帧不能视为可信的服务端事故报告。

## 插件输出

自定义 Java `Frame` 实现使用仅用于输出的封装：

```json
{"type":"EvaluationFrame","data":{"policyVersion":"v1","score":0.8}}
```

该示例展示结构，实际字段由插件决定。类型名是实现类的简单类名，不是稳定的全局 schema 命名空间。需要版本化外部契约的应用应提供专用传输编解码器。不接受自定义入站帧类名。

## 限制与错误处理

`maxFrameBytes` 限制完整 UTF-8 JSON 消息，包括 base64。会话输入队列已满时返回错误，不会静默丢弃已接纳帧：

```json
{"type":"error","code":"overloaded","message":"Session input queue is full or closed"}
```

无效 JSON、未知类型和无效模态字段会返回 `invalid_frame`，并附带已清理敏感信息的消息。每个成功解码的合法入站消息都会增加输入帧计数，包括控制帧以及因队列压力被拒绝的消息。拒绝另有独立计数器。

每条输出 JSON 消息也受 `maxFrameBytes` 限制。除 Netty 可写性检查外，待发送数据与 Pong 载荷共享最多为配置大小四倍的预算。出现压力时立即取消处理，并在两秒截止时间后强制关闭停滞连接。该载荷预算不包含帧头，也不是 JVM 总分配上限。慢消费者必须及时读取帧。

| 结果 | 代码／状态 |
| --- | --- |
| 管线完成或客户端关闭 | WebSocket `1000` |
| 不支持的二进制输入 | WebSocket `1003` |
| 超大／无效 WebSocket 输入或空闲超时 | WebSocket `1009` |
| 处理器初始化／执行或输出失败 | WebSocket `1011` |
| 输出超过消息／待发送字节预算，或通道不可写 | WebSocket `1013` |
| 达到活跃会话上限 | HTTP `503`，发生在升级前 |
| 已配置的 bearer token 缺失／无效 | HTTP `401` |
| 不匹配的浏览器来源 | HTTP `403` |

鉴权见[配置](configuration.md)。`/health` 返回 `{"status":"up"}`。`/metrics` 输出原始连接数、活跃连接、保留的接纳名额、活跃管线工作线程和待发送输出载荷字节的聚合计数器与度量，不含会话内容或逐会话标签。只读 [`/api/runtime`](studio.md) 为 Studio 大盘／调试器提供白名单配置蓝图与 JVM／聚合度量，不引入新帧类型。可选的 `/ws?observe=1` 连接通过 `/api/activity` 暴露有界阶段事件，普通连接不受观测。这是操作日志，不是任务提交或持久化交付协议。只有连接关闭且工作线程完成清理后，才释放接纳名额。本传输不保证持久化交付、重传、会话恢复或恰好一次语义。
