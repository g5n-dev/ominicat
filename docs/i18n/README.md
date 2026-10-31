# README languages / README 语言

[简体中文](../../README.zh-CN.md) · [English](../../README.md)

The two READMEs cover installation, configuration, embedding, plugin development and Linux performance. The Chinese edition also has a localized hero, composition diagram and framework-path diagram. Detailed component guides and original diagnostic figures currently remain primarily English; API identifiers and stack names retain their original spelling.

中英文 README 均覆盖安装、配置、嵌入、插件开发与 Linux 性能。中文页提供中文横幅、模块组成图与框架执行路径图。深入组件文档和原始诊断图目前主要为英文；API 标识符与调用栈名称保持原文。

Edit [zh-CN.json](zh-CN.json), then run from the repository root:

```sh
python3 scripts/localize_readme.py
python3 scripts/render_doc_previews.py
```

PNG rendering needs `rsvg-convert` and a Chinese font (`fonts-noto-cjk` on Ubuntu). CI's standard-library `--check` commands require neither. They reject changed English source figures without refreshed translations, missing phrases, changed numeric/version labels and stale previews. Translations change text and font selection; frame geometry, measured values and original report artifacts are preserved.

编辑翻译表后执行上述命令。绘制 PNG 时需安装 librsvg 与中文字体；CI 校验只依赖 Python 标准库，检查英文源图、翻译表、数字及预览的一致性。
