<!-- ominicat-language-nav -->
**简体中文** · [English](../voice-service-deployment.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 用 YAML 部署语音服务

本例把客户的一段语音问题转换成文字回复和可播放的语音。两个容器负责应用入口与会话处理，三个独立提供的模型服务分别完成识别、回复生成和语音合成。模型服务可以由不同运营方提供，并使用不同模型和凭据。

架构为 **客户端 → 网关 → OminiCat 会话服务 → ASR、LLM 与 TTS 服务**。会话服务按顺序调用模型服务，模型服务之间不相互调用。每条 WebSocket 连接独占自己的流水线和临时会话状态。

## 各服务的职责

| 服务 | 输入与处理 | 输出与边界 |
| --- | --- | --- |
| 网关 | 为 Studio 与 `/ws` 提供同源入口，将 HTTP 和 WebSocket 连接转发给会话容器 | 透明传输转发。仓库中的网关只通过主机回环端口开放；生产环境的 TLS 和用户认证需要由应用入口提供。 |
| OminiCat 会话服务 | 接收 PCM 帧和轮次边界，在同一个 JVM 中运行配置的适配器、`transcript-gate` 与 `prompt-template` | 通过原 WebSocket 返回文本和音频帧。门控与提示词阶段是本地处理器，不是独立网络服务。 |
| ASR 服务 | `POST /v1/audio/transcriptions`，携带 multipart WAV 语句和选定模型 | 最终 JSON 转写，例如 `{"text":"..."}`；适配器将其转换为带归属信息的转写帧。 |
| LLM 服务 | `POST /v1/chat/completions`，携带生成的提示词和 `stream: true` | SSE 文本增量，最后以 `[DONE]` 结束；适配器输出文本帧，再输出轮次边界。 |
| TTS 服务 | `POST /v1/audio/speech`，携带完整回复、选定音色和 `response_format: pcm` | 24 kHz 单声道 PCM16 字节，随后转换为音频帧。 |

外部服务必须实现上述兼容的请求与响应格式。模型服务镜像、GPU 容量、自动扩容及凭据由运营方提供；部署额外的 OminiCat 容器不会安装这些模型。参见[提供方格式与限制](ominicat-providers/README.md)。

## 进程拓扑：Compose YAML

[compose.yaml](../../deploy/voice-assistant/compose.yaml) 描述容器、网络、公开端口、只读配置文件和凭据注入：

```yaml
# Process topology. Model services are supplied separately via runtime.yaml.
services:
  gateway:
    image: nginx:stable-alpine
    ports:
      - "127.0.0.1:${OMINICAT_GATEWAY_PORT:-18088}:8080"
    configs:
      - source: gateway
        target: /etc/nginx/conf.d/default.conf
    depends_on:
      - conversation
    networks:
      - voice

  conversation:
    image: ${OMINICAT_IMAGE:-ominicat:voice-example}
    build:
      context: ../..
    command: ["--config", "/app/config/voice-service.yaml"]
    expose:
      - "8080"
    configs:
      - source: pipeline
        target: /app/config/voice-service.yaml
    environment:
      OMINICAT_TOKEN: ${OMINICAT_TOKEN:?Set the gateway-facing runtime token}
      ASR_API_KEY: ${ASR_API_KEY:?Set the ASR service credential}
      LLM_API_KEY: ${LLM_API_KEY:?Set the LLM service credential}
      TTS_API_KEY: ${TTS_API_KEY:?Set the TTS service credential}
    stop_grace_period: 30s
    networks:
      - voice

configs:
  gateway:
    file: ./gateway.conf
  pipeline:
    file: ./runtime.yaml

# Bridge networking permits outbound HTTPS to the configured model services.
networks:
  voice:
    driver: bridge
```

只有网关发布主机端口。会话服务在 Compose 网络内可达，并向模型端点发起出站 HTTPS 请求。该网络允许出站流量，不是出站目标白名单。Compose 解析自身的环境变量表达式，且要求在创建部署前提供全部四个凭据变量。[Compose 配置文件](https://docs.docker.com/reference/compose-file/configs/) 会挂载到所选容器。

[gateway.conf](../../deploy/voice-assistant/gateway.conf) 代理整个同源站点，包括 Studio 静态资源与 API。它保留外部 `Host`、`Origin`、`Authorization` 和 `Sec-WebSocket-Protocol`，显式转发 WebSocket 升级头，并关闭响应缓冲。运行时的同源校验及浏览器令牌处理依赖这些行为；参见 [NGINX WebSocket 代理](https://nginx.org/en/docs/http/websocket.html)。示例使用本地 HTTP/WS，不配置证书、用户账户或租户配额。

## 处理顺序与服务绑定：运行时 YAML

[runtime.yaml](../../deploy/voice-assistant/runtime.yaml) 由 OminiCat 读取。阶段顺序定义进程内流水线，每个提供方适配器的选项标识其调用的远端服务：

```yaml
# Replace each HTTPS endpoint and model with an operator-supplied compatible service.
# These .invalid endpoints are placeholders; this file does not deploy model servers.
# RuntimeConfig does not interpolate ${ENV}; only tokenEnv/apiKeyEnv name environment variables.
host: 0.0.0.0
port: 8080
tokenEnv: OMINICAT_TOKEN
maxSessions: 32
maxConnections: 64
queueCapacity: 128
maxFrameBytes: 4194304
idleTimeoutSeconds: 120

stages:
  # ASR service: PCM + turn_end -> multipart WAV -> final Transcript.
  - type: openai-transcribe
    options:
      baseUrl: https://asr.example.invalid/v1
      model: replace-with-asr-model
      apiKeyEnv: ASR_API_KEY
      speaker: user
      maxAudioBytes: "4194304"
      maxStreams: "1"
      timeoutSeconds: "30"
      maxResponseBytes: "1048576"

  # In-process state: suppress duplicate finals, then build bounded input history.
  - type: transcript-gate
    options:
      maxSegments: "256"
      passPartials: "false"
  - type: prompt-template
    options:
      inputSpeaker: user
      role: assistant
      instruction: "Answer the customer's latest question briefly. Do not invent account or order information."
      maxTurns: "16"

  # LLM service: prompt text -> SSE text chunks + TURN_END.
  - type: openai-chat
    options:
      baseUrl: https://llm.example.invalid/v1
      model: replace-with-chat-model
      apiKeyEnv: LLM_API_KEY
      maxOutputTokens: "256"
      timeoutSeconds: "30"
      maxResponseBytes: "1048576"

  # TTS service: completed reply -> 24 kHz mono PCM16. Text passes through immediately.
  - type: openai-speech
    options:
      baseUrl: https://tts.example.invalid/v1
      model: replace-with-speech-model
      apiKeyEnv: TTS_API_KEY
      voice: alloy
      maxTextChars: "4096"
      timeoutSeconds: "30"
      # 2 MiB raw PCM leaves room for Base64/JSON inside the 4 MiB message limit.
      maxResponseBytes: "2097152"
```

TTS 的 `maxResponseBytes` 预算允许 2 MiB 原始 PCM，低于 4 MiB 的 `maxFrameBytes` 传输消息上限，为 Base64 和帧元数据留出空间。这些是示例限制，需按实际负载调整。

将三处占位 `baseUrl` 及对应的 `model` 标识替换为明确选定的服务。在 URL 中保留 API 根路径，包括提供方需要的 `/v1`；适配器会追加具体操作路径。同时选择受支持的 TTS `voice`。示例中位于 `.invalid` 下的端点刻意不提供真实服务。

挂载的运行时文件不是 Compose 模板：OminiCat 不会展开其中的 `${ENV}`。只有 `tokenEnv` 与 `apiKeyEnv` 解析环境变量名。将端点与模型值直接写入文件，或由自己的部署工具在启动前生成包含具体值的文件。非回环提供方 URL 必须使用 HTTPS；`http://asr:8000` 这样的容器服务地址不属于本地 HTTP 例外。自建 HTTPS 端点须使用运行时 JVM 信任的证书。

## 一轮语音如何处理

1. 客户端通过网关发送 PCM 音频，再发送 `turn_end`。转写适配器在配置的字节上限内缓冲该语句，封装为 WAV，并调用 ASR 服务。
2. 最终转写经过去重门控。提示词阶段保留有界的输入转写窗口并构建下一次请求；它不会自动保留下游助手回复，也不提供完整的用户／助手消息历史。
3. 聊天适配器将提示词发给 LLM 服务。文本增量立即经过语音阶段发送给客户端，同时语音阶段在上限内累积回复。
4. SSE 成功结束后，聊天适配器输出 `TURN_END`。语音阶段调用一次 TTS，并在转发该边界之前返回合成音频。最初的输入边界也可能继续向下游传递；语音缓冲为空时不会追加 TTS 请求。
5. 连接可接收下一轮。`end` 请求结束会话；`interrupt` 协作取消当前代次，并隔离过期输出。

这是按语句识别、按整轮合成的流程。LLM 会流式输出文本，但示例没有实现提供方原生的连续流式 ASR 或分块 TTS。适配器和会话生命周期处理错误与超时；没有配置自动重试或跨服务事务。[传输控制](protocol.md)与[语音交互范围](scenario-coverage.md)说明其余边界。

如需账户或订单查询，可增加应用自有的处理器，以明确的授权、截止时间和有界结果调用相应业务服务，再将结果提供给提示词。在 YAML 中选择该类型之前，先实现并注册插件。持久化存储、审计要求及外部副作用的重试策略由该集成负责；本例不会安装数据库或通用业务服务 RPC 适配器。

## 启动并检查示例

安装带 Compose 的 Docker，在仓库根目录操作。先根据选定的服务修改运行时配置，再通过 shell 或部署密钥机制填写并导出环境变量 `OMINICAT_TOKEN`、`ASR_API_KEY`、`LLM_API_KEY` 与 `TTS_API_KEY`。第一个是运行时共享 bearer token，其余变量分别属于对应的模型服务。密钥值不要写入 YAML 或源码仓库。

```sh
docker compose -f deploy/voice-assistant/compose.yaml config --quiet
docker compose -f deploy/voice-assistant/compose.yaml up --build --detach
voice_port="${OMINICAT_GATEWAY_PORT:-18088}"
curl --fail --retry 30 --retry-connrefused --retry-delay 1 \
  "http://127.0.0.1:${voice_port}/health"
curl --fail -H "Authorization: Bearer ${OMINICAT_TOKEN}" \
  "http://127.0.0.1:${voice_port}/metrics"
```

静默验证命令避免打印解析后的凭据值。默认网关地址是 **http://127.0.0.1:18088**；需要其他可用主机端口时，在启动前设置 `OMINICAT_GATEWAY_PORT`。启动与健康检查只验证本地监听器和配置，不验证远端模型的可达性与能力。

在该地址打开 Studio，输入运行时令牌，并在流水线调试器中连接。选择音频输入，开始录音，再停止并发送。Studio 会自动在录音之后发送轮次边界。配置了兼容服务后，客户端会收到回复文本，随后收到合成音频。最后一轮完成后，使用“结束并排空”控件。使用以下命令移除本例的容器与网络：

```sh
docker compose -f deploy/voice-assistant/compose.yaml down
```

配置在进程启动时读取。修改挂载配置或插件内容后，执行 `docker compose -f deploy/voice-assistant/compose.yaml up --build --force-recreate --detach`。同时重新创建两项服务也会刷新 NGINX 在启动时解析的会话容器地址；这不提供热加载，也不保证活跃会话不中断。

## 生产职责与验证范围

提供的网关是本地开发入口。对外提供服务时，在前方增加 HTTPS/WSS 应用入口，实现用户身份与租户配额，保持浏览器同源，并限制直接访问运行时网络。共享运行时令牌不能区分各个用户。按环境的发布流程固定并维护部署镜像。

根据实际音频和提供方负载确定单实例容量。`maxSessions` 与 `maxConnections` 限定实例准入量；`queueCapacity`、`maxFrameBytes`、音频／文本缓冲及响应预算约束会话的不同部分。这些计数与字节上限不是完整的 JVM／原生内存预算。客户端心跳应与 `idleTimeoutSeconds` 配合，代理超时也应匹配所需的请求及会话时长。逐实例采集需认证的指标，并提供应用专属的就绪检查。

增加实例可以分配新连接。重连会创建新会话，内置 Studio 视图只反映单个实例。持久化与恢复需要应用自行实现快照存储、身份和还原逻辑。替换实例前，停止向其路由新连接，并通过应用完成活跃对话。`stop_grace_period: 30s` 是容器终止宽限时间示例，不是自动排空或迁移会话的机制。

[配置测试夹具](../../ominicat-server/src/test/java/io/ominicat/server/VoiceServiceDeploymentTest.java) 加载实际运行时 YAML，并将三个模型绑定重定向到使用合成数据的独立回环 HTTP 夹具。它检查服务路由、模型选择，以及从音频输入到回复文本和 PCM 的真实 WebSocket 路径。夹具验证的是配置连接与本地协议行为；真实提供方试验仍需运营方明确提供端点、模型和凭据配置，也不能据此推断模型质量或部署容量。

CI 还会启动 Compose 示例并运行[网关探测](../../scripts/check_voice_gateway.py)，验证健康接口、指标鉴权、拒绝不相关浏览器来源及经过认证的 WebSocket 升级，不提交模型工作。启动示例后，可在环境中保留相同运行时令牌，使用 `python3 scripts/check_voice_gateway.py --port "${OMINICAT_GATEWAY_PORT:-18088}"` 复跑探测。
