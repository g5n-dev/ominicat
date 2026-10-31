<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../../reports/comparison/java17-2026-10-31/profiles/camel/report.md) · [中文文档目录](../../../../../index.md)
<!-- /ominicat-language-nav -->

# 独立 Java 排队路径诊断

选定记录的全部范围：JVM／框架启动、准备、3 秒预热、5 秒 W=1 测量、测量程序及清理。不按阶段过滤。这是独立插桩诊断，不是主计时、框架独占分配、存活堆，也不是跨语言共用的每操作字节数坐标。

引擎：**camel**；四个应用阶段；Java 17；一个在途项。

保留 JFR 插桩结果仅用于校验此诊断负载，不得替代 45 个未插桩对比结果中的任何一个。父进程观测的阶段窗口记录 stdout 到达时间，不是精确 JVM 事件边界；图表不按这些窗口过滤。

[CPU／分配／GC 图表](profiles.md) · [诊断上下文](../../../../../../../reports/comparison/java17-2026-10-31/profiles/camel/diagnostic-summary.json) · [插桩验证结果](../../../../../../../reports/comparison/java17-2026-10-31/profiles/camel/result.json)

原始 JFR、未压缩事件导出、配置和日志保留在已忽略的 `reports/local` 下，不会自动复制到公开目录。分配采样权重既不是保留内存，也不是框架独占或跨语言 B/op。零采样事件不能证明不存在。
