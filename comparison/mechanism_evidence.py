#!/usr/bin/env python3
"""Collect and check the independent Java-only mechanism experiment (stdlib only)."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import signal
import statistics
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = 'ominicat-camel-mechanisms-v1'
PATH_BENCHMARK = 'io.ominicat.mechanisms.PathBenchmark.roundTrip'
COPY_BENCHMARK = 'io.ominicat.mechanisms.ExchangeCopyBenchmark.copy'
CPU_PROFILER = 'io.ominicat.mechanisms.ProcessCpuProfiler'
OWN_COORDINATE = 'io.ominicat.comparison:mechanism-probes:0.1.0-SNAPSHOT'
JAR = 'comparison/mechanisms/target/mechanisms.jar'
REQUIRED_SOURCES = ('pom.xml', 'comparison/mechanisms/pom.xml',
                    'comparison/mechanism_evidence.py', 'comparison/tests/test_mechanism_evidence.py',
                    'comparison/mechanism-protocol.md', '.github/workflows/mechanism-evidence.yml',
                    'scripts/package_profiles.py')
SHA = re.compile(r'[0-9a-f]{64}\Z')
ORIGIN = re.compile(r'^(?:# Warmup Iteration[ \t]+1:[ \t]*)?MECHANISM_ORIGIN_SHA256=([0-9a-f]{64})[ \t]*\r?$', re.M)
MAX_FILE_BYTES = 8 * 1024 * 1024
METRICS = {'timeNsPerOp': ('primaryMetric', 'ns/op'),
           'allocatedBytesPerOp': ('gc.alloc.rate.norm', 'B/op'),
           'processCpuNsPerOp': ('process.cpu.ns/op', 'ns/op')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def number(value, name, *, positive=False):
    require(type(value) in (int, float) and math.isfinite(value), 'nonfinite or invalid ' + name)
    require(value > 0 if positive else value >= 0, 'out-of-range ' + name)
    return value


def read_bytes(path):
    require(not path.is_symlink() and path.is_file(), 'expected a regular evidence file')
    require(path.stat().st_size <= MAX_FILE_BYTES, 'evidence file exceeds size limit')
    return path.read_bytes()


def read_json(path):
    def invalid(_):
        raise ValueError('nonfinite JSON number')
    def finite_float(value):
        parsed = float(value)
        require(math.isfinite(parsed), 'nonfinite JSON number')
        return parsed
    return json.loads(read_bytes(path), parse_constant=invalid, parse_float=finite_float)


def privacy(data):
    spec = importlib.util.spec_from_file_location('mechanism_privacy', ROOT / 'scripts/package_profiles.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.privacy_check('mechanism evidence', data)


def cases():
    result = []
    for framework in ('ominicat', 'camel'):
        for execution in ('caller', 'handoff'):
            result.append({'id': framework + '-' + execution, 'benchmark': PATH_BENCHMARK,
                           'params': {'framework': framework, 'execution': execution}})
    result.extend({'id': operation, 'benchmark': COPY_BENCHMARK, 'params': {'operation': operation}}
                  for operation in ('identity', 'correlated-copy'))
    return result


def validate_origin(data, jar_sha):
    text = data.decode('utf-8')
    require(text.count('MECHANISM_ORIGIN_SHA256=') == 1 and ORIGIN.findall(text) == [jar_sha],
            'fork JAR origin was not verified exactly once')


def plan():
    result = []
    for repeat, ordered in enumerate((cases(), list(reversed(cases()))), 1):
        for case in ordered:
            position = len(result) + 1
            name = f'{position:02d}-p{repeat}-{case["id"]}'
            result.append({**case, 'position': position, 'pass': repeat,
                           'raw': 'raw/' + name + '.json', 'log': 'logs/' + name + '.log'})
    return result


def settings(jar_sha):
    return {'jmhVersion': '1.37', 'mode': 'avgt', 'unit': 'ns/op', 'threads': 1,
            'forksPerCommand': 1, 'passes': 2, 'warmupIterations': 3, 'measurementIterations': 5,
            'warmupTime': '1 s', 'measurementTime': '1 s', 'warmupBatchSize': 1,
            'measurementBatchSize': 1, 'fixedApplicationStages': 4,
            'profilers': ['gc', CPU_PROFILER],
            'jvmArgs': ['-Xms256m', '-Xmx256m', '-Dominicat.mechanism.jarSha256=' + jar_sha]}


def validate_result(data, case, expected, environment):
    require(isinstance(data, list) and len(data) == 1, 'each command must contain exactly one JMH case')
    result = data[0]
    require(isinstance(result, dict), 'invalid JMH result')
    allowed = {'jmhVersion', 'benchmark', 'mode', 'threads', 'forks', 'jvm', 'jvmArgs', 'jdkVersion',
               'vmName', 'vmVersion', 'warmupIterations', 'warmupTime', 'warmupBatchSize',
               'measurementIterations', 'measurementTime', 'measurementBatchSize', 'params',
               'primaryMetric', 'secondaryMetrics'}
    require(set(result) <= allowed, 'unexpected JMH result field')
    require(result.get('benchmark') == case['benchmark'] and result.get('params') == case['params'],
            'benchmark or parameters differ from the plan')
    for key in ('jmhVersion', 'mode', 'threads', 'warmupIterations', 'measurementIterations',
                'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize'):
        require(type(result.get(key)) is type(expected[key]) and result[key] == expected[key],
                'JMH settings differ from the plan')
    require(type(result.get('forks')) is int and result['forks'] == 1, 'expected one fresh JMH fork')
    require(result.get('jvmArgs') == expected['jvmArgs'], 'unexpected fork JVM arguments')
    java = environment['java']
    for key, name in (('jdkVersion', 'version'), ('vmVersion', 'vmVersion'), ('vmName', 'vmName')):
        require(result.get(key) == java[name], 'mixed JDK runtime evidence')
    require(str(result.get('jdkVersion', '')).split('.')[0] == '17', 'Java 17 is required')
    observed = {}
    for name, (key, unit) in METRICS.items():
        metric = result.get('primaryMetric') if key == 'primaryMetric' else result.get('secondaryMetrics', {}).get(key)
        require(isinstance(metric, dict) and metric.get('scoreUnit') == unit, 'missing metric or incorrect unit')
        score = number(metric.get('score'), name, positive=name == 'timeNsPerOp')
        raw = metric.get('rawData')
        require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list)
                and len(raw[0]) == expected['measurementIterations'], 'incomplete per-iteration metric data')
        values = [number(value, name, positive=name == 'timeNsPerOp') for value in raw[0]]
        require(abs(score - statistics.mean(values)) <= max(1e-6, abs(score) * 1e-6),
                'metric mean disagrees with raw iterations')
        observed[name] = {'mean': score, 'iterations': values}
    return observed


def source_fingerprint(root):
    paths = [root / name for name in REQUIRED_SOURCES]
    for prefix in ('ominicat-core/src/main/java', 'comparison/mechanisms/src'):
        found = sorted((root / prefix).rglob('*.java'))
        require(bool(found), 'missing measured Java sources')
        paths.extend(found)
    files = {}
    for path in sorted(set(paths)):
        relative = path.relative_to(root).as_posix()
        files[relative] = digest(read_bytes(path))
    return {'files': files, 'aggregate': digest(canonical(files))}


def dependency_artifacts(jar, repository):
    """Bind resolved dependency JARs without exporting the Maven repository path."""
    artifacts = []
    with zipfile.ZipFile(jar) as archive:
        names = sorted(name for name in archive.namelist() if re.fullmatch(r'META-INF/maven/[^/]+/[^/]+/pom.properties', name))
        require(0 < len(names) <= 256, 'missing or excessive dependency metadata')
        seen = set()
        for name in names:
            info = archive.getinfo(name)
            require(info.file_size <= 65536, 'oversize dependency metadata')
            properties = dict(line.split('=', 1) for line in archive.read(name).decode('utf-8').splitlines()
                              if '=' in line and not line.lstrip().startswith('#'))
            parts = [properties.get(key, '').strip() for key in ('groupId', 'artifactId', 'version')]
            require(all(re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,159}', part)
                        and all(segment not in ('', '.', '..') for segment in part.split('.'))
                        for part in parts), 'invalid Maven coordinate')
            group, artifact, version = parts
            coordinate = ':'.join(parts)
            require(coordinate not in seen, 'duplicate dependency coordinate')
            seen.add(coordinate)
            if coordinate == OWN_COORDINATE:
                continue
            relative = Path(*group.split('.')) / artifact / version / (artifact + '-' + version + '.jar')
            path = repository / relative
            # Dependency JARs can be larger than the small JSON/log evidence budget.
            require(not path.is_symlink() and path.is_file(), 'resolved dependency JAR is unavailable')
            artifacts.append({'coordinates': coordinate, 'artifact': relative.as_posix(), 'sha256': digest(path.read_bytes())})
    required = {'io.ominicat:ominicat-core:0.1.0-SNAPSHOT', 'org.apache.camel:camel-direct:4.22.2',
                'org.openjdk.jmh:jmh-core:1.37'}
    require(required <= {entry['coordinates'] for entry in artifacts}, 'missing required pinned dependency artifacts')
    return sorted(artifacts, key=lambda entry: entry['coordinates'])


def child_environment():
    result = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'):
        result.pop(key, None)
    return result


def java_environment(java):
    process = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                             capture_output=True, text=True, timeout=20, check=True, env=child_environment())
    wanted = {'java.version': 'version', 'java.vm.version': 'vmVersion', 'java.vm.name': 'vmName',
              'java.vendor': 'vendor', 'os.name': 'os', 'os.arch': 'architecture'}
    result = {}
    for line in process.stderr.splitlines():
        key, separator, value = line.strip().partition(' = ')
        if separator and key in wanted:
            result[wanted[key]] = value
    require(set(result) == set(wanted.values()) and result['version'].split('.')[0] == '17',
            'missing or unsupported Java runtime metadata')
    return result


def host_environment(java):
    require(platform.system() == 'Linux', 'measurement requires Linux Ubuntu 24.04')
    distro = {}
    for line in Path('/etc/os-release').read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and key in ('ID', 'VERSION_ID', 'PRETTY_NAME'):
            parsed = shlex.split(value)
            require(len(parsed) == 1, 'invalid distribution metadata')
            distro[key] = parsed[0]
    require(distro.get('ID') == 'ubuntu' and distro.get('VERSION_ID') == '24.04', 'measurement requires Ubuntu 24.04')
    cpu = ''
    for line in Path('/proc/cpuinfo').read_text().splitlines():
        key, separator, value = line.partition(':')
        if separator and key.strip() in ('model name', 'Model name'):
            cpu = value.strip()
            break
    require(bool(cpu), 'CPU model metadata is unavailable')
    return {'os': 'Linux', 'kernelRelease': platform.release(), 'architecture': platform.machine(),
            'distro': distro, 'cpuModel': cpu, 'logicalProcessors': os.cpu_count(),
            'affinityCount': len(os.sched_getaffinity(0)), 'java': java_environment(java),
            'scope': 'Shared runner; sequential cases; no exclusive CPU isolation. No RSS or p99 measurement.'}


def stop_owned(process):
    # Kill the owned group even when the launcher has already exited but left a fork.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=3)


def run_command(command, cwd, log, timeout=120):
    with log.open('xb') as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=child_environment())
        started = time.monotonic()
        try:
            while process.poll() is None:
                require(log.stat().st_size <= MAX_FILE_BYTES, 'JMH diagnostics exceed size limit')
                if time.monotonic() - started > timeout:
                    raise TimeoutError('JMH command deadline exceeded')
                time.sleep(.05)
            require(process.returncode == 0, 'JMH command failed; inspect private local logs')
        finally:
            stop_owned(process)
    privacy(read_bytes(log))


def command(java, jar_sha, case, raw_argument):
    result = [str(java), '-jar', JAR, '^' + re.escape(case['benchmark']) + '$',
              '-bm', 'avgt', '-tu', 'ns', '-t', '1', '-f', '1', '-wi', '3', '-i', '5',
              '-w', '1s', '-r', '1s', '-foe', 'true', '-prof', 'gc', '-prof', CPU_PROFILER,
              '-jvmArgsAppend', ' '.join(settings(jar_sha)['jvmArgs']), '-rf', 'json', '-rff', raw_argument]
    for key, value in sorted(case['params'].items()):
        result.extend(['-p', key + '=' + value])
    return result


def write_json(path, value):
    path.write_bytes(json.dumps(value, indent=2, allow_nan=False).encode('utf-8') + b'\n')


def collect(root, output, *, java=None, repository=None):
    root, output = Path(root).resolve(), Path(output).absolute()
    require(not output.is_symlink(), 'output must not be a symlink')
    output = output.resolve()
    require(output.is_relative_to(root / 'reports/local'), 'collect output must remain under reports/local')
    require(not output.exists() or not any(output.iterdir()), 'choose a new empty measurements folder')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'raw').mkdir()
    (output / 'logs').mkdir()
    java = Path(java or (Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java')).resolve()
    repository = Path(repository or Path.home() / '.m2/repository').resolve()
    jar = root / JAR
    require(not jar.is_symlink() and jar.is_file(), 'measured JAR is missing')
    jar_sha = digest(jar.read_bytes())
    source = source_fingerprint(root)
    for name, executed in (('comparison/mechanism_evidence.py', Path(__file__)),
                           ('scripts/package_profiles.py', ROOT / 'scripts/package_profiles.py')):
        require(source['files'][name] == digest(read_bytes(executed)),
                'executed collector or privacy code differs from the measured checkout')
    dependencies = dependency_artifacts(jar, repository)
    environment = host_environment(java)
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True,
                              text=True, check=True).stdout.strip()
    require(re.fullmatch(r'[0-9a-f]{40}', revision) is not None, 'invalid source revision')
    dirty = bool(subprocess.run(['git', 'status', '--porcelain'], cwd=root, capture_output=True,
                                text=True, check=True).stdout.strip())
    require(not dirty, 'measurement requires a clean source tree')
    manifest = {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
                'recordedAt': datetime.now(timezone.utc).isoformat(), 'sourceRevision': revision,
                'sourceTreeDirty': dirty, 'sourceFingerprint': source,
                'artifact': {'file': JAR, 'sha256': jar_sha, 'dependencies': dependencies},
                'environment': environment, 'settings': settings(jar_sha), 'caseOrder': plan()}
    privacy(canonical(manifest))
    records = []
    for case in plan():
        try:
            raw, log = output / case['raw'], output / case['log']
            run_command(command(java, jar_sha, case, os.path.relpath(raw, root)), root, log)
            data = read_bytes(raw)
            privacy(data)
            observed = validate_result(read_json(raw), case, manifest['settings'], environment)
            validate_origin(read_bytes(log), jar_sha)
            require(digest(jar.read_bytes()) == jar_sha and source_fingerprint(root) == source,
                    'measured JAR or source changed during collection')
            require(dependency_artifacts(jar, repository) == dependencies, 'dependency artifacts changed during collection')
            records.append({'position': case['position'], 'raw': case['raw'], 'log': case['log'],
                            'rawSha256': digest(data), 'logSha256': digest(read_bytes(log)), 'metrics': observed})
        except Exception:
            write_json(output / 'failure.json', {'status': 'failed', 'position': case['position'],
                       'reason': 'Collection failed; incomplete evidence is not a successful experiment.'})
            raise
    suite = {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
             'manifestSha256': digest(canonical(manifest)), 'results': records}
    write_json(output / 'manifest.json', manifest)
    write_json(output / 'suite.json', suite)
    render(output, verify_checksums=False)
    return check(output)


def allowed_files():
    return {'manifest.json', 'suite.json', 'summary.json', 'summary.md', 'SHA256SUMS'} | {
        case[key] for case in plan() for key in ('raw', 'log')}


def validate_directory(folder):
    require(not folder.is_symlink() and folder.is_dir(), 'evidence directory must be a real directory')
    for path in folder.rglob('*'):
        require(not path.is_symlink(), 'symlink in evidence directory')
        relative = path.relative_to(folder).as_posix()
        require(relative in ('raw', 'logs') if path.is_dir() else relative in allowed_files(),
                'unapproved file or directory in measurements')


def validate_manifest(manifest):
    require(isinstance(manifest, dict) and set(manifest) == {'schemaVersion', 'protocol', 'status', 'recordedAt',
            'sourceRevision', 'sourceTreeDirty', 'sourceFingerprint', 'artifact', 'environment', 'settings', 'caseOrder'},
            'unexpected manifest fields')
    require(type(manifest.get('schemaVersion')) is int and manifest['schemaVersion'] == 1 and manifest.get('protocol') == PROTOCOL
            and manifest.get('status') == 'complete', 'incomplete or unknown measurement protocol')
    require(canonical(manifest.get('caseOrder')) == canonical(plan()), 'missing, duplicate or reordered measurement cases')
    require(re.fullmatch(r'[0-9a-f]{40}', manifest.get('sourceRevision', '')) is not None
            and manifest.get('sourceTreeDirty') is False, 'invalid source revision or dirty source')
    recorded = datetime.fromisoformat(manifest.get('recordedAt', '').replace('Z', '+00:00'))
    require(recorded.tzinfo is not None and recorded.utcoffset().total_seconds() == 0, 'recordedAt must be UTC')
    artifact = manifest.get('artifact', {})
    require(isinstance(artifact, dict) and set(artifact) == {'file', 'sha256', 'dependencies'}, 'unexpected artifact fields')
    require(artifact.get('file') == JAR and SHA.fullmatch(artifact.get('sha256', '')) is not None, 'invalid measured artifact')
    require(canonical(manifest.get('settings')) == canonical(settings(artifact['sha256'])), 'unexpected experiment settings')
    source = manifest.get('sourceFingerprint', {})
    require(isinstance(source, dict) and set(source) == {'files', 'aggregate'}, 'unexpected source fields')
    files = source.get('files', {})
    require(isinstance(files, dict) and set(REQUIRED_SOURCES) <= set(files), 'missing source fingerprints')
    require(any(path.startswith('ominicat-core/src/main/java/') for path in files)
            and any(path.startswith('comparison/mechanisms/src/') for path in files), 'missing measured module sources')
    require(all(isinstance(path, str) and not PurePosixPath(path).is_absolute() and '..' not in PurePosixPath(path).parts
                and '\\' not in path and isinstance(value, str) and SHA.fullmatch(value)
                for path, value in files.items()), 'invalid source fingerprint entries')
    require(all(path in REQUIRED_SOURCES or path.endswith('.java') and path.startswith(
                ('ominicat-core/src/main/java/', 'comparison/mechanisms/src/')) for path in files),
            'unapproved source inventory path')
    require(source.get('aggregate') == digest(canonical(files)), 'source fingerprint hash mismatch')
    dependencies = artifact.get('dependencies', [])
    require(isinstance(dependencies, list) and bool(dependencies), 'missing dependency artifact hashes')
    coordinates = set()
    for dependency in dependencies:
        require(isinstance(dependency, dict) and set(dependency) == {'coordinates', 'artifact', 'sha256'},
                'unexpected dependency fields')
        coordinate = dependency.get('coordinates', '')
        require(re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+', coordinate) is not None
                and coordinate not in coordinates, 'duplicate or invalid dependency coordinate')
        coordinates.add(coordinate)
        group, name, version = coordinate.split(':')
        require(all(part and part not in ('.', '..') for part in group.split('.') + [name, version]),
                'invalid dependency path component')
        require(group != 'org.apache.camel' or version == '4.22.2', 'mixed Camel dependency versions')
        expected = '/'.join(group.split('.') + [name, version, name + '-' + version + '.jar'])
        require(dependency.get('artifact') == expected and SHA.fullmatch(dependency.get('sha256', '')) is not None,
                'invalid dependency artifact hash or relative path')
    require({'io.ominicat:ominicat-core:0.1.0-SNAPSHOT', 'org.apache.camel:camel-direct:4.22.2',
             'org.openjdk.jmh:jmh-core:1.37'} <= coordinates, 'missing pinned dependencies')
    env = manifest.get('environment', {})
    require(isinstance(env, dict) and set(env) == {'os', 'kernelRelease', 'architecture', 'distro', 'cpuModel',
            'logicalProcessors', 'affinityCount', 'java', 'scope'}, 'unexpected host metadata fields')
    require(set(env.get('distro', {})) == {'ID', 'VERSION_ID', 'PRETTY_NAME'}, 'unexpected distribution fields')
    require(env.get('os') == 'Linux' and env.get('distro', {}).get('ID') == 'ubuntu'
            and env.get('distro', {}).get('VERSION_ID') == '24.04', 'wrong Linux execution target')
    for key in ('kernelRelease', 'architecture', 'cpuModel'):
        require(isinstance(env.get(key), str) and bool(env[key]), 'missing host metadata')
    for key in ('logicalProcessors', 'affinityCount'):
        require(type(env.get(key)) is int and env[key] > 0, 'missing processor/affinity metadata')
    java = env.get('java', {})
    require(isinstance(java, dict) and set(java) == {'version', 'vmVersion', 'vmName', 'vendor', 'os', 'architecture'},
            'unexpected Java runtime fields')
    require(all(isinstance(java.get(key), str) and bool(java[key]) for key in
                ('version', 'vmVersion', 'vmName', 'vendor', 'os', 'architecture'))
            and java['version'].split('.')[0] == '17' and java['os'] == 'Linux', 'unsupported Java runtime')
    normalize = lambda value: {'amd64': 'x86_64', 'arm64': 'aarch64'}.get(value, value)
    require(normalize(java['architecture']) == normalize(env['architecture']), 'JVM and host architectures differ')


def check(folder, *, verify_checksums=True):
    folder = Path(folder)
    validate_directory(folder)
    manifest, suite = read_json(folder / 'manifest.json'), read_json(folder / 'suite.json')
    privacy(canonical(manifest))
    validate_manifest(manifest)
    require(isinstance(suite, dict) and set(suite) == {'schemaVersion', 'protocol', 'status', 'manifestSha256', 'results'},
            'unexpected suite fields')
    require(type(suite.get('schemaVersion')) is int and suite['schemaVersion'] == 1 and suite.get('protocol') == PROTOCOL
            and suite.get('status') == 'complete' and suite.get('manifestSha256') == digest(canonical(manifest)),
            'suite manifest binding mismatch or incomplete suite')
    records = suite.get('results', [])
    require(isinstance(records, list) and len(records) == len(plan()), 'incomplete measurement matrix')
    for case, item in zip(plan(), records):
        require(isinstance(item, dict) and set(item) == {'position', 'raw', 'log', 'rawSha256', 'logSha256', 'metrics'},
                'unexpected measurement record fields')
        require(type(item.get('position')) is int and item['position'] == case['position'] and item.get('raw') == case['raw']
                and item.get('log') == case['log'], 'duplicate, missing or reordered measurement record')
        raw, log = read_bytes(folder / case['raw']), read_bytes(folder / case['log'])
        privacy(raw)
        privacy(log)
        require(digest(raw) == item.get('rawSha256') and digest(log) == item.get('logSha256'), 'raw evidence changed')
        observed = validate_result(read_json(folder / case['raw']), case, manifest['settings'], manifest['environment'])
        require(canonical(observed) == canonical(item.get('metrics')), 'suite metric summary disagrees with raw evidence')
        validate_origin(log, manifest['artifact']['sha256'])
    if verify_checksums:
        expected = checksums(folder)
        require(read_bytes(folder / 'SHA256SUMS').decode('utf-8') == expected, 'evidence checksum inventory mismatch')
        require(read_json(folder / 'summary.json') == summarize(manifest, records), 'summary disagrees with validated results')
        require(read_bytes(folder / 'summary.md').decode('utf-8') == summary_markdown(summarize(manifest, records)),
                'rendered summary disagrees with validated results')
    return manifest, suite


def summarize(manifest, records):
    cells = []
    for case in cases():
        selected = [(planned, record) for planned, record in zip(plan(), records) if planned['id'] == case['id']]
        metrics = {}
        for name in METRICS:
            means = [record['metrics'][name]['mean'] for _, record in selected]
            iterations = [value for _, record in selected for value in record['metrics'][name]['iterations']]
            metrics[name] = {'forkMeans': means, 'medianForkMean': statistics.median(means),
                             'iterationMin': min(iterations), 'iterationMax': max(iterations), 'iterations': iterations}
        cells.append({**case, 'metrics': metrics})
    return {'schemaVersion': 1, 'protocol': PROTOCOL, 'manifestSha256': digest(canonical(manifest)),
            'recordedAt': manifest['recordedAt'], 'environment': manifest['environment'], 'cells': cells}


def summary_markdown(summary):
    lines = ['# Controlled Java mechanism experiment', '',
             'Six cells; forward then reverse order; two fresh single-thread JMH forks per cell. '
             'Each fork has three 1-second warmup iterations and five 1-second measurement iterations. '
             'The table retains both fork means and the full ten-iteration range; a range is not a confidence interval.', '',
             '| Cell | Time ns/op: fork means [iteration range] | Allocation B/op: fork means [iteration range] | Process CPU ns/op: fork means [iteration range] |',
             '| --- | --- | --- | --- |']
    for cell in summary['cells']:
        metrics = cell['metrics']
        def show(name):
            metric = metrics[name]
            means = ' / '.join(f'{value:.6f}' for value in metric['forkMeans'])
            return means + f" [{metric['iterationMin']:.6f}–{metric['iterationMax']:.6f}]"
        lines.append(f"| {cell['id']} | {show('timeNsPerOp')} | {show('allocatedBytesPerOp')} | {show('processCpuNsPerOp')} |")
    lines.extend(['', 'Path cells use four immutable numeric stages and validate every terminal result. '
                  'Caller mode executes inline; handoff mode uses the same external bounded queues and worker. '
                  'Copy cells measure their declared isolated operation, not a complete SEDA request.', '',
                  'Time includes each benchmark’s oracle and acknowledgment work. JMH GC allocation includes harness work; '
                  'process CPU includes JVM activity. These measurements do not provide p99 latency or RSS, and cannot be '
                  'added or subtracted to explain a percentage of an earlier native SEDA comparison. '
                  'Full iteration values and allocation/CPU ranges are in [summary.json](summary.json); '
                  'all original JMH JSON and privacy-checked logs are retained with their checksums.', ''])
    return '\n'.join(lines)


def checksums(folder):
    names = sorted(allowed_files() - {'SHA256SUMS'})
    return ''.join(digest(read_bytes(folder / name)) + '  ' + name + '\n' for name in names)


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
    capture.add_argument('--root', type=Path, required=True)
    capture.add_argument('--output', type=Path, required=True)
    capture.add_argument('--java', type=Path)
    capture.add_argument('--repository', type=Path)
    for action in ('check', 'render'):
        commands.add_parser(action).add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'collect':
            collect(args.root, args.output, java=args.java, repository=args.repository)
        elif args.command == 'check':
            check(args.directory)
        else:
            render(args.directory)
    except (ValueError, OSError, TimeoutError, subprocess.SubprocessError, zipfile.BadZipFile,
            KeyError, TypeError, AttributeError, IndexError) as error:
        print('Mechanism evidence rejected (' + type(error).__name__ + '); no successful result was established.', file=sys.stderr)
        return 1
    print('Complete mechanism evidence validated; no failed cell omitted.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
