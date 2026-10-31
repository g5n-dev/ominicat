<!-- ominicat-language-nav -->
**简体中文** · [English](../iteration-plan.md) · [中文文档目录](index.md)
<!-- /ominicat-language-nav -->

# 迭代计划

这是 OminiCat 高性能 Java 17+ 多模态微内核的持续更新队列，用于安排范围小、由证据驱动的改进。已完成工作附有拉取请求与验证链接，新故障可以优先于队列。每个增量开始时应重新审视假设，不能把提案当作已确认缺陷。

## 执行循环

1. 阅读仓库指引、当前源码、已打开的拉取请求和近期 CI 结果。继续匹配的未完成工作，避免修改其他任务的文件。
2. 选择一个就绪项目，实现前说明触发证据、有界范围和验收标准。若所有项目都缺少外部信息，记录精确依赖并停止，不做推测性修改。
3. 实现并验证增量，默认使用本地合成协议测试。保留诊断证据与失败输出；未完成的测量必须失败，不能生成成功报告。
4. 用证据和下一项有用行动更新本队列，随后创建或更新范围集中的拉取请求，包含实现与计划变更。当前提交上的全部五项必需检查通过、审查会话解决后，通过受保护 `main` 合并。不得为了完成一次运行而削弱门禁。
5. 确认合并结果，将剩余工作带入下一个增量。实验优化中，测得负面结果同样有效；保留结论，不交付更差的运行时。

定时 profiling 是独立的证据采集作业。它每周在 Java 17 和 21 上运行，生成 CPU／分配火焰图及 GC 汇总，将带白名单与校验和的产物保留 30 天。成功表示采集与校验完成，不表示性能改进。CI 运行器波动不得自动更新对外性能主张。见[性能测量方法](performance.md)。

## 就绪队列

### 时间与注释语义

[时间契约提案](temporal-annotations.md)定义可选行为组合。最小 T1a 来源／区间／版本引用与不可变导入导出测试现已[实现](media-references.md)；下列能力仍在计划中。根据要启用的具体接入选择工作，现有原始帧管线不需要这些能力。I1 仍可独立执行；需要时，仅面向主体的基础 T2 是下一项小规模时间数据增量。

| 能力组合 | 增量 | 验收边界 |
| --- | --- | --- |
| T2 基础 | 独立、版本化的注释数据 | 仅面向主体的注释无需媒体时间线；区间目标仅使用必需的 T1a 子集。测试维度／值、限定范围的目标、schema 和载荷限制。分组区间与变更是分别声明的行为，不是基线前提。 |
| T1b 编辑 | 可选的本地片段变更 | 依赖编辑测试实际使用的引用子集。测试有界原子区间调整／拆分／合并及精确预期修订；状态变更前拒绝冲突。只读注释导入不必等待此归约器。 |
| T3 投影 | 可选证据依赖与结果生命周期 | 仅使用适配器拥有的有界依赖和显式影响策略。当前指针、有效性和证据可用性分开。不因缓存淘汰而失效，不引入全局图或自动调度器，也不宣称外部作用可回滚。 |

先建立最小进程内测试。这些能力不是一条强制依赖链：I1 独立，仅面向主体的 T2 绕过时间能力，只读区间 T2 不要求 T1b/T3。已交付 T1a 二进制适配器与拟议 I2 OminiCat 线协议相互独立；其他项目／文件 codec 也保持独立。I3 恢复绑定只纳入实际使用的版本。行为探测仅验证所声明能力。质量指标应匹配算法主张，可选表示／归约器成本与内核派发分开测量。

### 接入兼容性

[跨项目契约提案](integration-contracts.md)定义配套接入增量与三类测试。这些是计划中的能力，不是已交付 API。一次优先实现一个有界项目，保留 Java 17 及现有默认 API／协议行为。I1 用当前 API 建立兼容性测试；I2/I3 在对应契约交付时扩展。

| 顺序 | 增量 | 验收边界 |
| --- | --- | --- |
| I1 | 可选插件描述与兼容性测试 | 保留旧插件二进制；查询元数据不调用 `ProcessorFactory.create` 或网络代码；显式处理未知能力；拒绝已知的整句／流式能力不匹配。用当前 API 建立流式／重放／聚合基线，后续再由 I2/I3 扩展。 |
| I2 | 选择启用的命名空间结果 schema 与溯源 | 保留默认线协议测试；区分同名帧类；拒绝重复／不支持的 schema 注册；保留证据时间单位、因果 ID 和窗口范围。显式主体映射须区分跨应用的相同本地键。单独测量 codec 成本。 |
| I3 | 外部运行清单与检查点绑定 | 恢复时，在状态变化前拒绝输入／定义内容不匹配；允许将新策略重新评估作为新运行。sink 失败不得推进游标。存储与持久作用仍由外部负责。 |

### 性能证据

[性能深度审计](performance-audit.md)以 Ubuntu 配对测量验证了有界的提示词统计／历史优化，完整的不使用历史／完整历史／截短历史矩阵均通过逐字输出和运行时来源检查。其他源码可确认的候选仍需各自负载验证。

当相关负载有助于评估改动时，将这些测量增量与接入工作并行推进。分配实验依赖相应基线，不能先于基线。

| 顺序 | 增量 | 证据与范围 | 验收 |
| --- | --- | --- | --- |
| 1 | 异步 continuation 与 envelope 成本 | 现有 JMH 覆盖同步数值派发与会话往返。增加有界外部回调基准，分别测试原始帧与 envelope，保持运行时契约不变。 | 校验 nonce、因果关系与帧输出；一个请求在途；清理时执行器／会话资源排空。记录 Java 17 预热、至少两个独立进程、延迟、B/op、源码哈希和计时边界。范围明确包含执行器、标识与时钟开销。在 17/21/25 编译验证。 |
| 2 | 媒体快照与派发成本 | 数值帧结果不含音频／图像构造的防御性复制。将构造与预先构建的不可变帧派发分开测量。覆盖 640/3,840 字节 PCM 块及 64 KiB/1 MiB 图像字节。 | 在计时外准备可复用源数据，防止常量折叠，保留快照／只读回归。发布精确载荷大小对应的时间／吞吐、B/op 和 GC。不宣称编解码、模型或实时媒体容量。 |
| 3 | 有证据时减少同步调用分配 | 采样 profile 可见调用保护与捕获 lambda，但采样本身不能建立逐操作成本或优化收益。先建立匹配的 Linux JMH B/op 基线，再测试合并逐调用状态的可丢弃原型。依赖项目 1 和 2。 | 目标至少降低 10% 分配；在受控主机和测量不确定性下，吞吐或平均往返不能出现超过 5% 的退化。通过 17/21/25 验证和 512 个压力种子，包括过期 sink、错误线程、关闭与引用释放测试。不池化／重新激活 sink，不改变公共 API。未达目标时发布实验结论，运行时保持不变。 |

## 有条件队列

| 项目 | 依赖 | 验收 |
| --- | --- | --- |
| 真实兼容服务试验 | 操作者显式提供端点、所选模型与凭据环境变量名。现有本地协议测试不能满足此依赖。 | 有界、显式启用的调用；验证流式内容、对端 EOF／截止时间之前的取消、错误处理及名额／资源排空。仅发布带服务／运行时／源码上下文的脱敏证据。记录实际失败与支持能力。 |
| 后续传输或 provider 优化 | 当前证据中可复现的回归、profile 热点或负载缺口。 | 改变运行时行为前定义负载、资源预算与故障注入。重跑相关集成测试，附上绑定到已改产物的证据。 |
| 资源预算整合 | 有证据表明文档中的逐层限制无法表达某个所需接入预算；仅参数重复不构成依据。 | 对媒体复制、队列／分支／出口、原生／JVM 开销和插件限制建模；用明确负载验证矛盾与排空。身份、租户配额、计费和分布式接纳留在应用／边缘服务。 |
| 与 provider 无关的音频预处理 | 为可复用媒体插件提供代表性目标语料和明确质量／延迟目标。仅 RMS 能量检测不能满足降噪要求。 | 声明 PCM 格式、逐轨连续性／状态、流式范围、重置／取消及 CPU／缓冲上限。滤波／抑制与下游识别质量应独立于内核吞吐评估，不能仅凭能量推断归属。 |
| 传输无关的操作事件适配器 | 多次需要通过可复用适配器统一观测嵌入式、工作线程和传输驱动操作。 | 限制版本化事件载荷与投递，保留因果／目标引用，在声明提交点注入失败测试。避免把公共事件契约绑定到 HTTP controller 拦截器；授权、保留和持久审计保证由应用／存储负责。 |

## 已完成增量

| 增量 | 结果 | 证据 |
| --- | --- | --- |
| 有界提示词构造与性能深度审计 | 提示渲染跳过未使用的评估指标和多余历史实体化。Ubuntu／Java 17 配对测量覆盖九种窗口／历史用例，并逐字校验输出和运行时来源；独立的完整 Linux 内核 profile 为下一步分配假设提供依据。公开 API 与生命周期行为保持稳定。 | [PR #32](https://github.com/g5n-dev/ominicat/pull/32)、[审计与完整结果](performance-audit.md)、[配对原始证据](../../reports/performance-audit/prompt-render-2026-11-01/manifest.json)、[独立提示词回归](../../ominicat-conversation/src/test/java/io/ominicat/conversation/PromptTemplateOracleTest.java) |
| 完整中文文档阅读路径 | 所有项目指南、协议、配置与部署、模块说明和报告叙述均有中文阅读版。内部页面与标题跳转保留中文；所有已发布图表均有中文标签和绑定源文件的 PNG 预览。CI 检查源文新鲜度、可执行示例、API 字面值、表格数值和导航；原始测量归档保持不变。 | [PR #31](https://github.com/g5n-dev/ominicat/pull/31), [中文目录](index.md)、[页面绑定](../i18n/pages.json)、[图表绑定](../i18n/figures.json)、[导航与证据保护测试](../../scripts/test_chinese_docs.py) |
| 易发现的英文／简体中文 README | 主视觉之前提供语言切换。中文版本包含嵌入、插件打包和生命周期示例，并本地化横幅、组成与框架路径图。两版保留相同 Linux 证据；CI 检查译文新鲜度及绑定来源的 PNG。 | [PR #30](https://github.com/g5n-dev/ominicat/pull/30)、[项目首页](../../README.zh-CN.md)、[简体中文](../../README.zh-CN.md)、[翻译词表](../i18n/zh-CN.json)、[图形本地化检查](../../scripts/localize_readme.py) |
| 性能原理与框架执行路径 | 根据源码说明 OminiCat、Pipecat 和 Camel 的同步交接、保留的生命周期成本及原生确认。阶段数 CPU 图和六组比值来自全部 90 个已校验 Linux 进程；README 公开内存取舍与重叠轮次范围。运行时和已记录基准数值均未改变。 | [PR #29](https://github.com/g5n-dev/ominicat/pull/29)、[机制、固定源码与完整比较](performance.md#实测路径为何不同)、[可复现推导与原始证据检查](../../scripts/render_performance_explainer.py)、[派生数据](../assets/framework-costs.json) |
| 静态文档图片预览 | 两版 README 与架构／性能指南嵌入 11 张共享 2× PNG 预览，并链接到可编辑 SVG 原图。读者无需客户端图形引擎或 SVG 嵌入支持；Linux 测量归档未变。 | [PR #27](https://github.com/g5n-dev/ominicat/pull/27)、[预览导出器与来源／哈希／尺寸／链接检查](../../scripts/render_doc_previews.py)、[重生成指南](architecture.md#性能证据与图表源码)、必需的 source-hygiene CI |
| HTTP/1.1 响应关闭声明 | 将关闭 TCP 连接的响应显式声明为 `Connection: close`，包括不支持的 WebSocket 版本。原生 socket 回归检查完整响应体和 EOF；成功升级保留独立握手路径。 | [PR #28](https://github.com/g5n-dev/ominicat/pull/28)、[HTTP 响应边界测试](../../ominicat-server/src/test/java/io/ominicat/server/HttpResponseBoundaryTest.java)、[共享客户端运行时 API 检查](../../ominicat-server/src/test/java/io/ominicat/server/RuntimeApiTest.java) |
| Linux 优先 README 与架构说明 | 两个首页只展示 Ubuntu/CentOS 性能及 Linux 诊断。三张经源码核对的 SVG 图说明组成、执行与独立资源限制；确定性生成器和成本边界指南使图表可审查。数值测量归档未变。 | [PR #26](https://github.com/g5n-dev/ominicat/pull/26)、[架构](architecture.md)、[性能指南](performance.md)、[SVG 生成器](../../scripts/render_architecture.py) |
| Java 17 基线与可中断 HTTP 响应体读取 | 最低 Java 17，在 17/21/25 验证；取消可释放阻塞的本地 HTTP 读取，无需等待对端 EOF。 | [PR #12](https://github.com/g5n-dev/ominicat/pull/12)、[PR #14](https://github.com/g5n-dev/ominicat/pull/14)、[当前 Java 17 验证](reports/java17/http-cancellation/README.md) |
| 性能与 profile 文档 | README 展示实测吞吐、分配、CPU 栈、GC 和资源排空，并区分负载范围。 | [PR #13](https://github.com/g5n-dev/ominicat/pull/13)、[PR #15](https://github.com/g5n-dev/ominicat/pull/15)、[内核 profile](reports/java17/kernel/profiles.md) |
| 完整 profile 导出与可读图形 | 显式采集／导出 64 帧栈，完整渲染提供的栈，汇总保留热点权重，并提供 GC 暂停时间线。从原始 Java 17 记录恢复更深栈，不替换测量结果。成功采集会先生成全部图，再移动本地原始导出。 | [生成器回归](../../scripts/test_summarize_profiles.py)、[打包控制](../../scripts/test_package_profiles.py)、[已恢复 profile](reports/java17/kernel/profiles.md) |
| 可重复规划与证据采集 | 持续维护验收队列、每周 profiling、完整结果校验、白名单／校验和诊断以及经隐私检查的失败输出。 | [工作流](../../.github/workflows/performance.yml)、[报告校验](../../scripts/test_report.py)、[打包控制](../../scripts/test_package_profiles.py)、[失败控制](../../scripts/test_profile_failure.py) |
| 跨项目契约审查 | 根据源码区分现有机制、拟议兼容契约与外部职责，并提供显式负向验收案例。实现仍在 I1–I3 队列中。 | [设计与验收计划](integration-contracts.md) |
| 时间／注释覆盖审查 | 区分中间转写修订与通用片段变更、规则结果与独立注释，以及本地归约与外部发布保证。可选能力的实现仍在队列中。 | [语义与验收计划](temporal-annotations.md) |
| 最小能力架构审查 | 移除强制的编辑／媒体依赖，限定跨生产者引用，并将缓存可用性与结果有效性分开。指标与一致性检查针对实际声明的能力。 | [可选能力](temporal-annotations.md)、仓库架构审查指引 |
| T1a 不可变媒体引用 | 可选的来源／生产者限定区间版本值、显式区间／点／未知位置、PCM 每通道单位、精确给定帧边界和有界二进制导入导出。新增二十项测试；对应 Java 17 全量验证共 276 项、512 个压力种子。无内核源码或依赖变化。 | [API 指南](media-references.md)、[值／映射测试](../../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceTest.java)、[I/O 测试](../../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/MediaReferenceIOTest.java)、[验证](reports/verification.md) |
| Studio 任务大盘与独立管线调试器 | 任务／内容列、阶段调用观测、输出卡片及有界活动回放与详情；独立多模态调试器。连接级显式启用的服务端观测、游标缺口及脱敏预览，帧载荷与内核派发不变。HTTP/WS、异步取消、记录边界与浏览器生命周期测试，以及资源排空证据。原生滚动／拖动审查、可见模块端口和独立列滚动。 | [Studio 指南](studio.md)、[运行时 API 测试](../../ominicat-server/src/test/java/io/ominicat/server/RuntimeApiTest.java)、[浏览器控制](../../scripts/test_console.cjs)、[验证](reports/studio/README.md) |
| 可复现的跨框架排队比较 | 同机 Java 17／CPython 3.12 原生排队 W=1 路径，Pipecat 1.15.0 与 Camel 4.22.2 LTS；1/4/16 阶段共 45 个全新进程，精确输出／生命周期校验、源码／依赖哈希，以及可读吞吐／延迟／RSS 图。独立插桩不进入主计时。 | [PR #24](https://github.com/g5n-dev/ominicat/pull/24)、[协议](comparison/protocol.md)、[结果](reports/comparison/java17-2026-10-31/README.md)、[失败控制](../../comparison/tests/test_evidence.py) |
| 自动 Linux 框架性能证据 | Ubuntu 24.04 原生环境与以摘要固定的 CentOS Stream 9 用户态各通过 45 个全新进程及独立 JFR/cProfile 诊断，共验证 17,406,385 个测量输出。校验发行版／源码／内核／架构绑定、CPU/libc／资源范围、真实图形导出控制及不可变 CI 产物。相关 PR/main 变更自动触发采集，受保护 main 的五项检查仍为必需。 | [PR #25](https://github.com/g5n-dev/ominicat/pull/25)、[成功 Linux 运行](https://github.com/g5n-dev/ominicat/actions/runs/41222330745)、[图表及源码／归档溯源](reports/comparison/README.md)、[Linux 失败控制](../../comparison/tests/test_linux_evidence.py) |

本队列提出工作，不声称尚未实现的优化正确或更快。已记录主指标始终绑定原始源码、负载和主机，直到出现经审查的替代测量。
