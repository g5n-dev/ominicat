<!-- ominicat-language-nav -->
**简体中文** · [English](../../CONTRIBUTING.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 贡献指南

[迭代计划](iteration-plan.md)跟踪可执行工作与验收标准。仓库自动化遵循 [AGENTS.md](agent-workflow.md)，与其他变更采用相同的评审和验证要求。

使用 Java 17+ 和仓库自带的 Maven wrapper。提交变更前运行 `./mvnw -Pcoverage,sbom verify`。默认测试套件完全使用本地测试夹具，不要求付费提供方访问权限。

保持内核不依赖第三方生产库，也不承载提供方／应用策略。每个有状态处理器只属于一个会话。修改运行时契约时，测试生命周期、过载、取消与失败路径。新提供方需要协议测试夹具，以及明确的能力／限制文档。

影响调度或分配的变更应运行 `scripts/profile.sh quick reports/local/change`。在同一 JVM 和主机上比较，并记录不确定性及负载细节。绝不能把合成内核吞吐解释成端到端模型延迟。不得提交凭据、转写、录音、堆转储或真实应用的原始记录。

线协议字段变更应保留文档默认行为，并包含往返测试。新配置选项应尽早拒绝无效值。公开声明必须与可运行代码及可复现证据一致。

`main` 分支受到保护，管理员也不例外。通过拉取请求提交变更；保持分支最新，并在合并前解决评审讨论。必需的 GitHub Actions 检查为 `verify (17)`、`verify (21)`、`verify (25)`、`source-hygiene` 和 `container`。每个指向 `main` 的拉取请求都会运行这些检查，包括文档变更。使用 squash 或 rebase 合并以保留线性历史；禁用强制推送和分支删除。
