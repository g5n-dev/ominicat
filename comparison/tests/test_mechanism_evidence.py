"""Synthetic fail-closed mechanism evidence tests; no Java/Maven/benchmark runs."""
from copy import deepcopy
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock
import zipfile


SOURCE = Path(__file__).resolve().parents[1] / 'mechanism_evidence.py'
spec = importlib.util.spec_from_file_location('mechanism_evidence_fixture', SOURCE)
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def environment():
    return {'os': 'Linux', 'kernelRelease': '6.8.0-synthetic', 'architecture': 'x86_64',
            'distro': {'ID': 'ubuntu', 'VERSION_ID': '24.04', 'PRETTY_NAME': 'Ubuntu 24.04 LTS'},
            'cpuModel': 'Synthetic CPU', 'logicalProcessors': 4, 'affinityCount': 4,
            'java': {'version': '17.0.21', 'vmVersion': '17.0.21+9',
                     'vmName': 'OpenJDK 64-Bit Server VM', 'vendor': 'Synthetic JDK',
                     'os': 'Linux', 'architecture': 'amd64'},
            'scope': 'Synthetic fixture, never measured performance evidence.'}


def dependencies():
    result = []
    for coordinate in ('io.ominicat:ominicat-core:0.1.0-SNAPSHOT',
                       'org.apache.camel:camel-direct:4.22.2', 'org.openjdk.jmh:jmh-core:1.37'):
        group, name, version = coordinate.split(':')
        result.append({'coordinates': coordinate,
                       'artifact': '/'.join(group.split('.') + [name, version, name + '-' + version + '.jar']),
                       'sha256': evidence.digest(coordinate.encode())})
    return sorted(result, key=lambda item: item['coordinates'])


def raw_result(case, jar_sha, offset=0):
    settings = evidence.settings(jar_sha)
    result = {key: settings[key] for key in ('jmhVersion', 'mode', 'threads', 'warmupIterations',
              'measurementIterations', 'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize')}
    result.update({'benchmark': case['benchmark'], 'params': case['params'], 'forks': 1,
                   'jvmArgs': settings['jvmArgs'], 'jvm': '/opt/java17/bin/java',
                   'jdkVersion': '17.0.21', 'vmVersion': '17.0.21+9', 'vmName': 'OpenJDK 64-Bit Server VM'})
    def metric(base, unit):
        values = [base + offset + n for n in range(5)]
        return {'score': sum(values) / 5, 'scoreUnit': unit, 'rawData': [values]}
    result['primaryMetric'] = metric(10, 'ns/op')
    result['secondaryMetrics'] = {'gc.alloc.rate.norm': metric(64, 'B/op'),
                                  'process.cpu.ns/op': metric(8, 'ns/op'),
                                  'gc.count': {'score': 0, 'scoreError': 'NaN', 'scoreUnit': 'counts', 'rawData': [[0] * 5]}}
    return [result]


def fixture(folder):
    folder.mkdir(parents=True)
    (folder / 'raw').mkdir()
    (folder / 'logs').mkdir()
    jar_sha = evidence.digest(b'synthetic measured JAR')
    files = {path: evidence.digest(('synthetic ' + path).encode()) for path in evidence.REQUIRED_SOURCES}
    files.update({path: evidence.digest(path.encode()) for path in
                  ('ominicat-core/src/main/java/io/ominicat/core/Frame.java',
                   'comparison/mechanisms/src/main/java/io/ominicat/mechanisms/PathBenchmark.java')})
    manifest = {'schemaVersion': 1, 'protocol': evidence.PROTOCOL, 'status': 'complete',
                'recordedAt': '2026-11-02T00:00:00Z', 'sourceRevision': 'a' * 40, 'sourceTreeDirty': False,
                'sourceFingerprint': {'files': files, 'aggregate': evidence.digest(evidence.canonical(files))},
                'artifact': {'file': evidence.JAR, 'sha256': jar_sha, 'dependencies': dependencies()},
                'environment': environment(), 'settings': evidence.settings(jar_sha), 'caseOrder': evidence.plan()}
    records = []
    for case in evidence.plan():
        raw = raw_result(case, jar_sha, case['position'])
        evidence.write_json(folder / case['raw'], raw)
        (folder / case['log']).write_text('Synthetic JMH fixture\nMECHANISM_ORIGIN_SHA256=' + jar_sha + '\n')
        records.append({'position': case['position'], 'raw': case['raw'], 'log': case['log'],
                        'rawSha256': evidence.digest((folder / case['raw']).read_bytes()),
                        'logSha256': evidence.digest((folder / case['log']).read_bytes()),
                        'metrics': evidence.validate_result(raw, case, manifest['settings'], manifest['environment'])})
    suite = {'schemaVersion': 1, 'protocol': evidence.PROTOCOL, 'status': 'complete',
             'manifestSha256': evidence.digest(evidence.canonical(manifest)), 'results': records}
    evidence.write_json(folder / 'manifest.json', manifest)
    evidence.write_json(folder / 'suite.json', suite)
    evidence.render(folder, verify_checksums=False)
    return manifest, suite


def rebind(folder, manifest, suite):
    suite['manifestSha256'] = evidence.digest(evidence.canonical(manifest))
    evidence.write_json(folder / 'manifest.json', manifest)
    evidence.write_json(folder / 'suite.json', suite)
    (folder / 'SHA256SUMS').write_text(evidence.checksums(folder))


class MechanismEvidenceTest(unittest.TestCase):
    def test_complete_six_cell_forward_reverse_matrix_retains_every_iteration(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'measurements'
            manifest, suite = fixture(folder)
            self.assertEqual(evidence.check(folder), (manifest, suite))
            summary = evidence.read_json(folder / 'summary.json')
            self.assertEqual(len(summary['cells']), 6)
            for cell in summary['cells']:
                for metric in cell['metrics'].values():
                    self.assertEqual(len(metric['forkMeans']), 2)
                    self.assertEqual(len(metric['iterations']), 10)
                    self.assertEqual(metric['iterationMin'], min(metric['iterations']))
                    self.assertEqual(metric['iterationMax'], max(metric['iterations']))
            self.assertEqual([case['id'] for case in manifest['caseOrder'][6:]],
                             list(reversed([case['id'] for case in manifest['caseOrder'][:6]])))
            before = (folder / 'SHA256SUMS').read_bytes()
            evidence.render(folder)
            self.assertEqual(before, (folder / 'SHA256SUMS').read_bytes())

    def test_missing_duplicate_unknown_and_reordered_records_fail(self):
        for mutation in ('missing', 'duplicate', 'unknown', 'reordered'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'measurements'
                manifest, suite = fixture(folder)
                if mutation == 'missing':
                    suite['results'].pop()
                elif mutation == 'duplicate':
                    suite['results'][1] = deepcopy(suite['results'][0])
                elif mutation == 'unknown':
                    suite['results'][0]['position'] = 99
                else:
                    suite['results'].reverse()
                rebind(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    evidence.check(folder)

    def test_modified_raw_log_and_origin_cannot_pass_even_with_rebound_file_hashes(self):
        for mutation in ('raw', 'origin', 'duplicate-origin', 'missing-origin', 'private-log'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'measurements'
                manifest, suite = fixture(folder)
                item = suite['results'][0]
                if mutation == 'raw':
                    raw = evidence.read_json(folder / item['raw'])
                    raw[0]['params'] = {'framework': 'unknown', 'execution': 'caller'}
                    evidence.write_json(folder / item['raw'], raw)
                else:
                    log = folder / item['log']
                    content = log.read_text()
                    if mutation == 'origin': content = content.replace(manifest['artifact']['sha256'], 'b' * 64)
                    elif mutation == 'duplicate-origin': content += content
                    elif mutation == 'missing-origin': content = 'Synthetic missing origin\n'
                    else: content += 'sk-' + 'a' * 30
                    log.write_text(content)
                item['rawSha256'] = evidence.digest((folder / item['raw']).read_bytes())
                item['logSha256'] = evidence.digest((folder / item['log']).read_bytes())
                rebind(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    evidence.check(folder)

    def test_jar_rebinding_cannot_override_recorded_fork_origin_and_arguments(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'measurements'
            manifest, suite = fixture(folder)
            manifest['artifact']['sha256'] = 'b' * 64
            manifest['settings'] = evidence.settings('b' * 64)
            rebind(folder, manifest, suite)
            with self.assertRaises(ValueError):
                evidence.check(folder)

    def test_real_jmh_trial_prefix_preserves_the_original_log_and_rejects_other_markers(self):
        marker = 'MECHANISM_ORIGIN_SHA256=' + 'a' * 64
        for text in (marker + '\n', '# Warmup Iteration   1: ' + marker + '\n'):
            evidence.validate_origin(text.encode(), 'a' * 64)
        for text in ('# Warmup Iteration   2: ' + marker + '\n',
                     '# Warmup Iteration   1: ' + marker + '\n' + marker + '\n',
                     marker + '\nignored-prefix ' + marker + '\n',
                     '# Warmup Iteration   1: MECHANISM_ORIGIN_SHA256=' + 'b' * 64 + '\n'):
            with self.assertRaises(ValueError): evidence.validate_origin(text.encode(), 'a' * 64)

    def test_incomplete_nonfinite_and_wrong_metric_or_runtime_settings_fail(self):
        mutations = {
            'threads': lambda r: r.update(threads=2),
            'boolean-threads': lambda r: r.update(threads=True),
            'forks': lambda r: r.update(forks=2),
            'duration': lambda r: r.update(measurementTime='500 ms'),
            'iterations': lambda r: r.update(measurementIterations=4),
            'runtime': lambda r: r.update(jdkVersion='21.0.9'),
            'version': lambda r: r.update(jmhVersion='1.36'),
            'args': lambda r: r['jvmArgs'].append('-Dunexpected=true'),
            'missing-cpu': lambda r: r['secondaryMetrics'].pop('process.cpu.ns/op'),
            'nan': lambda r: r['primaryMetric'].update(score=float('nan')),
            'infinite': lambda r: r['secondaryMetrics']['gc.alloc.rate.norm'].update(score=float('inf')),
            'wrong-unit': lambda r: r['primaryMetric'].update(scoreUnit='us/op'),
            'raw-iterations': lambda r: r['primaryMetric']['rawData'][0].pop(),
            'raw-forks': lambda r: r['primaryMetric']['rawData'].append([1] * 5),
            'inconsistent-mean': lambda r: r['primaryMetric'].update(score=999),
            'unexpected-field': lambda r: r.update(privateNotes='unapproved field'),
        }
        case = evidence.plan()[0]
        jar_sha = 'a' * 64
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                raw = raw_result(case, jar_sha)
                mutate(raw[0])
                with self.assertRaises(ValueError):
                    evidence.validate_result(raw, case, evidence.settings(jar_sha), environment())

    def test_manifest_profile_source_and_environment_rejections(self):
        for mutation in ('partial', 'settings', 'boolean', 'source-path', 'source-hash', 'source-missing',
                         'dirty', 'os', 'architecture', 'host-extra', 'dependency-version', 'duplicate-dependency'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'measurements'
                manifest, suite = fixture(folder)
                if mutation == 'partial': manifest['status'] = 'collecting'
                elif mutation == 'settings': manifest['settings']['warmupIterations'] = 2
                elif mutation == 'boolean': manifest['settings']['threads'] = True
                elif mutation == 'source-path': manifest['sourceFingerprint']['files']['private-notes.txt'] = 'c' * 64
                elif mutation == 'source-hash': manifest['sourceFingerprint']['files'][evidence.REQUIRED_SOURCES[0]] = 'invalid'
                elif mutation == 'source-missing': manifest['sourceFingerprint']['files'].pop(evidence.REQUIRED_SOURCES[0])
                elif mutation == 'dirty': manifest['sourceTreeDirty'] = True
                elif mutation == 'os': manifest['environment']['os'] = 'Darwin'
                elif mutation == 'architecture': manifest['environment']['java']['architecture'] = 'aarch64'
                elif mutation == 'host-extra': manifest['environment']['hostname'] = 'must-not-export'
                elif mutation == 'dependency-version': manifest['artifact']['dependencies'][1]['coordinates'] = 'org.apache.camel:camel-direct:4.18.0'
                else: manifest['artifact']['dependencies'].append(deepcopy(manifest['artifact']['dependencies'][0]))
                manifest['sourceFingerprint']['aggregate'] = evidence.digest(evidence.canonical(manifest['sourceFingerprint']['files']))
                rebind(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    evidence.check(folder)

    def test_unknown_files_symlinks_and_diagnostics_are_refused_before_render(self):
        for unexpected in ('unreviewed.jfr', 'notes.md', 'diagnostics'):
            with self.subTest(unexpected=unexpected), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'measurements'
                fixture(folder)
                before = (folder / 'summary.md').read_bytes()
                if unexpected == 'diagnostics': (folder / unexpected).mkdir()
                else: (folder / unexpected).write_bytes(b'synthetic unapproved file')
                with self.assertRaises(ValueError): evidence.render(folder)
                self.assertEqual(before, (folder / 'summary.md').read_bytes())
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'measurements'
            fixture(folder)
            summary = folder / 'summary.md'
            summary.unlink()
            summary.symlink_to(SOURCE)
            with self.assertRaises(ValueError): evidence.check(folder)

    def test_checksum_and_derived_summary_corruption_are_rejected(self):
        for filename in ('summary.json', 'summary.md', 'SHA256SUMS'):
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'measurements'
                fixture(folder)
                if filename == 'summary.json':
                    value = evidence.read_json(folder / filename)
                    value['cells'][0]['metrics']['timeNsPerOp']['forkMeans'][0] += 1
                    evidence.write_json(folder / filename, value)
                else: (folder / filename).write_text('synthetic corrupted derivative')
                if filename != 'SHA256SUMS': (folder / 'SHA256SUMS').write_text(evidence.checksums(folder))
                with self.assertRaises(ValueError): evidence.check(folder)

    def test_cli_rejection_never_echoes_private_input_or_traceback(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'measurements'
            fixture(folder)
            (folder / 'manifest.json').write_text('[]')
            output = io.StringIO()
            with redirect_stderr(output), redirect_stdout(output):
                self.assertEqual(evidence.main(['check', str(folder)]), 1)
            self.assertNotIn(temporary, output.getvalue())
            self.assertNotIn('Traceback', output.getvalue())

    def test_nonfinite_numeric_json_is_rejected_even_in_unused_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'raw.json'
            for literal in ('NaN', 'Infinity', '1e999'):
                path.write_text('{"auxiliary":' + literal + '}')
                with self.assertRaises(ValueError): evidence.read_json(path)

    def test_dependency_jars_are_hashed_without_exporting_repository_locations(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            jar = folder / 'mechanisms.jar'
            repository = folder / 'repository'
            with zipfile.ZipFile(jar, 'w') as archive:
                for dependency in dependencies():
                    group, name, version = dependency['coordinates'].split(':')
                    archive.writestr(f'META-INF/maven/{group}/{name}/pom.properties',
                                     f'groupId={group}\nartifactId={name}\nversion={version}\n')
                    path = repository / dependency['artifact']
                    path.parent.mkdir(parents=True)
                    path.write_bytes(dependency['coordinates'].encode())
                group, name, version = evidence.OWN_COORDINATE.split(':')
                archive.writestr(f'META-INF/maven/{group}/{name}/pom.properties',
                                 f'groupId={group}\nartifactId={name}\nversion={version}\n')
            actual = evidence.dependency_artifacts(jar, repository)
            self.assertEqual(actual, dependencies())
            self.assertNotIn(temporary, json.dumps(actual))
            (repository / actual[0]['artifact']).unlink()
            with self.assertRaises(ValueError): evidence.dependency_artifacts(jar, repository)


class CollectorLifecycleTest(unittest.TestCase):
    def fake_root(self, temporary):
        root = Path(temporary) / 'checkout'
        root.mkdir()
        for name in evidence.REQUIRED_SOURCES + ('ominicat-core/src/main/java/Frame.java',
                                                'comparison/mechanisms/src/main/java/PathBenchmark.java'):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if name == 'comparison/mechanism_evidence.py':
                path.write_bytes(SOURCE.read_bytes())
            elif name == 'scripts/package_profiles.py':
                path.write_bytes((evidence.ROOT / name).read_bytes())
            else:
                path.write_text('synthetic measured source')
        jar = root / evidence.JAR
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b'synthetic measured JAR')
        return root

    def test_an_external_checkout_cannot_claim_a_different_collector_as_executed_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fake_root(temporary)
            (root / 'comparison/mechanism_evidence.py').write_text('synthetic different collector')
            output = root / 'reports/local/capture/evidence/measurements'
            with mock.patch.object(evidence, 'run_command') as launch:
                with self.assertRaisesRegex(ValueError, 'executed collector'):
                    evidence.collect(root, output, java='/opt/java17/bin/java')
            launch.assert_not_called()
            self.assertFalse((output / 'manifest.json').exists())

    def test_complete_collector_runs_twelve_fixed_commands_and_verifies_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fake_root(temporary)
            output = root / 'reports/local/capture/evidence/measurements'
            calls = []
            def run(command, cwd, log):
                case = evidence.plan()[len(calls)]
                calls.append(command)
                raw = root / command[command.index('-rff') + 1]
                jar_sha = evidence.digest((root / evidence.JAR).read_bytes())
                evidence.write_json(raw, raw_result(case, jar_sha, len(calls)))
                log.write_text('MECHANISM_ORIGIN_SHA256=' + jar_sha + '\n')
                self.assertFalse(Path(command[command.index('-rff') + 1]).is_absolute())
            def git(command, **kwargs):
                return types.SimpleNamespace(stdout='a' * 40 if command[1] == 'rev-parse' else '')
            with mock.patch.object(evidence, 'run_command', side_effect=run), \
                    mock.patch.object(evidence, 'host_environment', return_value=environment()), \
                    mock.patch.object(evidence, 'dependency_artifacts', return_value=dependencies()), \
                    mock.patch.object(evidence.subprocess, 'run', side_effect=git):
                evidence.collect(root, output, java='/opt/java17/bin/java')
            self.assertEqual(len(calls), 12)
            for command in calls:
                self.assertEqual(command[command.index('-f') + 1], '1')
                self.assertEqual(command[command.index('-foe') + 1], 'true')
                self.assertIn(evidence.CPU_PROFILER, command)
            evidence.check(output)

    def test_source_or_jar_change_during_collection_never_writes_success_manifest(self):
        for changed in ('source', 'jar', 'dependency', 'nonzero'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as temporary:
                root = self.fake_root(temporary)
                output = root / 'reports/local/capture/evidence/measurements'
                launched = False
                def run(command, cwd, log):
                    nonlocal launched
                    launched = True
                    if changed == 'nonzero': raise ValueError('synthetic nonzero exit')
                    case = evidence.plan()[0]
                    jar_sha = evidence.digest((root / evidence.JAR).read_bytes())
                    evidence.write_json(root / command[command.index('-rff') + 1], raw_result(case, jar_sha))
                    log.write_text('MECHANISM_ORIGIN_SHA256=' + jar_sha + '\n')
                    if changed in ('source', 'jar'):
                        target = root / (evidence.JAR if changed == 'jar' else 'ominicat-core/src/main/java/Frame.java')
                        target.write_bytes(b'changed after fork launch')
                def read_dependencies(*args):
                    values = dependencies()
                    if changed == 'dependency' and launched:
                        values[0]['sha256'] = 'b' * 64
                    return values
                def git(command, **kwargs):
                    return types.SimpleNamespace(stdout='a' * 40 if command[1] == 'rev-parse' else '')
                with mock.patch.object(evidence, 'run_command', side_effect=run), \
                        mock.patch.object(evidence, 'host_environment', return_value=environment()), \
                        mock.patch.object(evidence, 'dependency_artifacts', side_effect=read_dependencies), \
                        mock.patch.object(evidence.subprocess, 'run', side_effect=git):
                    with self.assertRaises(ValueError): evidence.collect(root, output, java='/opt/java17/bin/java')
                self.assertFalse((output / 'manifest.json').exists())
                self.assertFalse((output / 'summary.json').exists())
                self.assertEqual(evidence.read_json(output / 'failure.json')['status'], 'failed')

    def test_real_tiny_nonzero_process_cannot_become_successful_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'fork.log'
            with self.assertRaises(ValueError):
                evidence.run_command([sys.executable, '-c', 'print("synthetic result"); raise SystemExit(7)'],
                                     Path(temporary), log)
            self.assertIn('synthetic result', log.read_text())

    @unittest.skipUnless(os.name == 'posix', 'requires owned POSIX process groups')
    def test_timeout_terminates_and_reaps_launcher_and_its_waited_child(self):
        script = '''import os,signal,subprocess,sys,time
child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"])
def stop(signum,frame):
    child.wait(timeout=2)
    raise SystemExit(0)
signal.signal(signal.SIGTERM,stop)
print(child.pid,flush=True)
time.sleep(30)
'''
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'fork.log'
            with self.assertRaises(TimeoutError):
                evidence.run_command([sys.executable, '-c', script], Path(temporary), log, timeout=.5)
            child_pid = int(log.read_text().splitlines()[0])
            with self.assertRaises(ProcessLookupError): os.kill(child_pid, 0)

    def test_stop_group_still_runs_after_launcher_has_already_exited(self):
        process = mock.Mock(pid=12345)
        process.wait.return_value = 0
        with mock.patch.object(evidence.os, 'killpg') as kill:
            evidence.stop_owned(process)
        self.assertEqual(kill.call_args_list, [mock.call(12345, signal.SIGTERM), mock.call(12345, signal.SIGKILL)])


if __name__ == '__main__':
    unittest.main()
