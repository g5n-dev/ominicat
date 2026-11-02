<!-- ominicat-language-nav -->
**简体中文** · [English](../../../../../../../../reports/performance-audit/direct-state-2026-11-02/evidence/diagnostics/candidate-envelope-direct-4/report.md) · [中文文档目录](../../../../../../index.md)
<!-- /ominicat-language-nav -->

# 配对直接调用状态诊断： candidate-envelope-direct-4

独立运行、启用诊断采集的 Java 17 JMH JVM，记录范围包括启动、准备、两轮各 1 秒的预热、两轮各 1 秒的测量、测试驱动和清理。不按阶段过滤。CPU 采样请求间隔为 10ms；分配采样限流为每秒 50 个事件；录制和导出最多保留 64 层栈帧。这些诊断不作为正式计时证据，也不用于估算每次操作的分配量、保留堆、方法耗时、p99 延迟、RSS 或生产容量。

这份独立的 `-f 0` 录制不会增加正式测量的 fork 数。所选一侧使用与其绑定的内核 JAR，以及同一份探针 JAR。CPU 样本覆盖 JVM 的多个线程。分配权重和 GC 总量未按操作次数归一化，因此不能据此对这些录制的每次操作效率排序。

[CPU、分配与 GC 图表](profiles.md) · [事件摘要](../../../../../../../../reports/performance-audit/direct-state-2026-11-02/evidence/diagnostics/candidate-envelope-direct-4/jfr-hotspots.json) · [来源上下文](../../../../../../../../reports/performance-audit/direct-state-2026-11-02/evidence/diagnostics/candidate-envelope-direct-4/diagnostic-context.json) · [验证](../../../../../../../../reports/performance-audit/direct-state-2026-11-02/evidence/diagnostics/candidate-envelope-direct-4/validation.json)

原始 JFR、私有配置、未压缩的导出文件，以及未经审查的工具/JMH 日志，均不在此发布白名单中。
