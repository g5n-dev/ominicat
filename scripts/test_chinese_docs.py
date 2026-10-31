"""Failure controls for accidental English navigation and damaged translations."""
from contextlib import ExitStack, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import chinese_docs as docs


class ChineseGraphTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for name, value in {'ROOT': self.root, 'INDEX': self.root / 'docs/zh-CN/index.md',
                            'CATALOG': self.root / 'docs/i18n/pages.json',
                            'FIGURES': self.root / 'docs/i18n/figures.json'}.items():
            self.stack.enter_context(patch.object(docs, name, value))
        self.stack.enter_context(patch.object(docs, 'inventory', lambda: ['README.md', 'docs/a.md', 'docs/b.md']))
        self.write('README.md', '# Project\n')
        self.write('README.zh-CN.md', '# 项目\n')
        self.write('docs/a.md', '# Guide\n\n[Limits](b.md#queue-limits)\n\n| Count |\n| --- |\n| 10 |\n\n```java\nint value = 1;\n```\n')
        self.write('docs/b.md', '# Limits\n\n## Queue limits\n\nUse `queueCapacity`.\n')
        self.write('docs/zh-CN/a.md', '# 指南\n\n[限制](b.md#queue-limits)\n\n| 数量 |\n| --- |\n| 10 |\n\n```java\nint value = 1;\n```\n')
        self.write('docs/zh-CN/b.md', '# 限制\n\n## 队列限制\n\n使用 `queueCapacity`。\n')
        self.write('docs/i18n/figures.json', json.dumps({'figures': []}))
        self.write('docs/assets/previews/manifest.json', json.dumps({'figures': []}))
        with redirect_stdout(io.StringIO()):
            docs.main(True)

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')

    def mutate(self, relative, before, after):
        path = self.root / relative
        text = path.read_text()
        self.assertIn(before, text)
        path.write_text(text.replace(before, after))

    def check(self):
        with redirect_stdout(io.StringIO()):
            docs.main(False)

    def test_source_links_are_routed_to_chinese_headings_and_examples_survive(self):
        text = (self.root / 'docs/zh-CN/a.md').read_text()
        self.assertIn('[限制](b.md#队列限制)', text)
        self.assertIn('int value = 1;', text)
        self.check()

    def test_routing_is_idempotent_and_keeps_raw_artifact_directories(self):
        self.write('reports/raw/sample.json', '{}')
        graph = docs.Graph(docs.inventory())
        self.assertEqual('../../reports/raw', graph.resolve('docs/a.md', '../../reports/raw'))
        before = (self.root / 'docs/zh-CN/a.md').read_text()
        with redirect_stdout(io.StringIO()):
            docs.main(True)
        self.assertEqual(before, (self.root / 'docs/zh-CN/a.md').read_text())

    def test_repository_markdown_url_uses_chinese_page_and_heading(self):
        graph = docs.Graph(docs.inventory())
        self.assertEqual('b.md#队列限制', graph.resolve('docs/a.md',
            'https://github.com/g5n-dev/ominicat/blob/abc123/docs/b.md#queue-limits'))

    def test_repository_english_url_is_rejected(self):
        self.mutate('docs/zh-CN/a.md', '(b.md#队列限制)',
            '(https://github.com/g5n-dev/ominicat/blob/main/docs/b.md#queue-limits)')
        with self.assertRaisesRegex(ValueError, 'escaped to English repository'):
            self.check()

    def figure_fixture(self):
        self.write('docs/assets/plot.svg', '<svg/>')
        self.write('docs/assets/zh-CN/plot.svg', '<svg/>')
        self.write('docs/assets/previews/plot.png', 'original')
        self.write('docs/assets/previews/plot-zh-CN.png', 'localized')
        self.write('docs/i18n/figures.json', json.dumps({'figures': [{
            'source': 'docs/assets/plot.svg', 'localized': 'docs/assets/zh-CN/plot.svg',
            'preview': 'docs/assets/previews/plot-zh-CN.png'}]}))
        self.write('docs/assets/previews/manifest.json', json.dumps({'figures': [{
            'source': 'docs/assets/plot.svg', 'preview': 'docs/assets/previews/plot.png'}]}))

    def test_english_preview_is_rejected_even_without_svg_link(self):
        self.figure_fixture()
        path = self.root / 'docs/zh-CN/a.md'
        path.write_text(path.read_text() + '\n![图片](../assets/previews/plot.png)\n')
        with self.assertRaisesRegex(ValueError, 'Untranslated preview'):
            self.check()

    def test_localized_preview_cannot_link_to_english_svg(self):
        self.figure_fixture()
        path = self.root / 'docs/zh-CN/a.md'
        path.write_text(path.read_text() + '\n[![图片](../assets/previews/plot-zh-CN.png)](../assets/plot.svg)\n')
        with self.assertRaisesRegex(ValueError, 'Untranslated figure'):
            self.check()

    def test_plain_english_internal_link_is_rejected(self):
        self.mutate('docs/zh-CN/a.md', '(b.md#队列限制)', '(../b.md#queue-limits)')
        with self.assertRaisesRegex(ValueError, 'escaped to English'):
            self.check()

    def test_broken_chinese_heading_is_rejected(self):
        self.mutate('docs/zh-CN/a.md', '#队列限制)', '#不存在)')
        with self.assertRaisesRegex(ValueError, 'Broken Chinese heading'):
            self.check()

    def test_modified_numeric_evidence_is_rejected(self):
        self.mutate('docs/zh-CN/a.md', '| 10 |', '| 11 |')
        with self.assertRaisesRegex(ValueError, 'table numbers changed'):
            self.check()

    def test_modified_executable_example_is_rejected(self):
        self.mutate('docs/zh-CN/a.md', 'int value = 1;', 'int value = 2;')
        with self.assertRaisesRegex(ValueError, 'Executable examples changed'):
            self.check()

    def test_modified_api_identifier_is_rejected(self):
        self.mutate('docs/zh-CN/b.md', '`queueCapacity`', '`queueCapacity2`')
        with self.assertRaisesRegex(ValueError, 'API/configuration literals changed'):
            self.check()

    def test_stale_source_is_rejected(self):
        path = self.root / 'docs/b.md'
        path.write_text(path.read_text() + '\nNew requirement.\n')
        with self.assertRaisesRegex(ValueError, 'English source changed'):
            self.check()

    def test_catalog_cannot_link_back_to_english(self):
        self.mutate('docs/zh-CN/index.md', '(a.md)', '(../a.md)')
        with self.assertRaisesRegex(ValueError, 'catalog link escaped'):
            self.check()


if __name__ == '__main__':
    unittest.main()
