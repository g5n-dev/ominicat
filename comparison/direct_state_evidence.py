#!/usr/bin/env python3
"""Collect/check a preregistered three-block ABBA direct-invocation experiment."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('direct_state_common', Path(__file__).with_name('mechanism_evidence.py'))
common = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(common)
require, digest, canonical = common.require, common.digest, common.canonical
read_bytes, read_json, write_json = common.read_bytes, common.read_json, common.write_json

PROTOCOL = 'ominicat-direct-invocation-state-v1'
BENCHMARK = 'io.ominicat.benchmarks.DirectStateProbe.invoke'
CORE_JAR = 'ominicat-core/target/ominicat-core-0.1.0-SNAPSHOT.jar'
PROBE_JAR = 'ominicat-benchmarks/target/ominicat-benchmarks.jar'
PIPELINE = 'ominicat-core/src/main/java/io/ominicat/core/Pipeline.java'
PROBE_SOURCE = 'ominicat-benchmarks/src/main/java/io/ominicat/benchmarks/DirectStateProbe.java'
CORE_REQUIRED = ('pom.xml', 'ominicat-core/pom.xml', PIPELINE)
PROBE_REQUIRED = ('ominicat-benchmarks/pom.xml', PROBE_SOURCE, 'comparison/direct_state_evidence.py',
                  'comparison/tests/test_direct_state_evidence.py', 'comparison/mechanism_evidence.py',
                  'scripts/package_profiles.py', 'comparison/direct-state-protocol.md',
                  '.github/workflows/direct-state-evidence.yml')
SCENARIOS = ('raw-direct-1', 'raw-direct-4', 'raw-direct-16', 'envelope-direct-4',
             'raw-session-4', 'audio-direct-4', 'image-direct-4')
SIDES = ('baseline', 'candidate')
ABBA = ('baseline', 'candidate', 'candidate', 'baseline')
METRICS = {'timeNsPerOp': ('primaryMetric', 'ns/op'), 'allocatedBytesPerOp': ('gc.alloc.rate.norm', 'B/op')}
DECISION_RULE = {
    'primaryScenario': 'raw-direct-4', 'primaryAllocationRatioMax': .90,
    'directTimeRatioMax': 1.05, 'directAllocationRatioMax': 1.01,
    'negativeControl': 'raw-session-4',
    'blockStatistic': 'mean of two candidate fork means / mean of two baseline fork means within the same ABBA block',
    'pointStatistic': 'arithmetic mean of the three paired-block ratios',
    'zeroAllocationBaseline': '0/0 is 1; positive candidate over zero baseline is null, zeroBaselineIncrease and targets-not-met.',
    'rangeScope': 'Observed three-block ratio range, not a confidence interval; iterations are not independent forks.',
    'use': 'Observed experiment targets, not a CI performance gate or production guarantee.'}


def plan():
    rows = []
    for block in (1, 2, 3):
        scenarios = SCENARIOS if block != 2 else tuple(reversed(SCENARIOS))
        for scenario in scenarios:
            for slot, side in enumerate(ABBA, 1):
                position = len(rows) + 1
                name = f'{position:03d}-b{block}-{scenario}-{slot}-{side}'
                rows.append({'position': position, 'block': block, 'scenario': scenario, 'slot': slot, 'side': side,
                             'raw': 'raw/' + name + '.json', 'log': 'logs/' + name + '.log'})
    return rows


def settings():
    return {'jmhVersion': '1.37', 'mode': 'avgt', 'unit': 'ns/op', 'threads': 1, 'forksPerCommand': 1,
            'blocks': 3, 'sideOrder': list(ABBA), 'scenarios': list(SCENARIOS), 'reverseScenarioBlocks': [2],
            'warmupIterations': 3, 'measurementIterations': 5, 'warmupTime': '1 s', 'measurementTime': '1 s',
            'warmupBatchSize': 1, 'measurementBatchSize': 1, 'heapArgs': ['-Xms256m', '-Xmx256m'],
            'profilers': ['gc'], 'benchmark': BENCHMARK, 'decisionRule': dict(DECISION_RULE),
            'classpathOrder': ['selected core JAR', 'same candidate probe JAR']}


def jvm_args(artifacts, side):
    return settings()['heapArgs'] + ['-Dominicat.directState.coreSha256=' + artifacts[side]['sha256'],
                                   '-Dominicat.directState.probeSha256=' + artifacts['probe']['sha256']]


def validate_origin(data, artifacts, side):
    text = data.decode('utf-8')
    for marker, expected in (('DIRECT_STATE_CORE_SHA256', artifacts[side]['sha256']),
                             ('DIRECT_STATE_PROBE_SHA256', artifacts['probe']['sha256'])):
        pattern = r'^(?:# Warmup Iteration[ \t]+1:[ \t]*)?' + marker + r'=([0-9a-f]{64})[ \t]*\r?$'
        require(text.count(marker + '=') == 1 and re.findall(pattern, text, re.M) == [expected],
                'loaded core/probe origin was not verified exactly once')


def validate_result(data, case, manifest):
    require(isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict), 'expected exactly one JMH cell')
    result = data[0]
    allowed = {'jmhVersion', 'benchmark', 'mode', 'threads', 'forks', 'jvm', 'jvmArgs', 'jdkVersion', 'vmName',
               'vmVersion', 'warmupIterations', 'warmupTime', 'warmupBatchSize', 'measurementIterations',
               'measurementTime', 'measurementBatchSize', 'params', 'primaryMetric', 'secondaryMetrics'}
    require(set(result) <= allowed and result.get('benchmark') == BENCHMARK
            and result.get('params') == {'scenario': case['scenario']}, 'unexpected benchmark, scenario or result fields')
    for key in ('jmhVersion', 'mode', 'threads', 'warmupIterations', 'measurementIterations',
                'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize'):
        expected = settings()[key]
        require(type(result.get(key)) is type(expected) and result[key] == expected, 'JMH settings mismatch')
    require(type(result.get('forks')) is int and result['forks'] == 1, 'expected one fresh fork per command')
    require(result.get('jvmArgs') == jvm_args(manifest['artifacts'], case['side']), 'heap or runtime SHA arguments mismatch')
    java = manifest['environment']['java']
    for key, expected in (('jdkVersion', java['version']), ('vmVersion', java['vmVersion']), ('vmName', java['vmName'])):
        require(result.get(key) == expected, 'mixed Java runtime evidence')
    require(java['version'].split('.')[0] == '17', 'Java 17 is required')
    metrics = {}
    for name, (key, unit) in METRICS.items():
        metric = result.get('primaryMetric') if key == 'primaryMetric' else result.get('secondaryMetrics', {}).get(key)
        require(isinstance(metric, dict) and metric.get('scoreUnit') == unit, 'missing metric or incorrect unit')
        score = common.number(metric.get('score'), name, positive=name == 'timeNsPerOp')
        raw = metric.get('rawData')
        require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list) and len(raw[0]) == 5,
                'missing fork or measured iterations')
        values = [common.number(value, name, positive=name == 'timeNsPerOp') for value in raw[0]]
        require(abs(score - statistics.mean(values)) <= max(1e-6, abs(score) * 1e-6),
                'metric mean disagrees with measured iterations')
        metrics[name] = {'mean': score, 'iterations': values}
    return metrics


def revision(root):
    result = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, text=True, capture_output=True, check=True).stdout.strip()
    require(re.fullmatch(r'[0-9a-f]{40}', result) is not None, 'invalid source revision')
    return result


def require_clean(root):
    dirty = subprocess.run(['git', 'status', '--porcelain'], cwd=root, text=True, capture_output=True, check=True).stdout
    require(not dirty.strip(), 'paired measurement requires clean source trees')


def source_inventory(root, kind):
    require(kind in ('core', 'probe'), 'unknown source inventory kind')
    root = Path(root).resolve()
    required = CORE_REQUIRED if kind == 'core' else PROBE_REQUIRED
    prefix = 'ominicat-core/src/main/java' if kind == 'core' else 'ominicat-benchmarks/src/main/java'
    java = sorted((root / prefix).rglob('*.java'))
    require(bool(java), 'missing measured Java source inventory')
    paths = set(java + [root / name for name in required])
    files = {path.relative_to(root).as_posix(): digest(read_bytes(path)) for path in sorted(paths)}
    return {'revision': revision(root), 'files': files, 'aggregate': digest(canonical(files))}


def sources_for(baseline, candidate):
    return {'baseline': source_inventory(baseline, 'core'), 'candidate': source_inventory(candidate, 'core'),
            'probe': source_inventory(candidate, 'probe')}


def core_changes(sources):
    a, b = (sources[side]['files'] for side in SIDES)
    require(set(a) == set(b), 'core source inventory differs beyond the selected file')
    changed = sorted(path for path in a if a[path] != b[path])
    require(changed == [PIPELINE], 'only the selected Pipeline source may differ; an actual selected change is required')
    return changed


def artifacts_for(baseline, candidate):
    result = {}
    for side, root in (('baseline', baseline), ('candidate', candidate), ('probe', candidate)):
        relative = PROBE_JAR if side == 'probe' else CORE_JAR
        path = Path(root) / relative
        require(not path.is_symlink() and path.is_file(), 'missing or nonregular runtime artifact')
        result[side] = {'relativeJar': relative, 'sha256': digest(path.read_bytes())}
    return result


def command(java, baseline, candidate, output, case, artifacts):
    root = baseline if case['side'] == 'baseline' else candidate
    classpath = os.pathsep.join((os.path.relpath(Path(root) / CORE_JAR, candidate),
                                os.path.relpath(Path(candidate) / PROBE_JAR, candidate)))
    return [str(java), '-cp', classpath, 'org.openjdk.jmh.Main', '^' + re.escape(BENCHMARK) + '$',
            '-p', 'scenario=' + case['scenario'], '-bm', 'avgt', '-tu', 'ns', '-t', '1', '-f', '1',
            '-wi', '3', '-i', '5', '-w', '1s', '-r', '1s', '-foe', 'true', '-prof', 'gc',
            '-jvmArgsAppend', ' '.join(jvm_args(artifacts, case['side'])), '-rf', 'json', '-rff',
            os.path.relpath(Path(output) / case['raw'], candidate)]


def collect(baseline, candidate, output, *, java=None):
    baseline, candidate = Path(baseline).resolve(), Path(candidate).resolve()
    output = Path(output).absolute()
    require(not output.is_symlink(), 'evidence output must not be a symlink')
    output = output.resolve()
    require(output not in (baseline, candidate) and (not output.exists() or not any(output.iterdir())),
            'choose a new empty evidence directory')
    for root in (baseline, candidate):
        require_clean(root)
    sources = sources_for(baseline, candidate)
    changed = core_changes(sources)
    executed = {'comparison/direct_state_evidence.py': Path(__file__),
                'comparison/mechanism_evidence.py': Path(common.__file__),
                'scripts/package_profiles.py': common.ROOT / 'scripts/package_profiles.py'}
    require(all(sources['probe']['files'][name] == digest(read_bytes(path)) for name, path in executed.items()),
            'executed collector or helper differs from the candidate source')
    artifacts = artifacts_for(baseline, candidate)
    java = Path(java or Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java').resolve()
    environment = common.host_environment(java)
    manifest = {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
                'recordedAt': datetime.now(timezone.utc).isoformat(), 'artifacts': artifacts,
                'sources': sources, 'environment': environment, 'settings': settings(),
                'caseOrder': plan(), 'changedCoreSources': changed}
    common.privacy(canonical(manifest))
    validate_manifest(manifest)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'raw').mkdir()
    (output / 'logs').mkdir()
    records = []
    for case in plan():
        try:
            log, raw = output / case['log'], output / case['raw']
            common.run_command(command(java, baseline, candidate, output, case, artifacts), candidate, log)
            payload, log_bytes = read_bytes(raw), read_bytes(log)
            common.privacy(payload)
            common.privacy(log_bytes)
            metrics = validate_result(read_json(raw), case, manifest)
            validate_origin(log_bytes, artifacts, case['side'])
            require(sources_for(baseline, candidate) == sources and artifacts_for(baseline, candidate) == artifacts,
                    'paired source or runtime artifacts changed during collection')
            records.append({'position': case['position'], 'raw': case['raw'], 'log': case['log'],
                            'rawSha256': digest(payload), 'logSha256': digest(log_bytes), 'metrics': metrics})
        except Exception:
            write_json(output / 'failure.json', {'status': 'failed', 'position': case['position'],
                       'reason': 'Technical collection failed; partial data is not a completed paired experiment.'})
            raise
    write_json(output / 'manifest.json', manifest)
    write_json(output / 'suite.json', {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
               'manifestSha256': digest(canonical(manifest)), 'results': records})
    render(output, verify_checksums=False)
    return check(output)


def allowed_files():
    return {'manifest.json', 'suite.json', 'summary.json', 'summary.md', 'SHA256SUMS'} | {
        case[key] for case in plan() for key in ('raw', 'log')}


def validate_directory(folder):
    require(not folder.is_symlink() and folder.is_dir(), 'paired evidence must be a real directory')
    for path in folder.rglob('*'):
        relative = path.relative_to(folder).as_posix()
        require(not path.is_symlink(), 'symlink in paired evidence')
        require(relative in ('raw', 'logs') if path.is_dir() else relative in allowed_files(),
                'unapproved paired evidence file or directory')


def validate_manifest(manifest):
    require(isinstance(manifest, dict) and set(manifest) == {'schemaVersion', 'protocol', 'status', 'recordedAt',
            'artifacts', 'sources', 'environment', 'settings', 'caseOrder', 'changedCoreSources'}, 'unexpected manifest fields')
    require(type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
            and manifest['protocol'] == PROTOCOL and manifest['status'] == 'complete', 'incomplete or unknown paired protocol')
    require(canonical(manifest['settings']) == canonical(settings()) and canonical(manifest['caseOrder']) == canonical(plan()),
            'paired plan or preregistered settings changed')
    instant = datetime.fromisoformat(manifest['recordedAt'].replace('Z', '+00:00'))
    require(instant.tzinfo is not None and instant.utcoffset().total_seconds() == 0, 'recordedAt must be UTC')
    artifacts = manifest['artifacts']
    require(isinstance(artifacts, dict) and set(artifacts) == {'baseline', 'candidate', 'probe'}, 'missing runtime artifacts')
    for side, item in artifacts.items():
        require(isinstance(item, dict) and set(item) == {'relativeJar', 'sha256'} and item['relativeJar'] == (
                PROBE_JAR if side == 'probe' else CORE_JAR) and isinstance(item['sha256'], str)
                and common.SHA.fullmatch(item['sha256']), 'invalid runtime artifact binding')
    sources = manifest['sources']
    require(isinstance(sources, dict) and set(sources) == {'baseline', 'candidate', 'probe'}, 'missing source inventory')
    for side, item in sources.items():
        require(isinstance(item, dict) and set(item) == {'revision', 'files', 'aggregate'}
                and re.fullmatch(r'[0-9a-f]{40}', item.get('revision', '')), 'invalid source revision')
        required = PROBE_REQUIRED if side == 'probe' else CORE_REQUIRED
        prefix = 'ominicat-benchmarks/src/main/java/' if side == 'probe' else 'ominicat-core/src/main/java/'
        files = item['files']
        require(isinstance(files, dict) and set(required) <= set(files), 'missing measured source files')
        require(all(isinstance(name, str) and (name in required or name.startswith(prefix) and name.endswith('.java'))
                    and '..' not in name.split('/') and '\\' not in name and isinstance(value, str)
                    and common.SHA.fullmatch(value) for name, value in files.items()), 'unapproved source inventory')
        require(item['aggregate'] == digest(canonical(files)), 'source fingerprint mismatch')
    require(sources['candidate']['revision'] == sources['probe']['revision'], 'probe and candidate revisions differ')
    require(manifest['changedCoreSources'] == core_changes(sources), 'unrelated production core changes')
    # Reuse the established strict public host/JDK validator without borrowing its experiment schema.
    env = manifest['environment']
    require(isinstance(env, dict) and set(env) == {'os', 'kernelRelease', 'architecture', 'distro', 'cpuModel',
            'logicalProcessors', 'affinityCount', 'java', 'scope'}, 'unexpected environment fields')
    require(env['os'] == 'Linux' and env['distro'].get('ID') == 'ubuntu'
            and env['distro'].get('VERSION_ID') == '24.04' and set(env['distro']) == {'ID', 'VERSION_ID', 'PRETTY_NAME'},
            'wrong Linux execution target')
    for key in ('kernelRelease', 'architecture', 'cpuModel'):
        require(isinstance(env[key], str) and bool(env[key]), 'missing host metadata')
    for key in ('logicalProcessors', 'affinityCount'):
        require(type(env[key]) is int and env[key] > 0, 'invalid processor or affinity count')
    java = env['java']
    require(isinstance(java, dict) and set(java) == {'version', 'vmVersion', 'vmName', 'vendor', 'os', 'architecture'}
            and all(isinstance(value, str) and bool(value) for value in java.values())
            and java['version'].split('.')[0] == '17' and java['os'] == 'Linux', 'unsupported Java runtime')
    norm = lambda value: {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(value, value)
    require(norm(java['architecture']) == norm(env['architecture']), 'JVM and host architectures differ')


def summarize(manifest, records):
    cells = []
    for scenario in SCENARIOS:
        cell = {'scenario': scenario, 'negativeControl': scenario == 'raw-session-4'}
        for side in SIDES:
            selected = [item for case, item in zip(plan(), records) if case['scenario'] == scenario and case['side'] == side]
            cell[side] = {}
            for name in METRICS:
                means = [item['metrics'][name]['mean'] for item in selected]
                iterations = [value for item in selected for value in item['metrics'][name]['iterations']]
                cell[side][name] = {'forkMeans': means, 'meanForkMean': statistics.mean(means),
                                    'iterations': iterations, 'iterationRange': [min(iterations), max(iterations)]}
        blocks = []
        for block in (1, 2, 3):
            paired = {'block': block, 'baseline': {}, 'candidate': {}, 'ratios': {}, 'zeroBaselineIncrease': False}
            for name in METRICS:
                for side in SIDES:
                    means = [item['metrics'][name]['mean'] for case, item in zip(plan(), records)
                             if case['scenario'] == scenario and case['side'] == side and case['block'] == block]
                    require(len(means) == 2, 'incomplete paired ABBA block')
                    paired[side][name] = statistics.mean(means)
                denominator = paired['baseline'][name]
                numerator = paired['candidate'][name]
                if name == 'allocatedBytesPerOp' and denominator == 0:
                    ratio = 1 if numerator == 0 else None
                    paired['zeroBaselineIncrease'] = numerator > 0
                else:
                    require(denominator > 0, 'paired time requires positive baseline measurement')
                    ratio = numerator / denominator
                    common.number(ratio, 'paired ratio')
                paired['ratios'][name] = ratio
            blocks.append(paired)
        cell['pairedBlocks'] = blocks
        cell['ratioSummary'] = {}
        for name in METRICS:
            ratios = [block['ratios'][name] for block in blocks]
            defined = all(value is not None for value in ratios)
            cell['ratioSummary'][name] = {'blockRatios': ratios,
                                         'mean': statistics.mean(ratios) if defined else None,
                                         'range': [min(ratios), max(ratios)] if defined else None,
                                         'zeroBaselineIncrease': not defined}
        cells.append(cell)
    criteria = []
    for cell in cells:
        if cell['negativeControl']:
            continue
        limits = {'timeNsPerOp': DECISION_RULE['directTimeRatioMax'],
                  'allocatedBytesPerOp': DECISION_RULE['primaryAllocationRatioMax']
                  if cell['scenario'] == DECISION_RULE['primaryScenario'] else DECISION_RULE['directAllocationRatioMax']}
        for name, limit in limits.items():
            ratio = cell['ratioSummary'][name]
            criteria.append({'scenario': cell['scenario'], 'metric': name, 'limit': limit,
                             'meanBlockRatio': ratio['mean'], 'blockRatioRange': ratio['range'],
                             'zeroBaselineIncrease': ratio['zeroBaselineIncrease'],
                             'pointMet': ratio['mean'] is not None and ratio['mean'] <= limit,
                             'everyBlockMet': all(value is not None and value <= limit for value in ratio['blockRatios'])})
    point = all(item['pointMet'] for item in criteria)
    every = all(item['everyBlockMet'] for item in criteria)
    decision = 'observed-targets-met' if every else 'uncertain' if point else 'targets-not-met'
    return {'schemaVersion': 1, 'protocol': PROTOCOL, 'manifestSha256': digest(canonical(manifest)),
            'recordedAt': manifest['recordedAt'], 'environment': manifest['environment'], 'cells': cells,
            'decision': {'status': decision, 'pointTargetsMet': point, 'everyBlockTargetsMet': every,
                         'criteria': criteria, 'negativeControlExcluded': 'raw-session-4', 'rule': dict(DECISION_RULE)}}


def summary_markdown(summary):
    environment = summary['environment']
    lines = ['# Paired direct-invocation state experiment', '',
             'Three preregistered ABBA blocks; seven scenarios; 84 fresh single-fork JVMs. Each fork has '
             'three 1-second warmup and five 1-second measurement iterations. The middle block reverses scenario order.', '',
             environment['distro']['PRETTY_NAME'] + '; ' + environment['cpuModel'] + '; Java '
             + environment['java']['version'] + ' (' + environment['java']['vendor'] + '); '
             + str(environment['affinityCount']) + ' CPUs in the process affinity set. ' + environment['scope'], '',
             'Observed decision: **' + summary['decision']['status'] + '**. This is an experiment outcome, '
             'not a CI performance gate or production guarantee.', '',
             '| Scenario | Baseline time ns/op | Candidate time ns/op | Three paired time ratios | Baseline B/op | Candidate B/op | Three paired allocation ratios |',
             '| --- | ---: | ---: | --- | ---: | ---: | --- |']
    for cell in summary['cells']:
        mean = lambda side, name: cell[side][name]['meanForkMean']
        show = lambda name: ' / '.join('undefined (zero baseline increase)' if value is None else f'{value:.6f}'
                                      for value in cell['ratioSummary'][name]['blockRatios'])
        lines.append(f"| {cell['scenario']} | {mean('baseline', 'timeNsPerOp'):.6f} | {mean('candidate', 'timeNsPerOp'):.6f} | "
                     f"{show('timeNsPerOp')} | {mean('baseline', 'allocatedBytesPerOp'):.6f} | "
                     f"{mean('candidate', 'allocatedBytesPerOp'):.6f} | {show('allocatedBytesPerOp')} |")
    lines += ['', 'Each ratio divides the mean of the two candidate fork means by the mean of the two baseline '
              'fork means within one block. Decisions use all three block ratios; point estimates use their arithmetic '
              'mean. The block range is not a confidence interval. Thirty measured iterations per side/scenario '
              'are retained as observations inside six forks, not treated as thirty independent replicates.', '',
              'The primary raw-direct-4 allocation target is at least 10% less B/op in every block. Every direct '
              'scenario also targets time ratio ≤1.05 and allocation ratio ≤1.01. If point estimates meet the rules '
              'but a block exceeds a limit, the outcome is uncertain. The unchanged raw-session-4 path is a negative '
              'control only; it does not correct or override another result.', '',
              'Time must be strictly positive. For allocation ratios, zero divided by zero is 1 (unchanged); '
              'a positive candidate over a zero baseline is undefined, retained as null with zeroBaselineIncrease '
              'in JSON, and cannot meet an observation target. Affected ratio means and ranges remain null, never '
              'Infinity. A direct-case increase therefore yields targets-not-met rather than a technical collection '
              'failure; the session control remains excluded from the decision.', '',
              'See [summary.json](summary.json) for all six fork means, all measured iterations, block ranges and '
              'each criterion. Time and allocation include the common correctness oracle and harness. This experiment '
              'does not measure model/network latency, p99, RSS or CPU self-time. Raw JSON, privacy-checked logs, '
              '[source/artifact bindings](manifest.json) and checksums are preserved. Technical collection failures cannot become '
              'complete evidence; targets-not-met and uncertain outcomes remain complete, publishable observations.', '']
    return '\n'.join(lines)


def checksums(folder):
    return ''.join(digest(read_bytes(folder / name)) + '  ' + name + '\n'
                   for name in sorted(allowed_files() - {'SHA256SUMS'}))


def check(folder, *, verify_checksums=True):
    folder = Path(folder)
    validate_directory(folder)
    manifest, suite = read_json(folder / 'manifest.json'), read_json(folder / 'suite.json')
    common.privacy(canonical(manifest))
    validate_manifest(manifest)
    require(isinstance(suite, dict) and set(suite) == {'schemaVersion', 'protocol', 'status', 'manifestSha256', 'results'}
            and type(suite['schemaVersion']) is int and suite['schemaVersion'] == 1 and suite['protocol'] == PROTOCOL
            and suite['status'] == 'complete' and suite['manifestSha256'] == digest(canonical(manifest)),
            'incomplete or mixed paired suite')
    records = suite['results']
    require(isinstance(records, list) and len(records) == 84, 'missing paired forks or scenarios')
    for case, item in zip(plan(), records):
        require(isinstance(item, dict) and set(item) == {'position', 'raw', 'log', 'rawSha256', 'logSha256', 'metrics'}
                and type(item['position']) is int and item['position'] == case['position']
                and item['raw'] == case['raw'] and item['log'] == case['log'], 'duplicate or reordered paired evidence')
        raw, log = read_bytes(folder / case['raw']), read_bytes(folder / case['log'])
        common.privacy(raw)
        common.privacy(log)
        require(digest(raw) == item['rawSha256'] and digest(log) == item['logSha256'], 'raw paired evidence changed')
        metrics = validate_result(read_json(folder / case['raw']), case, manifest)
        require(canonical(metrics) == canonical(item['metrics']), 'derived metrics disagree with raw evidence')
        validate_origin(log, manifest['artifacts'], case['side'])
    summary = summarize(manifest, records)
    if verify_checksums:
        require(read_bytes(folder / 'SHA256SUMS').decode() == checksums(folder), 'paired checksum inventory mismatch')
        require(canonical(read_json(folder / 'summary.json')) == canonical(summary), 'paired summary mismatch')
        require(read_bytes(folder / 'summary.md').decode('utf-8') == summary_markdown(summary), 'rendered paired summary mismatch')
    return manifest, suite


def render(folder, *, verify_checksums=True):
    folder = Path(folder)
    manifest, suite = check(folder, verify_checksums=verify_checksums)
    summary = summarize(manifest, suite['results'])
    write_json(folder / 'summary.json', summary)
    (folder / 'summary.md').write_text(summary_markdown(summary), encoding='utf-8')
    (folder / 'SHA256SUMS').write_text(checksums(folder), encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('collect')
    for name in ('baseline', 'candidate', 'output'):
        capture.add_argument('--' + name, type=Path, required=True)
    capture.add_argument('--java', type=Path)
    for action in ('check', 'render'):
        commands.add_parser(action).add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'collect':
            collect(args.baseline, args.candidate, args.output, java=args.java)
        elif args.command == 'check':
            check(args.directory)
        else:
            render(args.directory)
    except (ValueError, OSError, KeyError, TypeError, AttributeError, IndexError, OverflowError,
            RecursionError, subprocess.SubprocessError) as error:
        print('Paired direct-state evidence rejected (' + type(error).__name__ + '); incomplete data is not a successful experiment.',
              file=sys.stderr)
        return 1
    print('Complete paired evidence validated; the observed decision is separate from technical success.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
