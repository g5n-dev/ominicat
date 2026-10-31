<!-- ominicat-language-nav -->
[简体中文](../zh-CN/i18n/README.md) · **English**
<!-- /ominicat-language-nav -->

# Documentation languages

The Chinese documentation catalog covers project guides, module descriptions, deployment/verification instructions and archived report narratives. Chinese pages link to their Chinese counterparts, including translated figures. API identifiers, configuration keys, method names, raw evidence and the recorded operating-system/runtime context retain their original meaning.

[Chinese catalog](../zh-CN/index.md) · [Page source bindings](pages.json) · [Figure source bindings](figures.json)

Edit the paired Chinese page when changing an English guide. Preserve heading order and levels, executable code blocks and technical literals; translate prose, table headings and explanatory image text completely. Review the meaning before refreshing source bindings. Automated checks cannot certify semantic translation quality.

From the repository root, after completing translations:

```sh
python3 scripts/localize_readme.py
python3 scripts/render_doc_previews.py
python3 scripts/chinese_docs.py --sync
```

PNG rendering requires librsvg and a Chinese font, such as `fonts-noto-cjk` on Ubuntu. Read-only CI checks need only the Python standard library:

```sh
python3 scripts/localize_readme.py --check
python3 scripts/render_doc_previews.py --check
python3 scripts/chinese_docs.py --check
```

The checks cover source freshness, page inventory, heading/code/table preservation, Chinese-to-Chinese internal links, fragments and localized figure/preview bindings. Explicit language-switch links lead to English; source files, external references and raw data remain technical artifacts. English files inside `reports/` are immutable evidence and receive no injected navigation bars. Their Chinese reading views live separately under `docs/zh-CN/reports/`.
