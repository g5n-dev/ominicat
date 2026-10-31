#!/usr/bin/env python3
"""Generate the Chinese README's native SVG figures; --check is read-only.

Keep API identifiers and numeric evidence unchanged. Rendering the resulting
PNGs requires librsvg and a Chinese font, e.g. fonts-noto-cjk on Ubuntu.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'docs/assets'
CATALOG = ROOT / 'docs/i18n/zh-CN.json'
FIGURES = ('banner', 'architecture-overview', 'framework-paths')
IDENTIFIERS = {'OminiCat', 'g5n-dev / ominicat', 'ominicat-server', 'ominicat-core',
               'ominicat-conversation', 'ominicat-providers', 'Netty NIO · HTTP / WebSocket',
               'Processor / AsyncProcessor', 'SPI', 'Apache Camel 4.22.2', 'Pipecat 1.15.0'}
FONT = 'Noto Sans CJK SC,Hiragino Sans GB,Microsoft YaHei,sans-serif'
SVG = 'http://www.w3.org/2000/svg'
ET.register_namespace('', SVG)


def localized(name, catalog):
    source = (ASSETS / f'{name}.svg').read_bytes()
    root = ET.fromstring(source)
    root.set('{http://www.w3.org/XML/1998/namespace}lang', 'zh-CN')
    for node in root.iter():
        if node.tag.rsplit('}', 1)[-1] not in ('text', 'tspan', 'title', 'desc') or not node.text:
            continue
        if node.text in catalog:
            if re.findall(r'\d+(?:\.\d+)*', node.text) != re.findall(r'\d+(?:\.\d+)*', catalog[node.text]):
                raise ValueError(f'Translation changed numeric/version labels in {name}: {node.text}')
            node.text = catalog[node.text]
            node.set('font-family', FONT)
        elif node.text not in IDENTIFIERS and node.text != '.':
            raise ValueError(f'Missing Chinese translation in {name}: {node.text}')
    metadata = ET.SubElement(root, f'{{{SVG}}}metadata', {'id': 'ominicat-locale-source'})
    metadata.text = json.dumps({'language': 'zh-CN', 'source': f'docs/assets/{name}.svg',
        'sha256': hashlib.sha256(source).hexdigest()}, ensure_ascii=False, sort_keys=True)
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


def run(check):
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    for name in FIGURES:
        expected = localized(name, catalog)
        path = ASSETS / 'zh-CN' / f'{name}.svg'
        if check:
            if path.read_bytes() != expected:
                raise ValueError(f'Stale Chinese figure: {path.relative_to(ROOT)}; regenerate translations.')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    print(f'Checked {len(FIGURES)} Chinese figures against English sources and translation catalog.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        run(args.check)
    except (OSError, ValueError) as error:
        parser.exit(1, f'{error}\n')
