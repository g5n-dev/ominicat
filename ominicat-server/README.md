# OminiCat server

A Netty HTTP/WebSocket container around the OminiCat microkernel. Each connection gets fresh processor instances, a bounded input queue and one virtual worker. The bundled playground supports text, individual PCM16 microphone recordings, images, transcripts and PCM playback.

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

Open [localhost:8080](http://127.0.0.1:8080). The default `echo` pipeline requires no external credentials.

- [Server configuration, authentication and embedding](../docs/configuration.md)
- [Frame protocol, metadata, lifecycle and capacity limits](../docs/protocol.md)
- [Plugin development](../docs/plugins.md)

Integration tests use actual loopback sockets and the JDK WebSocket client to check framing, per-connection state isolation, authorization, input/output limits, cancellation, preflight validation, failures and graceful completion.
