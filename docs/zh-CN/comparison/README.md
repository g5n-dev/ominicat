<!-- ominicat-language-nav -->
**简体中文** · [English](../../../comparison/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 可复现、无模型调用的框架对比

这套可选工具通过原生排队公开 API，对比 OminiCat、Pipecat **1.15.0** 和 Apache Camel **4.22.2 LTS**，不会增加生产依赖。[测量协议](protocol.md) 在测量前固定数值计算、精确输出校验、计时、预热、独立进程和资源范围。

主矩阵为 **3 个引擎 × 3 种阶段数 × 5 个全新进程 = 45 份结果**。同一时刻只有一帧在途，帧率衡量的是这一循环，而非饱和容量。对比不混入直接同步分发。Pipecat 是多模态框架对照，Camel 是 Java 路由框架对照；运行时、帧元数据和调度器的不同成本都包含在内。

## 复现

使用 POSIX 主机（macOS 或 Linux）、实际的 Java 17 JDK 和 CPython 3.12。在同一主机上串行构建和测量。选择新的输出目录，工具不会静默覆盖已有证据。

```sh
# JAVA_HOME must point to a Java 17 JDK.
./mvnw -B -ntp -pl ominicat-core -am install -DskipTests
./mvnw -B -ntp -f comparison/benchmarks/pom.xml clean verify
python3.12 -m venv reports/local/framework-env
reports/local/framework-env/bin/python -m pip install -r comparison/requirements.lock
reports/local/framework-env/bin/python comparison/run.py \
  --java "$JAVA_HOME/bin/java" \
  --python reports/local/framework-env/bin/python \
  --output reports/local/framework-run
reports/local/framework-env/bin/python comparison/report.py \
  reports/local/framework-run reports/comparison/my-java17-run
```

每个全新进程预热 3 秒、测量 5 秒。加上启动时间，完整矩阵需要数分钟。计时执行不需要供应商凭据、模型调用或网络操作；初次安装依赖会使用 Maven Central 和 PyPI。Pipecat 可选的 RTVI、轮次跟踪、指标、用量、心跳和追踪功能均显式关闭；普通阶段排队行为和原生输出事件钩子保持启用。

快速接线检查使用同一采集器，执行一次重复、阶段数设为 4，并使用 `--warmup 0.2 --measure 0.3`。它验证原生 API 和清理行为，但发布器拒绝将冒烟时长或不完整矩阵转成主性能图。

## 证据与失败行为

- 采集器记录精确的源码树和构件哈希、请求设置、轮换执行顺序、所选运行时版本、已安装 Python 依赖清单，以及已安装 Pipecat 源码哈希；采集后再次检查源码、构件和依赖状态。
- 终端校验器检查每个数值输出，包括序号、变换结果、阶段、原始计时绑定和样本位置。失败、重复、缺失、拒绝或未完成清理的输出都会使整个运行失败。
- 预触页的 16 MiB 基本类型缓冲区最多保存 2,000,000 个精确延迟样本。样本溢出会报失败，不会截断后仍判成功。最近秩分位数在计时结束后计算。
- 父进程对所有框架使用相同的操作系统 RSS 采样器，间隔为 50 ms。整个进程与测量阶段的峰值分开记录，缺失样本不会被记成零。RSS 包括 JVM/Python、依赖和公共缓冲区，不代表 B/op 或保留堆。
- 必须具备全部 45 个进程的结果、唯一且未经修改的原始文件，以及一份共享清单。缺失或重复用例、清单变动、版本不符、无效时长、非有限数值、清理失败及速率不一致都会阻止发布。五个进程的取值范围不是置信区间。
- 只发布最终 JSON、清单、图表、报告和校验和。私有诊断日志、环境、JAR、原始 JFR 和 Python 分析器转储保留在本地。

同主机报告不宣称普遍领先，也不宣称生命周期或背压行为等价、音频质量、饱和容量或生产部署规模。分析器运行单独采集，不能替代未插桩的计时。现有同步 JMH 和大规模并发负载结果仍保留原先的适用范围。

## 独立诊断分析

未插桩矩阵结束后，再执行以下插桩诊断：

```sh
reports/local/framework-env/bin/python comparison/profile_java.py \
  --java "$JAVA_HOME/bin/java" --output reports/local/framework-java-profiles
reports/local/framework-env/bin/python comparison/profile_python.py \
  --output reports/local/framework-python-profile
reports/local/framework-env/bin/python comparison/package_diagnostics.py \
  --suite reports/local/framework-run \
  --java-input reports/local/framework-java-profiles \
  --python-input reports/local/framework-python-profile \
  --output reports/comparison/my-java17-run/profiles
# Refresh the report links and all-file checksums after adding profiles:
reports/local/framework-env/bin/python comparison/report.py \
  reports/local/framework-run reports/comparison/my-java17-run
```

Java JFR 覆盖整个诊断进程，包括启动和预热；Pipecat cProfile 仅采集测量期间的事件循环。各自图表解释各自观测，调用自身耗时、原生 CPU 样本和分配权重不能放在同一比较轴上。发布器在复制前检查采集标志、源码与构件绑定、允许发布文件的哈希和隐私。已发布的插桩结果明确不能进入主对比；原始记录、私有路径和诊断日志均排除在外。

## Linux CI

[对比工作流](../../../.github/workflows/framework-comparison.yml) 会在修改内核、对比工具或相关构建/工作流输入的拉取请求，以及受保护 main 的推送上自动运行，也支持手动触发。每个目标都运行完整的 **45 进程矩阵**，随后分别采集 JFR/cProfile 诊断、校验报告，并按允许清单发布。

| 目标 | Java / Python | 执行环境 |
| --- | --- | --- |
| Ubuntu 24.04 x86_64 | Temurin 17 / CPython 3.12 | GitHub 托管的原生 Ubuntu runner |
| CentOS Stream 9 x86_64 | 发行版 OpenJDK 17 / CPython 3.12 RPMs | Ubuntu 24.04 runner 上按摘要固定的官方 CentOS 容器；共享 runner 内核 |

预检核对实际 `/etc/os-release`、架构和声明的执行目标。清单将这些上下文与被测检出源码绑定，并记录 CPU 型号、libc、亲和性、可读取的 cgroup-v2 限额、实际运行时版本和工作流运行 URL。操作系统报告的主机内存与容器内存限额分开记录。资源限额为 null 表示无限制或不可获取。这是经过核对的执行元数据，不是远程证明。

每个作业的摘要展示已校验的四阶段中位数。下载其 `framework-<target>-java17-<run>-<attempt>` 构件，可获得全部 SVG/PNG 图表、每份原始结果、源码/运行时/依赖清单、校验和，以及独立的 CPU/分配/GC 或调用分析。构件保留 30 天，不含原始记录、环境和私有日志。矩阵或诊断发布失败时，不会上传成功的性能构件。

每个作业在同一 runner 上顺序测量三个框架。两个平台作业使用独立主机，因此结果是两组独立观测，不是隔离发行版或内核差异的受控实验。CentOS 覆盖验证其用户空间和原生依赖，不验证 CentOS 内核。托管 runner 的波动不会自动替换已记录的 Mac 基线，也不能据此建立性能门槛。

## 检查

```sh
python3 -m unittest discover -s comparison/tests -v
python3 -m unittest discover -s comparison/pipecat -p 'test_*.py' -v
python3 -m unittest discover -s comparison/profile_tests -p 'test_*.py' -v
```

独立 Java 项目包含两个引擎的原生测试。无额外依赖的 Python 测试覆盖计算/输出校验器、证据和 Linux 来源校验的失败对照；Pipecat 原生行为由实际进程运行另行检查。CI 采集自己的 Linux 证据，不改变已记录的本地主性能结果。
