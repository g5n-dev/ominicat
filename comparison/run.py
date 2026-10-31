#!/usr/bin/env python3
"""Run the same model-free queued profile in fresh, sequential processes."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import threading
import time

import psutil
from evidence import ENGINES, canonical, digest, validate_result, validate_suite

ROOT = Path(__file__).resolve().parent.parent


def installed_dependencies(python):
    script = "import importlib.metadata as m,json; print(json.dumps({d.metadata['Name']:d.version for d in m.distributions()}))"
    raw = json.loads(subprocess.run([python, '-c', script], capture_output=True, text=True, check=True).stdout)
    normalize = lambda name: re.sub(r'[-_.]+', '-', name).lower()
    inventory = {normalize(name): version for name, version in raw.items()}
    expected = {}
    for line in (ROOT / 'comparison/requirements.lock').read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        name, version = line.split('==')
        expected[normalize(name)] = version
    actual = {name: version for name, version in inventory.items() if name not in ('pip', 'setuptools', 'wheel')}
    if actual != expected:
        raise ValueError('selected Python environment does not match requirements.lock')
    return {'packages': actual, 'sha256': digest(canonical(actual))}


def pipecat_source(python):
    script = """import pathlib,importlib.metadata as m,hashlib,json
d=m.distribution('pipecat-ai')
paths=[p for p in d.files if str(p).startswith('pipecat/') and str(p).endswith('.py')]
print(json.dumps({str(p):hashlib.sha256(d.locate_file(p).read_bytes()).hexdigest() for p in paths},sort_keys=True))"""
    files = json.loads(subprocess.run([python, '-c', script], capture_output=True, text=True, check=True).stdout)
    if not files:
        raise ValueError('missing installed Pipecat source files')
    return {'aggregate': digest(canonical(files)), 'files': files}


def source_fingerprint():
    paths = list((ROOT / 'ominicat-core/src/main/java').rglob('*.java'))
    paths += list((ROOT / 'comparison/benchmarks/src').rglob('*.java'))
    paths += [ROOT / p for p in ('comparison/benchmarks/pom.xml', 'comparison/pipecat/benchmark.py',
              'comparison/run.py', 'comparison/evidence.py', 'comparison/protocol.md', 'comparison/requirements.lock')]
    files = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(set(paths))}
    return {'aggregate': digest(canonical(files)), 'files': files}


def stop_owned(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=3)


def collect(command, output, timeout, diagnostic_path=None):
    if output.exists():
        raise ValueError('refusing stale adapter output')
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=True, bufsize=1)
    phase = {'name': 'startup', 'bytes': 0, 'error': None}
    logs = []
    lock = threading.Lock()

    def reader(stream, stdout):
        try:
            for line in stream:
                with lock:
                    phase['bytes'] += len(line.encode('utf-8'))
                    if phase['bytes'] > 131072:
                        phase['error'] = 'adapter diagnostics exceed bound'
                        return
                    logs.append(line)
                    if stdout:
                        try:
                            event = json.loads(line)
                            if event.get('event') == 'phase':
                                phase['name'] = event.get('phase')
                        except (json.JSONDecodeError, AttributeError):
                            pass
        finally:
            stream.close()

    readers = [threading.Thread(target=reader, args=(process.stdout, True), daemon=True),
               threading.Thread(target=reader, args=(process.stderr, False), daemon=True)]
    for thread in readers:
        thread.start()
    peak = measured_peak = samples = measured_samples = 0
    started = time.monotonic()
    try:
        monitored = psutil.Process(process.pid)
        while process.poll() is None:
            if time.monotonic() - started > timeout:
                raise TimeoutError('adapter deadline exceeded')
            with lock:
                current, error = phase['name'], phase['error']
            if error:
                raise ValueError(error)
            try:
                rss = monitored.memory_info().rss
                peak = max(peak, rss)
                samples += 1
                if current == 'measure':
                    measured_peak = max(measured_peak, rss)
                    measured_samples += 1
            except psutil.NoSuchProcess:
                pass
            time.sleep(.05)
        for thread in readers:
            thread.join(timeout=2)
        with lock:
            error = phase['error']
        if process.returncode != 0 or error:
            raise ValueError(f'adapter failed (exit {process.returncode}); {error or "see private diagnostics"}')
        if measured_samples == 0 or peak == 0 or measured_peak == 0:
            raise ValueError('missing RSS samples; never substitute zero')
        return {'peakRssBytes': peak, 'measuredPeakRssBytes': measured_peak,
                'rssSamples': samples, 'measuredRssSamples': measured_samples,
                'sampleIntervalSeconds': .05}, ''.join(logs)
    finally:
        stop_owned(process)
        for thread in readers:
            thread.join(timeout=2)
        if diagnostic_path is not None:
            Path(diagnostic_path).write_text(''.join(logs))


def run(args):
    folder = Path(args.output).resolve()
    if folder.exists() and any(folder.iterdir()):
        raise ValueError('choose a new empty output folder')
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'raw').mkdir()
    private = folder / 'diagnostics'
    private.mkdir()
    stages = [int(s) for s in args.stages.split(',')]
    engines = args.engines.split(',')
    if any(e not in ENGINES for e in engines) or len(set(engines)) != len(engines):
        raise ValueError('invalid engine list')
    if any(s not in (1, 4, 16) for s in stages) or len(set(stages)) != len(stages):
        raise ValueError('invalid stage list')
    settings = {'engines': engines, 'stages': stages, 'forks': args.forks,
                'warmupSeconds': args.warmup, 'measureSeconds': args.measure, 'maxSamples': args.max_samples,
                'versions': {'ominicat': '0.1.0-SNAPSHOT', 'pipecat': '1.15.0', 'camel': '4.22.2'},
                'sessions': 1, 'inflight': 1, 'mode': 'queued-w1'}
    order = []
    for fork in range(1, args.forks + 1):
        for stage_index, stage in enumerate(stages):
            offset = (fork - 1 + stage_index) % len(engines)
            rotated = engines[offset:] + engines[:offset]
            order += [{'engine': engine, 'stages': stage, 'fork': fork} for engine in rotated]
    artifact = Path(args.jar).resolve()
    source = source_fingerprint()
    manifest = {'schemaVersion': 1, 'protocol': 'model-free-framework-path-v1',
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'settings': settings, 'caseOrder': order, 'sourceFingerprint': source,
        'pythonDependencies': installed_dependencies(args.python),
        'pipecatInstalledSource': pipecat_source(args.python),
        'sourceRevision': subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip(),
        'sourceTreeDirty': bool(subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()),
        'javaArtifactSha256': digest(artifact.read_bytes()),
        'environment': {'os': platform.system(), 'osRelease': platform.release(), 'arch': platform.machine(),
            'logicalProcessors': os.cpu_count(), 'collectorPython': platform.python_version(), 'psutil': psutil.__version__,
            'cpuModel': subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True, check=True).stdout.strip() if platform.system() == 'Darwin' else platform.processor(),
            'physicalMemoryBytes': psutil.virtual_memory().total,
            'javaVersion': subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip(),
            'pythonVersion': subprocess.run([args.python, '--version'], capture_output=True, text=True, check=True).stdout.strip(),
            'context': 'Shared development host; framework order rotated; no exclusive CPU isolation'},
        'commands': {'java': ['${JAVA17}', '-Xms128m', '-Xmx512m', '-jar', 'comparison/benchmarks/target/benchmark.jar'],
            'pipecat': ['${BENCH_PYTHON}', '-u', 'comparison/pipecat/benchmark.py']}}
    manifest_hash = digest(canonical(manifest))
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    results = []
    for index, case in enumerate(order):
        name = f"{case['engine']}-s{case['stages']:02}-f{case['fork']:02}"
        output = folder / 'raw' / (name + '.json')
        common = ['--stages', str(case['stages']), '--warmup-seconds', str(args.warmup),
                  '--measure-seconds', str(args.measure), '--max-samples', str(args.max_samples), '--output', str(output)]
        if case['engine'] == 'pipecat':
            command = [args.python, '-u', str(ROOT / 'comparison/pipecat/benchmark.py')] + common
        else:
            command = [args.java, '-Xms128m', '-Xmx512m', '-jar', str(artifact), '--engine', case['engine']] + common
        try:
            resources, logs = collect(command, output, args.timeout, private / (name + '.log'))
            (private / (name + '.log')).write_text(logs)
            data = validate_result(json.loads(output.read_text()), case, settings)
            results.append({**case, 'manifestSha256': manifest_hash, 'file': 'raw/' + output.name,
                            'sha256': digest(output.read_bytes()), 'result': data, 'resources': resources})
            print(json.dumps({'completed': index + 1, 'total': len(order), **case,
                              'framesPerSecond': round(data['throughputFramesPerSecond'], 1)}), flush=True)
        except Exception as failure:
            (folder / 'failure.json').write_text(json.dumps({'case': case, 'manifestSha256': manifest_hash,
                'error': type(failure).__name__, 'message': str(failure), 'status': 'failed'}, indent=2) + '\n')
            raise
    if source_fingerprint() != source or digest(artifact.read_bytes()) != manifest['javaArtifactSha256']:
        raise ValueError('measurement sources or artifact changed during collection')
    if installed_dependencies(args.python) != manifest['pythonDependencies'] or pipecat_source(args.python) != manifest['pipecatInstalledSource']:
        raise ValueError('Python dependency environment changed during collection')
    suite = {'schemaVersion': 1, 'status': 'complete', 'manifest': manifest,
             'manifestSha256': manifest_hash, 'results': results}
    validate_suite(suite)
    (folder / 'suite.json').write_text(json.dumps(suite, indent=2, allow_nan=False) + '\n')
    print(f'Validated all {len(results)} independent processes; no failed forks omitted.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--python', default=sys.executable)
    parser.add_argument('--jar', default='comparison/benchmarks/target/benchmark.jar')
    parser.add_argument('--forks', type=int, choices=range(1, 10), default=5)
    parser.add_argument('--stages', default='1,4,16')
    parser.add_argument('--engines', default=','.join(ENGINES))
    parser.add_argument('--warmup', type=float, default=3)
    parser.add_argument('--measure', type=float, default=5)
    parser.add_argument('--max-samples', type=int, default=2000000)
    parser.add_argument('--timeout', type=float, default=90)
    args = parser.parse_args()
    if not all(0 < v <= 600 for v in (args.warmup, args.measure)) or not 100 <= args.max_samples <= 2000000:
        parser.error('invalid duration or sample budget')
    run(args)


if __name__ == '__main__':
    main()
