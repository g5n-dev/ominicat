<!-- ominicat-language-nav -->
**简体中文** · [English](../../i18n/README.md) · [中文文档目录](../index.md)
<!-- /ominicat-language-nav -->

# 文档语言

中文文档目录覆盖项目指南、模块说明、部署与验证步骤，以及归档报告的完整说明。中文页面之间互相链接，并展示中文图表。API 标识符、配置键、方法名、原始证据和记录中的操作系统／运行时环境保持原有技术含义。

[中文目录](../index.md) · [页面来源绑定](../../i18n/pages.json) · [图表来源绑定](../../i18n/figures.json)

修改英文指南时，应同步修改对应中文版。保留标题顺序与层级、可执行代码块和技术字面量，完整翻译正文、表头和图中说明。刷新来源绑定前应审查翻译含义；自动检查不能证明语义翻译质量。

完成翻译后，在仓库根目录执行：

```sh
python3 scripts/localize_readme.py
python3 scripts/render_doc_previews.py
python3 scripts/chinese_docs.py --sync
```

绘制 PNG 需要 librsvg 和中文字体，例如 Ubuntu 的 `fonts-noto-cjk`。CI 的只读检查只需要 Python 标准库：

```sh
python3 scripts/localize_readme.py --check
python3 scripts/render_doc_previews.py --check
python3 scripts/chinese_docs.py --check
```

检查覆盖来源新鲜度、页面清单、标题／代码／表格保留、中文页面内部跳转、章节锚点，以及中文图表与预览绑定。明确标为语言切换的链接进入英文；源码、外部参考和原始数据仍属于技术资料。`reports/` 下英文文件是不可变证据，不注入导航栏；完整中文阅读版单独放在 `docs/zh-CN/reports/` 下。
