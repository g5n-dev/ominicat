#!/usr/bin/env python3
"""Paired Linux JMH evidence for one bounded prompt-rendering path.

Use the same probe JAR with baseline/candidate runtime JARs first on the classpath.
No models, network, pipeline scheduling or real application data are used.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET

PROBE = 'io.ominicat.benchmarks.PromptCostProbe.render'
ORDER = ('baseline', 'candidate', 'candidate', 'baseline')
CASES = {(str(n), mode) for n in (32, 256, 10000) for mode in ('none', 'full', 'trimmed')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def command(args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def source(root):
    paths = sorted(p for component in ('ominicat-core', 'ominicat-conversation', 'ominicat-benchmarks')
                   for p in (root / component / 'src/main/java').rglob('*.java'))
    paths += [root / p for p in ('pom.xml', 'ominicat-core/pom.xml',
                                 'ominicat-conversation/pom.xml', 'ominicat-benchmarks/pom.xml')]
    files = {str(p.relative_to(root)): sha(p) for p in paths}
    return {'revision': command(['git', 'rev-parse', 'HEAD'], root), 'files': files}


def finite(value, label):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, 'Invalid ' + label)
    return value


def validate_rows(rows, runtime, probe_hash):
    require(isinstance(rows, list) and len(rows) == len(CASES), 'Incomplete JMH case matrix')
    result = {}
    for row in rows:
        key = (row.get('params', {}).get('maxTurns'), row.get('params', {}).get('history'))
        require(key in CASES and key not in result, 'Unknown or duplicate JMH case')
        require(row.get('benchmark') == PROBE and row.get('mode') == 'avgt', 'Wrong probe/mode')
        for field, expected in {'forks': 1, 'threads': 1, 'warmupIterations': 3,
                                'measurementIterations': 5, 'warmupTime': '1 s', 'measurementTime': '1 s'}.items():
            require(row.get(field) == expected, 'Wrong JMH setting: ' + field)
        require(str(row.get('jdkVersion', '')).split('.')[0] == '17', 'Probe needs Java 17')
        args = row.get('jvmArgs', [])
        expected_args = ('-Xms512m', '-Xmx512m',
            '-Dominicat.probe.conversationSha256=' + runtime['conversation_sha256'],
            '-Dominicat.probe.coreSha256=' + runtime['core_sha256'],
            '-Dominicat.probe.harnessSha256=' + probe_hash)
        require(isinstance(args, list) and len(args) == len(expected_args)
                and all(args.count(arg) == 1 for arg in expected_args), 'Runtime origin/heap binding missing')
        protected = ('-Xms', '-Xmx', '-XX:InitialHeapSize=', '-XX:MaxHeapSize=', '-Dominicat.probe.')
        require(all(arg in expected_args for arg in args if arg.startswith(protected)), 'Conflicting heap or origin setting')
        metrics = {}
        for name, metric, unit in (('micros', row.get('primaryMetric', {}), 'us/op'),
            ('bytes', row.get('secondaryMetrics', {}).get('gc.alloc.rate.norm', {}), 'B/op')):
            require(metric.get('scoreUnit') == unit, 'Wrong metric unit')
            raw = metric.get('rawData')
            require(isinstance(raw, list) and len(raw) == 1 and len(raw[0]) == 5, 'Incomplete measured iterations')
            for value in raw[0]:
                finite(value, name + ' sample')
            metrics[name] = finite(metric.get('score'), name + ' score')
            require(math.isclose(statistics.mean(raw[0]), metrics[name], rel_tol=1e-7, abs_tol=1e-7), 'Metric mean mismatch')
            metrics[name + '_samples'] = raw[0]
        result[key] = metrics
    return result


def check(folder):
    manifest = json.loads((folder / 'manifest.json').read_text())
    require(manifest.get('schemaVersion') == 1, 'Unknown evidence schema')
    for field in ('probe_sha256', 'collector_sha256'):
        require(isinstance(manifest.get(field), str) and re.fullmatch('[0-9a-f]{64}', manifest[field]), 'Invalid artifact binding')
    require(set(manifest['sources']) == {'baseline', 'candidate'} and set(manifest['runtimes']) == {'baseline', 'candidate'}, 'Missing source/runtime bindings')
    for side in ('baseline', 'candidate'):
        binding = manifest['sources'][side]
        require(re.fullmatch('[0-9a-f]{40}', binding['revision']), 'Missing source revision')
        files = binding['files']
        require(isinstance(files, dict) and all(name in files for name in ('pom.xml', 'ominicat-core/pom.xml',
            'ominicat-conversation/pom.xml', 'ominicat-benchmarks/pom.xml')), 'Incomplete source inventory')
        require(any(name.startswith('ominicat-conversation/src/main/java/') for name in files), 'Missing conversation sources')
        for name, digest in files.items():
            require(not Path(name).is_absolute() and '..' not in Path(name).parts
                    and isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest), 'Invalid source fingerprint')
        for component in ('conversation', 'core'):
            digest = manifest['runtimes'][side][component + '_sha256']
            require(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest), 'Invalid runtime fingerprint')
    require(manifest['order'] == list(ORDER), 'Wrong paired run order')
    require(manifest['context']['system'] == 'Linux' and manifest['context']['distribution']['ID'] == 'ubuntu'
            and manifest['context']['distribution']['VERSION_ID'] == '24.04', 'Wrong Linux distribution')
    require(len(manifest['runs']) == 4, 'Missing paired process groups')
    names = [entry[field] for entry in manifest['runs'] for field in ('json', 'log')]
    require(len(set(names)) == 8, 'Duplicated raw process evidence')
    groups = {'baseline': {}, 'candidate': {}}
    for index, entry in enumerate(manifest['runs']):
        side = ORDER[index]
        require(entry['side'] == side and entry['index'] == index, 'Mixed paired groups')
        for filename in (entry['json'], entry['log']):
            require(Path(filename).name == filename and sha(folder / filename) == manifest['sha256'][filename], 'Modified raw evidence')
        log = (folder / entry['log']).read_text()
        runtime = manifest['runtimes'][side]
        for component in ('conversation', 'core'):
            hashes = re.findall(f'PROBE_{component.upper()}_SHA256=([0-9a-f]{{64}})', log)
            require(hashes == [runtime[component + '_sha256']] * len(CASES), 'Fork runtime origin verification missing')
        hashes = re.findall('PROBE_HARNESS_SHA256=([0-9a-f]{64})', log)
        require(hashes == [manifest['probe_sha256']] * len(CASES), 'Fork probe origin verification missing')
        rows = validate_rows(json.loads((folder / entry['json']).read_text()), runtime, manifest['probe_sha256'])
        for key, values in rows.items():
            groups[side].setdefault(key, []).append(values)
    comparisons = []
    for key in sorted(CASES, key=lambda x: (int(x[0]), x[1])):
        row = dict(maxTurns=int(key[0]), history=key[1])
        for metric in ('micros', 'bytes'):
            for side in groups:
                means = [v[metric] for v in groups[side][key]]
                row[side + '_' + metric] = {'forkMeans': means, 'mean': statistics.mean(means),
                    'iterationRange': [min(x for v in groups[side][key] for x in v[metric + '_samples']),
                                       max(x for v in groups[side][key] for x in v[metric + '_samples'])]}
            before, after = row['baseline_' + metric]['mean'], row['candidate_' + metric]['mean']
            require(before > 0, 'Zero baseline metric')
            row[metric + '_reduction_percent'] = 100 * (1 - after / before)
            require(math.isfinite(row[metric + '_reduction_percent']), 'Nonfinite derived reduction')
        comparisons.append(row)
    return comparisons


def summarize(folder, rows):
    write(folder / 'comparison.json', rows)
    lines = ['# Paired prompt-rendering cost', '',
        'Ubuntu 24.04 / Java 17; same host, same probe; baseline → candidate → candidate → baseline.', '',
        'Synthetic final-transcript processing only: transcript/segment-ID construction, window insertion, prompt rendering, exact output oracle and Blackhole. '
        'Preloaded full windows and expected strings are prepared outside timing. No pipeline, model, codec or network.', '',
        'Two independent JVM forks per side/case; each fork warms 3 × 1 s and measures 5 × 1 s. '
        'Reported values average fork means; ranges below cover measured iteration means, not confidence intervals. '
        'Hosted-runner variability remains; no fixed performance gate or production-latency claim.', '',
        '| Turns | History | Baseline µs/op | Candidate µs/op | Time reduction | Baseline B/op | Candidate B/op | Allocation reduction |',
        '| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for row in rows:
        lines.append('| ' + ' | '.join((str(row['maxTurns']), row['history'],
            f"{row['baseline_micros']['mean']:.3f}", f"{row['candidate_micros']['mean']:.3f}", f"{row['micros_reduction_percent']:.1f}%",
            f"{row['baseline_bytes']['mean']:.1f}", f"{row['candidate_bytes']['mean']:.1f}", f"{row['bytes_reduction_percent']:.1f}%")) + ' |')
    lines += ['', 'Full fork means, measured iteration ranges and raw per-fork JMH output are in comparison.json and the four JMH files. '
              'Runtime JAR origins are checked inside every fork; manifests bind both revisions, sources, artifacts and raw outputs.', '']
    (folder / 'summary.md').write_text('\n'.join(lines), encoding='utf-8')


def chart_bytes(folder, rows):
    """Static, data-bound paired chart; ranges are iteration means, never CIs."""
    ns = 'http://www.w3.org/2000/svg'
    ET.register_namespace('', ns)
    root = ET.Element('svg', xmlns=ns, viewBox='0 0 1440 1050', role='img')
    def element(tag, attrs=None, text=None):
        item = ET.SubElement(root, tag, {k: str(v) for k, v in (attrs or {}).items()})
        if text is not None:
            item.text = text
        return item
    def label(x, y, text, size=18, fill='#9cafc6', **attrs):
        return element('text', dict(x=x, y=y, fill=fill, **{'font-size': size,
            'font-family': 'Inter,Arial,sans-serif'}, **attrs), text)
    element('rect', dict(width=1440, height=1050, rx=24, fill='#0b1320'))
    label(52, 57, 'OMINICAT / PAIRED LINUX EVIDENCE', 18, '#62d7c0')
    label(52, 113, 'Bounded prompt rendering', 40, '#e6edf7', **{'font-weight': 700})
    label(52, 153, 'Ubuntu 24.04 / Java 17 · same host · same probe · synthetic final transcripts', 18)
    for x, color, name in ((800, '#efbd67', 'Baseline'), (1010, '#62d7c0', 'Candidate')):
        element('circle', dict(cx=x, cy=197, r=6, fill=color)); label(x + 17, 203, name, 19, '#e6edf7')
    ordered = sorted(rows, key=lambda r: (('none', 'full', 'trimmed').index(r['history']), r['maxTurns']))
    policies = {'none': 'No history', 'full': 'Full history', 'trimmed': 'Truncated'}
    for panel, metric, title in ((0, 'micros', 'Time · µs/op · log scale'),
                                  (1, 'bytes', 'Allocation · B/op · log scale')):
        left, right, top = 210 + panel * 690, 640 + panel * 690, 280
        label(left, 248, title, 20, '#e6edf7')
        values = [v for r in ordered for side in ('baseline', 'candidate')
                  for v in r[side + '_' + metric]['iterationRange']]
        low = math.floor(math.log10(min(values))); high = math.ceil(math.log10(max(values)))
        require(high > low, 'Chart axis needs a positive range')
        position = lambda value: left + (math.log10(value) - low) / (high - low) * (right - left)
        for power in range(low, high + 1):
            x = position(10 ** power)
            element('path', dict(d=f'M {x:.4f} {top} V 856', stroke='#25344a', **{'stroke-width': 1}))
            label(x, 884, f'{10 ** power:g}', 15, **{'text-anchor': 'middle'})
        for index, row in enumerate(ordered):
            y = top + 30 + index * 62
            if panel == 0:
                label(52, y + 5, f"{policies[row['history']]} · {row['maxTurns']}", 16, '#d4dfea')
            for side, shift, color in (('baseline', -8, '#efbd67'), ('candidate', 8, '#62d7c0')):
                data = row[side + '_' + metric]; a, b = map(position, data['iterationRange']); x = position(data['mean'])
                element('path', dict(d=f'M {a:.4f} {y+shift} H {b:.4f}', stroke=color, **{'stroke-width': 2}))
                element('circle', dict(cx=f'{x:.4f}', cy=y+shift, r=4.5, fill=color))
                label(right + 12, y + shift + 4, f"{data['mean']:.2f}" if metric == 'micros' else f"{data['mean']:,.0f}", 14, color)
    label(52, 936, 'Dots: two-fork means · whiskers: min–max measured iteration means, not confidence intervals', 18)
    label(52, 972, '36 fork trials · 9 cases · 3 × 1 s warmup + 5 × 1 s measurement per fork · GC allocation profiler', 17)
    label(52, 1008, 'Includes transcript construction, window updates and exact output validation; excludes pipeline, model and network.', 16)
    element('metadata', {'id': 'ominicat-evidence'}, json.dumps({'comparison_sha256': sha(folder / 'comparison.json'),
        'manifest_sha256': sha(folder / 'manifest.json'), 'generator_sha256': sha(Path(__file__))}, sort_keys=True))
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


def collect(baseline, candidate, folder):
    require(platform.system() == 'Linux', 'Collect on Linux; local smoke tests are not publishable performance evidence')
    require(not folder.exists() or not any(folder.iterdir()), 'Refusing nonempty evidence output')
    folder.mkdir(parents=True, exist_ok=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    probe = candidate / 'ominicat-benchmarks/target/ominicat-benchmarks.jar'
    context = {'system': platform.system(), 'machine': platform.machine(), 'kernel': platform.release(),
        'distribution': platform.freedesktop_os_release(), 'java': command([java, '-version']),
        'availableProcessors': len(os.sched_getaffinity(0)), 'cpu': next((s.split(':', 1)[1].strip()
            for s in Path('/proc/cpuinfo').read_text().splitlines() if s.startswith('model name')), 'unknown')}
    manifest = {'schemaVersion': 1, 'order': list(ORDER), 'context': context,
        'probe_sha256': sha(probe), 'collector_sha256': sha(Path(__file__)), 'sources': {}, 'runtimes': {}, 'runs': [], 'sha256': {}}
    for side, root in (('baseline', baseline), ('candidate', candidate)):
        manifest['sources'][side] = source(root)
        manifest['runtimes'][side] = {component + '_sha256': sha(root / f'ominicat-{component}/target/ominicat-{component}-0.1.0-SNAPSHOT.jar')
                                    for component in ('core', 'conversation')}
    for index, side in enumerate(ORDER):
        root = baseline if side == 'baseline' else candidate
        runtime = manifest['runtimes'][side]
        classpath = os.pathsep.join(str(root / f'ominicat-{component}/target/ominicat-{component}-0.1.0-SNAPSHOT.jar')
                                   for component in ('conversation', 'core')) + os.pathsep + str(probe)
        jmh, log = f'{index}-{side}.json', f'{index}-{side}.log'
        fork_args = ' '.join(('-Xms512m', '-Xmx512m',
            '-Dominicat.probe.conversationSha256=' + runtime['conversation_sha256'],
            '-Dominicat.probe.coreSha256=' + runtime['core_sha256'],
            '-Dominicat.probe.harnessSha256=' + manifest['probe_sha256']))
        with (folder / log).open('w') as stream:
            subprocess.run([java, '-cp', classpath, 'org.openjdk.jmh.Main', PROBE,
                '-wi', '3', '-i', '5', '-w', '1s', '-r', '1s', '-f', '1', '-t', '1', '-foe', 'true',
                '-jvmArgsAppend', fork_args, '-prof', 'gc', '-rf', 'json', '-rff', str(folder / jmh)],
                stdout=stream, stderr=subprocess.STDOUT, check=True)
        manifest['runs'].append(dict(index=index, side=side, json=jmh, log=log))
        manifest['sha256'].update({name: sha(folder / name) for name in (jmh, log)})
        write(folder / 'manifest.json', manifest)
    require(sha(probe) == manifest['probe_sha256'] and sha(Path(__file__)) == manifest['collector_sha256'], 'Probe/collector changed during measurement')
    for side, root in (('baseline', baseline), ('candidate', candidate)):
        require(source(root) == manifest['sources'][side], 'Source changed during measurement')
        require(all(sha(root / f'ominicat-{component}/target/ominicat-{component}-0.1.0-SNAPSHOT.jar')
                    == manifest['runtimes'][side][component + '_sha256'] for component in ('conversation', 'core')), 'Runtime changed during measurement')
    rows = check(folder)
    summarize(folder, rows)
    print(f'Validated {len(rows)} paired prompt cases, 36 fork trials, exact output and runtime origin checks.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    run = commands.add_parser('collect')
    run.add_argument('--baseline', type=Path, required=True)
    run.add_argument('--candidate', type=Path, required=True)
    run.add_argument('--output', type=Path, required=True)
    verify = commands.add_parser('check')
    verify.add_argument('folder', type=Path)
    render = commands.add_parser('render')
    render.add_argument('folder', type=Path)
    args = parser.parse_args()
    try:
        if args.action == 'collect':
            collect(args.baseline.resolve(), args.candidate.resolve(), args.output.resolve())
        elif args.action == 'check':
            folder = args.folder.resolve()
            rows = check(folder)
            if (folder / 'comparison.json').exists():
                require(json.loads((folder / 'comparison.json').read_text()) == rows, 'Stale derived comparison')
            if (folder / 'costs.svg').exists():
                require((folder / 'costs.svg').read_bytes() == chart_bytes(folder, rows), 'Stale paired cost chart')
            print(f'Validated {len(rows)} paired prompt cases.')
        else:
            folder = args.folder.resolve()
            rows = check(folder)
            summarize(folder, rows)
            (folder / 'costs.svg').write_bytes(chart_bytes(folder, rows))
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')
