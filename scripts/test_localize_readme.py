"""Failure controls for source-bound Chinese SVG labels and glyph recovery."""
from contextlib import ExitStack, redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import localize_readme as locale


SVG = 'http://www.w3.org/2000/svg'
SOURCE = '''<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
  <title>Queued requests 64</title>
  <g id="line2d_1"><path d="M 1 2 L 17 38" stroke="#123456" fill="none"/></g>
  <text x="10" y="20">Queued requests 64</text>
</svg>'''


def parse(data):
    return ET.fromstring(data, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))


def plotted_geometry(root):
    """Independent oracle: compare the actual plotted series and patch attributes."""
    result = []
    for group in root.iter(f'{{{SVG}}}g'):
        if group.get('id', '').startswith(('line2d_', 'patch_', 'PolyCollection_', 'PathCollection_')):
            shapes = [(node.tag, tuple(sorted(node.attrib.items()))) for node in group.iter()
                      if node.tag in {f'{{{SVG}}}{name}' for name in ('path', 'use', 'rect', 'polygon', 'polyline')}]
            result.append((tuple(sorted(group.attrib.items())), shapes))
    return result


class TranslationFailureTest(unittest.TestCase):
    def test_unknown_human_label_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing Chinese translation'):
            locale.translate('New unreviewed caption', {})

    def test_catalog_cannot_change_numbers_or_versions(self):
        for original, replacement in (
                ('Processed frames 64', '已处理帧 65'),
                ('Pipecat 1.15.0', 'Pipecat 1.15.1')):
            with self.subTest(original=original):
                with self.assertRaisesRegex(ValueError, 'numeric/version'):
                    locale.translate(original, {original: replacement})

    def test_pattern_cannot_change_numeric_evidence(self):
        catalog = {'@patterns': [{'pattern': r'Total: (\d+) samples', 'replacement': '总计: 999 个样本'}]}
        with self.assertRaisesRegex(ValueError, 'numeric/version'):
            locale.translate('Total: 64 samples', catalog)

    def test_known_weighted_labels_preserve_count_and_share(self):
        self.assertEqual(locale.translate('Heap allocation: 1,024 sampled bytes (12.5%)',
                                          {'Heap allocation': '堆分配'}),
                         '堆分配: 1,024 个采样字节 (12.5%)')
        self.assertEqual(locale.translate('Pipecat 1.15.0', {'Pipecat 1.15.0': 'Pipecat 1.15.0'}),
                         'Pipecat 1.15.0')


class SourceFreshnessTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        stack = ExitStack()
        self.addCleanup(stack.close)
        self.figure = {'source': 'source.svg', 'localized': 'zh-CN/source.svg', 'preview': 'preview.png'}
        for name, value in {'ROOT': self.root, 'CATALOG': self.root / 'catalog.json',
                            'INDEX': self.root / 'index.json'}.items():
            stack.enter_context(patch.object(locale, name, value))
        stack.enter_context(patch.object(locale, 'figures', lambda: {'schemaVersion': 1, 'figures': [self.figure]}))
        (self.root / 'source.svg').write_text(SOURCE, encoding='utf-8')
        (self.root / 'catalog.json').write_text(json.dumps({'Queued requests 64': '排队请求 64'}), encoding='utf-8')
        with redirect_stdout(io.StringIO()):
            locale.run(False)

    def test_check_rejects_stale_target_after_source_geometry_changes(self):
        target = self.root / self.figure['localized']
        before = target.read_bytes()
        source = self.root / 'source.svg'
        source.write_text(SOURCE.replace('L 17 38', 'L 17 39'), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Stale Chinese figure'):
            locale.run(True)
        self.assertEqual(target.read_bytes(), before, '--check must not rewrite the stale target')

    def test_check_rejects_edited_target_and_does_not_repair_it(self):
        target = self.root / self.figure['localized']
        damaged = target.read_bytes().replace('排队请求 64'.encode(), '排队请求 65'.encode())
        self.assertNotEqual(damaged, target.read_bytes())
        target.write_bytes(damaged)
        with self.assertRaisesRegex(ValueError, 'Stale Chinese figure'):
            locale.run(True)
        self.assertEqual(target.read_bytes(), damaged)


class GlyphRecoveryTest(unittest.TestCase):
    def test_three_archived_soaks_restore_labels_without_changing_plotted_data(self):
        repository = Path(__file__).resolve().parents[1]
        catalog = json.loads((repository / 'docs/i18n/zh-CN.json').read_text(encoding='utf-8'))
        captions = ('Validated frames/s', 'Rate-limited workload progress',
                    'OminiCat constrained-memory mixed-media soak')
        for relative in ('reports/deep/soak-resources.svg', 'reports/java17/soak-resources.svg',
                         'reports/java17/http-cancellation/soak-resources.svg'):
            with self.subTest(source=relative):
                source = repository / relative
                original = source.read_bytes()
                original_hash = hashlib.sha256(original).hexdigest()
                before = parse(original)
                with patch.object(locale, 'ROOT', repository):
                    translated = locale.localized({'source': relative}, catalog)
                after = parse(translated)
                original_series = plotted_geometry(before)
                self.assertTrue(original_series, 'the geometry oracle must inspect real plotted data')
                self.assertEqual(plotted_geometry(after), original_series)
                self.assertEqual(locale.geometry(after), locale.geometry(before))
                old_groups = {n.get('id'): n for n in before.iter(f'{{{SVG}}}g')}
                new_groups = {n.get('id'): n for n in after.iter(f'{{{SVG}}}g')}
                for caption in captions:
                    matches = [n for n in old_groups.values()
                               if any(c.tag is ET.Comment and c.text.strip() == caption for c in n)]
                    self.assertEqual(len(matches), 1, caption)
                    group = new_groups[matches[0].get('id')]
                    recovered = [n for n in group if n.get('data-localized-glyph') == 'true']
                    self.assertEqual(len(recovered), 1, caption)
                    self.assertEqual(recovered[0].find(f'{{{SVG}}}text').text, catalog[caption])
                    hidden = [n for n in group if n.get('display') == 'none']
                    self.assertEqual(len(hidden), 1)
                    self.assertEqual(hidden[0].get('aria-hidden'), 'true')
                # Numeric glyph ticks and their original font references must survive unchanged.
                ticks = [n for n in old_groups.values() if any(c.tag is ET.Comment and
                         c.text.strip().replace('.', '').isdigit() for c in n)]
                self.assertTrue(ticks)
                for tick in ticks:
                    self.assertEqual(ET.tostring(new_groups[tick.get('id')]), ET.tostring(tick))
                metadata = after.find(f'{{{SVG}}}metadata[@id="ominicat-locale-source"]')
                self.assertEqual(json.loads(metadata.text)['sha256'], original_hash)
                self.assertEqual(source.read_bytes(), original, 'archival English evidence must remain read-only')


if __name__ == '__main__':
    unittest.main()
