<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-conversation/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 会话处理原语

带种子的区间、修订、证据和快照检查见[对抗性验证矩阵](../ominicat-providers/DEEP_VERIFICATION.md)。

`ominicat-conversation` 为微内核增加有界转写状态、可配置策略和提示组合。它只依赖 `ominicat-core`，不需要网络服务或模型凭据。状态是临时的，限定在一个会话内。本模块不提供持久化存储、自动崩溃恢复或投递保证。

## 可选媒体引用

`io.ominicat.conversation.reference` 提供不可变的来源／生产者／片段版本值，独立于已配置的阶段。`MediaReference` 区分半开区间、点与未知位置，其 PCM 适配器按通道计算样本。`FrameBoundaryMap` 使用显式、严格递增的整数毫秒边界，并包含最后的结束边界。`MediaReferenceIO` 提供有界的版本 1 二进制导入导出，保留完整 Java `long` 精度，严格校验 UTF-8。

来源／指纹检查、限制与失败案例见 [API 指南和示例](../media-references.md)。应用负责提供标识、已验证的来源元数据和映射。这些类型不会编辑已提交转写，也不会改变默认 WebSocket 编解码或内核派发。

## 可配置阶段

JAR 通过 Java `ServiceLoader` 注册三个 `ProcessorFactory` 实现。每个工厂为每个会话创建全新状态。

```yaml
stages:
  - type: conversation-evaluator
    options:
      speaker: agent
      policyVersion: "document-v1"
      requiredPhrases: "hello,thank you"
      prohibitedPhrases: "unsupported guarantee"
      minimumQuestionRatio: "0.2"
      maxTurns: "256"
      maxTextChars: "8192"
      evaluateOnTurnEnd: "false"
```

此阶段原样透传输入帧。最终 `Transcript` 会把一个轮次提交到保留窗口，中间转写不会进入窗口。去重使用稳定的 `segmentId`。说话人、区间、文本和角色完全相同的重复最终结果不会增加轮次；置信度或修订号变化不会覆盖已提交最终结果。保留片段 ID 对应的最终事实若发生冲突会被拒绝。去重只覆盖保留轮次。说话人标识和领域角色仍是不同字段。置信度 `-1` 表示不可用。便捷转写构造器从说话人和区间派生 ID；处理修订的适配器应提供显式 ID。

此阶段在透传 `Control(END)` 之前输出 `EvaluationFrame`。设置 `evaluateOnTurnEnd: "true"` 后，还会在每次 `Control(TURN_END)` 之前输出当前窗口的评估。重复边界帧会产生重复评估。控制帧本身不会关闭内核会话，调用方负责 `finish()` 和 `close()`。

策略采用不区分大小写的字面匹配，针对指定说话人，范围限定为保留窗口。配置短语以逗号分隔；包含逗号的短语请使用 Java API。每个短语选项最多接受 64 个不同条目。可选的问句比例规则统计包含 `?` 或 `？` 的轮次，是标点启发式。规则分数介于零与一之间，汇总值为适用分数的平均数。目标说话人缺失或规则列表为空时输出 `score: null`，不会给出通过分数。证据为每条规则提供最多八段摘录、说话人标签和转写区间。

证据摘录最多保留 180 个 UTF-16 正文单元，另加最多两个省略号。输入 Unicode 完整时，裁剪仅将切断代理对的边界向内收缩，以保留完整 Unicode 码点；不扩大原始窗口，也不定义字素簇边界。归属与整个轮次的媒体区间保持不变。

`EvaluationFrame.policyVersion` 是调用方提供的策略配置标签。`retainedTurns` 与 `evictedTurns` 明确保留范围。若需要日后重现评估，须在外部保存应用版本、配置与输入转写。

```yaml
stages:
  - type: prompt-template
    options:
      inputSpeaker: user
      role: assistant
      context: "Discuss the shared document."
      instruction: "Respond concisely and ask a relevant question."
      template: |
        Role: {{role}}
        Context: {{context}}
        Instruction: {{instruction}}
        Transcript:
        {{history}}
        Latest {{speaker}} turn: {{transcript}}
      maxTurns: "32"
      maxTextChars: "8192"
      maxPromptChars: "65536"
  - type: openai-chat
    options:
      model: "your-model"
```

`prompt-template` 保留所有说话人的最终转写。仅进入该阶段的转写会写入其历史，下游模型回复不会自动回灌；因此默认语音管线不维护完整的用户／助手消息历史。每个来自 `inputSpeaker` 的新最终转写会被消费，并替换为一个 `Frame.Text` 提示；该说话人的重复最终结果不产生输出。其他帧，包括中间转写和其他说话人的转写，直接透传。占位符只替换一遍，因此转写文本中的 `{{role}}`、美元符号或反斜杠仍是字面数据。

支持变量 `role`、`context`、`instruction`、`transcript`、`speaker` 与 `history`。未知变量名会在创建阶段时失败。历史使用 `speaker: text` 行；为适应 `maxPromptChars`，必要时丢弃最旧的完整行。最新轮次或静态模板无法容纳时会明确报错。`Processor.onInterrupt()` 在串行化的会话中断边界清空提示历史与去重状态。评估器跨中断保留已提交转写窗口。

## Java 扩展点

```java
PolicyRule rule = (snapshot, targetSpeaker) -> {
    var turns = snapshot.turnsBySpeaker(targetSpeaker);
    return new RuleResult("custom-rule", RuleResult.Status.PASS, 1.0,
            "Evaluated " + turns.size() + " retained turns", List.of());
};

var stage = new ConversationEvaluatorProcessor("agent", List.of(rule), 256);
```

`PolicyRule` 接收不可变 `ConversationSnapshot`，自定义规则可使用独立规则引擎或模型适配器，无需改变内核。规则异常传播到会话错误边界。`EvaluationFrame` 实现开放的 `Frame` 接口，传输层可将其序列化，无需让内核依赖本模块。

`ConversationEvaluatorProcessor.snapshot()` 与 `.restore(snapshot)` 允许调用方在外部保留和恢复已提交窗口。`ConversationWindow` 的构造器也可接收快照。恢复会在替换现有状态之前校验限制、唯一片段 ID 和由区间推导的指标，不执行存储 I/O。相同的已提交快照配合相同规则与策略版本，会产生相同的内置评估；自定义规则自行保证确定性。

`ConversationWindow` 最多保留 `maxTurns` 个最终轮次，拒绝超过 `maxTextChars` 个 UTF-16 代码单元的最终文本。说话人标签最多 128 个字符。默认上限为 256 个轮次、每轮 8192 个文本字符。各阶段的限制相互独立，阶段实例必须归属一个会话。

`ConversationMetrics` 合并转写区间，报告保留窗口时长、发声区间并集、区间间隔及各说话人的区间并集。重叠部分不会重复计入总发声时长。这些指标使用提供的转写时间戳，不测量声学静音、不识别说话人，也不推断意图。快照与提示保留到达顺序；只有区间计算会按时间戳排序。

## 可寻址步骤流程

`step-flow` 进入步骤时输出 `StepEvent` 和配置的文本。只需发送一次 `START`，重复 START 是幂等的。每份匹配 `inputRole` 的新最终转写都会推进配置序列；中间结果和其他角色不会推进流程。最终输入会被消费，避免在下游触发第二次模型请求。

```yaml
stages:
  - type: step-flow
    options:
      steps: "welcome,details,complete"
      definitionVersion: "v1"
      inputRole: user
      step.welcome.prompt: "What would you like to discuss?"
      step.details.prompt: "Please share one more detail."
      step.complete.prompt: "Thank you."
```

用 `step.<id>.next` 显式指定后继，空值表示终止步骤。Java `StepFlowProcessor.Step`／`Transition` API 还支持有序谓词与分支。定义在使用前校验。`snapshot()` 与 `restore()` 保留步骤标识、版本、修订及有界的已提交片段 ID。恢复会拒绝其他定义版本，应用须持久化并校验匹配的定义。媒体中断不会重置已提交的流程进度。去重覆盖 `maxRememberedSegments` 个保留 ID（默认 1024）。原子存储、外部副作用重放和重连授权由调用应用负责。
