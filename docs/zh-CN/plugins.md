<!-- ominicat-language-nav -->
**简体中文** · [English](../plugins.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 添加插件

插件依赖 `io.ominicat:ominicat-core:0.1.0-SNAPSHOT`。请先运行 `./mvnw install` 构建并安装本仓库；产物尚未发布至 Maven Central。

```java
package example;

import io.ominicat.core.*;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.Map;

public final class PrefixFactory implements ProcessorFactory {
    public String type() { return "prefix"; }
    public Processor create(Map<String, String> options) {
        String prefix = options.getOrDefault("value", "[event] ");
        return (frame, next) -> {
            if (frame instanceof Frame.Text text) next.accept(new Frame.Text(prefix + text.text()));
            else next.accept(frame);
        };
    }
}
```

在插件 JAR 的 `META-INF/services/io.ominicat.core.spi.ProcessorFactory` 中加入以下一行：

```text
example.PrefixFactory
```

将插件及其依赖放到应用 classpath。扩展独立 shaded 发行物时可使用：

```bash
java -cp 'ominicat-server/target/ominicat-server.jar:plugins/*' \
  io.ominicat.server.OminiCat --config config/custom.yaml
```

```yaml
stages:
  - type: prefix
    options:
      value: "[event] "
```

注册表拒绝重复插件名。工厂必须为每个会话创建新的处理器状态，拒绝未知／无效选项，并在 `close()` 中释放资源。服务器在绑定前执行创建／关闭预检。避免在构造时发起网络请求；应在处理或启动回调中执行由插件管理的 I/O。

## 可选能力声明

I1a 新增可选接口 `DescribedProcessorFactory`；现有 `ProcessorFactory` 实现和 ServiceLoader 注册保持有效。实现 `describe(options)`，为所给配置返回不可变的 `ProcessorDescription`。`PluginRegistry.describe(type, options)` 不调用 `create`；`requireDeclaredCapabilities(type, options, required)` 会拒绝已知不支持及未知的要求。这些是显式的绑定期操作，不是逐帧检查。

描述不得创建处理器、发起网络请求、解析凭据或披露选项值。注册表不按类型缓存描述。第三方代码仍是受信任的 Java 代码，不受沙箱隔离。能力声明不是已验证的行为结果，不是完整选项校验，也不证明远端模型可用。

[能力指南](plugin-capabilities.md)包含可运行检查，以及整段终稿与边界前部分转写的精确定义。普通创建与服务端预检不变；不会新增 YAML、WebSocket 或 Studio 字段来自动启用这项检查。

## 流式回调

当提供方在自己的线程上返回结果时，实现 `AsyncProcessor`。`processAsync` 启动或输入操作后应及时返回。请保留它的 `AsyncEmitter`，不要保留同步 `FrameSink`：

```java
if (!emitter.tryEmit(transcript)) {
    // Explicit backpressure: pause the source or terminate this operation.
}
// When no further callbacks can arrive for this invocation:
emitter.complete();
```

在初始调用期间用 `emitter.onCancel(...)` 注册提供方取消句柄，通过 `fail(...)` 传播提供方错误，并设置提供方截止时间。结束 emitter 是幂等操作。中断／完成后的迟到输出会被拒绝。内核将已接纳输出安排到会话工作线程上的下一阶段。

适配器能力并不相同。应在插件文档声明输入／输出格式、流式方向、声道映射、时间戳原点、中间／最终结果语义、限制、截止时间与取消行为。不能把文件转写 HTTP 端点宣传为流式 ASR 连接。

## 组合

独立分支的有界顺序汇合使用 `ParallelProcessor`，输出前故障切换使用 `FallbackProcessor`，独立有界目的端使用 `AsyncOutlet`。资源清理由组合节点负责。持久化确认、去重与重试策略属于目的端插件，它应使用信封 ID 和外部存储。

参见[架构](architecture.md)、[配置](configuration.md)、[线协议](protocol.md)与[提供方参考](ominicat-providers/README.md)。

[跨项目接入提案](integration-contracts.md)区分已实现的 I1a 声明，以及计划中的丰富描述、兼容性测试、版本化结果编解码器和运行／检查点清单。其余契约不是配置字段或运行时保证；I1a 不改变现有工厂 SPI 方法。

[时间／注释提案](temporal-annotations.md)为绑定来源的区间、片段变更和独立注释定义分别的未来语义。若没有可执行的一致性证据，能力描述或注册线协议类型不得宣称这些操作已实现。
