<!-- ominicat-language-nav -->
**简体中文** · [English](../../../comparison/protocol.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 无模型框架路径对比：协议 1

此可选基准对比原生排队执行路径，不比较功能对等性或生产容量。它不属于生产多模块构建，不为 OminiCat 增加运行时依赖。

## 主矩阵

- 引擎：当前 OminiCat、Pipecat 1.15.0、Apache Camel 4.22.2 LTS。
- Java：17 平台工作线程；Python：记录的 CPython 3.12 解释器和普通 asyncio，不使用 uvloop/多进程。
- 一个进程、一条管线/一个会话，且**只有一个尚未完成的数据帧（W=1）**。
- 应用阶段数：1、4、16。框架的源、汇和 worker 成本仍包含在内。
- 每个组合使用 5 个全新进程，重复之间按确定规则轮换引擎顺序。每个进程预热 3 秒，完全排空后再测量 5 秒。
- 计时范围内没有模型、HTTP、JSON、媒体编解码器、追踪或逐帧日志。启动和销毁不计入。

## 载荷与输出校验器

每个应用阶段接收不可变数值，其中包含 `sequence`、`value`、输入单调时间戳，以及测量索引/阶段。初始值等于序号。阶段 i（从零开始）生成新值，计算方式为 `value = rotateLeft64(value XOR (0x9e3779b97f4a7c15 + i), 7)`，并保留序号、时间戳和测量阶段。Python 将所有计算掩码限制在 64 位内。此测量不复制媒体字节。

每个终端数据输出都必须符合预期序号、顺序和变换值。预期值使用预先计算的合并 XOR 掩码和旋转量，校验器不会重跑整条阶段链。缺失、重复、错误或意外数据，以及拒绝、超时、清理失败或遗留工作，都会使进程失败。框架控制帧会正确转发，但不计入数据数量。

OminiCat 使用 PipelineSession；Camel 使用 size=1、单消费者、不丢弃且带终端汇的 SEDA；Pipecat 使用当前 PipelineWorker/WorkerRunner 和默认排队 FrameProcessor 模式。Pipecat 可选的 RTVI、轮次跟踪、指标、用量、心跳和追踪均显式关闭。测试驱动的 W=1 门控不能证明 Pipecat 内部无界队列具有相同的背压契约。

## 计时与资源

每次延迟观测都在构造/提交输入数值项之前立即开始，在终端汇**完成输出校验后**、向调用方确认前停止。它包含输入/阶段分配、原生准入、排队、处理和终端校验。墙钟吞吐量是所有已校验测量输出数除以整个被测客户端循环的实际耗时，其中包含调用方确认。它不是饱和容量，也不严格等于终端延迟的倒数。

预热前分配并预触页一个固定的 2,097,152 元素 / 16 MiB 缓冲区，最多保留 2,000,000 个精确延迟样本。超出容量明确判为失败。计时后记录精确最近秩 p50/p95/p99/max。完整输出校验器和计时/样本开销仍在测量路径内。记录整个测量阶段的进程 CPU 时间。

父运行器每隔 50 ms 采样一次操作系统进程 RSS，分别保留整个进程和测量阶段的峰值。RSS 包括解释器/JVM、导入库及公共测试缓冲区，既不是保留堆，也不是框架自身分配。采样可能错过短暂峰值。Java 使用 -Xms128m -Xmx512m；Python 没有等价的托管堆开关。跨语言差异包括运行时、调度器和对象模型成本。

分析器运行与主性能计时分开。Java JFR 的分配/CPU/GC 和 Python 分析器的观测不能放在同一条 B/op 分配轴上。

## 适配器接口 / 结果结构

适配器接受 `--stages N --warmup-seconds S --measure-seconds S --max-samples 2000000 --output FILE`。Java 适配器还接受 `--engine ominicat|camel`。输出并立即刷新 JSON 行形式的阶段事件（`event: phase`、`phase: warmup|measure|teardown`），父进程仅将其用于资源采样。将最终成功 JSON 写入 FILE，并打印一次。

必需的结果字段：

- `schemaVersion: 1`、`engine`、`version`、`mode: queued-w1`、`stages`、`sessions: 1`、`inflight: 1`；
- `warmupSeconds`（实际值）、`warmupFrames`、`measuredFrames`、`elapsedSeconds`、`throughputFramesPerSecond`；
- `latencyMicros: {p50,p95,p99,max}`、`processCpuSeconds`；
- `checks: {allOutputsValidated:true, exactOutputCount:true, noRejections:true, drained:true, cleanupComplete:true}`；
- `environment` 包含运行时/版本/后端，不含无关进程清单或环境变量值。

失败必须以非零状态退出，且不生成成功结果。不能静默丢弃失败的独立进程，也不能只保留最佳运行。报告展示全部五次重复、中位数和观测到的最小—最大范围；取值范围不是置信区间。
