"""Synthetic diagnostic archive/failure fixtures; never run Java or report fake measurements."""
from contextlib import ExitStack, redirect_stderr, redirect_stdout
from copy import deepcopy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
import direct_state_profiles as profiles

spec = importlib.util.spec_from_file_location('direct_state_measurement_archive_fixture', HERE / 'test_direct_state_evidence.py')
measurement_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measurement_fixture)


def events():
    stack = {'frames': [{'method': {'type': {'name': 'synthetic/ProfileStage'}, 'name': 'process'}}],
             'truncated': False}
    return {'recording': {'events': [
        {'type': 'jdk.ExecutionSample', 'values': {'startTime': '2026-11-02T00:00:00Z', 'stackTrace': stack}},
        {'type': 'jdk.ObjectAllocationSample', 'values': {'startTime': '2026-11-02T00:00:01Z',
            'stackTrace': stack, 'weight': 4096, 'objectClass': {'name': 'synthetic/Value'}}}]}}


def fixture(folder):
    """Use the real 84-fork archive checker with explicitly synthetic raw fixtures."""
    measured, _ = measurement_fixture.archive_fixture(folder / 'measurements')
    raw = (folder / 'measurements/manifest.json').read_bytes()
    tools = {name: profiles.digest(('synthetic ' + name).encode()) for name in profiles.TOOLS}
    diagnostics = folder / 'diagnostics'
    diagnostics.mkdir()
    for case in profiles.cases():
        directory = diagnostics / case['id']
        directory.mkdir()
        profiles.write_json(directory / 'jfr-events.json', events())
        profiles.write_json(directory / 'load.json', profiles.load_context(case, measured['environment']['java']))
        with redirect_stdout(io.StringIO()): profiles.shared.renderer.main(directory)
        path = directory / 'profiles.md'
        path.write_text(profiles.renderer_scope(path.read_text()), encoding='utf-8')
        summary = profiles.selected_events(directory / 'jfr-events.json.gz')
        profiles.write_json(directory / 'jfr-hotspots.json', summary)
        profiles.write_json(directory / 'validation.json', profiles.validation_for(case, measured['environment']['java']))
        (directory / 'report.md').write_text(profiles.report_text(case), encoding='utf-8')
        profiles.write_json(directory / 'diagnostic-context.json', {
            'schemaVersion': 1, 'protocol': profiles.PROTOCOL, 'status': 'complete',
            'recordedAt': '2026-11-02T00:00:00Z', 'case': case, 'scope': profiles.SCOPE,
            'instrumented': True, 'headlineEligible': False, 'bindings': profiles.bindings(measured, raw),
            'settings': profiles.settings(), 'toolSha256': tools, 'eventSummary': summary,
            'rawSha256': {name: profiles.digest(('synthetic ' + name).encode())
                          for name in ('recording', 'settings', 'jmhResult', 'jmhLog')}})
        (directory / 'jfr-events.json').unlink()
    profiles.write_json(diagnostics / 'manifest.json', profiles.manifest_for(measured, raw, tools))
    reindex(folder)
    return measured


def reindex(folder):
    profiles.write_json(folder / 'evidence-index.json', profiles.index_for(folder))


def mutate_json(path, mutation):
    value = profiles.read_json(path)
    mutation(value)
    profiles.write_json(path, value)


def parent_arguments(measured, side):
    return ['-Xms256m', '-Xmx256m',
            '-Dominicat.directState.coreSha256=' + measured['artifacts'][side]['sha256'],
            '-Dominicat.directState.probeSha256=' + measured['artifacts']['probe']['sha256'],
            '-XX:FlightRecorderOptions=stackdepth=64',
            '-XX:StartFlightRecording=filename=reports/local/synthetic/recording.jfr,'
            'settings=reports/local/synthetic/safe.jfc,dumponexit=true']


def profiled_result(case, measured):
    java = measured['environment']['java']
    result = {key: profiles.settings()[key] for key in ('jmhVersion', 'mode', 'threads', 'forks',
              'warmupIterations', 'measurementIterations', 'warmupTime', 'measurementTime',
              'warmupBatchSize', 'measurementBatchSize')}
    result.update({'benchmark': 'io.ominicat.benchmarks.DirectStateProbe.invoke',
                   'params': {'scenario': case['scenario']}, 'jvmArgs': parent_arguments(measured, case['side']),
                   'jdkVersion': java['version'], 'vmVersion': java['vmVersion'], 'vmName': java['vmName'],
                   'primaryMetric': {'score': 11, 'scoreUnit': 'ns/op', 'rawData': [[10, 12]]},
                   'secondaryMetrics': {'gc.alloc.rate.norm': {'score': 50, 'scoreUnit': 'B/op', 'rawData': [[48, 52]]}}})
    return [result]


class DirectStateProfilesTest(unittest.TestCase):
    def test_four_diagnostics_bind_both_core_sides_and_the_same_probe_to_complete_measurements(self):
        expected = [{'id': 'baseline-raw-direct-4', 'side': 'baseline', 'scenario': 'raw-direct-4'},
                    {'id': 'candidate-raw-direct-4', 'side': 'candidate', 'scenario': 'raw-direct-4'},
                    {'id': 'baseline-envelope-direct-4', 'side': 'baseline', 'scenario': 'envelope-direct-4'},
                    {'id': 'candidate-envelope-direct-4', 'side': 'candidate', 'scenario': 'envelope-direct-4'}]
        self.assertEqual(profiles.cases(), expected)
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            measured = fixture(folder)
            manifest = profiles.check(folder)
            hashes = manifest['bindings']['artifactSha256']
            self.assertNotEqual(hashes['baseline'], hashes['candidate'])
            self.assertEqual(hashes['probe'], measured['artifacts']['probe']['sha256'])
            self.assertEqual(manifest['bindings']['measurementManifestSha256'],
                             profiles.digest((folder / 'measurements/manifest.json').read_bytes()))
            for case in expected:
                directory = folder / 'diagnostics' / case['id']
                self.assertEqual({path.name for path in directory.iterdir()}, profiles.CASE_FILES)
                self.assertEqual(profiles.read_json(directory / 'validation.json')['case'], case)

    def test_nonforked_result_has_exact_six_parent_options_and_no_annotation_heap_suffix(self):
        measured = measurement_fixture.manifest_fixture()
        for case in profiles.cases():
            raw = profiled_result(case, measured)
            args = parent_arguments(measured, case['side'])
            self.assertEqual(len(args), 6)
            log = measurement_fixture.log_fixture(case, measured).encode()
            result = profiles.validate_result(raw, case, measured, args, log)
            self.assertFalse(result['headlineEligible'])
            self.assertEqual(result['runtime']['version'].split('.')[0], '17')
            for invalid in (args + ['-Xms256m', '-Xmx256m'], args[:-1], args + ['-Dunexpected=true']):
                modified = deepcopy(raw)
                modified[0]['jvmArgs'] = invalid
                with self.assertRaises(ValueError): profiles.validate_result(modified, case, measured, args, log)

    def test_wrong_side_origin_probe_runtime_settings_and_partial_metrics_fail(self):
        measured = measurement_fixture.manifest_fixture()
        case = profiles.cases()[1]
        raw = profiled_result(case, measured)
        args = parent_arguments(measured, 'candidate')
        log = measurement_fixture.log_fixture(case, measured).encode()
        mutations = {
            'fork': lambda d: d.update(forks=1),
            'three-iterations': lambda d: d.update(measurementIterations=3),
            'boolean-thread': lambda d: d.update(threads=True),
            'version': lambda d: d.update(jdkVersion='21.0.9'),
            'scenario': lambda d: d.update(params={'scenario': 'raw-session-4'}),
            'benchmark': lambda d: d.update(benchmark='io.ominicat.benchmarks.PipelineBenchmark.dispatch'),
            'core-side': lambda d: d['jvmArgs'].__setitem__(2, parent_arguments(measured, 'baseline')[2]),
            'missing-allocation': lambda d: d['secondaryMetrics'].pop('gc.alloc.rate.norm'),
            'nan': lambda d: d['primaryMetric'].update(score=float('nan')),
            'missing-iteration': lambda d: d['primaryMetric']['rawData'][0].pop(),
            'wrong-unit': lambda d: d['primaryMetric'].update(scoreUnit='us/op'),
            'wrong-mean': lambda d: d['primaryMetric'].update(score=999),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                modified = deepcopy(raw)
                mutation(modified[0])
                with self.assertRaises(ValueError): profiles.validate_result(modified, case, measured, args, log)
        for invalid in (log + log, b'', measurement_fixture.log_fixture(profiles.cases()[0], measured).encode(),
                        log.replace(measured['artifacts']['probe']['sha256'].encode(), b'0' * 64)):
            with self.assertRaises(ValueError): profiles.validate_result(raw, case, measured, args, invalid)
        java21 = deepcopy(measured)
        java21['environment']['java']['version'] = '21.0.9'
        with self.assertRaises(ValueError):
            profiles.validate_result(profiled_result(case, java21), case, java21, args, log)

    def test_scope_replacement_requires_both_templates_and_removes_wrong_kernel_array_claims(self):
        old = profiles.shared.OLD_SCOPE + '\n\n' + profiles.shared.OLD_ALLOCATION
        result = profiles.renderer_scope(old)
        self.assertIn(profiles.SCOPE, result)
        self.assertIn(profiles.ALLOCATION_SCOPE, result)
        self.assertNotIn('bounded kernel load', result)
        self.assertNotIn('retains an exact latency array', result)
        self.assertIn('50 events/second', result)
        for bad in (profiles.shared.OLD_SCOPE, profiles.shared.OLD_ALLOCATION,
                    old + profiles.shared.OLD_SCOPE, old.replace('exact latency array', 'changed template')):
            with self.assertRaises(ValueError): profiles.renderer_scope(bad)

    def test_rehashed_manifest_cannot_swap_core_source_probe_or_main_manifest_bindings(self):
        changes = {
            'candidate-core': lambda d: d['bindings']['artifactSha256'].update(candidate=d['bindings']['artifactSha256']['baseline']),
            'probe': lambda d: d['bindings']['artifactSha256'].update(probe='f' * 64),
            'source': lambda d: d['bindings']['sourceAggregateSha256'].update(candidate='f' * 64),
            'measurement': lambda d: d['bindings'].update(measurementManifestSha256='f' * 64),
            'missing-case': lambda d: d['cases'].pop(),
            'unknown-case': lambda d: d['cases'][0].update(scenario='raw-session-4'),
            'settings': lambda d: d['settings'].update(forks=1),
            'tool': lambda d: d['toolSha256'].pop(profiles.TOOLS[0]),
        }
        for name, change in changes.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                mutate_json(folder / 'diagnostics/manifest.json', change)
                reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_raw_main_manifest_binding_detects_whitespace_change_even_when_measurements_still_validate(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            fixture(folder)
            measured = folder / 'measurements'
            path = measured / 'manifest.json'
            path.write_bytes(path.read_bytes() + b'\n')
            (measured / 'SHA256SUMS').write_text(profiles.evidence.checksums(measured))
            profiles.evidence.check(measured)
            reindex(folder)
            with self.assertRaises(ValueError): profiles.check(folder)

    def test_case_scope_timestamp_private_hash_and_boolean_metadata_are_validated_after_rehash(self):
        changes = {
            'wrong-case': ('diagnostic-context.json', lambda d: d['case'].update(side='candidate')),
            'missing-private-hash': ('diagnostic-context.json', lambda d: d['rawSha256'].pop('recording')),
            'wrong-source': ('diagnostic-context.json', lambda d: d['bindings']['sourceAggregateSha256'].update(baseline='f' * 64)),
            'naive-time': ('diagnostic-context.json', lambda d: d.update(recordedAt='2026-11-02T00:00:00')),
            'offset-time': ('diagnostic-context.json', lambda d: d.update(recordedAt='2026-11-02T08:00:00+08:00')),
            'invalid-time': ('diagnostic-context.json', lambda d: d.update(recordedAt=None)),
            'boolean-setting': ('diagnostic-context.json', lambda d: d['settings'].update(threads=True)),
            'boolean-summary': ('jfr-hotspots.json', lambda d: d['selectedEventCounts'].update({'jdk.ExecutionSample': True})),
            'boolean-load': ('load.json', lambda d: d.update(schemaVersion=True)),
            'boolean-validation': ('validation.json', lambda d: d.update(headlineEligible=0)),
            'partial': ('validation.json', lambda d: d.update(status='partial')),
        }
        for name, (file, change) in changes.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                mutate_json(folder / 'diagnostics/baseline-raw-direct-4' / file, change)
                reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_exact_inventory_refuses_missing_unknown_raw_and_symlink_files(self):
        for name in ('missing-svg', 'raw.jfr', 'jfr-events.json', 'unreviewed.log', 'unknown-empty-dir', 'file-symlink', 'root-symlink'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                folder = parent / 'evidence'
                fixture(folder)
                directory = folder / 'diagnostics/baseline-raw-direct-4'
                if name == 'missing-svg': (directory / 'cpu-flamegraph.svg').unlink()
                elif name == 'unknown-empty-dir': (directory / 'unknown').mkdir()
                elif name == 'file-symlink':
                    path = directory / 'report.md'
                    path.unlink()
                    path.symlink_to(directory / 'profiles.md')
                elif name == 'root-symlink':
                    link = parent / 'link'
                    link.symlink_to(folder, target_is_directory=True)
                    folder = link
                else: (directory / name).write_text('synthetic forbidden raw file')
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_changed_files_and_forged_traversal_index_fail(self):
        for name in ('modified-file', 'traversal-index', 'missing-index', 'stale-scope'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                index = folder / 'evidence-index.json'
                if name == 'modified-file':
                    with (folder / 'diagnostics/baseline-raw-direct-4/cpu-flamegraph.svg').open('ab') as output:
                        output.write(b' changed')
                elif name == 'traversal-index': mutate_json(index, lambda d: d['files'].update({'../outside': 'a' * 64}))
                elif name == 'missing-index': index.unlink()
                else:
                    path = folder / 'diagnostics/baseline-raw-direct-4/profiles.md'
                    path.write_text(path.read_text().replace(profiles.ALLOCATION_SCOPE, profiles.shared.OLD_ALLOCATION))
                    reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_collection_failure_reports_only_fixed_stage_case_and_numeric_tool_sizes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            baseline, candidate, capture = root / 'baseline', root / 'candidate', root / 'capture'
            baseline.mkdir()
            candidate.mkdir()
            measured, _ = measurement_fixture.archive_fixture(capture / 'evidence/measurements')
            for name in profiles.TOOLS:
                path = candidate / name
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, path)
            java_home = root / 'synthetic-jdk'
            (java_home / 'bin').mkdir(parents=True)
            (java_home / 'bin/jfr').write_text('never executed')
            private_error = '/Users/synthetic/private value sk-' + 'a' * 40
            for error, expected_reason in ((ValueError(private_error), 'DETAILS_PRIVATE'),
                    (profiles.shared.ToolOutputLimitError(134217729, 0), 'TOOL_OUTPUT_SIZE')):
                with ExitStack() as stack:
                    stack.enter_context(mock.patch.dict(os.environ, {'JAVA_HOME': str(java_home)}))
                    stack.enter_context(mock.patch.object(profiles.evidence, 'require_clean'))
                    stack.enter_context(mock.patch.object(profiles.evidence, 'sources_for', return_value=measured['sources']))
                    stack.enter_context(mock.patch.object(profiles.evidence, 'artifacts_for', return_value=measured['artifacts']))
                    stack.enter_context(mock.patch.object(profiles.common, 'host_environment', return_value=measured['environment']))
                    configured = stack.enter_context(mock.patch.object(profiles.shared, 'command_to_file', side_effect=error))
                    output = io.StringIO()
                    stack.enter_context(redirect_stderr(output))
                    self.assertEqual(profiles.main(['collect', '--baseline', str(baseline), '--candidate', str(candidate),
                                                    '--output', str(capture)]), 1)
                metadata = json.loads(output.getvalue().split('DIRECT_STATE_DIAGNOSTIC_FAILURE=', 1)[1])
                self.assertEqual(metadata['phase'], 'JFR_CONFIGURE')
                self.assertEqual(metadata['case'], 'baseline-raw-direct-4')
                self.assertEqual(metadata['reason'], expected_reason)
                self.assertNotIn(private_error, output.getvalue())
                self.assertNotIn(str(root), output.getvalue())
                self.assertEqual(configured.call_count, 1)
                if expected_reason == 'TOOL_OUTPUT_SIZE':
                    self.assertEqual(metadata['stdoutBytes'], 134217729)
                    self.assertEqual(metadata['stderrBytes'], 0)
                    self.assertEqual(set(metadata), {'phase', 'case', 'reason', 'stdoutBytes', 'stderrBytes',
                                                     'stdoutLimitBytes', 'stderrLimitBytes'})
                else: self.assertEqual(set(metadata), {'phase', 'case', 'reason'})
                self.assertFalse((capture / 'evidence/evidence-index.json').exists())
                shutil.rmtree(capture / 'raw-direct-state')
                profiles.evidence.check(capture / 'evidence/measurements')

    def test_public_check_failure_never_echoes_untrusted_exception_text_or_private_paths(self):
        private = '/Users/synthetic/private sk-' + 'a' * 40
        for error in (ValueError(private), OSError(private), TypeError(private), AttributeError(private)):
            output = io.StringIO()
            with mock.patch.object(profiles, 'check', side_effect=error), redirect_stderr(output):
                self.assertEqual(profiles.main(['check', '/synthetic/unread/path']), 1)
            self.assertNotIn(private, output.getvalue())
            self.assertNotIn('Traceback', output.getvalue())

    def test_corrupt_deflate_public_cli_fails_without_a_traceback_or_private_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            fixture(folder)
            path = folder / 'diagnostics/baseline-raw-direct-4/jfr-events.json.gz'
            # Valid gzip header followed by invalid deflate blocks raises zlib.error,
            # rather than OSError/ValueError; exercise the real CLI boundary.
            path.write_bytes(b'\x1f\x8b\x08\x00' + b'\x00' * 6 + b'\xff' * 10)
            reindex(folder)
            result = subprocess.run([sys.executable, str(ROOT / 'comparison/direct_state_profiles.py'),
                                     'check', str(folder)], capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('incomplete or invalid', result.stderr)
            for private in ('Traceback', str(ROOT), str(folder)):
                self.assertNotIn(private, result.stderr)


if __name__ == '__main__':
    unittest.main()
