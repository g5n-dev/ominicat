<!-- ominicat-language-nav -->
**简体中文** · [English](../plugin-capabilities.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 可选插件能力声明

I1a 增加显式启用的声明查询和能力要求检查，保留现有工厂方法、ServiceLoader 条目、YAML、帧协议及 Studio 元数据。这只是[接入计划](integration-contracts.md)的一小部分，不是通用兼容性或服务就绪结果。

## 创建处理器前查询

使用 Java 17+ 和当前仓库，将以下代码保存为仓库根目录的 `CapabilityCheck.java`：

```java
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.providers.TranscriptionFactory;
import java.util.Map;
import java.util.Set;

public class CapabilityCheck {
    public static void main(String[] args) {
        var plugins = new PluginRegistry();
        var options = Map.<String, String>of();
        var description = plugins.describe("openai-transcribe", options).orElseThrow();
        System.out.println(description.capabilities());
        String required = args.length == 0
                ? TranscriptionFactory.UTTERANCE_FINALS
                : TranscriptionFactory.PRE_BOUNDARY_PARTIALS;
        plugins.requireDeclaredCapabilities("openai-transcribe", options, Set.of(required));
        System.out.println("Declared requirement accepted");
    }
}
```

构建内置服务端 JAR，然后分别运行可通过的整段终稿检查，以及故意被拒绝的部分结果检查：

```sh
./mvnw -pl ominicat-server -am package -DskipTests
java --class-path ominicat-server/target/ominicat-server.jar CapabilityCheck.java
java --class-path ominicat-server/target/ominicat-server.jar CapabilityCheck.java partials
```

第一次调用接受所声明的能力，第二次以能力不受支持的错误退出。使用内置工厂时，两种查询都不创建处理器、不解析供应商凭据，也不发送供应商请求。声明查询可使用空选项；这不证明供应商配置已经能够运行。

## 发布有界声明

工厂可在保留原有 `ProcessorFactory` 契约的基础上实现 `DescribedProcessorFactory`，仍在 `META-INF/services/io.ominicat.core.spi.ProcessorFactory` 下注册。实现 `describe(Map<String, String>)` 时使用所给的不可变选项快照，返回所属工厂的类型。例如，已导入 `ProcessorDescription` 和 `java.util.Optional` 的工厂可提供：

```java
public ProcessorDescription describe(Map<String, String> options) {
    return new ProcessorDescription(type(), Optional.empty(),
            Map.of("example.prefix.v1", ProcessorDescription.Support.SUPPORTED));
}
```

| 字段 | 契约 |
| --- | --- |
| `type` | 已注册的工厂身份；描述类型不匹配会被拒绝。 |
| `implementationVersion` | `Optional.empty()` 表示未知。已提供的值必须非空白且最长 128 个字符；标签不能证明构件身份或行为兼容。 |
| `capabilities` | 防御性复制的不可变映射，最多 128 项。带命名空间的 ID 匹配 `[a-z][a-z0-9]*(?:[._:-][a-z0-9]+)+`，最长 128 个字符。键和支持状态不得为空。 |
| `ProcessorDescription.Support` | `SUPPORTED`、`UNSUPPORTED` 或 `UNKNOWN`。缺少条目表示未知，不等于不支持或支持。 |
| `support(capability)` | 返回已声明状态；缺少条目时返回未知，并校验能力 ID。 |

旧工厂在 `PluginRegistry.describe(type, options)` 中得到 `Optional.empty()`，未注册类型仍是错误。已有描述仍可能缺少所需能力，或显式声明为未知。`requireDeclaredCapabilities(type, options, required)` 仅接受已声明支持，并区分未知与不支持的拒绝；要求集合也最多为 128 项。请在开始工作前显式调用；普通 `create` 调用方不会获得新的要求检查。

## 转写能力定义

| 常量 | 能力 ID 与含义 | 内置声明 |
| --- | --- | --- |
| `TranscriptionFactory.UTTERANCE_FINALS` | `io.ominicat.transcription.utterance-finals.v1`：缓冲整段语音，由显式边界触发最终转写结果。 | `SUPPORTED` |
| `TranscriptionFactory.PRE_BOUNDARY_PARTIALS` | `io.ominicat.transcription.pre-boundary-partials.v1`：显式边界前输出非最终转写，同时仍接纳后续 PCM 输入。 | `UNSUPPORTED` |

内置文件转写适配器缓冲音频，在 `TURN_END`、`SPEECH_STOPPED` 或 `END` 时刷出，不在这些边界之前输出部分转写。增量能力要求在边界前实际出现非最终的 `Frame.Transcript`，且语音段仍打开时能接纳后续 `Frame.Audio`。仅接收音频分块、输出聊天 SSE 或实现 `AsyncProcessor` 接口，都不满足此能力。这些标识描述适配器行为，不代表远端模型准确性或可用性。

## 兼容性与信任边界

描述依赖选项，不按插件类型缓存。使用不同选项查询同一工厂时，必须得到相应声明。要求检查与创建应使用同一个不可变选项快照，例如通过 `Map.copyOf(options)` 获得。检查成功不会将后续调用绑定到已检查设置：在 `create` 前修改可变映射，就改变了实际创建的配置。元数据不是完整配置校验：内置转写方法检查允许的选项名，但不解析数值、不要求模型，也不解析凭据／端点值。因此无效的 `maxAudioBytes` 仍可能在后续 `create` 或服务端预检中失败。

描述方法不得创建处理器、启动自有工作线程、解析凭据、执行网络 I/O 或返回秘密选项值。注册表不调用 `create`，但不能对第三方构造器或描述实现施加沙箱。ServiceLoader 可能在查询前实例化工厂。声明应小而不可变，不在其中保留媒体、处理器实例或回调。

支持声明不授予访问权、不验证供应商、不选择模型，也不认证行为探针。实现版本可以未知；没有已验证标志。会话创建仍创建独立状态，服务端预检仍创建／关闭配置的处理器。不改变默认线协议／配置行为或逐帧调度。

## 验收与剩余范围

[冻结的旧插件 JAR](../../ominicat-core/src/test/resources/legacy-plugin/legacy-plugin.jar)、[基线／编译器绑定](../../ominicat-core/src/test/resources/legacy-plugin/metadata.json)及[源夹具](../../ominicat-core/src/test/fixtures/legacy-plugin/LegacyFactory.java)使用 Java 17，针对描述功能加入前的 d6698fa 核心构建。[维护者重建工具](../../scripts/build_legacy_plugin.py)读取该冻结版本，常规 CI 直接加载保存的 JAR，不重新编译。[二进制兼容测试](../../ominicat-core/src/test/java/io/ominicat/core/spi/LegacyBinaryPluginTest.java)检查类版本、产物哈希、独立状态和清理；[本地行为测试](../../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionDescriptionTest.java)将声明与观察到的转写交付分开。


- 在 Java 17、21 和 25 上加载改动前的 Java 17 插件 JAR，无需重编译；保留未知元数据、普通创建、独立状态和清理。
- 覆盖无效描述 ID、边界、空值、类型不匹配及修改映射的尝试。查询不得调用 `ProcessorFactory.create`，显式的未知／不支持要求必须在创建处理器前失败。
- 在未配置供应商凭据且不发送 HTTP 请求时验证内置声明。完整选项校验与元数据查询保持分开。
- 使用本地脚本化正／负行为夹具：观测到边界前部分结果后继续输入 PCM，并拒绝用仅在边界输出终稿的实现满足该能力。这些是本地一致性检查，不是真实 ASR 验证。

丰富选项 schema、帧／schema 与媒体格式、时间契约、更广 I1 测试家族、I2/I3 和 T2 仍在计划中。参见[插件开发](plugins.md)、[接入契约](integration-contracts.md)与[迭代计划](iteration-plan.md)。
