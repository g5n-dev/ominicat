<!-- ominicat-language-nav -->
**简体中文** · [English](../media-references.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 不可变媒体引用

`ominicat-conversation` 提供可选的 Java 17 值契约，用于导入和导出绑定来源的片段版本。它独立于会话窗口、规则评估器和传输，内核不依赖这些类型。

## 绑定 PCM 区间

```java
import io.ominicat.core.Frame;
import io.ominicat.conversation.reference.MediaReference;
import java.nio.ByteBuffer;

var source = new MediaReference.Source(
        "example", "asset-42", "v1", "left", "asset-start",
        MediaReference.Unit.PCM_SAMPLES, 16_000, "");
var audio = new Frame.Audio(ByteBuffer.allocate(640), 16_000, 2, "left", 800);
var reference = MediaReference.fromPcm16(source, "producer/run-A", "segment-1", 0, audio);

// 640 bytes / (2 bytes * 2 channels) = 160 samples per channel.
assert reference.position().start() == 800;
assert reference.position().end() == 960;
assert reference.durationMillisExact() == 10;
```

适配器校验音轨和采样率，不改动原始音频载荷，也不保留媒体字节。`startSample == -1` 的旧版音频帧会产生未知位置。适配器不会虚构累计游标或改变原帧。

来源区分命名空间、资产、版本、音轨、坐标原点与单位。生产者作用域进一步区分各自独立分配的片段 ID：两次运行都能产生修订号为 0 的 `segment-1` 而不冲突。作用域由应用提供；该契约不提供全局身份分配器或访问控制。

位置类型是显式的：

| 类型 | 含义 |
| --- | --- |
| `RANGE` | 以来源单位表示的非空 `[start, end)` |
| `POINT` | 一个已知坐标，起点与终点相同 |
| `UNKNOWN` | 无已知坐标，两个值均为 `-1` |

`requireSeekStart(resolvedSource)` 在检查调用方解析出的来源绑定后，返回已知采样或毫秒位置。若声明了 SHA-256，则必须匹配；缺少解析后的指纹不能满足要求。空指纹显式表示不声明内容完整性。元数据获取与验证、媒体打开和定位仍由适配器负责。

`durationMillisExact()` 计算区间时长，不转换时钟原点。它拒绝未知／点位置、帧索引、溢出及需要亚毫秒舍入的结果。采样率按每通道采样数计。

## 映射非均匀帧边界

```java
import io.ominicat.conversation.reference.FrameBoundaryMap;

var frames = new MediaReference.Source("example", "video-1", "v1", "video",
        "frame-boundaries", MediaReference.Unit.FRAME_INDEX, 0, "");
var millis = new MediaReference.Source("example", "video-1", "v1", "video",
        "asset-start", MediaReference.Unit.MEDIA_MILLISECONDS, 0, "");
var mapping = new FrameBoundaryMap(frames, millis, 0, new long[]{0, 40, 100, 180});
var secondFrame = mapping.mapInclusiveRange("producer/run-A", "segment-2", 0, 1, 1);
assert secondFrame.position().equals(MediaReference.Position.range(40, 100));
```

最后一项提供最后一帧的结束边界。边界缺失、来源绑定不匹配或索引越界都会失败。`firstFrame` 支持大来源中的一段有界范围。映射防御性复制并持有至多 65,537 个边界（65,536 个区间）。

该映射 profile 支持一个资产／版本／音轨，以及严格递增、非负的**整数毫秒**边界。亚毫秒或重合边界需要另一种适配器表示。不提供平均帧率换算、对齐发现、跨来源时钟映射或解码器。起点为 0/40/100 ms 时，第 1 帧从 40 ms 开始；平均 20 fps 会错误地推算成 50 ms。帧索引引用不能直接传给 `requireSeekStart`。

## 不含媒体载荷的导入与导出

```java
import io.ominicat.conversation.reference.MediaReferenceIO;
import java.io.*;

var bytes = new ByteArrayOutputStream();
MediaReferenceIO.write(reference, new DataOutputStream(bytes));
var restored = MediaReferenceIO.read(
        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
assert restored.equals(reference);
```

`MediaReferenceIO` 是显式的版本 1 二进制适配器（魔数为 `OMRF`），独立于默认 WebSocket／JSON 协议。它保留 Java `long` 的完整精度，包括大于 2^53 的值。每次读取一条记录，调用方可自行对多条记录分帧。未知版本／单位／类型、格式错误的 UTF-8、截断记录和无效值均显式失败。

命名空间限制为 128 个 UTF-16 代码单元；来源 ID／版本／音轨／时间基准，以及生产者作用域／片段 ID，各限制为 256。标识保留大小写、空格与分隔符，但拒绝空白字符串、ISO 控制字符和不配对的代理项。PCM 来源采样率为 1..384,000 Hz；其他单位要求 `sampleRate == 0`。可选指纹为空或为 64 个十六进制字符。八个编码字符串各有 1,024 字节 UTF-8 上限，分配输入缓冲区前检查；`MAX_RECORD_BYTES` 限制完整记录。

调用方负责流生命周期、记录分帧、I/O 超时与提交策略。适配器既不关闭流，也不刷新流。写入失败可能留下部分记录；不隐含事务或持久化发布保证。导入的标识是引用数据，绝不是路径、URL 或授权决策。

在一次运行中复用不可变来源绑定和边界映射。API 不计算媒体指纹，也不查找来源；应用可在导入边界提供这些元数据。内核分发不会自动执行引用校验或序列化。

[值与映射测试](../../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceTest.java)及[导入／导出测试夹具](../../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceIOTest.java)覆盖上述正向和反向边界。片段编辑、注释和依赖投影仍是独立的[计划 profile](temporal-annotations.md)。可选引用构造、映射与序列化各有自身成本；已记录的数值帧内核基准不测量这些成本。
