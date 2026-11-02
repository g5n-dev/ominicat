#!/usr/bin/env python3
"""Collect/check separate pipeline-construction diagnostics bound to a complete main experiment."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('direct_plan_primary', Path(__file__).with_name('direct_state_evidence.py'))
primary = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(primary)
common = primary.common
require, digest, canonical = primary.require, primary.digest, primary.canonical
read_bytes, read_json, write_json = primary.read_bytes, primary.read_json, primary.write_json

PROTOCOL = 'ominicat-direct-plan-startup-v1'
BENCHMARK = 'io.ominicat.benchmarks.DirectPlanStartupProbe.invoke'
SCENARIOS = ('build-4', 'first-direct-4')
TOOLS = ('comparison/direct_plan_startup.py', 'comparison/tests/test_direct_plan_startup.py',
         'ominicat-benchmarks/src/main/java/io/ominicat/benchmarks/DirectPlanStartupProbe.java')
SCOPE = ('Separate pipeline-construction and first-direct-call diagnostics in warmed-up Java 17 JVMs. '
         'Every operation builds a fresh four-stage pipeline. This is not OS/JVM cold-start latency, '
         'a main acceptance case, a saturated-load capacity measurement or a main-result correction.')


def plan():
    rows = []
    for scenario in SCENARIOS:
        for slot, side in enumerate(primary.ABBA, 1):
            position = len(rows) + 1
            name = f'{position:03d}-{scenario}-{slot}-{side}'
            rows.append({'position': position, 'scenario': scenario, 'slot': slot, 'side': side,
                         'raw': 'raw/' + name + '.json', 'log': 'logs/' + name + '.log'})
    return rows


def settings():
    return {'benchmark': BENCHMARK, 'scenarios': list(SCENARIOS), 'sideOrder': list(primary.ABBA),
            'jmhVersion': '1.37', 'mode': 'avgt', 'unit': 'ns/op', 'threads': 1, 'forksPerCommand': 1,
            'warmupIterations': 3, 'measurementIterations': 5, 'warmupTime': '1 s', 'measurementTime': '1 s',
            'warmupBatchSize': 1, 'measurementBatchSize': 1, 'heapArgs': ['-Xms256m', '-Xmx256m'],
            'profilers': ['gc'], 'scope': SCOPE, 'acceptanceImpact': 'none', 'headlineEligible': False,
            'classpathOrder': ['selected core JAR', 'same candidate probe JAR']}


def tool_sources(candidate):
    candidate = Path(candidate)
    files = {name: digest(read_bytes(candidate / name)) for name in TOOLS}
    return {'revision': primary.revision(candidate), 'files': files, 'aggregate': digest(canonical(files))}


def validate_result(data, case, measured):
    require(isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict)
            and data[0].get('benchmark') == BENCHMARK, 'unexpected construction benchmark')
    # The settings, metrics, runtime properties and origin protocol are identical;
    # only this explicit, independently checked benchmark name differs.
    adapted = dict(data[0], benchmark=primary.BENCHMARK)
    return primary.validate_result([adapted], case, measured)


def validate_manifest(manifest):
    require(isinstance(manifest, dict) and set(manifest) == {'schemaVersion', 'protocol', 'status', 'recordedAt',
            'binding', 'toolSources', 'settings', 'caseOrder'}, 'unexpected construction manifest fields')
    require(type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
            and manifest['protocol'] == PROTOCOL and manifest['status'] == 'complete', 'incomplete construction evidence')
    require(canonical(manifest['settings']) == canonical(settings())
            and canonical(manifest['caseOrder']) == canonical(plan()), 'construction settings or case order changed')
    instant = datetime.fromisoformat(manifest['recordedAt'].replace('Z', '+00:00'))
    require(instant.tzinfo is not None and instant.utcoffset().total_seconds() == 0, 'recordedAt must be UTC')
    binding = manifest['binding']
    require(isinstance(binding, dict) and set(binding) == {'measurementManifestSha256', 'primaryManifest'}
            and isinstance(binding['measurementManifestSha256'], str)
            and common.SHA.fullmatch(binding['measurementManifestSha256']), 'invalid main measurement binding')
    measured = binding['primaryManifest']
    primary.validate_manifest(measured)
    tools = manifest['toolSources']
    require(isinstance(tools, dict) and set(tools) == {'revision', 'files', 'aggregate'}
            and tools['revision'] == measured['sources']['probe']['revision'], 'mixed construction source revision')
    files = tools['files']
    require(isinstance(files, dict) and set(files) == set(TOOLS)
            and all(isinstance(value, str) and common.SHA.fullmatch(value) for value in files.values())
            and tools['aggregate'] == digest(canonical(files)), 'invalid construction tool source inventory')
    require(measured['sources']['probe']['files'].get(TOOLS[2]) == files[TOOLS[2]],
            'construction probe was not part of the same measured probe source')
    return measured


def command(java, baseline, candidate, output, case, measured):
    selected = baseline if case['side'] == 'baseline' else candidate
    classpath = os.pathsep.join((os.path.relpath(Path(selected) / primary.CORE_JAR, candidate),
                                os.path.relpath(Path(candidate) / primary.PROBE_JAR, candidate)))
    return [str(java), '-cp', classpath, 'org.openjdk.jmh.Main', '^' + re.escape(BENCHMARK) + '$',
            '-p', 'scenario=' + case['scenario'], '-bm', 'avgt', '-tu', 'ns', '-t', '1', '-f', '1',
            '-wi', '3', '-i', '5', '-w', '1s', '-r', '1s', '-foe', 'true', '-prof', 'gc',
            '-jvmArgsAppend', ' '.join(primary.jvm_args(measured['artifacts'], case['side'])), '-rf', 'json',
            '-rff', os.path.relpath(Path(output) / case['raw'], candidate)]


def collect(baseline, candidate, measurements, output, *, java=None):
    baseline, candidate, measurements = map(lambda path: Path(path).resolve(), (baseline, candidate, measurements))
    output = Path(output).absolute()
    require(not output.is_symlink(), 'construction output must not be a symlink')
    output = output.resolve()
    require(not output.is_relative_to(measurements) and output not in (baseline, candidate)
            and (not output.exists() or not any(output.iterdir())), 'choose a new separate empty construction directory')
    measured, _ = primary.check(measurements)
    manifest_bytes = read_bytes(measurements / 'manifest.json')
    for root in (baseline, candidate):
        primary.require_clean(root)
    require(primary.sources_for(baseline, candidate) == measured['sources']
            and primary.artifacts_for(baseline, candidate) == measured['artifacts'], 'main source or artifacts differ')
    tools = tool_sources(candidate)
    require(tools['files'][TOOLS[0]] == digest(read_bytes(Path(__file__))), 'construction collector origin mismatch')
    executed = {'comparison/direct_state_evidence.py': Path(primary.__file__),
                'comparison/mechanism_evidence.py': Path(common.__file__),
                'scripts/package_profiles.py': common.ROOT / 'scripts/package_profiles.py'}
    require(all(measured['sources']['probe']['files'][name] == digest(read_bytes(path)) for name, path in executed.items()),
            'construction helper origin mismatch')
    java = Path(java or Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java').resolve()
    require(common.host_environment(java) == measured['environment'], 'construction host or Java differs from main evidence')
    manifest = {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
                'recordedAt': datetime.now(timezone.utc).isoformat(),
                'binding': {'measurementManifestSha256': digest(manifest_bytes), 'primaryManifest': measured},
                'toolSources': tools, 'settings': settings(), 'caseOrder': plan()}
    validate_manifest(manifest)
    common.privacy(canonical(manifest))
    output.mkdir(parents=True, exist_ok=True)
    (output / 'raw').mkdir()
    (output / 'logs').mkdir()
    records, position = [], 0
    try:
        for case in plan():
            position = case['position']
            raw, log = output / case['raw'], output / case['log']
            common.run_command(command(java, baseline, candidate, output, case, measured), candidate, log)
            payload, log_bytes = read_bytes(raw), read_bytes(log)
            common.privacy(payload)
            common.privacy(log_bytes)
            metrics = validate_result(read_json(raw), case, measured)
            primary.validate_origin(log_bytes, measured['artifacts'], case['side'])
            require(primary.sources_for(baseline, candidate) == measured['sources']
                    and primary.artifacts_for(baseline, candidate) == measured['artifacts']
                    and tool_sources(candidate) == tools and read_bytes(measurements / 'manifest.json') == manifest_bytes,
                    'main binding or construction sources changed during collection')
            records.append({'position': position, 'raw': case['raw'], 'log': case['log'],
                            'rawSha256': digest(payload), 'logSha256': digest(log_bytes), 'metrics': metrics})
        primary.check(measurements)
        write_json(output / 'manifest.json', manifest)
        write_json(output / 'suite.json', {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
                   'manifestSha256': digest(canonical(manifest)), 'results': records})
        render(output, measurements=measurements, verify_checksums=False)
        return check(output, measurements=measurements)
    except Exception:
        for name in ('manifest.json', 'suite.json', 'summary.json', 'summary.md', 'SHA256SUMS'):
            (output / name).unlink(missing_ok=True)
        write_json(output / 'failure.json', {'status': 'failed', 'position': position,
                   'reason': 'Technical collection failed; partial construction data is not complete evidence.'})
        raise


def allowed_files():
    return {'manifest.json', 'suite.json', 'summary.json', 'summary.md', 'SHA256SUMS'} | {
        case[key] for case in plan() for key in ('raw', 'log')}


def validate_directory(folder):
    require(folder.is_dir() and not folder.is_symlink(), 'construction evidence must be a real directory')
    for path in folder.rglob('*'):
        name = path.relative_to(folder).as_posix()
        require(not path.is_symlink(), 'symlink in construction evidence')
        require(name in ('raw', 'logs') if path.is_dir() else name in allowed_files(), 'unapproved construction evidence file')


def summarize(manifest, records):
    cells = []
    for scenario in SCENARIOS:
        cell = {'scenario': scenario}
        for side in primary.SIDES:
            selected = [item for case, item in zip(plan(), records) if case['scenario'] == scenario and case['side'] == side]
            cell[side] = {}
            for name in primary.METRICS:
                means = [item['metrics'][name]['mean'] for item in selected]
                require(len(means) == 2, 'missing construction forks')
                iterations = [value for item in selected for value in item['metrics'][name]['iterations']]
                cell[side][name] = {'forkMeans': means, 'meanForkMean': statistics.mean(means),
                                    'forkRange': [min(means), max(means)], 'iterations': iterations,
                                    'iterationRange': [min(iterations), max(iterations)]}
        cells.append(cell)
    return {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete', 'headlineEligible': False,
            'acceptanceImpact': 'none', 'scope': SCOPE, 'manifestSha256': digest(canonical(manifest)),
            'measurementManifestSha256': manifest['binding']['measurementManifestSha256'], 'cells': cells}


def summary_markdown(summary):
    lines = ['# Separate pipeline-construction diagnostics', '', SCOPE, '',
             'Two scenarios, each in fixed baseline / candidate / candidate / baseline order: eight fresh single-fork '
             'JVMs. Each fork uses one thread, a 256 MiB heap, three 1-second warmup iterations and five 1-second '
             'measurement iterations. Each side/scenario retains two fork means and ten iterations; iterations '
             'are nested observations, not ten independent replicates.', '',
             '| Scenario | Baseline time ns/op | Candidate time ns/op | Baseline B/op | Candidate B/op |',
             '| --- | ---: | ---: | ---: | ---: |']
    for cell in summary['cells']:
        metric = lambda side, name: cell[side][name]['meanForkMean']
        lines.append(f"| {cell['scenario']} | {metric('baseline', 'timeNsPerOp'):.6f} | "
                     f"{metric('candidate', 'timeNsPerOp'):.6f} | {metric('baseline', 'allocatedBytesPerOp'):.6f} | "
                     f"{metric('candidate', 'allocatedBytesPerOp'):.6f} |")
    lines += ['', 'build-4 times building a fresh four-stage pipeline; first-direct-4 also executes its first direct '
              'call. Timed work includes the declared probe checks. Setup, artifact verification and cleanup remain '
              'outside timing. Consult the bound probe source for the exact method boundary.', '',
              'No observation target is applied here. These costs cannot pass, repair or select cases from the main '
              'seven-scenario experiment. Do not subtract times across different probes to infer a capacity, an '
              'exclusive component cost or an explanation of a prior framework gap. Two-fork and iteration ranges '
              'are observations, not confidence intervals.', '',
              'The [manifest](manifest.json) binds the actual complete main measurement manifest, its core/probe '
              'artifacts, source inventories, host/JVM and the construction collector/probe sources. '
              '[summary.json](summary.json) preserves all fork means, iterations and ranges. Raw JSON, '
              'privacy-checked logs and [checksums](SHA256SUMS) are retained. This directory alone is insufficient '
              'for validation: the complete bound main measurements must also be available.', '']
    return '\n'.join(lines)


def checksums(folder):
    return ''.join(digest(read_bytes(folder / name)) + '  ' + name + '\n'
                   for name in sorted(allowed_files() - {'SHA256SUMS'}))


def check(folder, *, measurements=None, verify_checksums=True):
    folder = Path(folder)
    validate_directory(folder)
    manifest, suite = read_json(folder / 'manifest.json'), read_json(folder / 'suite.json')
    common.privacy(canonical(manifest))
    measured = validate_manifest(manifest)
    measurements = Path(measurements) if measurements is not None else folder.parent / 'evidence/measurements'
    actual, _ = primary.check(measurements)
    require(digest(read_bytes(measurements / 'manifest.json')) == manifest['binding']['measurementManifestSha256']
            and canonical(actual) == canonical(measured), 'actual main evidence differs from construction binding')
    require(isinstance(suite, dict) and set(suite) == {'schemaVersion', 'protocol', 'status', 'manifestSha256', 'results'}
            and type(suite['schemaVersion']) is int and suite['schemaVersion'] == 1 and suite['protocol'] == PROTOCOL
            and suite['status'] == 'complete' and suite['manifestSha256'] == digest(canonical(manifest)),
            'incomplete or mixed construction suite')
    records = suite['results']
    require(isinstance(records, list) and len(records) == 8, 'missing construction cases or forks')
    for case, item in zip(plan(), records):
        require(isinstance(item, dict) and set(item) == {'position', 'raw', 'log', 'rawSha256', 'logSha256', 'metrics'}
                and type(item['position']) is int and item['position'] == case['position']
                and item['raw'] == case['raw'] and item['log'] == case['log'], 'duplicate or reordered construction fork')
        raw, log = read_bytes(folder / case['raw']), read_bytes(folder / case['log'])
        common.privacy(raw)
        common.privacy(log)
        require(digest(raw) == item['rawSha256'] and digest(log) == item['logSha256'], 'construction raw evidence changed')
        metrics = validate_result(read_json(folder / case['raw']), case, measured)
        require(canonical(metrics) == canonical(item['metrics']), 'construction derived metrics disagree with raw evidence')
        primary.validate_origin(log, measured['artifacts'], case['side'])
    summary = summarize(manifest, records)
    if verify_checksums:
        require(read_bytes(folder / 'SHA256SUMS').decode() == checksums(folder), 'construction checksum inventory mismatch')
        require(canonical(read_json(folder / 'summary.json')) == canonical(summary), 'construction summary mismatch')
        require(read_bytes(folder / 'summary.md').decode() == summary_markdown(summary), 'construction summary Markdown mismatch')
    return manifest, suite


def render(folder, *, measurements=None, verify_checksums=True):
    folder = Path(folder)
    manifest, suite = check(folder, measurements=measurements, verify_checksums=verify_checksums)
    summary = summarize(manifest, suite['results'])
    write_json(folder / 'summary.json', summary)
    (folder / 'summary.md').write_text(summary_markdown(summary), encoding='utf-8')
    (folder / 'SHA256SUMS').write_text(checksums(folder), encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    capture = sub.add_parser('collect')
    for name in ('baseline', 'candidate', 'measurements', 'output'):
        capture.add_argument('--' + name, type=Path, required=True)
    capture.add_argument('--java', type=Path)
    verify = sub.add_parser('check')
    verify.add_argument('folder', type=Path)
    verify.add_argument('--measurements', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'collect':
            collect(args.baseline, args.candidate, args.measurements, args.output, java=args.java)
        else:
            check(args.folder, measurements=args.measurements)
    except (ValueError, OSError, KeyError, TypeError, AttributeError, IndexError, OverflowError,
            RecursionError, subprocess.SubprocessError) as error:
        print('Construction diagnostics rejected (' + type(error).__name__
              + '); complete bound main measurements are required and partial data is not success.', file=sys.stderr)
        return 1
    print('Complete construction diagnostics validated; they do not alter main observation targets.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
