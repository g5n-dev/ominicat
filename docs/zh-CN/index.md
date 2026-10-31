# 中文文档目录

[项目首页](../../README.zh-CN.md)

所有内部说明页均有中文版本；代码标识符、配置键、原始数值和历史测试环境保持其技术含义。

## 使用指南

- [运行时架构 · docs/architecture](architecture.md)
- [配置 · docs/configuration](configuration.md)
- [跨项目接入契约 · docs/integration-contracts](integration-contracts.md)
- [迭代计划 · docs/iteration-plan](iteration-plan.md)
- [Java 兼容性 · docs/java-compatibility](java-compatibility.md)
- [不可变媒体引用 · docs/media-references](media-references.md)
- [性能深度审计 · docs/performance-audit](performance-audit.md)
- [性能设计与 Linux 证据 · docs/performance](performance.md)
- [添加插件 · docs/plugins](plugins.md)
- [帧协议 · docs/protocol](protocol.md)
- [场景覆盖 · docs/scenario-coverage](scenario-coverage.md)
- [OminiCat Studio · docs/studio](studio.md)
- [时间片段与注释 · docs/temporal-annotations](temporal-annotations.md)

## 模块、扩展与部署验证

- [嵌入式回放 · examples](examples/README.md)
- [会话处理原语 · ominicat-conversation](ominicat-conversation/README.md)
- [内核对抗性验证 · ominicat-core/DEEP_VERIFICATION](ominicat-core/DEEP_VERIFICATION.md)
- [对抗性验证 · ominicat-providers/DEEP_VERIFICATION](ominicat-providers/DEEP_VERIFICATION.md)
- [处理适配器 · ominicat-providers](ominicat-providers/README.md)
- [原生传输与资源验证 · ominicat-server/DEEP_VERIFICATION](ominicat-server/DEEP_VERIFICATION.md)
- [OminiCat 服务端 · ominicat-server](ominicat-server/README.md)
- [对抗性与持续验证 · validation](validation/README.md)

## 性能对比与复跑

- [可复现、无模型调用的框架对比 · comparison](comparison/README.md)
- [Java 排队适配器 · comparison/benchmarks](comparison/benchmarks/README.md)
- [独立 Java JFR 诊断 · comparison/profile_java](comparison/profile_java.md)
- [无模型框架路径对比：协议 1 · comparison/protocol](comparison/protocol.md)

## Linux 实测与诊断报告

- [框架性能证据 · reports/comparison](reports/comparison/README.md)
- [无模型框架对比 · reports/comparison/linux-centos9-java17-2026-10-31](reports/comparison/linux-centos9-java17-2026-10-31/README.md)
- [独立诊断剖析 · reports/comparison/linux-centos9-java17-2026-10-31/profiles](reports/comparison/linux-centos9-java17-2026-10-31/profiles/README.md)
- [CPU、分配与 GC 剖析 · reports/comparison/linux-centos9-java17-2026-10-31/profiles/camel/profiles](reports/comparison/linux-centos9-java17-2026-10-31/profiles/camel/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/linux-centos9-java17-2026-10-31/profiles/camel/report](reports/comparison/linux-centos9-java17-2026-10-31/profiles/camel/report.md)
- [CPU、分配与 GC 剖析 · reports/comparison/linux-centos9-java17-2026-10-31/profiles/ominicat/profiles](reports/comparison/linux-centos9-java17-2026-10-31/profiles/ominicat/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/linux-centos9-java17-2026-10-31/profiles/ominicat/report](reports/comparison/linux-centos9-java17-2026-10-31/profiles/ominicat/report.md)
- [Pipecat 插桩 Python 诊断 · reports/comparison/linux-centos9-java17-2026-10-31/profiles/pipecat/profile](reports/comparison/linux-centos9-java17-2026-10-31/profiles/pipecat/profile.md)
- [无模型框架对比 · reports/comparison/linux-ubuntu24-java17-2026-10-31](reports/comparison/linux-ubuntu24-java17-2026-10-31/README.md)
- [独立诊断剖析 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/README.md)
- [CPU、分配与 GC 剖析 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/camel/profiles](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/camel/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/camel/report](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/camel/report.md)
- [CPU、分配与 GC 剖析 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/profiles](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/report](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/report.md)
- [Pipecat 插桩 Python 诊断 · reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/pipecat/profile](reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/pipecat/profile.md)

## 验证与历史记录（保留原测试环境）

- [CPU、分配与 GC 性能剖析 · reports/baseline/profiles](reports/baseline/profiles.md)
- [本地内核性能证据 · reports/baseline/report](reports/baseline/report.md)
- [无模型框架对比 · reports/comparison/java17-2026-10-31](reports/comparison/java17-2026-10-31/README.md)
- [独立诊断剖析 · reports/comparison/java17-2026-10-31/profiles](reports/comparison/java17-2026-10-31/profiles/README.md)
- [CPU、分配与 GC 剖析 · reports/comparison/java17-2026-10-31/profiles/camel/profiles](reports/comparison/java17-2026-10-31/profiles/camel/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/java17-2026-10-31/profiles/camel/report](reports/comparison/java17-2026-10-31/profiles/camel/report.md)
- [CPU、分配与 GC 剖析 · reports/comparison/java17-2026-10-31/profiles/ominicat/profiles](reports/comparison/java17-2026-10-31/profiles/ominicat/profiles.md)
- [独立 Java 排队路径诊断 · reports/comparison/java17-2026-10-31/profiles/ominicat/report](reports/comparison/java17-2026-10-31/profiles/ominicat/report.md)
- [Pipecat 插桩 Python 诊断 · reports/comparison/java17-2026-10-31/profiles/pipecat/profile](reports/comparison/java17-2026-10-31/profiles/pipecat/profile.md)
- [深度验证报告 · reports/deep](reports/deep/README.md)
- [回调生命周期修复后的历史比较 · reports/deep/kernel/comparison](reports/deep/kernel/comparison.md)
- [CPU、分配与 GC 性能剖析 · reports/deep/kernel/profiles](reports/deep/kernel/profiles.md)
- [本地内核性能证据 · reports/deep/kernel/report](reports/deep/kernel/report.md)
- [Java 17 兼容性验证 · reports/java17](reports/java17/README.md)
- [Java 17 上可中断的 HTTP 响应体 · reports/java17/http-cancellation](reports/java17/http-cancellation/README.md)
- [CPU、分配与 GC 性能剖析 · reports/java17/kernel/profiles](reports/java17/kernel/profiles.md)
- [本地内核性能证据 · reports/java17/kernel/report](reports/java17/kernel/report.md)
- [CPU、分配与 GC 剖析 · reports/performance-audit/linux-java17-2026-11-01/profiles](reports/performance-audit/linux-java17-2026-11-01/profiles.md)
- [本地内核性能证据 · reports/performance-audit/linux-java17-2026-11-01/report](reports/performance-audit/linux-java17-2026-11-01/report.md)
- [提示词生成成本配对测量 · reports/performance-audit/prompt-render-2026-11-01/summary](reports/performance-audit/prompt-render-2026-11-01/summary.md)
- [Studio 验证 · reports/studio](reports/studio/README.md)
- [Studio 交互评审 · reports/studio/interaction-review](reports/studio/interaction-review.md)
- [验证证据 · reports/verification](reports/verification.md)

## 贡献与维护

- [仓库工作流程 · AGENTS](agent-workflow.md)
- [贡献指南 · CONTRIBUTING](CONTRIBUTING.md)
- [安全 · SECURITY](SECURITY.md)
- [文档语言 · docs/i18n](i18n/README.md)
