<!-- ominicat-language-nav -->
[简体中文](../docs/zh-CN/ominicat-server/README.md) · **English**
<!-- /ominicat-language-nav -->

# OminiCat server

A Netty HTTP/WebSocket container around the OminiCat microkernel. Each connection gets fresh processor instances, a bounded input queue and one session worker (platform on Java 17; virtual on Java 21+). The bundled playground supports text, individual PCM16 microphone recordings, images, transcripts and PCM playback.

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

Open [localhost:8080](http://127.0.0.1:8080). The default `echo` pipeline requires no external credentials.

- [Server configuration, authentication and embedding](../docs/configuration.md)
- [Frame protocol, metadata, lifecycle and capacity limits](../docs/protocol.md)
- [Plugin development](../docs/plugins.md)

Integration tests use actual loopback sockets and the JDK WebSocket client to check framing, per-connection state isolation, authorization, input/output limits, cancellation, preflight validation, failures and graceful completion.

Resource protection covers raw TCP connections as well as WebSocket sessions. Optional `maxConnections` defaults to `max(32, 2 * maxSessions)`. Data and Pong frames share the same finite pending-output budget. Closing a pressured connection cancels its pipeline immediately and force-closes an unflushable socket after two seconds. An admission slot remains occupied until both the socket and its pipeline cleanup have completed: a noncooperative custom processor cannot create unlimited orphan workers through reconnect churn. Java cannot forcibly terminate such a processor; `resources()` and the admitted-session/live-worker gauges expose the retained work.
