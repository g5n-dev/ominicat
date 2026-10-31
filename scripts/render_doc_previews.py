#!/usr/bin/env python3
"""Publish PNG previews; --check needs only Python's standard library.

Rendering requires librsvg's rsvg-convert (Ubuntu: librsvg2-bin; macOS: librsvg).
SVGs remain the editable originals and retain their diagnostic hover labels.
"""
import argparse
from collections import Counter
import hashlib
import json
import re
import shutil
import struct
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'docs/assets/previews'
MANIFEST = OUTPUT / 'manifest.json'
SCALE = 2
UBUNTU = 'reports/comparison/linux-ubuntu24-java17-2026-10-31'
SOURCES = {
    'banner': 'docs/assets/banner.svg',
    'architecture-overview': 'docs/assets/architecture-overview.svg',
    'execution-path': 'docs/assets/execution-path.svg',
    'bounded-runtime': 'docs/assets/bounded-runtime.svg',
    'framework-paths': 'docs/assets/framework-paths.svg',
    'framework-cost-scaling': 'docs/assets/framework-cost-scaling.svg',
    'prompt-render-cost': 'reports/performance-audit/prompt-render-2026-11-01/costs.svg',
    'ubuntu-comparison': f'{UBUNTU}/overview.svg',
    'centos-comparison': 'reports/comparison/linux-centos9-java17-2026-10-31/overview.svg',
    **{f'ubuntu-{name}': f'{UBUNTU}/profiles/ominicat/{name}.svg'
       for name in ('cpu-hotspots', 'allocation-hotspots', 'gc-pauses',
                    'cpu-flamegraph', 'allocation-flamegraph')},
}
SOURCES.update({Path(item['preview']).stem: item['localized']
                for item in json.loads((ROOT / 'docs/i18n/figures.json').read_text())['figures']})
DOCUMENTS = ('README.md', 'README.zh-CN.md', 'docs/architecture.md', 'docs/performance.md', 'docs/performance-audit.md',
             *sorted(str(path.relative_to(ROOT)) for path in (ROOT / 'docs/zh-CN').rglob('*.md')))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dimensions(source):
    root = ET.parse(source).getroot()
    # Some plot exporters use physical pt units; use their unitless viewBox.
    return [round(float(value) * SCALE) for value in root.attrib['viewBox'].split()[2:]]


def render():
    renderer = shutil.which('rsvg-convert')
    if not renderer:
        raise ValueError('Install librsvg2-bin (Ubuntu) or librsvg (macOS) to render previews.')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    entries = []
    for name, relative in SOURCES.items():
        source, preview = ROOT / relative, OUTPUT / f'{name}.png'
        width, height = dimensions(source)
        subprocess.run([renderer, '--width', str(width), '--height', str(height), '--output', str(preview),
                        str(source)], check=True)
        entries.append({'source': relative, 'source_sha256': sha256(source),
                        'preview': str(preview.relative_to(ROOT)),
                        'preview_sha256': sha256(preview), 'pixels': dimensions(source)})
    MANIFEST.write_text(json.dumps({'scale': SCALE,
        'renderer': subprocess.check_output([renderer, '--version'], text=True).strip(),
        'figures': entries}, indent=2) + '\n', encoding='utf-8')


def check():
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    if manifest['scale'] != SCALE or len(manifest['figures']) != len(SOURCES):
        raise ValueError('Preview inventory changed; regenerate previews.')
    expected = {(source, f'docs/assets/previews/{name}.png') for name, source in SOURCES.items()}
    actual = {(item['source'], item['preview']) for item in manifest['figures']}
    if actual != expected:
        raise ValueError('Preview source mapping changed; regenerate previews.')
    for item in manifest['figures']:
        source, preview = ROOT / item['source'], ROOT / item['preview']
        if sha256(source) != item['source_sha256'] or sha256(preview) != item['preview_sha256']:
            raise ValueError(f'Stale or modified preview: {preview.relative_to(ROOT)}; regenerate previews.')
        header = preview.read_bytes()[:24]
        if header[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR':
            raise ValueError(f'Invalid PNG header: {preview}')
        if list(struct.unpack('>II', header[16:24])) != item['pixels'] or item['pixels'] != dimensions(source):
            raise ValueError(f'Incorrect preview dimensions: {preview}')
    for relative in DOCUMENTS:
        document = ROOT / relative
        text = document.read_text(encoding='utf-8')
        if re.search(r'^\s*(`{3,}|~{3,})\s*mermaid\b', text, re.M | re.I):
            raise ValueError(f'Publish a static diagram instead of a Mermaid fence: {relative}')
        images = re.findall(r'!\[[^\]]*\]\(([^\s)]+)\)', text)
        images += re.findall(r'<img\b[^>]*\bsrc=["\']([^"\']+)', text)
        pairs = re.findall(r'\[!\[[^\]]*\]\(([^\s)]+)\)\]\(([^\s)]+)\)', text)
        pairs += [(image, source) for source, image in re.findall(
            r'<a\b[^>]*\bhref=["\']([^"\']+)["\'][^>]*>\s*<img\b[^>]*\bsrc=["\']([^"\']+)', text)]
        originals = {(ROOT / item['preview']).resolve(): (ROOT / item['source']).resolve()
                     for item in manifest['figures']}
        linked = Counter()
        for image, source in pairs:
            preview = (document.parent / image).resolve()
            if preview in originals:
                if (document.parent / source).resolve() != originals[preview]:
                    raise ValueError(f'Preview links to the wrong SVG original: {relative}: {image}')
                linked[image] += 1
        for image in images:
            if image.startswith(('https://', 'http://')):
                continue
            target = document.parent / image
            if not target.is_file() or target.suffix.lower() == '.svg':
                raise ValueError(f'Missing image or SVG without PNG preview: {relative}: {image}')
            if target.resolve() in originals:
                if linked[image] == 0:
                    raise ValueError(f'Preview needs a link to its SVG original: {relative}: {image}')
                linked[image] -= 1
    print(f'Checked {len(SOURCES)} source-bound PNG previews and {len(DOCUMENTS)} documents.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate committed assets without a renderer.')
    args = parser.parse_args()
    try:
        if not args.check:
            render()
        check()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')
