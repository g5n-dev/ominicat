<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../reports/performance-audit/direct-state-2026-11-02/startup-evidence/summary.md) · [中文文档目录](../../../../index.md)
<!-- /ominicat-language-nav -->

# 独立的流水线构建诊断

在已预热的 Java 17 JVM 中，独立诊断流水线构建及首次直接调用。每次操作都构建一条新的四阶段流水线。这里测量的不是操作系统/JVM 冷启动延迟，不属于主实验验收场景，也不是饱和负载容量测量或对主实验结果的校正。

两个场景均按固定的基线 / 候选 / 候选 / 基线顺序执行，共运行八个全新的单 fork JVM。每个 fork 使用一个线程、256 MiB 堆，执行三轮各 1 秒的预热和五轮各 1 秒的测量。每侧、每种场景保留两个 fork 均值及十次迭代；这些迭代是 fork 内部的观测，不是十个独立重复样本。

| 场景 | 基线时间 ns/op | 候选时间 ns/op | 基线 B/op | 候选 B/op |
| --- | ---: | ---: | ---: | ---: |
| build-4 | 217.755988 | 229.977451 | 824.001306 | 960.001374 |
| first-direct-4 | 250.636521 | 259.014361 | 1016.001489 | 1048.001541 |

build-4 对新建一条四阶段流水线的过程计时；first-direct-4 还执行这条流水线的首次直接调用。计时工作包含已声明的探针检查。准备、产物验证和清理仍位于计时范围之外。精确的方法边界请查看已绑定的探针源代码。

这里不设置观测目标。这些成本不能用于判定主实验七个场景通过验收、补救其结果或筛选其中的场景。不得将不同探针的耗时相减，以推断容量、某个组件的独占成本，或解释此前框架间的差距。两个 fork 及其迭代的范围只是观测范围，不是置信区间。

[清单](../../../../../../reports/performance-audit/direct-state-2026-11-02/startup-evidence/manifest.json)绑定了实际完整的主测量清单、对应的内核/探针产物、源代码清单、主机/JVM，以及构建诊断的采集器/探针源代码。[summary.json](../../../../../../reports/performance-audit/direct-state-2026-11-02/startup-evidence/summary.json)保留全部 fork 均值、迭代及范围。保留原始 JSON、通过隐私检查的日志和[校验和](../../../../../../reports/performance-audit/direct-state-2026-11-02/startup-evidence/SHA256SUMS)。仅凭本目录不足以完成验证：还必须提供与其绑定的完整主测量。
