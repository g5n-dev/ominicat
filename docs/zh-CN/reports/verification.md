<!-- ominicat-language-nav -->
**简体中文** · [English](../../../reports/verification.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 验证证据

验证日期为 **2026-10-31**，实际使用 Homebrew OpenJDK **17.0.21.0.3**，环境为 macOS/aarch64。所有模块生成 Java 17 字节码。

命令：`./mvnw -B -ntp -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`。全部六个 reactor 项目成功。

| 模块 | 测试数 | 失败／错误／跳过 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | --- | ---: | ---: |
| `ominicat-core` | 89 | 0 / 0 / 0 | 92.34% | 79.91% |
| `ominicat-conversation` | 60 | 0 / 0 / 0 | 96.57% | 82.87% |
| `ominicat-providers` | 67 | 0 / 0 / 0 | 97.89% | 88.19% |
| `ominicat-server` | 81 | 0 / 0 / 0 | 94.05% | 80.00% |

**297 项测试通过，失败／错误／跳过均为零。**二十项[媒体引用测试](../../../ominicat-conversation/src/test/java/io/ominicat/conversation/reference)覆盖生产者／来源／版本隔离、PCM 每通道单位、未知位置、必需指纹、非均匀帧边界、整数溢出及有界二进制导入／导出。I/O 夹具检查完整 `long` 精度、格式错误的 UTF-8、每个截断前缀及由调用方管理的流生命周期。

另有十六项活动测试覆盖默认关闭观测、游标／环形缓冲上限、预览隐私、真实 WebSocket 事件结构、异步完成／取消及清理失败分类。五项[运行时 API 测试](../../../ominicat-server/src/test/java/io/ominicat/server/RuntimeApiTest.java)覆盖安全元数据、鉴权／来源、不可变配置、静态路由和真实 WebSocket 计数／排空行为。十项仅使用 Node 的[浏览器平台夹具](../../../scripts/test_console.cjs)覆盖媒体／会话竞态、有界状态、轮询、视图分离与详情交互。[Studio 验证](studio/README.md)将浏览器检查及并发观测流量与内核基准分开。

[HTTP 响应体取消报告](java17/http-cancellation/README.md)保留修复前的确定性失败、Java 17/21 模块验证、保持打开流的交付，以及精确发行物／源码差异。公开 Flow 桥接在 Java 17 上保留中断语义，不使用轮询或每响应读取线程。[CI 矩阵](https://github.com/g5n-dev/ominicat/actions/workflows/ci.yml)验证 Java 17/21/25 及默认 Java 17 容器。

[当前计数](../../../reports/verification.json) · [依赖清单](../../../reports/runtime-dependencies.cdx.json) · [Java 兼容性](../java-compatibility.md)。

重新构建的基准 JAR 保持 SHA-256 `a312af033f1f9886883479147e4d82ede152afba3628baf5fb12a48e2db87bc7`，与[内核性能剖析](java17/kernel/report.md)一致。可选引用 API 和 Studio 服务端观测器不改变核心分发。引用构造、映射与序列化不在该数值帧基准范围内。较早的 [Java 17 资源证据](java17/README.md)与 [Java 21 深度验证](deep/README.md)保留原始源码和产物哈希。不声称真实提供方验证成功。
