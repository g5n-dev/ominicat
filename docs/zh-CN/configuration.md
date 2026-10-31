<!-- ominicat-language-nav -->
**简体中文** · [English](../configuration.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 配置

OminiCat 在启动时读取一次 YAML，配置变更后需重启运行时。未知的服务器属性会尽早报错；各插件负责校验自身以字符串表示的选项。在监听器绑定之前，启动预检会创建并关闭处理器，之后再为每个接纳的会话新建处理器。工厂构造不得发起外部请求或长时间运行的工作。

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

不传 `--config` 时使用相同的本机回环回显默认配置。无效命令行参数以退出码 2 结束。

## 服务器设置

```yaml
host: 127.0.0.1
port: 8080
queueCapacity: 256
maxSessions: 256
maxConnections: 512
maxFrameBytes: 1048576
idleTimeoutSeconds: 60
# tokenEnv: OMINICAT_TOKEN
stages:
  - type: echo
    options: {}
```

| 设置 | 默认值 | 含义／范围 |
| --- | --- | --- |
| `host` | `127.0.0.1` | 绑定地址；不能是空白 |
| `port` | `8080` | `0..65535`；零表示选择可用端口 |
| `queueCapacity` | `256` | 每个会话 `1..65536` 个待处理输入帧，另有正在执行的工作 |
| `maxSessions` | `256` | `1..100000` 个已接纳的 WebSocket 会话；直到连接关闭且工作线程清理完成，接纳名额才被释放 |
| `maxConnections` | `max(32, 2 * maxSessions)` | `1..1000000` 条已接纳 TCP 连接，包括未完成的 HTTP／WebSocket 握手 |
| `maxFrameBytes` | `1048576` | 每条完整 WebSocket JSON 消息 `1024..16777216` 字节 |
| `idleTimeoutSeconds` | `60` | 连续 `1..86400` 秒未收到入站活动 |
| `tokenEnv` | 未设置 | 必须提供的 bearer token 所在环境变量的名称 |
| `stages` | 一个 `echo` 阶段 | 按顺序排列的 `1..64` 个插件定义 |

这些限制以会话为单位，不构成整机内存预算。媒体帧含有载荷字节，JSON／base64 还会增加开销。容纳 256 个大帧的队列比容纳 256 个小文本帧的队列消耗更多内存。应按负载共同调整大小限制、会话接纳量与队列容量。输出有独立且有限的待发送字节预算；连接不可写时会因压力而关闭。详见[协议](protocol.md)。

Pong 与数据输出共享该有限预算。输出压力会立即取消处理；若关闭帧无法刷出，连接会在两秒后被强制关闭。忽略中断的插件会一直占据接纳名额，直到清理完成，因此重复重连不能创建无限的孤立工作线程。传输关闭无法强杀任意 Java 代码；保留的工作线程／接纳名额度量仍然可见。

`idleTimeoutSeconds` 测量入站活动。没有入站流量的长请求可能超过此超时；需要时发送 WebSocket ping 或增大该值。

## 插件选项

```yaml
stages:
  - type: openai-chat
    options:
      model: "your-chat-model"
      apiKeyEnv: OPENAI_API_KEY
      baseUrl: https://api.openai.com/v1
      timeoutSeconds: "30"
      maxOutputTokens: "1024"
      systemPrompt: "Respond concisely."
```

`type` 是已安装的 `ProcessorFactory.type()`。插件选项均使用字符串，包括数字和布尔设置。`apiKeyEnv` 是环境变量的**名称**，不是密钥值。模型名称由调用方选择；可用性和模态支持取决于所选提供方。兼容 HTTP 客户端要求 HTTPS，本地开发和测试使用的回环端点除外。不跟随 HTTP 重定向。

内置工厂包括 `echo`、`rms-vad`、`audio-channel-split`、`transcript-gate`、`openai-transcribe`、`openai-chat`、`openai-speech`、`conversation-evaluator`、`prompt-template` 和 `step-flow`。各自的选项与帧行为见[插件文档](plugins.md)和[会话基础组件](ominicat-conversation/README.md)。不同提供方阶段可以使用不同端点、模型和凭据环境变量。

基于片段的语音路径应按转写 → 聊天 → 语音合成排列。发送 PCM 块后再发送 `turn_end`，以刷新缓冲的转写音轨。聊天在 SSE 响应结束时输出 `TURN_END`；语音合成将文本缓冲到该边界。这不会让 HTTP 转写适配器变成连续流式 ASR。RMS 阶段检测能量事件，但这些事件不会自动中断播放。

普通配置错误会指出阶段与选项键，同时隐藏可能包含敏感值的原始工厂异常。致命错误会在先前已拥有的资源均获得清理机会后继续传播。请检查所指阶段的文档要求、数值范围及引用的环境变量。通过校验的工厂仍可能在实际提供方 I/O 中失败；此类失败会关闭会话，不向客户端发送提供方细节。

## 鉴权与浏览器来源

```yaml
tokenEnv: OMINICAT_TOKEN
```

在运行时环境中设置 `OMINICAT_TOKEN`。若已配置的变量缺失或为空白，启动失败。此后 `/ws`、`/metrics`、`/api/runtime` 和 `/api/activity` 需要 `Authorization: Bearer <token>`。`/`、`/console.css`、`/console.js` 和 `/health` 仍公开可访问。[Studio 指南](studio.md)介绍独立大盘／调试视图和元数据白名单。

绑定回环地址且未配置鉴权的监听器，还会在路由前校验 `Host`：允许 `localhost`、规范的 `127/8` IPv4 字面量以及带方括号的 `::1`。`Host` 不在上述允许列表中时，即使 `Origin` 与其匹配也会被拒绝；此检查不执行 DNS 查询。这项服务端检查补充了浏览器的本地网络访问限制。通过反向代理或外部主机名访问时应配置 `tokenEnv`；通配地址／公网监听仍需显式部署鉴权和配额。

原生浏览器 WebSocket 无法设置任意授权头。随附调试页提供 `ominicat` 和 `bearer.<base64url-encoded UTF-8 token>` 子协议。服务器选择 `ominicat` 并校验 bearer 值。调试页不存储 token，也不会将它放进 URL。反向代理必须保留子协议头，并避免记录含凭据的头部。

浏览器 `Origin` 必须与请求 `Host` 匹配；无关的浏览器站点无法连接未鉴权的回环运行时。非浏览器客户端可以省略 `Origin` 并使用 bearer 头。不提供跨域例外列表。请将调试页与 WebSocket 端点部署在同一来源，或提供应用专用的已鉴权传输。

## 部署与嵌入

独立传输使用 HTTP／WebSocket。向 localhost 之外暴露服务时，应置于 HTTPS／WSS 反向代理之后，保留 WebSocket 升级头和外部 `Host`，并按预期会话时长配置代理超时。运行时不提供 TLS 终止、用户账户、租户路由、持久化或集群协调。

```java
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.server.OminiCatServer;
import io.ominicat.server.RuntimeConfig;
import java.nio.file.Path;

var config = RuntimeConfig.load(Path.of("config/demo.yaml"));
var plugins = new PluginRegistry();
try (var server = new OminiCatServer(config, plugins).start()) {
    server.awaitTermination();
}
```

启动前注册进程内工厂，或将含 `ServiceLoader` 描述文件的插件 JAR 放到应用 classpath。打包服务会合并随附的服务描述文件。外部插件可通过 classpath 启动，例如类 Unix 系统上的 `java -cp 'ominicat-server.jar:plugins/*' io.ominicat.server.OminiCat --config runtime.yaml`。Windows 请使用平台对应的 classpath 分隔符。

服务器实例只能使用一次。`close()` 停止接纳、关闭连接、取消管线工作线程并释放 Netty 事件循环。处理器取消是合作式的；自定义实现必须响应中断并释放资源。需要等待所有阶段清理的调用方，可以使用内核会话的 `completion()`。
