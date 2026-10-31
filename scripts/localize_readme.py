#!/usr/bin/env python3
"""Generate source-bound Chinese SVG figures; --check is read-only.

English evidence and data geometry remain untouched. Matplotlib glyph labels
are recovered from their original comments; numeric glyph ticks stay intact.
PNG rendering is separate and requires librsvg plus a Chinese font.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'docs/i18n/zh-CN.json'
INDEX = ROOT / 'docs/i18n/figures.json'
FONT = 'Noto Sans CJK SC,Hiragino Sans GB,Microsoft YaHei,sans-serif'
SVG = 'http://www.w3.org/2000/svg'
XLINK = 'http://www.w3.org/1999/xlink'
LANG = '{http://www.w3.org/XML/1998/namespace}lang'
ET.register_namespace('', SVG)
ET.register_namespace('xlink', XLINK)
IDENTIFIERS = {'OminiCat', 'g5n-dev / ominicat', 'ominicat-server', 'ominicat-core',
               'ominicat-conversation', 'ominicat-providers', 'Processor', 'FrameSink',
               'Processor / AsyncProcessor', 'SPI', 'Apache Camel', 'Pipecat',
               'Netty NIO · HTTP / WebSocket',
               'Apache Camel 4.22.2', 'Pipecat 1.15.0', '.',
               'Pipeline.process(frame, sink)', 'openSession(...) → trySubmit(frame)',
               'AsyncEmitter.tryEmit(frame)', 'ReentrantLock + ArrayDeque',
               'M = maxFrameBytes'}
NUMBER = r'[\d,]+(?:\.\d+)?'
CODE = re.compile(r'^[\w.$<>\[\];/+\-]+(?:…)?$')


def tag(node):
    return node.tag.rsplit('}', 1)[-1] if isinstance(node.tag, str) else ''


def figures():
    tracked = subprocess.check_output(['git', 'ls-files', '*.svg'], cwd=ROOT, text=True).splitlines()
    result = []
    for source in sorted(p for p in tracked if '/zh-CN/' not in p and not p.startswith('reports/local/')):
        path = Path(source)
        if source.startswith('docs/assets/'):
            localized = 'docs/assets/zh-CN/' + str(path.relative_to('docs/assets'))
            preview = 'docs/assets/previews/' + path.stem + '-zh-CN.png'
        elif source.startswith('reports/'):
            localized = 'docs/assets/zh-CN/' + source
            preview = 'docs/assets/previews/' + source[:-4].replace('/', '--') + '-zh-CN.png'
        else:
            raise ValueError(f'Unmapped source figure: {source}')
        result.append(dict(source=source, localized=localized, preview=preview))
    if not result:
        raise ValueError('No tracked source figures found')
    for field in ('source', 'localized', 'preview'):
        if len({f[field] for f in result}) != len(result):
            raise ValueError(f'Duplicate figure index field: {field}')
    return dict(schemaVersion=1, figures=result)


def protected_numbers(text):
    return re.findall(r'\d+(?:[.,]\d+)*', text)


def is_code(text):
    return (text in IDENTIFIERS or
            bool(re.fullmatch(r'\[+[BCDFIJSZ]', text)) or
            bool(re.fullmatch(r'\$\\mathdefault\{[\d{}^.+−×\-]+\}\$', text)) or
            bool(CODE.fullmatch(text) and ('.' in text or '$' in text or '_' in text
                 or re.search(r'[a-z][A-Z]', text))) or
            bool(re.fullmatch(r'(?:[\w.]+ \u00b7 )?__[\w]+__', text)) or
            bool(re.fullmatch(r'[\w.]+ \u00b7 [\w]+', text)) or
            bool(re.fullmatch(r'<(?:built-in method [\w.]+|method \'[\w]+\' of \'[\w.]+\' objects)>', text)) or
            text.startswith('Matplotlib v') and 'https://matplotlib.org/' in text)


def translate(text, catalog, code_context=False):
    if text in catalog:
        result = catalog[text]
    else:
        result = None
        weighted = re.fullmatch(r'(.+): (' + NUMBER + r')(?:( sampled bytes| samples| MiB))?( \([\d.]+%\))?', text)
        if weighted:
            prefix, count, unit, share = weighted.groups()
            if prefix in catalog or is_code(prefix):
                result = translate(prefix, catalog) + ': ' + count
                if unit:
                    result += {' sampled bytes': ' 个采样字节', ' samples': ' 个样本', ' MiB': ' MiB'}[unit]
                result += share or ''
        for rule in catalog.get('@patterns', []):
            if result is None and re.fullmatch(rule['pattern'], text):
                result = re.sub(rule['pattern'], rule['replacement'], text)
                break
        if result is None:
            # Native method/class identities are data, including clipped labels
            # whose enclosing tooltip carries the complete code identity.
            if is_code(text) or code_context and CODE.fullmatch(text):
                result = text
            elif re.fullmatch(r'[\d\s+.,%e−×\-]+', text):
                result = text
            else:
                raise ValueError(f'Missing Chinese translation: {text}')
    if not isinstance(result, str) or protected_numbers(text) != protected_numbers(result):
        raise ValueError(f'Translation changed numeric/version labels: {text}')
    return result


def geometry(root):
    """Protect all non-text geometry/attributes, including stack/data weights."""
    records = []
    def visit(node):
        if tag(node) in ('metadata', 'text', 'tspan', 'title', 'desc') or not tag(node):
            return
        if tag(node) == 'g' and re.fullmatch(r'text_\d+', node.get('id', '')):
            return
        attrs = {k: v for k, v in node.attrib.items() if k != LANG}
        records.append((node.tag, sorted(attrs.items()), (node.text or '').strip()))
        for child in node:
            visit(child)
    visit(root)
    return hashlib.sha256(json.dumps(records, ensure_ascii=False).encode()).hexdigest()


def use_x(node):
    match = re.search(r'translate\(([-\d.]+)', node.get('transform', ''))
    return float(match[1]) if match else float(node.get('x', '0'))


def glyph_labels(root, catalog):
    """Replace only readable glyph groups, retaining hidden font definitions.

    Original advance positions locate the old label center. Hiding the old
    glyph run keeps shared glyph IDs valid for unchanged numeric tick groups.
    """
    advances = {}
    groups = [n for n in root.iter() if tag(n) == 'g' and re.fullmatch(r'text_\d+', n.get('id', ''))]
    for group in groups:
        uses = [n for n in group.iter() if tag(n) == 'use']
        for current, following in zip(uses, uses[1:]):
            delta = use_x(following) - use_x(current)
            if delta > 0:
                advances.setdefault(current.get(f'{{{XLINK}}}href'), []).append(delta)
    replaced = 0
    for group in groups:
        comments = [n for n in group if n.tag is ET.Comment]
        if not comments:
            continue
        original = comments[0].text.strip()
        translated = translate(original, catalog)
        if translated == original:
            continue
        run = next((n for n in group if tag(n) == 'g' and n.get('transform')), None)
        if run is None or not re.search(r'scale\([\d.]+ -[\d.]+\)', run.get('transform', '')):
            raise ValueError(f'Unsupported glyph label placement: {original}')
        uses = [n for n in run.iter() if tag(n) == 'use']
        if not uses:
            raise ValueError(f'Glyph label has no character positions: {original}')
        last = uses[-1]
        width = use_x(last) + statistics.median(advances.get(last.get(f'{{{XLINK}}}href'), [60]))
        run.set('display', 'none')
        run.set('aria-hidden', 'true')
        native = ET.SubElement(group, f'{{{SVG}}}g', {'transform': run.get('transform'), 'data-localized-glyph': 'true'})
        text = ET.SubElement(native, f'{{{SVG}}}text', {
            'x': f'{width / 2:.6f}', 'y': '0', 'transform': 'scale(1 -1)',
            'font-size': '100', 'font-family': FONT, 'text-anchor': 'middle',
            'fill': run.get('fill', '#111111')})
        text.text = translated
        comments[0].text = ' ' + translated + ' '
        replaced += 1
    return replaced


def localized(figure, catalog):
    source = (ROOT / figure['source']).read_bytes()
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.fromstring(source, parser=parser)
    original_geometry = geometry(root)
    root.set(LANG, 'zh-CN')
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in list(root.iter()):
        if tag(node) not in ('text', 'tspan', 'title', 'desc') or not node.text or not node.text.strip():
            continue
        original = node.text
        parent = parents.get(node)
        titles = [n.text for n in parent if tag(n) == 'title' and n.text] if parent is not None else []
        context = any(is_code(t.split(': ', 1)[0]) for t in titles)
        node.text = translate(original, catalog, context)
        if tag(node) in ('text', 'tspan') and node.text != original:
            node.set('font-family', FONT)
            node.set('style', node.get('style', '').rstrip(';') + ';font-family:' + FONT)
    recovered = glyph_labels(root, catalog)
    if geometry(root) != original_geometry:
        raise ValueError(f'Translation changed data geometry: {figure["source"]}')
    metadata = ET.SubElement(root, f'{{{SVG}}}metadata', {'id': 'ominicat-locale-source'})
    metadata.text = json.dumps({'language': 'zh-CN', 'source': figure['source'],
        'sha256': hashlib.sha256(source).hexdigest(), 'geometrySha256': original_geometry,
        'recoveredGlyphLabels': recovered}, ensure_ascii=False, sort_keys=True)
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


def run(check):
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    index = figures()
    if check:
        if json.loads(INDEX.read_text(encoding='utf-8')) != index:
            raise ValueError('Stale Chinese figure index; regenerate translations.')
    else:
        INDEX.write_text(json.dumps(index, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    pending = []
    # Validate every source before writing any localized figure.
    for figure in index['figures']:
        expected = localized(figure, catalog)
        path = ROOT / figure['localized']
        if check:
            if not path.is_file() or path.read_bytes() != expected:
                raise ValueError(f'Stale Chinese figure: {figure["localized"]}; regenerate translations.')
        else:
            pending.append((path, expected))
    for path, expected in pending:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(expected)
    print(f'Checked {len(index["figures"])} Chinese figures; source hashes, numeric/version labels and data geometry preserved.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        run(args.check)
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')
