#!/usr/bin/env python3
"""Publish only verified diagnostic summaries/figures; never raw recordings or logs."""
import argparse
import gzip
import html
import json
import re
from pathlib import Path
import shutil
import sys

from evidence import canonical, digest, load_suite, validate_result

ROOT = Path(__file__).resolve().parent.parent
JAVA_FILES = ('report.md', 'profiles.md', 'cpu-flamegraph.svg', 'allocation-flamegraph.svg',
              'cpu-hotspots.svg', 'allocation-hotspots.svg', 'gc-pauses.svg', 'jfr-events.json.gz',
              'load.json', 'result.json', 'jfr-summary.txt')
PYTHON_FILES = ('summary.json', 'summary.csv', 'profile.md')


def checked_file(folder, name):
    path = folder / name
    if path.is_symlink() or not path.is_file():
        raise ValueError('diagnostic input must be a regular allowlisted file')
    return path


def private_text_check(name, data):
    sys.path.insert(0, str(ROOT / 'scripts'))
    from package_profiles import privacy_check
    return privacy_check(name, html.unescape(data.decode('utf-8')).encode('utf-8'))


def validate_java(folder, engine, artifact, settings):
    context_path = checked_file(folder, 'diagnostic-summary.json')
    context = json.loads(private_text_check('diagnostic context', context_path.read_bytes()))
    if context.get('status') != 'complete' or context.get('headlineTiming') is not False or context.get('engine') != engine:
        raise ValueError('invalid diagnostic context')
    if context.get('benchmarkArtifactSha256') != artifact or context.get('kind') != 'separate-profiled-diagnostics':
        raise ValueError('diagnostic artifact does not match the measured suite')
    validate_result(context['result'], {'engine': engine, 'stages': 4}, settings)
    for name in JAVA_FILES:
        path = checked_file(folder, name)
        if digest(path.read_bytes()) != context.get('artifactSha256', {}).get(name):
            raise ValueError('diagnostic file changed after collection')
        if name != 'jfr-events.json.gz':
            private_text_check(name, path.read_bytes())
    sys.path.insert(0, str(ROOT / 'scripts'))
    from package_profiles import EVENTS, parse_json, privacy_check
    events = parse_json('diagnostic selected events', privacy_check('diagnostic selected events', gzip.decompress((folder / 'jfr-events.json.gz').read_bytes())))
    if any(event.get('type') not in EVENTS for event in events['recording']['events']):
        raise ValueError('unapproved diagnostic event category')
    return context


def validate_python(folder, suite):
    for name in PYTHON_FILES:
        private_text_check(name, checked_file(folder, name).read_bytes())
    context = json.loads((folder / 'summary.json').read_text())
    expected = suite['manifest']['sourceFingerprint']['files']['comparison/pipecat/benchmark.py']
    if context.get('engine') != 'pipecat' or context.get('version') != '1.15.0' or context.get('adapterSha256') != expected:
        raise ValueError('Python diagnostic source mismatch')
    if context.get('instrumented') is not True or context.get('headlineEligible') is not False:
        raise ValueError('instrumented profile must be excluded from headline evidence')
    if context.get('capture', {}).get('phases') != ['warmup', 'measure', 'teardown']:
        raise ValueError('incomplete Python profile capture')
    if any(context.get('capture', {}).get(name) is not False for name in ('startupIncluded', 'warmupIncluded', 'teardownIncluded')):
        raise ValueError('Python capture must exclude startup, warmup and teardown')
    if not context.get('topSelf') or context.get('totalSelfSeconds', 0) <= 0:
        raise ValueError('empty Python call profile')
    for row in context['topSelf'] + context['topCumulative']:
        if not isinstance(row.get('file'), str) or row['file'].startswith(('/', '\\')) or re.match(r'^[A-Za-z]:', row['file']) or '\\' in row['file'] or '..' in row['file'].split('/'):
            raise ValueError('unsafe Python code location')
    return context


def plot_python(context, destination):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    rows = context['topSelf'][:12]
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'svg.fonttype': 'none', 'svg.hashsalt': context['adapterSha256']})
    fig, ax = plt.subplots(figsize=(12, 6.8), facecolor='#0b1220')
    ax.set_facecolor('#111d2e')
    labels = []
    for row in rows:
        label = row['function']
        if row['file'].startswith('site-packages/pipecat/'):
            label = row['file'].split('/')[-1] + ' · ' + label
        elif row['file'].startswith('stdlib/'):
            label = row['file'].removeprefix('stdlib/').split('.')[0].replace('/', '.') + ' · ' + label
        labels.append(label if len(label) <= 74 else label[:71] + '…')
    ax.barh(range(len(rows)), [row['selfSeconds'] for row in rows], height=.6, color='#76adf7')
    ax.set_yticks(range(len(rows)), labels, color='#c6d4e6', fontsize=10)
    ax.invert_yaxis(); ax.tick_params(axis='x', colors='#98abc2'); ax.tick_params(length=0)
    for spine in ax.spines.values(): spine.set_color('#2a3a50')
    ax.spines[['top', 'right']].set_visible(False)
    ax.grid(axis='x', color='#2a3a50', alpha=.5); ax.set_axisbelow(True)
    ax.set_xlabel('Instrumented self time (seconds); includes system calls / waiting', color='#98abc2', labelpad=12)
    ax.set_title('Pipecat / Python call profile', color='#e4edf8', loc='left', pad=30, fontsize=21, fontweight='bold')
    fig.text(.125, .91, 'Measurement window only · cProfile · separate diagnostic run', color='#98abc2', fontsize=11)
    fig.text(.125, -.025, 'These are deterministic call timings, not native CPU samples or an allocation / B-op comparison.', color='#98abc2', fontsize=10)
    for extension in ('svg', 'png'):
        path = destination / ('call-hotspots.' + extension)
        fig.savefig(path, dpi=180, bbox_inches='tight', pad_inches=.25,
                    facecolor=fig.get_facecolor(), metadata={'Date': context['recordedAt']})
        if extension == 'svg':
            path.write_text('\n'.join(line.rstrip() for line in path.read_text().splitlines()).rstrip() + '\n')
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, required=True)
    parser.add_argument('--java-input', type=Path, required=True)
    parser.add_argument('--python-input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    suite = load_suite(args.suite)
    settings = suite['manifest']['settings']
    contexts = {engine: validate_java(args.java_input / engine, engine, suite['manifest']['javaArtifactSha256'], settings)
                for engine in ('ominicat', 'camel')}
    python_context = validate_python(args.python_input, suite)
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('choose an empty diagnostic publication folder')
    args.output.mkdir(parents=True, exist_ok=True)
    for engine in contexts:
        destination = args.output / engine; destination.mkdir()
        for name in JAVA_FILES + ('diagnostic-summary.json',):
            shutil.copyfile(args.java_input / engine / name, destination / name)
            if Path(name).suffix in ('.md', '.txt', '.csv', '.svg'):
                text = (destination / name).read_text()
                if name == 'profiles.md':
                    text = text.replace('[GC log](gc.log)', '[GC pause timeline](gc-pauses.svg)')
                (destination / name).write_text('\n'.join(line.rstrip() for line in text.splitlines()).rstrip() + '\n')
        # Preserve capture hashes while making every published standalone result
        # explicitly ineligible for the unprofiled headline matrix.
        context = json.loads(json.dumps(contexts[engine]))
        context.setdefault('sourceCapturedArtifacts', context['artifactSha256'])
        context['publicationInputArtifacts'] = context['artifactSha256']
        context['publicationPostprocessorSha256'] = digest(Path(__file__).read_bytes())
        context['result'].update({'instrumented': True, 'headlineEligible': False, 'profileType': 'JFR'})
        (destination / 'result.json').write_text(json.dumps(context['result'], indent=2) + '\n')
        context['artifactSha256'] = {name: digest((destination / name).read_bytes()) for name in JAVA_FILES}
        (destination / 'diagnostic-summary.json').write_text(json.dumps(context, indent=2) + '\n')
    destination = args.output / 'pipecat'; destination.mkdir()
    for name in PYTHON_FILES:
        shutil.copyfile(args.python_input / name, destination / name)
        if Path(name).suffix in ('.md', '.csv'):
            text = (destination / name).read_text()
            (destination / name).write_text('\n'.join(line.rstrip() for line in text.splitlines()).rstrip() + '\n')
    plot_python(python_context, destination)
    (args.output / 'README.md').write_text('''# Separate diagnostic profiles

These recordings explain their own instrumented workload. They do not replace any of the 45 unprofiled timing results. Java uses sampled JFR across process startup, warmup, measurement and teardown; Python uses deterministic cProfile only in the measured event-loop phase. Percentages and allocation observations have different scopes and must not be ranked on a common cross-language axis.

## OminiCat / Java 17

![OminiCat sampled CPU leaves](ominicat/cpu-hotspots.svg)

[CPU and allocation stacks, class weights and GC](ominicat/profiles.md) · [Collection context](ominicat/diagnostic-summary.json)

## Apache Camel / Java 17

![Camel sampled CPU leaves](camel/cpu-hotspots.svg)

[CPU and allocation stacks, class weights and GC](camel/profiles.md) · [Collection context](camel/diagnostic-summary.json)

## Pipecat / CPython 3.12

![Pipecat instrumented call self times](pipecat/call-hotspots.svg)

[Call statistics and collection scope](pipecat/profile.md) · [Safe JSON](pipecat/summary.json)

Python imports, startup, warmup and teardown are outside its capture; Python/native exposed call timing includes waits and system calls and does not resolve native implementation internals. Java's whole-recording samples include initialization and the benchmark harness. Sampled allocation weights are not retained heap or exact B/op. Zero events do not prove absence. Raw JFR, Python profiler dumps, uncompressed exports and diagnostic logs remain local.

[Main unprofiled framework comparison](../README.md).
''')
    files = [p for p in args.output.rglob('*') if p.is_file()]
    (args.output / 'SHA256SUMS').write_text(''.join(f'{digest(p.read_bytes())}  {p.relative_to(args.output).as_posix()}\n' for p in sorted(files)))
    print(f'Published {len(files)} allowlisted diagnostic files; raw recordings and logs excluded.')


if __name__ == '__main__':
    main()
