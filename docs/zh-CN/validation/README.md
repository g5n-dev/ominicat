<!-- ominicat-language-nav -->
**简体中文** · [English](../../../validation/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 对抗性与持续验证

常规测试套件包含确定性的并发/模型测试，以及真实的本地 HTTP/TCP/WebSocket 故障夹具。这里的驱动程序补充持续混合媒体验证和必须显式启用的真实服务检查，不使用生产环境对话数据。

## 持续混合媒体探针

先构建不可变的服务端构件，再基于它编译观测器和驱动程序：

```bash
./mvnw -pl ominicat-server -am clean package -DskipTests
mkdir -p reports/local/deep/classes
javac --release 17 -cp ominicat-server/target/ominicat-server.jar \
  -d reports/local/deep/classes validation/ValidationServer.java validation/Soak.java
```

在受限内存下运行服务端。附加观测器只绑定回环地址，是生产发行包之外的验证插桩：

```bash
java -Xms256m -Xmx256m -XX:MaxDirectMemorySize=128m -XX:NativeMemoryTracking=summary \
  -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  ValidationServer validation/soak.yaml 18082
```

在另一个终端中先预热 60 秒，再运行 20 分钟的测量流量：

```bash
java -Xmx512m -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  Soak http://127.0.0.1:18081 http://127.0.0.1:18082 1200 60 64 reports/local/deep/soak
```

探针保持 64 条长连接，每条约 20 帧/s，另有 4 个连接更替工作线程（暂停 100ms）。一半更替连接会完成 WebSocket 关闭握手，另一半直接中止 TCP。帧依次轮换文本、PCM 音频、支持修订的转写、编码图片和因果信封。每个响应都与序列化输入核对；通过解析已发送表示，消除 JSON 数值宽度差异。长连接失败会记录下来，不会静默修复。采集持续运行证据前，先用短时运行预检测试驱动程序本身。

延迟直方图有界：100ms 以内使用 1µs 桶，此后使用 1ms 桶，超出 10.1s 的值记入溢出桶；报告分位数为桶上界。测量包含发送/接收与载荷校验，采用闭环和限速方式。连接更替延迟不含握手时间。意外失败单独计数，并使运行失败。这是正确性/资源探针，不是饱和容量基准。

回环观测器记录堆、直接缓冲区、Netty 分配器内存、类、平台线程、GC 时间、CPU 和打开的文件描述符。Prometheus 样本跟踪活动 socket、保留的准入槽位和存活管线工作线程。Java 17 的会话工作线程为平台线程，包含在此计数中；Java 21+ 的会话工作线程为虚拟线程，不包含在 JDK 平台线程计数中。预热后和排空后各请求一次 Full GC，以比较保留堆。Full-GC 端点只用于验证。持续指标、Full-GC 对比与排空后的类直方图相互补充；仅凭采样内存稳定，不能证明不存在任何泄漏。

`soak-criteria.json` 在运行前记录探索性检查界限。驱动程序要求每个长连接客户端持续达到至少 10 个已成功校验帧/s、意外失败为零，且活动会话/准入/工作线程/输出全部排空。最终分析器检查所有帧类型都已覆盖、直方图/类型计数守恒，并检查 Full-GC 堆增长（8MiB）、直接内存预留增长（4MiB），以及描述符/平台线程增长（各 8）。这些阈值是本地调查界限，不是生产承诺。

原始 JFR、原生内存输出和特定机器日志应保留在被忽略的 `reports/local/` 下。发布选定计数、负载、源码/构件哈希和实际失败数。本地运行 20 分钟不能证明多日稳定性或生产 SLA。单独的 Java 17 兼容性回归测量 120 秒、预热十秒；应将 `validation/java17-soak-criteria.json` 作为分析器可选的第三个参数，而不是修改历史 20 分钟检查标准。

可选类直方图诊断会请求额外 GC。JFR 记录覆盖测量窗口周围的整个进程，包括预热、排空及这些诊断。NMT 的已提交/预留内存不是 RSS；256MiB 堆上限不等于 256MiB 进程内存上限。

## 验证检查器本身

`NegativeControl.java` 在调用方选择的回环端口上提供故意错误的 `corrupt` 和 `drop` 夹具。将其与其他驱动程序一起编译，用观测器端口运行夹具，再让短时 `Soak` 指向它。损坏夹具必须无法通过载荷检查；丢弃夹具必须无法通过每客户端进度检查，即使错误在测量停止后才到达。其观测器值明确为占位值，因此这些对照不属于资源测量。[已记录的负向对照](../../../reports/deep/harness-controls.json) 保留了两次预期拒绝。

## 显式启用真实兼容服务

将 `live-example.json` 复制为被忽略的 `*.local.json` 文件，填写测试端点和已配置凭据的环境变量名。模型字段必须明确填写。探针对每种已配置能力执行一次请求，使用简短合成输入和有界输出；不会自动发现模型或静默使用其他账号。

```bash
javac --release 17 -cp ominicat-server/target/ominicat-server.jar \
  -d reports/local/deep/classes validation/LiveCompatible.java
java -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  LiveCompatible validation/test.local.json reports/local/deep/live-result.json
```

可选配置字段包括 `vision: true`、`transcriptionModel`、`pcmFixture`（PCM16LE/16kHz/mono，最长 10 秒）、`speechModel` 和 `voice`。语音夹具应说出 “The blue cat has seven stars.”。文本和视觉检查使用精确标记及纯蓝图片。语音输出必须包含非静音 PCM，转写必须恢复已知词语。结果不包含端点和凭据。真实供应商测试成功只适用于已配置的能力，以及测试时的该端点/模型。没有凭据就没有真实供应商结果。
