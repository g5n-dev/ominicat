#!/usr/bin/env python3
"""Route and verify the complete Chinese documentation graph.

--sync is a maintainer operation after translating pages; the default/--check
is read-only and needs only the standard library. Archived originals are never
rewritten. Public API identifiers, executable examples and evidence stay intact.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'docs/i18n/pages.json'
FIGURES = ROOT / 'docs/i18n/figures.json'
INDEX = ROOT / 'docs/zh-CN/index.md'
START, END = '<!-- ominicat-language-nav -->', '<!-- /ominicat-language-nav -->'
README_ALIASES = {
    'linux-performance-on-java-17': 'linux-框架性能实测',
    'ubuntu-2404--native-x86_64': 'linux-框架性能实测',
    'centos-stream-9--container-user-space': 'linux-框架性能实测',
    'microkernel-architecture': '小内核可组合的能力',
    'why-the-execution-path-stays-small': '高性能从哪里来',
    'bounded-work-and-cancellation': '高性能从哪里来',
    'linux-profiles': 'linux-cpu分配与-gc', 'profiles': 'linux-cpu分配与-gc',
    'quick-start': '先运行起来', 'a-kernel-with-replaceable-capabilities': '小内核可组合的能力',
    'configure-a-pipeline': '配置管线', 'embed-the-runtime': '嵌入-java-应用',
    'add-a-plugin': '开发插件', 'measure-what-runs': '复跑与验证',
    'documentation': '继续阅读', 'license': '继续阅读',
}
ARTIFACT_REDIRECTS = {
    f'reports/comparison/java17-2026-10-31/profiles/{engine}/gc.log':
        f'reports/comparison/java17-2026-10-31/profiles/{engine}/gc-pauses.svg'
    for engine in ('ominicat', 'camel')
}


def strip_nav(text):
    text = re.sub(re.escape(START) + r'.*?' + re.escape(END) + r'\n*', '', text, flags=re.S)
    text = re.sub(r'^<p[^>]*>[^\n]*(?:Languages / 语言|简体中文（当前）)[^\n]*</p>\n*', '', text)
    return text.lstrip('\n')


def chunks(text):
    """Yield outside/inside fence chunks without touching commands or examples."""
    inside, fence, length, buffer = False, '', 0, []
    for line in text.splitlines(keepends=True):
        marker = re.match(r'^\s{0,3}(`{3,}|~{3,})(.*)$', line)
        if marker and not inside:
            if buffer:
                yield False, ''.join(buffer)
            buffer, inside, fence, length = [line], True, marker[1][0], len(marker[1])
        elif marker and inside and marker[1][0] == fence and len(marker[1]) >= length and not marker[2].strip():
            buffer.append(line)
            yield True, ''.join(buffer)
            buffer, inside = [], False
        else:
            buffer.append(line)
    if inside:
        raise ValueError('Unclosed Markdown fence')
    if buffer:
        yield False, ''.join(buffer)


def slug(text):
    text = re.sub(r'<[^>]+>', '', text)
    text = re.sub(r'!?\[([^]]+)\]\([^)]*\)', r'\1', text).replace('`', '')
    text = ''.join(c for c in text.lower() if c in '-_' or not unicodedata.category(c).startswith(('P', 'S')))
    return re.sub(r'\s', '-', text.strip())


def headings(text):
    result, seen = [], Counter()
    for code, block in chunks(strip_nav(text)):
        if code:
            continue
        for line in block.splitlines():
            match = re.match(r'^(#{1,6})\s+(.*?)\s*#*$', line)
            if match:
                base = slug(match[2]); suffix = seen[base]; seen[base] += 1
                result.append((len(match[1]), base + (f'-{suffix}' if suffix else ''), match[2]))
    return result


def inventory():
    tracked = subprocess.check_output(['git', 'ls-files', '*.md'], cwd=ROOT, text=True).splitlines()
    return sorted(p for p in tracked if not p.startswith('docs/zh-CN/') and p != 'README.zh-CN.md')


def tracked_files():
    return set(subprocess.check_output(['git', 'ls-files'], cwd=ROOT, text=True).splitlines())


def translated(source):
    if source == 'README.md':
        return 'README.zh-CN.md'
    if source == 'AGENTS.md':
        return 'docs/zh-CN/agent-workflow.md'
    return 'docs/zh-CN/' + (source[5:] if source.startswith('docs/') else source)


def language_nav(path, original, chinese, is_chinese):
    relative = lambda p: os.path.relpath(ROOT / p, path.parent)
    if is_chinese:
        body = f'**简体中文** · [English]({relative(original)}) · [中文文档目录]({os.path.relpath(INDEX, path.parent)})'
    else:
        body = f'[简体中文]({relative(chinese)}) · **English**'
    return f'{START}\n{body}\n{END}\n\n'


class Graph:
    def __init__(self, sources):
        self.pairs = {source: translated(source) for source in sources}
        self.originals = {value: key for key, value in self.pairs.items()}
        self.chinese = {str((ROOT / value).resolve()) for value in self.pairs.values()} | {str(INDEX)}
        self.heading_map, self.ids = {}, {}
        for source, target in self.pairs.items():
            src = headings((ROOT / source).read_text(encoding='utf-8'))
            dst = headings((ROOT / target).read_text(encoding='utf-8'))
            self.ids[target] = {h[1] for h in dst}
            if source == 'README.md':
                self.heading_map[source] = README_ALIASES
            else:
                if [h[0] for h in src] != [h[0] for h in dst]:
                    raise ValueError(f'Incomplete heading structure: {target}')
                self.heading_map[source] = {a[1]: b[1] for a, b in zip(src, dst)}
        self.figures, self.local_assets = {}, set()
        for item in json.loads(FIGURES.read_text(encoding='utf-8'))['figures']:
            self.figures[item['source']] = item
            self.local_assets.update((str(ROOT / item['localized']), str(ROOT / item['preview'])))
        previews = json.loads((ROOT / 'docs/assets/previews/manifest.json').read_text())['figures']
        for item in previews:
            if item['source'] in self.figures:
                self.figures[item['preview']] = self.figures[item['source']]

    def fragment(self, source, fragment):
        fragment = unquote(fragment)
        target = self.pairs[source]
        if fragment in self.ids[target]:
            return fragment
        return self.heading_map[source].get(fragment, fragment)

    def resolve(self, source, url, image=False):
        parsed = urlsplit(url)
        repository_page = re.fullmatch(r'/g5n-dev/ominicat/blob/[^/]+/(.+\.md)', unquote(parsed.path))
        if parsed.netloc == 'github.com' and repository_page and repository_page[1] in self.pairs:
            original = repository_page[1]
            value = os.path.relpath(ROOT / self.pairs[original], (ROOT / self.pairs[source]).parent)
            return value + ('#' + self.fragment(original, parsed.fragment) if parsed.fragment else '')
        if parsed.scheme or parsed.netloc or url.startswith('//'):
            return url
        destination = ROOT / self.pairs[source]
        path, fragment = unquote(parsed.path), parsed.fragment
        if not path:
            return '#' + self.fragment(source, fragment) if fragment else url
        candidate = (destination.parent / path).resolve()
        if str(candidate) in self.chinese or str(candidate) in self.local_assets:
            resolved = candidate
        elif candidate.exists() and candidate.suffix != '.md':
            resolved = candidate
        else:
            resolved = ((ROOT / source).parent / path).resolve()
        try:
            relative = resolved.relative_to(ROOT).as_posix()
        except ValueError:
            raise ValueError(f'Link escapes repository: {source}: {url}')
        relative = ARTIFACT_REDIRECTS.get(relative, relative)
        if relative in self.originals and fragment:
            fragment = self.fragment(self.originals[relative], fragment)
        if (ROOT / relative).is_dir() and (ROOT / relative / 'README.md').is_file():
            relative += '/README.md'
        if relative in self.pairs:
            if fragment:
                fragment = self.fragment(relative, fragment)
            relative = self.pairs[relative]
        elif relative in self.figures:
            figure = self.figures[relative]
            relative = figure['preview' if image else 'localized']
        value = os.path.relpath(ROOT / relative, destination.parent)
        return value + ('?' + parsed.query if parsed.query else '') + ('#' + fragment if fragment else '')

    def route(self, source, text):
        def wrapper(m):
            return f'[![{m[1]}]({self.resolve(source,m[2],True)})]({self.resolve(source,m[3])})'
        def image(m):
            image_url = self.resolve(source, m[2], True)
            original = self.resolve(source, m[2])
            return f'[![{m[1]}]({image_url})]({original})' if image_url != original else f'![{m[1]}]({image_url})'
        def link(m):
            return f'[{m[1]}]({self.resolve(source,m[2])})'
        result = []
        for code, block in chunks(strip_nav(text)):
            if not code:
                block = re.sub(r'\[!\[([^]]*)\]\(([^\s)]+)\)\]\(([^\s)]+)\)', wrapper, block)
                block = re.sub(r'(?<!\[)!\[([^]]*)\]\(([^\s)]+)\)', image, block)
                block = re.sub(r'(?<!!)\[([^\[\]]+)\]\(([^\s)]+)\)', link, block)
                block = re.sub(r'\b(href|src)="([^"]+)"', lambda m: f'{m[1]}="{self.resolve(source,m[2],m[1]=="src")}"', block)
            result.append(block)
        return ''.join(result)


def urls(text):
    for code, block in chunks(strip_nav(text)):
        if code:
            continue
        for match in re.finditer(r'\[!\[[^]]*\]\([^\s)]+\)\]\(([^\s)]+)\)', block):
            yield match[1]
        for match in re.finditer(r'!?\[[^]]*\]\(([^\s)]+)\)|\b(?:href|src)="([^"]+)"', block):
            yield match[1] or match[2]


def table_numbers(text):
    rows = []
    for code, block in chunks(strip_nav(text)):
        if code:
            continue
        for line in block.splitlines():
            if line.lstrip().startswith('|'):
                # Destinations change with locale; visible numbers are evidence.
                visible = re.sub(r'\]\([^)]*\)', ']', line)
                values = re.findall(r'\d+(?:[.,]\d+)*(?:[eE][+-]?\d+)?', visible)
                if values:
                    rows.append(sorted(values))
    return rows


def inline_code(text):
    return Counter(value for code, block in chunks(strip_nav(text)) if not code
                   for value in re.findall(r'(?<!`)`([^`\n]+)`(?!`)', block))


def check(manifest, graph):
    if set(graph.pairs) != set(inventory()):
        raise ValueError('Chinese document inventory is incomplete; update the page catalog.')
    recorded = {item['source']: item for item in manifest['documents']}
    published = tracked_files()
    links = 0
    for source, target in graph.pairs.items():
        original = strip_nav((ROOT / source).read_text(encoding='utf-8'))
        text = strip_nav((ROOT / target).read_text(encoding='utf-8'))
        if hashlib.sha256(original.encode()).hexdigest() != recorded[source]['source_sha256']:
            raise ValueError(f'English source changed without refreshed translation: {source}')
        if source != 'README.md':
            if [block for code, block in chunks(original) if code] != [block for code, block in chunks(text) if code]:
                raise ValueError(f'Executable examples changed during translation: {target}')
            if table_numbers(original) != table_numbers(text):
                raise ValueError(f'Evidence/table numbers changed during translation: {target}')
            if inline_code(original) != inline_code(text):
                raise ValueError(f'API/configuration literals changed during translation: {target}')
        for url in urls(text):
            parsed = urlsplit(url)
            if parsed.netloc == 'github.com' and re.fullmatch(r'/g5n-dev/ominicat/blob/[^/]+/.+\.md', unquote(parsed.path)):
                raise ValueError(f'Chinese navigation escaped to English repository page: {target}: {url}')
            if parsed.scheme or parsed.netloc or url.startswith('//'):
                continue
            path = (ROOT / target).parent / unquote(parsed.path) if parsed.path else ROOT / target
            path = path.resolve(); links += 1
            if not path.exists():
                raise ValueError(f'Missing Chinese-page link: {target}: {url}')
            relative = path.relative_to(ROOT).as_posix()
            prefix = '' if relative == '.' else relative.rstrip('/') + '/'
            if (path.is_file() and relative not in published) or (path.is_dir() and
                    not any(name.startswith(prefix) for name in published)):
                raise ValueError(f'Untracked Chinese-page link: {target}: {url}; stage the published artifact.')
            if path.suffix == '.md':
                if str(path) not in graph.chinese:
                    raise ValueError(f'Chinese navigation escaped to English: {target}: {url}')
                if parsed.fragment and unquote(parsed.fragment) not in {h[1] for h in headings(path.read_text())}:
                    raise ValueError(f'Broken Chinese heading link: {target}: {url}')
            if path.suffix == '.svg' and str(path) not in graph.local_assets:
                raise ValueError(f'Untranslated figure linked from Chinese page: {target}: {url}')
            if path.suffix == '.png' and path.relative_to(ROOT).as_posix() in graph.figures and str(path) not in graph.local_assets:
                raise ValueError(f'Untranslated preview linked from Chinese page: {target}: {url}')
    for url in urls(INDEX.read_text(encoding='utf-8')):
        parsed = urlsplit(url)
        if parsed.scheme or parsed.netloc:
            continue
        target = (INDEX.parent / unquote(parsed.path)).resolve()
        if not target.is_file() or str(target) not in graph.chinese or target.relative_to(ROOT).as_posix() not in published:
            raise ValueError(f'Chinese catalog link escaped or is missing: {url}')
        links += 1
    print(f'Checked {len(graph.pairs)} Chinese document pairs, executable examples, table numbers and {links} local links.')


def index(graph):
    groups = [('使用指南', lambda p: p.startswith('docs/') and p != 'docs/i18n/README.md'),
              ('模块、扩展与部署验证', lambda p: p.startswith(('ominicat-', 'examples/', 'validation/'))),
              ('性能对比与复跑', lambda p: p.startswith('comparison/')),
              ('Linux 实测与诊断报告', lambda p: p.startswith('reports/comparison/linux-') or p == 'reports/comparison/README.md'),
              ('验证与历史记录（保留原测试环境）', lambda p: p.startswith('reports/') and not p.startswith('reports/comparison/linux-') and p != 'reports/comparison/README.md'),
              ('贡献与维护', lambda p: p in ('CONTRIBUTING.md', 'SECURITY.md', 'AGENTS.md', 'docs/i18n/README.md'))]
    parts = ['# 中文文档目录', '', '[项目首页](../../README.zh-CN.md)', '',
             '所有内部说明页均有中文版本；代码标识符、配置键、原始数值和历史测试环境保持其技术含义。', '']
    for label, select in groups:
        parts += [f'## {label}', '']
        for source, target in graph.pairs.items():
            if select(source):
                title = headings((ROOT / target).read_text())[0][2]
                context = source.removesuffix('/README.md').removesuffix('.md')
                parts.append(f'- [{title} · {context}]({os.path.relpath(ROOT / target, INDEX.parent)})')
        parts.append('')
    INDEX.parent.mkdir(parents=True, exist_ok=True)
    INDEX.write_text('\n'.join(parts), encoding='utf-8')


def main(sync):
    graph = Graph(inventory())
    if sync:
        for source, target in graph.pairs.items():
            path = ROOT / target
            body = graph.route(source, path.read_text(encoding='utf-8'))
            path.write_text(language_nav(path, source, target, True) + body, encoding='utf-8')
            if not source.startswith('reports/') and source != 'AGENTS.md':
                original = ROOT / source
                original.write_text(language_nav(original, source, target, False) + strip_nav(original.read_text()), encoding='utf-8')
        index(graph)
        manifest = {'schemaVersion': 1, 'documents': [{'source': source, 'translation': target,
            'source_sha256': hashlib.sha256(strip_nav((ROOT / source).read_text()).encode()).hexdigest()}
            for source, target in graph.pairs.items()]}
        CATALOG.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    else:
        manifest = json.loads(CATALOG.read_text())
    check(manifest, graph)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sync', action='store_true', help='Route completed translations, add language navigation and refresh source hashes.')
    parser.add_argument('--check', action='store_true', help='Read-only verification (default).')
    args = parser.parse_args()
    try:
        main(args.sync)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f'{error}\n')
