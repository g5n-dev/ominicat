<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-server/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# OminiCat 服务端

围绕 OminiCat 微内核构建的 Netty HTTP/WebSocket 容器。每条连接获得全新的处理器实例、有界输入队列和一个会话工作线程（Java 17 使用平台线程，Java 21+ 使用虚拟线程）。内置交互页支持文本、单次 PCM16 麦克风录音、图片、转写和 PCM 播放。

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

打开 [localhost:8080](http://127.0.0.1:8080)。默认 `echo` 管线无需外部凭据。

- [服务端配置、认证与嵌入](../configuration.md)
- [帧协议、元数据、生命周期与容量限制](../protocol.md)
- [插件开发](../plugins.md)

集成测试使用真实回环 socket 和 JDK WebSocket 客户端，检查帧传输、每连接状态隔离、授权、输入/输出限制、取消、启动预检、失败和优雅完成。

资源保护同时覆盖原始 TCP 连接和 WebSocket 会话。可选 `maxConnections` 默认为 `max(32, 2 * maxSessions)`。数据帧和 Pong 帧共享同一有限待输出预算。关闭受压连接时会立即取消其管线，无法完成刷出的 socket 会在两秒后被强制关闭。准入槽位会一直占用，直到 socket 关闭和管线清理均完成：不配合取消的自定义处理器无法通过不断重连累积无限孤儿工作线程。Java 无法强制终止此类处理器；`resources()` 和准入会话/存活工作线程指标会显示这些仍被保留的工作。
