<!-- ominicat-language-nav -->
**简体中文** · [English](../../../comparison/profile_java.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 独立 Java JFR 诊断

在**未插桩对比结束后**，于同一主机上串行运行。封装脚本不会构建或修改被测 JAR。使用 Java 17 可执行文件，也可绑定对比清单中的精确 JAR SHA-256：

```sh
python3 comparison/profile_java.py \
  --java "$JAVA_HOME/bin/java" \
  --jar comparison/benchmarks/target/benchmark.jar \
  --expected-jar-sha256 <javaArtifactSha256-from-manifest> \
  --output reports/local/comparison-java-profiles
```

默认依次采集 OminiCat 和 Camel：每个引擎四个阶段、一个在途项、预热三秒、测量五秒。`--engines ominicat` 或 `--engines camel` 可选择单个引擎。请选择新的空输出目录；只接受位于被忽略的 `reports/local` 下的目标。工具使用对应 JDK 中与 java 同目录的 `jfr`。

每份记录请求 1 ms 的 Java 执行采样周期和 64 层栈深度。分析配置关闭初始环境/系统/安全属性、属性变更、进程清单/启动及 OS/JVM 信息事件。导出对五种选定诊断事件请求保留深度 64 以内的所有已提供帧。现有渲染 API 生成完整的已提供 CPU/分配火焰图、紧凑热点图及 GC 收集暂停时间线。固定 16 MiB 样本缓冲区和测试驱动分配仍在记录范围内，采样可能遗漏个别分配。

这些是**完整记录范围的诊断**，包括 JVM/框架启动、设置、预热、测量循环、测试驱动和销毁，不按测量阶段过滤。父进程观测的 UTC 阶段窗口描述标准输出接收和进程启动/退出，不是精确的 JVM 事件边界。`load.json` 明确是包含所观测 Java 版本的渲染上下文，不含虚构的 CoreLoad 结果。

只有每个选定引擎都完成校验、排空、关闭、导出和渲染后，才会出现 `summary.json`。每个引擎的 `diagnostic-summary.json` 通过 SHA-256 绑定未变的基准 JAR 和诊断输出，保留阶段观测，并将插桩结果标为不可进入主性能对比。这两次插桩运行绝不会替换或补入主对比表中的 45 次未插桩重复。分配采样权重不代表保留内存、框架自身分配或跨语言 B/op 轴。

原始 JFR、未压缩的选定事件 JSON、日志和配置保留在本地，工具不执行公开复制。发布已审查的诊断时，应明确列出允许发布的生成图表、摘要、上下文/结果 JSON 和经过隐私检查的压缩选定事件，并排除原始 JFR 和日志。样本缺失应如实报告；观测到零个事件不能证明事件不存在。
