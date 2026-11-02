<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../../../reports/mechanisms/java17-2026-11-02/evidence/diagnostics/camel-handoff/report.md) · [中文文档目录](../../../../../../index.md)
<!-- /ominicat-language-nav -->

# 独立机制诊断：camel-handoff

独立运行、启用分析工具的 Java 17 JMH 诊断 JVM：整段录制包含 JVM／框架启动、初始化、两次各 1 秒的预热迭代、两次各 1 秒的测量迭代、测试工具与收尾过程。不按阶段过滤。CPU 执行采样请求 10ms 周期；分配使用 profile.jfc 的默认配置。录制与导出的栈最多保留 64 层帧。这些数据不能作为主性能计时、框架独占 CPU、保留堆、精确对象数量、p99 延迟或 RSS 测量。

这是一个独立的 `-f 0` 诊断 JVM，不属于两个全新测量 fork 中的任何一个。identity 复制对照在主矩阵中测量，但此处有意不对其采集分析数据。CPU 栈覆盖所有 JVM 线程；分配权重表示采样得到的已分配字节权重。GC 总和覆盖选定的整段录制，而不只是计时测量区间。

[CPU、分配与 GC 图表](profiles.md) · [精简事件汇总](../../../../../../../../reports/mechanisms/java17-2026-11-02/evidence/diagnostics/camel-handoff/jfr-hotspots.json) · [录制上下文](../../../../../../../../reports/mechanisms/java17-2026-11-02/evidence/diagnostics/camel-handoff/diagnostic-context.json) · [验证](../../../../../../../../reports/mechanisms/java17-2026-11-02/evidence/diagnostics/camel-handoff/validation.json)

原始 JFR、未压缩事件、JMH 输出与工具日志均保留在本地 `capture/raw` 中，不纳入此诊断归档。
