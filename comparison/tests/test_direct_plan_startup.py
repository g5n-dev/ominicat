"""Synthetic construction-evidence and binding controls; no Java/Maven is executed."""
from contextlib import ExitStack, redirect_stderr, redirect_stdout
from copy import deepcopy
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

COMPARISON = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('direct_plan_startup_fixture', COMPARISON / 'direct_plan_startup.py')
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)
fixture_spec = importlib.util.spec_from_file_location('startup_primary_fixture', Path(__file__).with_name('test_direct_state_evidence.py'))
fixtures = importlib.util.module_from_spec(fixture_spec)
fixture_spec.loader.exec_module(fixtures)


def primary_archive(parent):
    directory = parent / 'evidence/measurements'
    measured, suite = fixtures.archive_fixture(directory)
    files = measured['sources']['probe']['files']
    actual = {'comparison/direct_state_evidence.py': Path(startup.primary.__file__),
              'comparison/mechanism_evidence.py': Path(startup.common.__file__),
              'scripts/package_profiles.py': startup.common.ROOT / 'scripts/package_profiles.py',
              startup.TOOLS[2]: startup.ROOT / startup.TOOLS[2]}
    for name, path in actual.items():
        files[name] = startup.digest(path.read_bytes())
    measured['sources']['probe']['aggregate'] = startup.digest(startup.canonical(files))
    fixtures.rebind(directory, measured, suite)
    startup.primary.render(directory, verify_checksums=False)
    return directory, measured


def manifest_fixture(measurements, measured):
    files = {name: startup.digest((startup.ROOT / name).read_bytes()) for name in startup.TOOLS}
    return {'schemaVersion': 1, 'protocol': startup.PROTOCOL, 'status': 'complete',
            'recordedAt': '2026-11-02T00:00:00Z',
            'binding': {'measurementManifestSha256': startup.digest((measurements / 'manifest.json').read_bytes()),
                        'primaryManifest': measured},
            'toolSources': {'revision': measured['sources']['probe']['revision'], 'files': files,
                            'aggregate': startup.digest(startup.canonical(files))},
            'settings': startup.settings(), 'caseOrder': startup.plan()}


def raw_fixture(case, measured):
    raw = fixtures.raw_fixture(case, measured)
    raw[0]['benchmark'] = startup.BENCHMARK
    return raw


def archive_fixture(parent):
    measurements, measured = primary_archive(parent)
    folder = parent / 'startup-evidence'
    folder.mkdir()
    (folder / 'raw').mkdir()
    (folder / 'logs').mkdir()
    manifest = manifest_fixture(measurements, measured)
    records = []
    for case in startup.plan():
        raw = raw_fixture(case, measured)
        startup.write_json(folder / case['raw'], raw)
        (folder / case['log']).write_text(fixtures.log_fixture(case, measured))
        records.append({'position': case['position'], 'raw': case['raw'], 'log': case['log'],
                        'rawSha256': startup.digest((folder / case['raw']).read_bytes()),
                        'logSha256': startup.digest((folder / case['log']).read_bytes()),
                        'metrics': startup.validate_result(raw, case, measured)})
    suite = {'schemaVersion': 1, 'protocol': startup.PROTOCOL, 'status': 'complete',
             'manifestSha256': startup.digest(startup.canonical(manifest)), 'results': records}
    startup.write_json(folder / 'manifest.json', manifest)
    startup.write_json(folder / 'suite.json', suite)
    startup.render(folder, verify_checksums=False)
    return folder, measurements, manifest, suite


def rebind(folder, manifest, suite):
    suite['manifestSha256'] = startup.digest(startup.canonical(manifest))
    startup.write_json(folder / 'manifest.json', manifest)
    startup.write_json(folder / 'suite.json', suite)
    (folder / 'SHA256SUMS').write_text(startup.checksums(folder))


class StartupEvidenceTest(unittest.TestCase):
    def test_exact_eight_fork_plan_two_scenarios_and_no_main_target(self):
        plan = startup.plan()
        self.assertEqual(len(plan), 8)
        self.assertEqual(len({case['raw'] for case in plan}), 8)
        self.assertEqual([case['scenario'] for case in plan], ['build-4'] * 4 + ['first-direct-4'] * 4)
        self.assertEqual([case['side'] for case in plan], list(startup.primary.ABBA) * 2)
        self.assertNotIn('decisionRule', startup.settings())
        self.assertEqual(startup.settings()['acceptanceImpact'], 'none')
        self.assertFalse(startup.settings()['headlineEligible'])

    def test_complete_21_file_archive_retains_two_fork_means_and_ten_iterations(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder, _, manifest, suite = archive_fixture(Path(temporary))
            self.assertEqual(startup.check(folder), (manifest, suite))
            self.assertEqual(len(startup.allowed_files()), 21)
            self.assertEqual(len([path for path in folder.rglob('*') if path.is_file()]), 21)
            summary = startup.read_json(folder / 'summary.json')
            self.assertNotIn('decision', summary)
            self.assertFalse(summary['headlineEligible'])
            for cell in summary['cells']:
                for side in startup.primary.SIDES:
                    for metric in cell[side].values():
                        self.assertEqual(len(metric['forkMeans']), 2)
                        self.assertEqual(len(metric['iterations']), 10)
                        self.assertEqual(metric['forkRange'], [min(metric['forkMeans']), max(metric['forkMeans'])])
            before = (folder / 'SHA256SUMS').read_bytes()
            startup.render(folder)
            self.assertEqual(before, (folder / 'SHA256SUMS').read_bytes())
            text = (folder / 'summary.md').read_text()
            self.assertIn('warmed-up', text)
            self.assertIn('not OS/JVM cold-start', text)
            self.assertIn('No observation target', text)

    def test_actual_primary_archive_is_mandatory_default_and_explicit_path_work(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, measurements, _, _ = archive_fixture(root)
            relocated = root / 'elsewhere'
            measurements.rename(relocated)
            with self.assertRaises(ValueError): startup.check(folder)
            startup.check(folder, measurements=relocated)
            (relocated / startup.primary.plan()[0]['raw']).unlink()
            with self.assertRaises(ValueError): startup.check(folder, measurements=relocated)

    def test_primary_raw_bytes_and_embedded_copy_are_both_cross_checked(self):
        for change in ('whitespace', 'embedded-copy', 'claimed-raw-hash'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                folder, measurements, manifest, suite = archive_fixture(Path(temporary))
                if change == 'whitespace':
                    path = measurements / 'manifest.json'
                    path.write_bytes(path.read_bytes() + b'\n')
                    (measurements / 'SHA256SUMS').write_text(startup.primary.checksums(measurements))
                    startup.primary.check(measurements)
                elif change == 'embedded-copy':
                    manifest['binding']['primaryManifest']['environment']['scope'] = 'Changed embedded context.'
                    rebind(folder, manifest, suite)
                else:
                    manifest['binding']['measurementManifestSha256'] = 'f' * 64
                    rebind(folder, manifest, suite)
                with self.assertRaises(ValueError): startup.check(folder)

    def test_missing_duplicate_or_reordered_forks_never_become_complete(self):
        for change in ('missing', 'duplicate', 'reordered'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                folder, _, manifest, suite = archive_fixture(Path(temporary))
                if change == 'missing': suite['results'].pop()
                elif change == 'duplicate': suite['results'][1] = deepcopy(suite['results'][0])
                else: suite['results'].reverse()
                rebind(folder, manifest, suite)
                with self.assertRaises(ValueError): startup.check(folder)

    def test_method_runtime_settings_metrics_and_actual_jar_properties_are_strict(self):
        measured = fixtures.manifest_fixture()
        case = startup.plan()[0]
        changes = {
            'main-method': lambda row: row.update(benchmark=startup.primary.BENCHMARK),
            'alias': lambda row: row.update(benchmark=startup.BENCHMARK.replace('invoke', 'roundTrip')),
            'scenario': lambda row: row.update(params={'scenario': 'raw-direct-4'}),
            'f0': lambda row: row.update(forks=0),
            'iterations': lambda row: row['primaryMetric']['rawData'][0].pop(),
            'runtime': lambda row: row.update(jdkVersion='21.0.9'),
            'warmup': lambda row: row.update(warmupIterations=2),
            'boolean': lambda row: row.update(threads=True),
            'unit': lambda row: row['primaryMetric'].update(scoreUnit='us/op'),
            'heap': lambda row: row['jvmArgs'].__setitem__(0, '-Xms512m'),
            'core-sha': lambda row: row['jvmArgs'].__setitem__(2, '-Dominicat.directState.coreSha256=' + 'f' * 64),
            'probe-sha': lambda row: row['jvmArgs'].__setitem__(3, '-Dominicat.directState.probeSha256=' + 'f' * 64),
            'nan': lambda row: row['primaryMetric'].update(score=float('nan')),
            'infinite': lambda row: row['secondaryMetrics']['gc.alloc.rate.norm'].update(score=float('inf')),
            'missing-bop': lambda row: row.update(secondaryMetrics={}),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                data = raw_fixture(case, measured)
                change(data[0])
                with self.assertRaises(ValueError): startup.validate_result(data, case, measured)
        data = raw_fixture(case, measured)
        data[0]['secondaryMetrics']['gc.alloc.rate.norm'].update(score=0, rawData=[[0] * 5])
        self.assertEqual(startup.validate_result(data, case, measured)['allocatedBytesPerOp']['mean'], 0)

    def test_raw_tamper_rebound_wrong_origin_and_privacy_fail(self):
        for change in ('raw-bytes', 'origin', 'duplicate-origin', 'privacy'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                folder, _, manifest, suite = archive_fixture(Path(temporary))
                item = suite['results'][0]
                if change == 'raw-bytes':
                    path = folder / item['raw']
                    path.write_bytes(path.read_bytes() + b' ')
                else:
                    path = folder / item['log']
                    text = path.read_text()
                    if change == 'origin': text = text.replace(manifest['binding']['primaryManifest']['artifacts']['baseline']['sha256'], 'f' * 64)
                    elif change == 'duplicate-origin': text += text
                    else: text += 'sk-' + 'a' * 40
                    path.write_text(text)
                    item['logSha256'] = startup.digest(path.read_bytes())
                    rebind(folder, manifest, suite)
                with self.assertRaises(ValueError): startup.check(folder)

    def test_tools_probe_revision_and_no_target_settings_are_bound(self):
        for change in ('probe-source', 'revision', 'missing-tool', 'aggregate', 'new-target'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                folder, _, manifest, _ = archive_fixture(Path(temporary))
                tools = manifest['toolSources']
                if change == 'probe-source': tools['files'][startup.TOOLS[2]] = 'f' * 64
                elif change == 'revision': tools['revision'] = 'f' * 40
                elif change == 'missing-tool': tools['files'].pop(startup.TOOLS[1])
                elif change == 'new-target': manifest['settings']['acceptanceImpact'] = 'main'
                else: tools['aggregate'] = 'f' * 64
                if change != 'aggregate': tools['aggregate'] = startup.digest(startup.canonical(tools['files']))
                with self.assertRaises(ValueError): startup.validate_manifest(manifest)

    def test_allowlist_symlinks_and_forged_summary_refuse_before_rewrite(self):
        for change in ('recording.jfr', 'private.log', 'symlink', 'summary'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                folder, _, _, _ = archive_fixture(Path(temporary))
                before = (folder / 'summary.md').read_bytes()
                if change == 'symlink':
                    path = folder / 'summary.md'
                    path.unlink()
                    path.symlink_to(folder / 'manifest.json')
                elif change == 'summary':
                    summary = startup.read_json(folder / 'summary.json')
                    summary['headlineEligible'] = True
                    startup.write_json(folder / 'summary.json', summary)
                    (folder / 'SHA256SUMS').write_text(startup.checksums(folder))
                else: (folder / change).write_text('not public')
                with self.assertRaises(ValueError): startup.render(folder)
                if change != 'symlink': self.assertEqual(before, (folder / 'summary.md').read_bytes())

    def test_public_cli_failure_does_not_leak_input_paths_or_traceback(self):
        with tempfile.TemporaryDirectory(prefix='synthetic-private-') as temporary:
            output = io.StringIO()
            with redirect_stderr(output), redirect_stdout(output):
                self.assertEqual(startup.main(['check', temporary]), 1)
            self.assertNotIn(temporary, output.getvalue())
            self.assertNotIn('Traceback', output.getvalue())


class StartupCollectionTest(unittest.TestCase):
    def prepare(self, parent):
        measurements, measured = primary_archive(parent)
        baseline, candidate = parent / 'baseline', parent / 'candidate'
        baseline.mkdir()
        candidate.mkdir()
        for name in startup.TOOLS:
            path = candidate / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((startup.ROOT / name).read_bytes())
        return baseline, candidate, measurements, measured

    def bindings(self, stack, measured):
        stack.enter_context(mock.patch.object(startup.primary, 'require_clean'))
        stack.enter_context(mock.patch.object(startup.primary, 'revision', return_value=measured['sources']['probe']['revision']))
        stack.enter_context(mock.patch.object(startup.primary, 'sources_for', return_value=measured['sources']))
        stack.enter_context(mock.patch.object(startup.primary, 'artifacts_for', return_value=measured['artifacts']))
        stack.enter_context(mock.patch.object(startup.common, 'host_environment', return_value=measured['environment']))

    def test_eight_fresh_commands_use_same_probe_and_selected_core_first(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            baseline, candidate, measurements, measured = self.prepare(root)
            output, calls = root / 'startup-evidence', []
            def run(command, cwd, log):
                case = startup.plan()[len(calls)]
                calls.append(command)
                cp = command[command.index('-cp') + 1].split(os.pathsep)
                selected = baseline if case['side'] == 'baseline' else candidate
                self.assertEqual((candidate / cp[0]).resolve(), selected / startup.primary.CORE_JAR)
                self.assertEqual((candidate / cp[1]).resolve(), candidate / startup.primary.PROBE_JAR)
                self.assertEqual(command[command.index('-f') + 1], '1')
                self.assertEqual(command[command.index('-p') + 1], 'scenario=' + case['scenario'])
                startup.write_json(candidate / command[-1], raw_fixture(case, measured))
                log.write_text(fixtures.log_fixture(case, measured))
            with ExitStack() as stack:
                self.bindings(stack, measured)
                stack.enter_context(mock.patch.object(startup.common, 'run_command', side_effect=run))
                startup.collect(baseline, candidate, measurements, output, java='/opt/java17/bin/java')
            self.assertEqual(len(calls), 8)
            startup.check(output)

    def test_subset_failure_and_midrun_tool_or_primary_changes_do_not_publish_success(self):
        for change in ('nonzero', 'tool', 'primary'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                baseline, candidate, measurements, measured = self.prepare(root)
                output = root / 'startup-evidence'
                original_run = startup.common.run_command
                def run(command, cwd, log):
                    if change == 'nonzero':
                        return original_run([sys.executable, '-c', 'raise SystemExit(7)'], cwd, log)
                    case = startup.plan()[0]
                    startup.write_json(candidate / command[-1], raw_fixture(case, measured))
                    log.write_text(fixtures.log_fixture(case, measured))
                    if change == 'tool': (candidate / startup.TOOLS[1]).write_text('changed source during collection')
                    else:
                        path = measurements / 'manifest.json'
                        path.write_bytes(path.read_bytes() + b'\n')
                with ExitStack() as stack:
                    self.bindings(stack, measured)
                    stack.enter_context(mock.patch.object(startup.common, 'run_command', side_effect=run))
                    with self.assertRaises(ValueError):
                        startup.collect(baseline, candidate, measurements, output, java='/opt/java17/bin/java')
                for name in ('manifest.json', 'suite.json', 'summary.json', 'summary.md', 'SHA256SUMS'):
                    self.assertFalse((output / name).exists())
                self.assertEqual(startup.read_json(output / 'failure.json')['status'], 'failed')

    def test_wrong_host_and_output_inside_main_are_rejected_before_launch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            baseline, candidate, measurements, measured = self.prepare(root)
            output = root / 'startup-evidence'
            wrong = deepcopy(measured['environment'])
            wrong['java']['version'] = '25.0.1'
            with ExitStack() as stack:
                self.bindings(stack, measured)
                stack.enter_context(mock.patch.object(startup.common, 'host_environment', return_value=wrong))
                launch = stack.enter_context(mock.patch.object(startup.common, 'run_command'))
                for target in (output, measurements / 'nested-startup'):
                    with self.assertRaises(ValueError):
                        startup.collect(baseline, candidate, measurements, target, java='/opt/java25/bin/java')
                launch.assert_not_called()
            self.assertFalse(output.exists())
            self.assertFalse((measurements / 'nested-startup').exists())


if __name__ == '__main__':
    unittest.main()
