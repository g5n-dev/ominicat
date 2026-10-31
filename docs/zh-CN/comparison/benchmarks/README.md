<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../comparison/benchmarks/README.md) · [中文文档目录](../../index.md)
<!-- /ominicat-language-nav -->

# Java 排队适配器

实现[对比协议 1](../protocol.md) 的可选独立项目，不属于生产 Maven 多模块构建。依赖为当前 OminiCat core 和 Apache Camel **4.22.2 LTS**（[官方 Java 17 支持](https://camel.apache.org/releases/release-4.22.2/)）。

先在本地构建当前 core，再从仓库根目录串行构建此项目：

```sh
./mvnw -B -pl ominicat-core -am install -DskipTests
./mvnw -B -f comparison/benchmarks/pom.xml clean verify
java -Xms128m -Xmx512m -jar comparison/benchmarks/target/benchmark.jar \
  --engine ominicat --stages 4 --warmup-seconds 3 --measure-seconds 5 \
  --max-samples 2000000 --output reports/local/comparison/ominicat-4.json
# Use --engine camel for the same payload, oracle, and whole-loop harness.
```

测量需要实际的 Java 17 JVM。输出路径必须尚不存在。进程会输出并立即刷新预热/测量/销毁阶段的 JSON 行，仅在排空和清理完成后写出成功结果 JSON；失败以非零状态退出。父运行器负责全新进程、重复次数/顺序、RSS 采样、环境/源码/构件清单和汇总。

OminiCat 使用 `trySubmit` 提交到容量为一的会话，并通过 `ArrayBlockingQueue(1)` 完成终端确认。Camel 使用原生 [SEDA](https://camel.apache.org/components/4.22.x/seda-component.html)，配置为单消费者、容量为一、不丢弃、不使用每任务一个虚拟线程、队列满时立即失败，以及 `waitForTaskToComplete=Always`。`ProducerTemplate.requestBody` 包含其正常的 Exchange/Message 创建、关联副本和请求/响应成本；返回值还会独立与终端校验器核对。这些是公开 API 路径成本，不代表内部实现或功能完全相同。

每个阶段都会创建新的、共用类型的不可变数值 record。两个终端汇都在校验序号、测量阶段、索引、变换值和输出数量后停止延迟计时，再确认调用方。输入构造计入延迟，确认位于终端延迟之外，但计入整个循环的墙钟吞吐量。固定 **16 MiB** 基本类型缓冲区在预热前预触页，最多接受 2,000,000 个精确测量样本。分位数使用最近秩法，排序在计时后进行。CPU 时间覆盖进程的测量阶段，堆/GC 活动可能包含此前预热产生的对象。启动、设置校验、排空、关闭、排序和 JSON 均不计入计时。

设置阶段会核实阶段/终端确实运行在另一个工作线程上；阶段之间必须排空所有工作。关闭必须完成，观测到的工作线程必须在有限看门狗期限内终止。测试覆盖两个引擎的全部三种阶段数、输出运算、无效或重复输出、样本溢出和无效选项。W=1 结果描述排队往返执行，不代表饱和容量、模型延迟或生产会话容量。
