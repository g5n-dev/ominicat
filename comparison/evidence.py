"""Strict validation for optional framework comparisons; never rank partial runs."""
import hashlib
import json
import math
from pathlib import Path

ENGINES = ('ominicat', 'pipecat', 'camel')
VERSIONS = {'ominicat': '0.1.0-SNAPSHOT', 'pipecat': '1.15.0', 'camel': '4.22.2'}
CHECKS = ('allOutputsValidated', 'exactOutputCount', 'noRejections', 'drained', 'cleanupComplete')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def positive(value, name, zero=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0 or (not zero and value == 0):
        raise ValueError(f'invalid {name}')
    return value


def validate_result(data, case, settings):
    for name in ('schemaVersion', 'sessions', 'inflight', 'stages'):
        if isinstance(data.get(name), bool) or not isinstance(data.get(name), int):
            raise ValueError('non-integer execution profile')
    if data.get('schemaVersion') != 1 or data.get('engine') != case['engine']:
        raise ValueError('unexpected engine/schema')
    if data.get('mode') != 'queued-w1' or data.get('sessions') != 1 or data.get('inflight') != 1 or data.get('stages') != case['stages']:
        raise ValueError('mismatched execution profile')
    if data.get('version') != settings['versions'][case['engine']]:
        raise ValueError('mismatched framework version')
    for key in ('warmupFrames', 'measuredFrames'):
        value = data.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise ValueError(f'invalid {key}')
    if data['measuredFrames'] > settings['maxSamples']:
        raise ValueError('sample capacity exceeded')
    for key, requested in [('warmupSeconds', settings['warmupSeconds']), ('elapsedSeconds', settings['measureSeconds'])]:
        if positive(data.get(key), key) + 1e-6 < requested:
            raise ValueError(f'short {key}')
    rate = positive(data.get('throughputFramesPerSecond'), 'throughput')
    expected = data['measuredFrames'] / data['elapsedSeconds']
    if abs(rate - expected) > max(.01, expected * .0001):
        raise ValueError('throughput/count/duration mismatch')
    latency = data.get('latencyMicros', {})
    values = [positive(latency.get(k), 'latency ' + k) for k in ('p50', 'p95', 'p99', 'max')]
    if values != sorted(values):
        raise ValueError('unordered latency quantiles')
    positive(data.get('processCpuSeconds'), 'process CPU', zero=True)
    if any(data.get('checks', {}).get(k) is not True for k in CHECKS):
        raise ValueError('output/lifecycle validation failed')
    if not isinstance(data.get('environment'), dict) or not data['environment']:
        raise ValueError('missing runtime environment')
    return data


def validate_suite(suite):
    if suite.get('schemaVersion') != 1 or suite.get('status') != 'complete':
        raise ValueError('incomplete comparison suite')
    manifest = suite.get('manifest', {})
    if digest(canonical(manifest)) != suite.get('manifestSha256'):
        raise ValueError('manifest hash mismatch')
    if manifest.get('schemaVersion') != 1 or manifest.get('protocol') != 'model-free-framework-path-v1':
        raise ValueError('unsupported measurement protocol')
    environment = manifest.get('environment', {})
    if 'ci' in environment:
        from ci_context import validate_context
        context = validate_context(environment['ci'])
        if (environment.get('os') != 'Linux' or context['machine'] != environment.get('arch')
                or context['kernelRelease'] != environment.get('osRelease') or context['sourceSha'] != manifest.get('sourceRevision')):
            raise ValueError('CI provenance disagrees with the measured host/source')
    fingerprint = manifest.get('sourceFingerprint', {})
    files = fingerprint.get('files', {})
    if not files or digest(canonical(files)) != fingerprint.get('aggregate'):
        raise ValueError('missing or inconsistent source fingerprint')
    if any(not isinstance(v, str) or len(v) != 64 or any(c not in '0123456789abcdef' for c in v) for v in files.values()):
        raise ValueError('invalid source file hashes')
    artifact = manifest.get('javaArtifactSha256', '')
    if not isinstance(artifact, str) or len(artifact) != 64 or any(c not in '0123456789abcdef' for c in artifact):
        raise ValueError('invalid measured Java artifact hash')
    settings = manifest.get('settings', {})
    engines, stages, forks = settings.get('engines', []), settings.get('stages', []), settings.get('forks', 0)
    if not engines or len(set(engines)) != len(engines) or any(e not in ENGINES for e in engines):
        raise ValueError('invalid engine matrix')
    if not stages or len(set(stages)) != len(stages) or any(isinstance(s, bool) or not isinstance(s, int) or s not in (1, 4, 16) for s in stages):
        raise ValueError('invalid stage matrix')
    if any(settings.get('versions', {}).get(e) != VERSIONS[e] for e in engines):
        raise ValueError('unrecognized framework version profile')
    if isinstance(forks, bool) or not isinstance(forks, int) or not 1 <= forks <= 9:
        raise ValueError('invalid fork count')
    for key in ('warmupSeconds', 'measureSeconds'):
        positive(settings.get(key), key)
    capacity = settings.get('maxSamples')
    if isinstance(capacity, bool) or not isinstance(capacity, int) or not 100 <= capacity <= 2000000:
        raise ValueError('invalid sample budget')
    expected = {(e, s, f) for e in engines for s in stages for f in range(1, forks + 1)}
    order = manifest.get('caseOrder', [])
    order_keys = [(c.get('engine'), c.get('stages'), c.get('fork')) for c in order]
    if len(order_keys) != len(expected) or set(order_keys) != expected:
        raise ValueError('incomplete or duplicate planned matrix')
    records = suite.get('results', [])
    seen = set()
    for item in records:
        key = (item.get('engine'), item.get('stages'), item.get('fork'))
        if key in seen or key not in expected:
            raise ValueError('duplicate or unknown result')
        seen.add(key)
        if item.get('manifestSha256') != suite['manifestSha256']:
            raise ValueError('mixed measurement manifests')
        validate_result(item['result'], item, settings)
        resource = item.get('resources', {})
        for name in ('peakRssBytes', 'measuredPeakRssBytes', 'rssSamples', 'measuredRssSamples'):
            positive(resource.get(name), name)
            if not isinstance(resource[name], int):
                raise ValueError('non-integer RSS observation')
        if resource['measuredPeakRssBytes'] > resource['peakRssBytes']:
            raise ValueError('invalid RSS scope')
        if resource['measuredRssSamples'] > resource['rssSamples']:
            raise ValueError('invalid RSS sample subset')
        positive(resource.get('sampleIntervalSeconds'), 'RSS sample interval')
    if seen != expected:
        raise ValueError('missing results: comparison cannot be ranked')
    return suite


def load_suite(folder):
    folder = Path(folder).resolve()
    suite = json.loads((folder / 'suite.json').read_text())
    validate_suite(suite)
    if json.loads((folder / 'manifest.json').read_text()) != suite['manifest']:
        raise ValueError('external and embedded measurement manifests disagree')
    seen_paths = set()
    for item in suite['results']:
        original = folder / item['file']
        if original.is_symlink():
            raise ValueError('raw results must not be symlink aliases')
        path = original.resolve()
        if not path.is_relative_to(folder) or path.parent != folder / 'raw':
            raise ValueError('unsafe raw-result path')
        if path in seen_paths:
            raise ValueError('each process requires an independent raw-result file')
        seen_paths.add(path)
        raw = path.read_bytes()
        if digest(raw) != item['sha256'] or json.loads(raw) != item['result']:
            raise ValueError('raw result changed after collection')
    raw_files = {p.resolve() for p in (folder / 'raw').glob('*.json')}
    expected_files = {(folder / i['file']).resolve() for i in suite['results']}
    if raw_files != expected_files:
        raise ValueError('unaccounted raw results')
    return suite
