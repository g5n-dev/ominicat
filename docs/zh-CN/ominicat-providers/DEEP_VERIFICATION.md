<!-- ominicat-language-nav -->
**简体中文** · [English](../../../ominicat-providers/DEEP_VERIFICATION.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 对抗性验证

供应商和对话测试套件使用本地 HTTP 对端及确定性生成的媒体，不发起云请求，也无需凭据。本次验证覆盖仓库中的 Java 适配器和状态原语，不证明供应商模型准确率、真实 API 兼容性、生产容量或长期可靠性。

## 可重复矩阵

| 范围 | 负载与独立检查 |
| --- | --- |
| SSE 分片 | 42 种组合：LF/CRLF/CR、有/无起始 UTF-8 BOM，以及 1/2/3/5/7/13/64 字节输入分片；Unicode 输出相同 |
| SSE 截断 | `[DONE]` 之前全部 65 个字节截断位置，包括 Unicode 码点内部；均不能成功完成 |
| 无效数据 | 无效 UTF-8、无效 JSON、超长行、字节/字符预算及截断的定长响应体 |
| 远端失败 | HTTP 301/302/400/401/408/413/429/500/502/503；禁用重定向，错误中不包含响应体内容 |
| 网络故障 | 慢响应头、停滞的 SSE 响应体、停滞的 PCM 响应体、部分文本后发生真实 TCP reset，以及后续请求成功 |
| 截止时间竞态 | EOF 解除阻塞时，计划截止任务的 `close()` 被故意保持在执行中；部分数据不得转成成功输出 |
| 资源退役 | 250 个已完成请求，配置截止时间为 300 秒，调度器中残留的已取消截止任务为零；在受支持的 Unix JVM 上检查描述符增长 |
| 修订提交 | 种子 `0x6a7e..0x6a9d`：32,000 个分段、352,000 次门控操作、打乱顺序的部分修订、重复/过期终稿和冲突提交 |
| 有界身份保留 | 使用只保留七个 ID 的门控处理 10,000 个已提交 ID；抑制仍被保留的重复项，明确允许已逐出 ID 再次进入 |
| 音频时间线 | 种子 `0x5a91e..0x5a921`：三条独立轨道上生成 1,440 个 PCM 帧和 108 个 WAV 请求，混合已知/未知偏移、向前间隙和中断 |
| 音频载荷隔离 | 将 WAV 声道/采样率头和精确 PCM 字节与独立的每轨缓冲区对比；使用 `BigInteger` 校验采样点到时间的换算 |
| 区间指标 | 种子 `0x51a7..0x51e6`：32,000 个轮次、3,200 次与离散时间占用位图对比的保留窗口检查，另加快照重放 |
| 证据来源 | 种子 `0xeff1de`：1,000 个随机位置的短语匹配；检查分段、角色、时间戳和摘录 |
| 状态损坏 | 无效快照、重复 ID、抛异常的转移谓词、修订溢出和逐出计数溢出，均保留上一次已提交状态 |

现有适配器测试还覆盖 multipart 表单体、PCM 格式变化、流总预算、采样取整漂移、取消重置、TTS 轮次聚合和声道解交织。

## 已复现并修正的缺陷

四个新增回归在修复前失败：有效的纯 CR SSE 行被拒绝；无效 UTF-8 被静默替换；步骤修订溢出在失败前先改变步骤和去重状态；恢复的逐出计数器可能静默回绕为负数。仓库中的测试保留了这些复现。

附加加固在解码前统计响应字节、立即移除已取消的截止任务、在关闭响应体前记录超时已到期，并拒绝不可能成立的未启动/零修订流程快照。确定性注入的关闭中测试保护了截止时间竞态。这些是实现检查，不表示不可能存在其他故障。

## 在独立检出目录中复现

使用 Java 21+ 和 Maven 3.9+，也可使用仓库的 Maven wrapper。在类 Unix 系统上，从仓库根目录执行：

```sh
task_verify_dir=$(mktemp -d)
git archive HEAD | tar -x -C "$task_verify_dir"
rsync -a --exclude target ominicat-providers/ "$task_verify_dir/ominicat-providers/"
rsync -a --exclude target ominicat-conversation/ "$task_verify_dir/ominicat-conversation/"
mvn -f "$task_verify_dir/pom.xml" -B -Pcoverage \
  -pl ominicat-providers,ominicat-conversation -am clean verify
```

覆盖层包含所负责模块的未提交变更，同时将构建输出隔离于共享检出目录之外。Surefire XML 和 HTML 覆盖率报告位于各独立模块的 `target` 目录。只重复故障和校验模型用例时执行：

```sh
mvn -f "$task_verify_dir/pom.xml" -B \
  -pl ominicat-providers,ominicat-conversation -am \
  -Dtest=ProviderFaultMatrixTest,SseBoundaryTest,TranscriptionTimelineOracleTest,TranscriptGateOracleTest,ConversationOracleTest,StateAtomicityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Java 21 上的干净构建验证通过了 55 个供应商测试和 40 个对话测试，无断言失败或错误。随后额外重复了三次 19 个故障/校验模型测试方法，均未失败。供应商行覆盖率为 359/363（98.90%），分支覆盖率为 282/314（89.81%）；对话模块行覆盖率为 416/434（95.85%），分支覆盖率为 297/360（82.50%）。测试方法数不同于上文列出的生成用例和操作数。计时断言提供宽松的测试看门狗和超时检查，不代表服务延迟目标。

## 发布补充：保持连接的 CR 流式活性

上述计数描述的是较早的供应商源码。后续保持流打开的探针发现了另一个活性缺陷：解析器读到 CR 行终止符后，为区分 CRLF，立即多读一个字符。因此完整的 `data: ...\r\r` 事件会等到后续事件或 EOF 才交付文本；完整的 `[DONE]\r\r` 也可能继续等待更多字节，而不是终止。较早的有限响应体 CR 夹具会关闭流，因此没有暴露这一延迟。

两个新增本地 HTTP 对照在旧源码上失败，记录为两个断言失败、零错误。每个对端都刷出完整事件，然后等待闩锁；文本交付或成功完成必须在释放闩锁前发生。这两次复现都不依赖截止时间到期。

修正只修改生产 `ChatFactory.java`：每流行读取器现在读到 CR 即返回，将可选 LF 的跳过推迟到下一次读取。UTF-8 校验、BOM 处理、字节/字符预算和其他适配器行为保持不变。

### 此次变更的来源记录

已完成的 20 分钟回显/资源运行属于 SHA-256 为 `081520dcc8c78baea46c013f78a3be0878bcba1c24789bce3b8c6c535710978f` 的构件。该构件没有改变，也不包含此次 SSE 修正，不能将其回显测量重新标注为修正版发布的持续 SSE 测试。

| 项目 | 修复前 | 修正后的独立构建 |
| --- | --- | --- |
| `ChatFactory.java` SHA-256 | `150edf64c285a6b09febe6518a43e3230e69a83b65fa88dd898231893bb4ca0b` | `5ad587fb28bbdc1f093f25fd9c7c6b35ef8f16003e52515a3f4ff4cc9f20946b` |
| `ChatFactory.class` SHA-256 | `a740d1bd94132947647580b91768c67878033211621e3d281f386e2d6ea466e7` | `ebbaddfc4950b288dddfc3058852318a04be08c559f5d08c200ee5051771ba91` |
| `ChatFactory$SseLines.class` SHA-256 | 不存在 | `5e78363c7d4bb526a8b2ad8331a10a99b2a5ab0322f8a2b2e36222c60afceba3` |

将独立构建的供应商生产类与该冻结构件对比，只发现这两个条目被修改或新增。完整发行包哈希和归档条目对比在最终发布证据中另行记录；本模块对比不会将新字节归到旧构件哈希名下。

### 修正后完成的检查

| 检查 | 观测结果 |
| --- | --- |
| 所负责模块的干净构建套件 | 57 个供应商测试和 40 个对话测试；无断言失败、错误或跳过 |
| 供应商覆盖率 | 363/367 行（98.91%）；282/314 分支（89.81%） |
| 对话覆盖率 | 不变：416/434 行（95.85%）；297/360 分支（82.50%） |
| 保持连接的边界回归 | 文本和 DONE，各自覆盖 LF/CRLF/CR 及有/无 BOM；全部 12 种配置通过 |
| 额外重复 | 对两个保持连接的测试方法及有限响应体 SSE 边界套件运行五次，全部通过，其中包含额外 60 个保持连接配置 |

随后，显式启用的 CR 流式夹具运行了 **60.061915583 秒**，生产者按 100 事件/s 限速。它交付了 **6,000 个中的全部 6,000 个精确、有序 Unicode 文本块**。每个块写出后，生产者都等到客户端确认观测到该块，才写下一个事件；全部 6,000 次确认均到达。生产者在 `[DONE]\r\r` 后保持响应体打开，客户端无需生产者 EOF 即完成。其供应商截止时间为 120 秒，原始响应预算为 4 MiB。

首段文本在生产者启动 12.591667 ms 后到达，观测到的最大文本间隔为 51.422292 ms。这些是限速夹具中的工作站观测，不是延迟目标或饱和基准。[原始 CR 流式结果](../../../reports/deep/sse-cr-streaming.json) 记录了计数和计时。

按上文准备独立检出目录后复现：

```sh
mvn -f "$task_verify_dir/pom.xml" -B -Pcoverage \
  -pl ominicat-providers,ominicat-conversation -am clean verify

mvn -f "$task_verify_dir/pom.xml" -B -pl ominicat-providers -am \
  -Dtest=SseOpenStreamTest,SseBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -f "$task_verify_dir/pom.xml" -B -pl ominicat-providers -am \
  -Dtest=SseCrStreamingSoakIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dominicat.sse.soak.report="$task_verify_dir/cr-streaming-60s.json" test
```

`SseCrStreamingSoakIT` 需要显式选定，不属于普通短测试套件。本补充验证受影响的本地流式路径，不宣称又完成了新的 20 分钟发行包运行或真实云供应商测试。
