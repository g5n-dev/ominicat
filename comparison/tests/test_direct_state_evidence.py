"""Synthetic ABBA evidence and failure controls; no Java or Maven is executed."""
from copy import deepcopy
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

FILE = Path(__file__).resolve().parents[1] / 'direct_state_evidence.py'
spec = importlib.util.spec_from_file_location('direct_state_evidence_fixture', FILE)
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def environment():
    return {'os': 'Linux', 'kernelRelease': '6.8.0-synthetic', 'architecture': 'x86_64',
            'distro': {'ID': 'ubuntu', 'VERSION_ID': '24.04', 'PRETTY_NAME': 'Ubuntu 24.04 LTS'},
            'cpuModel': 'Synthetic CPU', 'logicalProcessors': 4, 'affinityCount': 4,
            'java': {'version': '17.0.21', 'vmVersion': '17.0.21+9', 'vmName': 'OpenJDK 64-Bit Server VM',
                     'vendor': 'Synthetic JDK', 'os': 'Linux', 'architecture': 'amd64'},
            'scope': 'Synthetic fixture, never actual performance evidence.'}


def manifest_fixture():
    core = {name: evidence.digest(name.encode()) for name in evidence.CORE_REQUIRED}
    core['ominicat-core/src/main/java/io/ominicat/core/Frame.java'] = evidence.digest(b'same immutable frame')
    candidate = dict(core)
    candidate[evidence.PIPELINE] = evidence.digest(b'selected direct-only prototype')
    probe = {name: evidence.digest(name.encode()) for name in evidence.PROBE_REQUIRED}
    def source(files, revision):
        return {'revision': revision * 40, 'files': files, 'aggregate': evidence.digest(evidence.canonical(files))}
    return {'schemaVersion': 1, 'protocol': evidence.PROTOCOL, 'status': 'complete',
            'recordedAt': '2026-11-02T00:00:00Z',
            'artifacts': {side: {'relativeJar': evidence.PROBE_JAR if side == 'probe' else evidence.CORE_JAR,
                                 'sha256': evidence.digest((side + ' synthetic JAR').encode())}
                          for side in ('baseline', 'candidate', 'probe')},
            'sources': {'baseline': source(core, 'a'), 'candidate': source(candidate, 'b'), 'probe': source(probe, 'b')},
            'environment': environment(), 'settings': evidence.settings(), 'caseOrder': evidence.plan(),
            'changedCoreSources': [evidence.PIPELINE]}


def raw_fixture(case, manifest, ratios=None):
    expected = evidence.settings()
    row = {key: expected[key] for key in ('jmhVersion', 'mode', 'threads', 'warmupIterations', 'measurementIterations',
                                        'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize')}
    row.update({'benchmark': evidence.BENCHMARK, 'params': {'scenario': case['scenario']}, 'forks': 1,
                'jvm': '/opt/java17/bin/java', 'jvmArgs': evidence.jvm_args(manifest['artifacts'], case['side']),
                'jdkVersion': '17.0.21', 'vmVersion': '17.0.21+9', 'vmName': 'OpenJDK 64-Bit Server VM'})
    def metric(name, unit):
        ratio = .8 if name == 'allocatedBytesPerOp' and case['scenario'] == 'raw-direct-4' else 1
        if ratios and (case['scenario'], name) in ratios:
            ratio = ratios[(case['scenario'], name)][case['block'] - 1]
        mean = 100 * (ratio if case['side'] == 'candidate' else 1)
        mean += -.1 if case['slot'] in (1, 2) else .1
        values = [mean + index * .01 for index in (-2, -1, 0, 1, 2)]
        return {'score': sum(values) / 5, 'scoreUnit': unit, 'rawData': [values]}
    row['primaryMetric'] = metric('timeNsPerOp', 'ns/op')
    row['secondaryMetrics'] = {'gc.alloc.rate.norm': metric('allocatedBytesPerOp', 'B/op'),
                               'gc.count': {'score': 0, 'scoreError': 'NaN'}}
    return [row]


def records_fixture(manifest, ratios=None):
    result = []
    for case in evidence.plan():
        raw = raw_fixture(case, manifest, ratios)
        result.append({'position': case['position'], 'raw': case['raw'], 'log': case['log'],
                       'metrics': evidence.validate_result(raw, case, manifest)})
    return result


def log_fixture(case, manifest):
    return ('# Warmup Iteration   1: DIRECT_STATE_CORE_SHA256=' + manifest['artifacts'][case['side']]['sha256'] + '\n'
            + 'DIRECT_STATE_PROBE_SHA256=' + manifest['artifacts']['probe']['sha256'] + '\n')


def archive_fixture(folder, ratios=None):
    folder.mkdir(parents=True)
    (folder / 'raw').mkdir()
    (folder / 'logs').mkdir()
    manifest = manifest_fixture()
    records = records_fixture(manifest, ratios)
    for case, item in zip(evidence.plan(), records):
        evidence.write_json(folder / case['raw'], raw_fixture(case, manifest, ratios))
        (folder / case['log']).write_text(log_fixture(case, manifest))
        item['rawSha256'] = evidence.digest((folder / case['raw']).read_bytes())
        item['logSha256'] = evidence.digest((folder / case['log']).read_bytes())
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


class PairedStatisticsTest(unittest.TestCase):
    def test_exact_84_fork_plan_has_three_abba_blocks_and_middle_reverse_order(self):
        plan = evidence.plan()
        self.assertEqual(len(plan), 84)
        self.assertEqual(len({item['raw'] for item in plan}), 84)
        for block in (1, 2, 3):
            scenarios = evidence.SCENARIOS if block != 2 else tuple(reversed(evidence.SCENARIOS))
            selected = [case for case in plan if case['block'] == block]
            for index, scenario in enumerate(scenarios):
                self.assertEqual([case['scenario'] for case in selected[index * 4:index * 4 + 4]], [scenario] * 4)
                self.assertEqual([case['side'] for case in selected[index * 4:index * 4 + 4]], list(evidence.ABBA))

    def test_block_ratios_control_slow_drift_instead_of_global_extrema(self):
        manifest = manifest_fixture()
        records = records_fixture(manifest)
        # Multiply both sides within each block by the same large host-speed factor.
        for case, item in zip(evidence.plan(), records):
            factor = {1: 1, 2: 10, 3: .1}[case['block']]
            for metric in item['metrics'].values():
                metric['mean'] *= factor
                metric['iterations'] = [value * factor for value in metric['iterations']]
        summary = evidence.summarize(manifest, records)
        self.assertEqual(summary['decision']['status'], 'observed-targets-met')
        for cell in summary['cells']:
            for side in evidence.SIDES:
                for metric in cell[side].values():
                    self.assertEqual(len(metric['forkMeans']), 6)
                    self.assertEqual(len(metric['iterations']), 30)
            self.assertEqual(len(cell['pairedBlocks']), 3)
            self.assertTrue(all(abs(ratio - 1) < 1e-12 for ratio in cell['ratioSummary']['timeNsPerOp']['blockRatios']))

    def test_uncertain_and_targets_not_met_are_complete_observations(self):
        cases = (
            ({('raw-direct-4', 'allocatedBytesPerOp'): [.88, .88, .92]}, 'uncertain'),
            ({('image-direct-4', 'timeNsPerOp'): [1.02, 1.02, 1.07]}, 'uncertain'),
            ({('raw-direct-4', 'allocatedBytesPerOp'): [.95, .95, .95]}, 'targets-not-met'),
            ({('audio-direct-4', 'allocatedBytesPerOp'): [1.02, 1.02, 1.02]}, 'targets-not-met'),
        )
        for ratios, expected in cases:
            with self.subTest(expected=expected):
                manifest = manifest_fixture()
                summary = evidence.summarize(manifest, records_fixture(manifest, ratios))
                self.assertEqual(summary['decision']['status'], expected)
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            archive_fixture(folder, cases[-1][0])
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(evidence.main(['check', str(folder)]), 0)
            self.assertEqual(evidence.read_json(folder / 'summary.json')['decision']['status'], 'targets-not-met')

    def test_negative_control_is_reported_without_correcting_or_deciding_other_cells(self):
        manifest = manifest_fixture()
        ratios = {('raw-session-4', metric): [2, .5, 10] for metric in evidence.METRICS}
        summary = evidence.summarize(manifest, records_fixture(manifest, ratios))
        self.assertEqual(summary['decision']['status'], 'observed-targets-met')
        control = next(cell for cell in summary['cells'] if cell['negativeControl'])
        self.assertEqual(control['scenario'], 'raw-session-4')
        self.assertTrue(all(item['scenario'] != 'raw-session-4' for item in summary['decision']['criteria']))

    def test_settings_are_not_mutable_global_acceptance_rules(self):
        copy = evidence.settings()
        copy['decisionRule']['directTimeRatioMax'] = 99
        self.assertEqual(evidence.settings()['decisionRule']['directTimeRatioMax'], 1.05)

    def test_execution_target_is_checked_before_launching_any_measurement(self):
        for change in ('java', 'os', 'distribution', 'architecture'):
            with self.subTest(change=change):
                manifest = manifest_fixture()
                env = manifest['environment']
                if change == 'java': env['java']['version'] = '21.0.9'
                elif change == 'os': env['os'] = 'Darwin'
                elif change == 'distribution': env['distro']['VERSION_ID'] = '22.04'
                else: env['java']['architecture'] = 'aarch64'
                with self.assertRaises(ValueError): evidence.validate_manifest(manifest)

    def test_zero_allocation_baselines_remain_complete_without_nonfinite_ratios(self):
        for candidate_bytes, expected in ((0, 'observed-targets-met'), (4, 'targets-not-met')):
            with self.subTest(candidate_bytes=candidate_bytes), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                manifest, suite = archive_fixture(folder)
                for case, record in zip(evidence.plan(), suite['results']):
                    if case['scenario'] != 'image-direct-4':
                        continue
                    data = evidence.read_json(folder / case['raw'])
                    score = candidate_bytes if case['side'] == 'candidate' else 0
                    data[0]['secondaryMetrics']['gc.alloc.rate.norm'].update(score=score, rawData=[[score] * 5])
                    evidence.write_json(folder / case['raw'], data)
                    record['rawSha256'] = evidence.digest((folder / case['raw']).read_bytes())
                    record['metrics'] = evidence.validate_result(data, case, manifest)
                rebind(folder, manifest, suite)
                evidence.render(folder, verify_checksums=False)
                evidence.check(folder)
                summary = evidence.read_json(folder / 'summary.json')
                cell = next(cell for cell in summary['cells'] if cell['scenario'] == 'image-direct-4')
                ratio = cell['ratioSummary']['allocatedBytesPerOp']
                self.assertEqual(summary['decision']['status'], expected)
                self.assertEqual(ratio['blockRatios'], [None] * 3 if candidate_bytes else [1] * 3)
                self.assertEqual(ratio['zeroBaselineIncrease'], bool(candidate_bytes))
                if candidate_bytes:
                    self.assertIsNone(ratio['mean'])
                    self.assertIsNone(ratio['range'])
                self.assertNotIn('Infinity', (folder / 'summary.json').read_text())
                self.assertIn('zero baseline', (folder / 'summary.md').read_text())


class PairedFailureTest(unittest.TestCase):
    def test_complete_archive_retains_raw_forks_iterations_and_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            manifest, suite = archive_fixture(folder)
            self.assertEqual(evidence.check(folder), (manifest, suite))
            self.assertEqual(len(evidence.allowed_files()), 173)
            before = (folder / 'SHA256SUMS').read_bytes()
            evidence.render(folder)
            self.assertEqual(before, (folder / 'SHA256SUMS').read_bytes())

    def test_missing_duplicate_case_or_fork_cannot_become_complete(self):
        for mutation in ('missing', 'duplicate', 'reorder'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                manifest, suite = archive_fixture(folder)
                if mutation == 'missing': suite['results'].pop()
                elif mutation == 'duplicate': suite['results'][1] = deepcopy(suite['results'][0])
                else: suite['results'].reverse()
                rebind(folder, manifest, suite)
                with self.assertRaises(ValueError): evidence.check(folder)

    def test_runtime_settings_iteration_and_nonfinite_rejections(self):
        manifest = manifest_fixture()
        case = evidence.plan()[0]
        changes = {
            'method-alias': lambda r: r.update(benchmark=evidence.BENCHMARK.replace('.invoke', '.roundTrip')),
            'scenario': lambda r: r.update(params={'scenario': 'unknown'}),
            'missing-iteration': lambda r: r['primaryMetric']['rawData'][0].pop(),
            'missing-fork': lambda r: r['primaryMetric'].update(rawData=[]),
            'multiple-forks': lambda r: r.update(forks=2),
            'runtime': lambda r: r.update(jdkVersion='21.0.9'),
            'heap': lambda r: r['jvmArgs'].__setitem__(0, '-Xms512m'),
            'wrong-sha': lambda r: r['jvmArgs'].__setitem__(2, '-Dominicat.directState.coreSha256=' + 'f' * 64),
            'extra-arg': lambda r: r['jvmArgs'].append('-Dunknown=true'),
            'duration': lambda r: r.update(measurementTime='100 ms'),
            'boolean-thread': lambda r: r.update(threads=True),
            'nan': lambda r: r['primaryMetric'].update(score=float('nan')),
            'zero-time': lambda r: r['primaryMetric'].update(score=0, rawData=[[0] * 5]),
            'infinite': lambda r: r['secondaryMetrics']['gc.alloc.rate.norm']['rawData'][0].__setitem__(0, float('inf')),
            'missing-allocation': lambda r: r.update(secondaryMetrics={}),
            'wrong-unit': lambda r: r['primaryMetric'].update(scoreUnit='us/op'),
            'wrong-mean': lambda r: r['primaryMetric'].update(score=999),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                data = raw_fixture(case, manifest)
                change(data[0])
                with self.assertRaises(ValueError): evidence.validate_result(data, case, manifest)

    def test_source_differences_are_limited_to_the_one_selected_production_file(self):
        for mutation in ('unrelated', 'missing', 'pom', 'aggregate', 'candidate-revision', 'none', 'unsafe-path', 'rule'):
            with self.subTest(mutation=mutation):
                manifest = manifest_fixture()
                files = manifest['sources']['candidate']['files']
                if mutation == 'unrelated': files['ominicat-core/src/main/java/io/ominicat/core/Frame.java'] = 'f' * 64
                elif mutation == 'missing': files.pop('ominicat-core/src/main/java/io/ominicat/core/Frame.java')
                elif mutation == 'pom': files['ominicat-core/pom.xml'] = 'f' * 64
                elif mutation == 'aggregate': manifest['sources']['baseline']['aggregate'] = 'f' * 64
                elif mutation == 'candidate-revision': manifest['sources']['probe']['revision'] = 'c' * 40
                elif mutation == 'none': files[evidence.PIPELINE] = manifest['sources']['baseline']['files'][evidence.PIPELINE]
                elif mutation == 'unsafe-path': manifest['sources']['probe']['files']['/Users/synthetic/private'] = 'f' * 64
                else: manifest['settings']['decisionRule']['primaryAllocationRatioMax'] = .99
                manifest['sources']['candidate']['aggregate'] = evidence.digest(evidence.canonical(files))
                with self.assertRaises(ValueError): evidence.validate_manifest(manifest)

    def test_both_origin_markers_are_required_exactly_once_and_sha_specific(self):
        manifest = manifest_fixture()
        case = evidence.plan()[0]
        good = log_fixture(case, manifest)
        evidence.validate_origin(good.encode(), manifest['artifacts'], 'baseline')
        for bad in (good + good, good.replace('DIRECT_STATE_PROBE_SHA256=', 'missing='),
                    good.replace(manifest['artifacts']['baseline']['sha256'], 'f' * 64),
                    good.replace('# Warmup Iteration   1:', '# Warmup Iteration   2:')):
            with self.assertRaises(ValueError): evidence.validate_origin(bad.encode(), manifest['artifacts'], 'baseline')

    def test_raw_tamper_and_rebound_wrong_origin_still_fail(self):
        for mutation in ('raw-bytes', 'origin', 'privacy'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                manifest, suite = archive_fixture(folder)
                item = suite['results'][0]
                if mutation == 'raw-bytes':
                    (folder / item['raw']).write_bytes((folder / item['raw']).read_bytes() + b' ')
                else:
                    log = folder / item['log']
                    log.write_text(log.read_text().replace(manifest['artifacts']['baseline']['sha256'], 'f' * 64)
                                   if mutation == 'origin' else log.read_text() + 'sk-' + 'a' * 30)
                    item['logSha256'] = evidence.digest(log.read_bytes())
                    rebind(folder, manifest, suite)
                with self.assertRaises(ValueError): evidence.check(folder)

    def test_unknown_files_symlinks_and_decision_tampering_fail_before_rewrite(self):
        for mutation in ('jfr', 'symlink', 'decision'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                archive_fixture(folder)
                before = (folder / 'summary.md').read_bytes()
                if mutation == 'jfr': (folder / 'recording.jfr').write_bytes(b'synthetic private recording')
                elif mutation == 'symlink':
                    (folder / 'summary.md').unlink()
                    (folder / 'summary.md').symlink_to(FILE)
                else:
                    summary = evidence.read_json(folder / 'summary.json')
                    summary['decision']['status'] = 'fake-pass'
                    evidence.write_json(folder / 'summary.json', summary)
                    (folder / 'SHA256SUMS').write_text(evidence.checksums(folder))
                with self.assertRaises(ValueError): evidence.render(folder)
                if mutation != 'symlink': self.assertEqual(before, (folder / 'summary.md').read_bytes())

    def test_cli_errors_do_not_echo_paths_or_tracebacks(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            archive_fixture(folder)
            (folder / 'manifest.json').write_text('[]')
            output = io.StringIO()
            with redirect_stderr(output), redirect_stdout(output):
                self.assertEqual(evidence.main(['check', str(folder)]), 1)
            self.assertNotIn(temporary, output.getvalue())
            self.assertNotIn('Traceback', output.getvalue())


class CollectorBindingTest(unittest.TestCase):
    def fake_roots(self, temporary):
        roots = [(Path(temporary) / side).resolve() for side in evidence.SIDES]
        for root in roots:
            for name in evidence.CORE_REQUIRED + ('ominicat-core/src/main/java/io/ominicat/core/Frame.java',):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('shared core' if name != evidence.PIPELINE else root.name + ' Pipeline')
            artifact = root / evidence.CORE_JAR
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes((root.name + ' synthetic core').encode())
        candidate = roots[1]
        actual = {'comparison/direct_state_evidence.py': FILE,
                  'comparison/mechanism_evidence.py': Path(evidence.common.__file__),
                  'scripts/package_profiles.py': evidence.common.ROOT / 'scripts/package_profiles.py'}
        for name in evidence.PROBE_REQUIRED:
            path = candidate / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(actual[name].read_bytes() if name in actual else b'synthetic probe source')
        probe = candidate / evidence.PROBE_JAR
        probe.parent.mkdir(parents=True)
        probe.write_bytes(b'synthetic shared probe')
        return roots

    def test_collector_uses_same_probe_and_selected_core_first_for_all_84_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            baseline, candidate = self.fake_roots(temporary)
            output = Path(temporary) / 'evidence'
            calls = []
            manifest = manifest_fixture()
            manifest['artifacts'] = evidence.artifacts_for(baseline, candidate)
            def run(command, cwd, log):
                case = evidence.plan()[len(calls)]
                calls.append(command)
                selected = baseline if case['side'] == 'baseline' else candidate
                cp = command[command.index('-cp') + 1].split(':')
                self.assertEqual((candidate / cp[0]).resolve(), selected / evidence.CORE_JAR)
                self.assertEqual((candidate / cp[1]).resolve(), candidate / evidence.PROBE_JAR)
                self.assertEqual(command[command.index('-f') + 1], '1')
                raw = candidate / command[command.index('-rff') + 1]
                evidence.write_json(raw, raw_fixture(case, manifest))
                log.write_text(log_fixture(case, manifest))
            with mock.patch.object(evidence, 'revision', side_effect=lambda root: ('a' if root == baseline else 'b') * 40), \
                    mock.patch.object(evidence, 'require_clean'), \
                    mock.patch.object(evidence.common, 'host_environment', return_value=environment()), \
                    mock.patch.object(evidence.common, 'run_command', side_effect=run):
                evidence.collect(baseline, candidate, output, java='/opt/java17/bin/java')
            self.assertEqual(len(calls), 84)
            evidence.check(output)

    def test_midrun_source_artifact_or_technical_failure_never_produces_complete_manifest(self):
        for changed in ('source', 'core', 'probe', 'nonzero'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as temporary:
                baseline, candidate = self.fake_roots(temporary)
                output = Path(temporary) / 'evidence'
                manifest = manifest_fixture()
                manifest['artifacts'] = evidence.artifacts_for(baseline, candidate)
                def run(command, cwd, log):
                    if changed == 'nonzero':
                        # Actual small owned process, never Java/JMH.
                        return original_run([sys.executable, '-c', 'print("synthetic incomplete fork");raise SystemExit(7)'], cwd, log)
                    case = evidence.plan()[0]
                    evidence.write_json(candidate / command[command.index('-rff') + 1], raw_fixture(case, manifest))
                    log.write_text(log_fixture(case, manifest))
                    target = candidate / ('ominicat-core/src/main/java/io/ominicat/core/Frame.java' if changed == 'source'
                                          else evidence.CORE_JAR if changed == 'core' else evidence.PROBE_JAR)
                    target.write_bytes(b'changed while process was running')
                original_run = evidence.common.run_command
                with mock.patch.object(evidence, 'revision', side_effect=lambda root: ('a' if root == baseline else 'b') * 40), \
                        mock.patch.object(evidence, 'require_clean'), \
                        mock.patch.object(evidence.common, 'host_environment', return_value=environment()), \
                        mock.patch.object(evidence.common, 'run_command', side_effect=run):
                    with self.assertRaises(ValueError): evidence.collect(baseline, candidate, output, java='/opt/java17/bin/java')
                self.assertFalse((output / 'manifest.json').exists())
                self.assertFalse((output / 'summary.json').exists())
                self.assertEqual(evidence.read_json(output / 'failure.json')['status'], 'failed')

    def test_wrong_execution_target_cannot_launch_even_the_first_fork(self):
        with tempfile.TemporaryDirectory() as temporary:
            baseline, candidate = self.fake_roots(temporary)
            output = Path(temporary) / 'evidence'
            wrong = environment()
            wrong['java']['version'] = '25.0.1'
            with mock.patch.object(evidence, 'revision', side_effect=lambda root: ('a' if root == baseline else 'b') * 40), \
                    mock.patch.object(evidence, 'require_clean'), \
                    mock.patch.object(evidence.common, 'host_environment', return_value=wrong), \
                    mock.patch.object(evidence.common, 'run_command') as launch:
                with self.assertRaises(ValueError): evidence.collect(baseline, candidate, output, java='/opt/java25/bin/java')
                launch.assert_not_called()
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
