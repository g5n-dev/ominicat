<!-- ominicat-language-nav -->
**简体中文** · [English](../../../examples/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 嵌入式回放

构建发行物，再执行确定性的本地转写回放：

```bash
./mvnw package
java --class-path ominicat-server/target/ominicat-server.jar \
  examples/Replay.java examples/transcripts.jsonl reports/local/replay-checkpoint.json
```

使用同一检查点再次运行，可恢复已提交会话窗口，并从下一条输入行继续。输入摘要与策略版本必须一致。示例在 END 输出保留窗口的评估；时间戳与 ID 是确定性的。使用新检查点路径会开始新的回放。

这是转写驱动，不是长音频解码器或分布式作业调度器。它流式读取 JSONL 输入，每接纳一行后写入本地原子检查点，用于演示快照契约。示例输入应为可信本地数据。它不会把下游作用与检查点一起原子提交，也不会对重复 END 评估去重；持久化出口必须采用应用管理的幂等与确认机制。

音频回放时，驱动提供带稳定 `streamId`／`startSample` 的 PCM 块，组织成有界语音片段，并将文件／采样游标记录在同一外部检查点中。提供方限制仍然适用；内核不会累积整段录音。
