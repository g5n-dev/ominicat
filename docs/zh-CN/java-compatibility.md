<!-- ominicat-language-nav -->
**简体中文** · [English](../java-compatibility.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# Java 兼容性

所有模块构建和运行的最低版本均为 Java 17。Maven 编译器生成 Java 17 字节码（`--release 17`），不要求预览特性或 Java 21 运行时。同一发行物可运行于 Java 17、21 和 25。

| 运行时 | 会话与分支工作线程 | 构建目标 |
| --- | --- | --- |
| Java 17 | 命名守护平台线程 | Java 17 |
| Java 21 | 虚拟线程 | Java 17 |
| Java 25 | 虚拟线程 | Java 17 |

`WorkerThreads` 在初始化期间一次性发现 Java 21+ 的公开虚拟线程工厂。此后通过普通 `ThreadFactory` 创建线程；帧分发不使用反射。Java 17 直接使用平台线程工厂。截止时间调度器始终使用平台线程。[虚拟线程 API 在 Java 21 正式定版](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)。

每个会话仍有一个工作线程与有界入口队列。各 JDK 上的分支执行器都有显式并行度与队列限制。取消 generation、顺序处理、逆序清理以及保留至工作线程终止的接纳名额，都适用于两个后端。

Java 17 的会话工作线程会占用原生平台线程及其栈。应按所选 JDK 和负载确定会话上限；虚拟线程测量不能证明 Java 17 容量。自定义处理器必须在每个运行时上响应中断。

## 构建与运行

通过 `JAVA_HOME` 与 `PATH` 选择 JDK 17 安装，再运行：

```sh
./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

默认容器在 Java 17 上构建与运行：

```sh
docker build -t ominicat .
docker run --rm -p 127.0.0.1:8080:8080 -e OMINICAT_TOKEN=your-runtime-token ominicat
```

使用 `--build-arg JAVA_VERSION=21` 可将相同源码构建为 Java 21 容器。这些运行时共用提供方 API、YAML 和线协议帧。

CI 在 Java 17、21 和 25 上执行完整验证套件与独立验证驱动。容器作业检查 Java 17 默认镜像。受保护的 `main` 要求全部五项检查通过，文档路径没有绕过机制。

## 证据

[Java 17 兼容性报告](reports/java17/README.md)记录已完成检查、实际 JDK 版本和产物范围。现有[基线](reports/baseline/report.md)与[深度验证](reports/deep/README.md)保留原始 Java 21 源码和发行物哈希。这些历史测量不描述变更后的工作线程实现或 Java 17 容量。请使用部署所用 JDK 重现剖析。
