"""Synthetic archive/validator fixtures only; no Java, Maven or real measurements."""
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import gzip
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('mechanism_profiles_fixture', HERE.parent / 'mechanism_profiles.py')
profiles = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profiles)
fixture_spec = importlib.util.spec_from_file_location('measurement_archive_fixture', HERE / 'test_mechanism_evidence.py')
measurement_fixture = importlib.util.module_from_spec(fixture_spec)
fixture_spec.loader.exec_module(measurement_fixture)


def events():
    stack = {'truncated': False, 'frames': [
        {'method': {'type': {'name': 'io/ominicat/mechanisms/SyntheticStage'}, 'name': 'process'}},
        {'method': {'type': {'name': 'org/openjdk/jmh/SyntheticHarness'}, 'name': 'invoke'}}]}
    return {'recording': {'events': [
        {'type': 'jdk.ExecutionSample', 'values': {'startTime': '2026-11-02T00:00:01Z', 'stackTrace': stack}},
        {'type': 'jdk.ObjectAllocationSample', 'values': {'startTime': '2026-11-02T00:00:02Z',
            'stackTrace': stack, 'weight': 4096, 'objectClass': {'name': 'io/ominicat/SyntheticValue'}}},
        {'type': 'jdk.GarbageCollection', 'values': {'startTime': '2026-11-02T00:00:03Z',
            'sumOfPauses': 'PT0.001S', 'gcId': 1, 'name': 'Synthetic GC', 'cause': 'synthetic fixture'}}]}}


def fixture(folder, version=profiles.PROTOCOL):
    """Make complete synthetic archives, exercising the real measurement checker too."""
    measured, suite = measurement_fixture.fixture(folder / 'measurements')
    files = measured['sourceFingerprint']['files']
    files[profiles.PROFILER_SOURCE] = profiles.digest(b'synthetic profiler source')
    measured['sourceFingerprint']['aggregate'] = profiles.digest(profiles.canonical(files))
    measurement_fixture.rebind(folder / 'measurements', measured, suite)
    measurement_fixture.evidence.render(folder / 'measurements', verify_checksums=False)
    raw_manifest = (folder / 'measurements/manifest.json').read_bytes()
    tools = {name: profiles.digest(('synthetic ' + name).encode()) for name in profiles.TOOLS}
    diagnostics = folder / 'diagnostics'
    diagnostics.mkdir()
    for case in profiles.cases():
        directory = diagnostics / case['id']
        directory.mkdir()
        profiles.write_json(directory / 'jfr-events.json', events())
        with redirect_stdout(io.StringIO()):
            profiles.publish_case(directory, case, measured, raw_manifest, tools,
                {key: profiles.digest(('synthetic ' + key).encode())
                 for key in ('recording', 'settings', 'jmhResult', 'jmhLog')}, '2026-11-02T00:00:00Z', version)
        (directory / 'jfr-events.json').unlink()
    profiles.write_json(diagnostics / 'manifest.json', profiles.manifest_for(measured, raw_manifest, tools, version))
    reindex(folder)
    return measured


def reindex(folder):
    profiles.write_json(folder / 'evidence-index.json', profiles.index_for(folder))


def rewrite_json(path, mutate):
    data = profiles.read_json(path)
    mutate(data)
    profiles.write_json(path, data)


def raw_result(case, java, arguments):
    result = {key: profiles.settings()[key] for key in ('jmhVersion', 'mode', 'threads', 'forks',
              'warmupIterations', 'measurementIterations', 'warmupTime', 'measurementTime',
              'warmupBatchSize', 'measurementBatchSize')}
    result.update({'benchmark': case['benchmark'], 'params': case['params'],
                   'jvmArgs': arguments + list(profiles.ANNOTATION_HEAP_ARGS),
                   'jdkVersion': java['version'], 'vmVersion': java['vmVersion'], 'vmName': java['vmName']})
    result['primaryMetric'] = {'score': 11, 'scoreUnit': 'ns/op', 'rawData': [[10, 12]]}
    result['secondaryMetrics'] = {
        'gc.alloc.rate.norm': {'score': 20, 'scoreUnit': 'B/op', 'rawData': [[18, 22]]},
        'process.cpu.ns/op': {'score': 8, 'scoreUnit': 'ns/op', 'rawData': [[7, 9]]}}
    return [result]


class MechanismProfilesTest(unittest.TestCase):
    def test_diagnostic_sampling_uses_ten_ms_without_changing_stack_depth_or_event_selection(self):
        settings = profiles.settings()
        self.assertEqual(settings['executionSamplePeriod'], '10ms')
        self.assertIn('10ms', profiles.SCOPE)
        self.assertEqual(settings['recordingStackDepth'], 64)
        self.assertEqual(settings['exportStackDepth'], 64)
        self.assertEqual(settings['selectedEvents'], sorted(profiles.guard.EVENTS))
        self.assertEqual(settings['allocationThrottle'], '50/s')
        self.assertIn('50 events/second', profiles.SCOPE)
        self.assertEqual(profiles.MAX_SELECTED_BYTES, 128 * 1024 ** 2)
        command = profiles.configuration_command('synthetic-jfr', 'reports/local/synthetic/safe.jfc')
        self.assertEqual(command, ['synthetic-jfr', 'configure', '--input', 'profile.jfc', '--output',
                         'reports/local/synthetic/safe.jfc']
                         + [event + '#enabled=false' for event in profiles.DISABLED]
                         + ['jdk.ExecutionSample#period=10ms', 'jdk.ObjectAllocationSample#throttle=50/s'])

    def test_legacy_and_current_profiles_are_explicit_and_small_complete_archives_both_validate(self):
        self.assertEqual(profiles.LEGACY_PROTOCOL, 'ominicat-camel-mechanism-profiles-v1')
        self.assertEqual(profiles.PROTOCOL, 'ominicat-camel-mechanism-profiles-v2')
        old = profiles.settings(profiles.LEGACY_PROTOCOL)
        self.assertEqual(old['allocationSampling'], 'profile.jfc defaults')
        self.assertNotIn('allocationThrottle', old)
        self.assertNotIn('ObjectAllocationSample#throttle', ' '.join(
            profiles.configuration_command('jfr', 'synthetic.jfc', profiles.LEGACY_PROTOCOL)))
        for version in (profiles.LEGACY_PROTOCOL, profiles.PROTOCOL):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder, version)
                result = profiles.check(folder)
                self.assertEqual(result['protocol'], version)
                self.assertEqual(result['settings'], profiles.settings(version))
                self.assertEqual(result['scope'], profiles.profile_for_version(version)['scope'])

    def test_real_published_v1_metadata_hashes_scopes_and_figures_are_compatible_without_reparsing_large_events(self):
        # Event decoding is independently covered by the small v1/v2 archives.
        # This check reads the actual immutable archive and every file hash, but
        # substitutes its recorded compact summary to avoid repeatedly parsing
        # hundreds of megabytes in every source-hygiene run. Root also runs the
        # full, unmocked historical archive checker before publication.
        folder = HERE.parents[1] / 'reports/mechanisms/java17-2026-11-02/evidence'
        recorded = profiles.read_json(folder / 'diagnostics/manifest.json')
        self.assertEqual(recorded['protocol'], profiles.LEGACY_PROTOCOL)
        self.assertEqual(recorded['settings'], profiles.settings(profiles.LEGACY_PROTOCOL))
        self.assertEqual(recorded['scope'], profiles.LEGACY_SCOPE)
        before = profiles.read_bytes(folder / 'evidence-index.json')
        versions = []
        def recorded_summary(path, version):
            versions.append(version)
            return profiles.read_json(path.parent / 'jfr-hotspots.json')
        with mock.patch.object(profiles, 'selected_events', side_effect=recorded_summary):
            self.assertEqual(profiles.check(folder), recorded)
        self.assertEqual(versions, [profiles.LEGACY_PROTOCOL] * 5)
        self.assertEqual(profiles.read_bytes(folder / 'evidence-index.json'), before)

    def test_unknown_protocols_types_and_cross_version_metadata_fail_closed(self):
        for invalid in ('ominicat-camel-mechanism-profiles-v3', 'v2', '', None, 2, True, [], {}):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError): profiles.profile_for_version(invalid)
                with self.assertRaises(ValueError): profiles.settings(invalid)
        for target in ('manifest', 'index', 'context', 'load', 'summary', 'report', 'validation'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                directory = folder / 'diagnostics/ominicat-caller'
                if target == 'manifest':
                    rewrite_json(folder / 'diagnostics/manifest.json', lambda d: d.update(protocol='unknown-version'))
                    profiles.write_json(folder / 'evidence-index.json', profiles.index_for(folder, profiles.PROTOCOL))
                elif target == 'index':
                    rewrite_json(folder / 'evidence-index.json', lambda d: d.update(protocol=profiles.LEGACY_PROTOCOL))
                else:
                    if target == 'context':
                        rewrite_json(directory / 'diagnostic-context.json', lambda d: d.update(protocol=profiles.LEGACY_PROTOCOL))
                    elif target == 'load':
                        rewrite_json(directory / 'load.json', lambda d: d.update(kind=profiles.LEGACY_PROTOCOL))
                    elif target == 'summary':
                        rewrite_json(directory / 'jfr-hotspots.json', lambda d: d.update(scope=profiles.LEGACY_SCOPE))
                    elif target == 'report':
                        (directory / 'report.md').write_text(profiles.report_text(profiles.cases()[0], profiles.LEGACY_PROTOCOL))
                    else:
                        rewrite_json(directory / 'validation.json', lambda d: d.update(jmhSettingsValidated=profiles.settings(profiles.LEGACY_PROTOCOL)))
                    reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_selected_event_helper_default_is_still_single_argument_and_versions_change_only_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events.gz'
            path.write_bytes(gzip.compress(json.dumps(events()).encode()))
            current = profiles.selected_events(path)
            old = profiles.selected_events(path, profiles.LEGACY_PROTOCOL)
            self.assertEqual(current.pop('scope'), profiles.SCOPE)
            self.assertEqual(old.pop('scope'), profiles.LEGACY_SCOPE)
            self.assertEqual(current, old)
            for invalid in (None, 1, 'unknown-version'):
                with self.assertRaises(ValueError): profiles.selected_events(path, invalid)
            path.write_bytes(b'\x1f\x8b\x08\x00' + b'\x00' * 6 + b'\xff' * 10)
            with self.assertRaises(ValueError): profiles.selected_events(path)

    def test_tool_output_limit_reports_only_safe_numeric_stdout_stderr_and_fixed_case(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output, error = directory / 'stdout', directory / 'stderr'
            output.write_bytes(b'12345678')
            error.write_bytes(b'')
            with mock.patch.object(profiles, 'MAX_SELECTED_BYTES', 4), mock.patch.object(profiles, 'MAX_BYTES', 4):
                with self.assertRaises(profiles.ToolOutputLimitError) as raised:
                    profiles.check_tool_sizes(output, error)
                failure = profiles.DiagnosticFailure('JFR_EXPORT', raised.exception, 'ominicat-caller')
                self.assertEqual(failure.metadata(), {'schemaVersion': 1, 'phase': 'JFR_EXPORT',
                    'reason': 'JFR_OUTPUT_SIZE', 'case': 'ominicat-caller', 'stdoutBytes': 8,
                    'stderrBytes': 0, 'stdoutLimitBytes': 4, 'stderrLimitBytes': 4})
                stream = io.StringIO()
                with mock.patch.object(profiles, 'collect', side_effect=failure), redirect_stderr(stream):
                    self.assertEqual(profiles.main(['collect', '--root', str(directory), '--output', str(directory)]), 1)
                text = stream.getvalue()
                payload = json.loads(text.split('DIAGNOSTIC_FAILURE=', 1)[1])
                self.assertEqual(payload, failure.metadata())
                self.assertNotIn(str(directory), text)
                output.write_bytes(b'1234')
                error.write_bytes(b'1234')
                profiles.check_tool_sizes(output, error)  # Exact caps remain valid.
                error.write_bytes(b'12345')
                with self.assertRaises(profiles.ToolOutputLimitError) as raised:
                    profiles.check_tool_sizes(output, error)
                self.assertEqual(raised.exception.stdout_bytes, 4)
                self.assertEqual(raised.exception.stderr_bytes, 5)
        unknown = profiles.DiagnosticFailure('JFR_EXPORT', ValueError('/Users/synthetic/private'), '/Users/private')
        self.assertEqual(unknown.metadata()['case'], None)
        self.assertEqual(set(unknown.metadata()), {'schemaVersion', 'phase', 'reason', 'case'})

    def test_private_exporter_keeps_same_size_guard_after_a_successful_tool_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with mock.patch.object(profiles, 'MAX_SELECTED_BYTES', 4):
                with self.assertRaises(profiles.ToolOutputLimitError) as raised:
                    profiles.command_to_file([sys.executable, '-c', 'import sys;sys.stdout.write("12345678")'],
                                             directory, directory / 'stdout', directory / 'stderr')
                self.assertEqual(raised.exception.stdout_bytes, 8)
                self.assertEqual(raised.exception.stderr_bytes, 0)

    def test_private_jfr_tool_output_can_contain_an_absolute_path_without_becoming_public(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output, error = directory / 'configure.log', directory / 'configure-error.txt'
            # JDK17 Configure prints output.toPath().toAbsolutePath(), including
            # /home/runner on Ubuntu. This child is Python, not a JFR/Java run.
            content = 'Configuration written successfully to:\n/home/runner/work/synthetic/safe-profile.jfc\n'
            profiles.command_to_file([sys.executable, '-c', 'import sys;sys.stdout.write(' + repr(content) + ')'],
                                     directory, output, error)
            self.assertEqual(output.read_text(), content)
            self.assertEqual(error.read_bytes(), b'')
            # Retaining private tool output never relaxes the publication guard.
            with self.assertRaises(ValueError): profiles.guard.privacy_check('public log', output.read_bytes())
            self.assertNotIn('configure.log', profiles.CASE_FILES)
            self.assertNotIn('configure-error.txt', profiles.CASE_FILES)

    def test_collect_reports_a_fixed_preflight_phase_without_echoing_exception_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            capture = root / 'reports/local/capture'
            (capture / 'evidence/measurements').mkdir(parents=True)
            private_message = 'private path /Users/synthetic/secret and sk-' + 'a' * 40
            with mock.patch.object(profiles.measurement, 'check', side_effect=ValueError(private_message)):
                with self.assertRaises(profiles.DiagnosticFailure) as raised:
                    profiles.collect(root, capture)
                self.assertEqual(raised.exception.phase, 'MEASUREMENT_VALIDATION')
                self.assertEqual(raised.exception.reason, 'DETAILS_PRIVATE')
                error = io.StringIO()
                with redirect_stderr(error):
                    self.assertEqual(profiles.main(['collect', '--root', str(root), '--output', str(capture)]), 1)
                self.assertIn('[MEASUREMENT_VALIDATION/DETAILS_PRIVATE]', error.getvalue())
                self.assertNotIn(private_message, error.getvalue())
                self.assertNotIn(str(root), error.getvalue())
            measured = {'artifact': {'sha256': 'a' * 64}}
            with mock.patch.object(profiles.measurement, 'check', return_value=(measured, {})):
                (capture / 'evidence/measurements/manifest.json').write_text('{}')
                with self.assertRaises(profiles.DiagnosticFailure) as raised:
                    profiles.collect(root, capture)
                self.assertEqual(raised.exception.phase, 'ARTIFACT_BINDING')
                self.assertEqual(raised.exception.reason, 'MISSING_OR_NONREGULAR_FILE')

    def test_known_stage_reason_codes_do_not_accept_dynamic_or_lookalike_error_text(self):
        known = profiles.DiagnosticFailure('JFR_CONFIGURE', ValueError('JFR tool deadline exceeded'))
        self.assertEqual(str(known), 'JFR_CONFIGURE/JFR_TOOL_DEADLINE')
        for error in (ValueError('JFR tool deadline exceeded /Users/synthetic/private'),
                      ValueError('JFR tool deadline exceeded', '/Users/synthetic/private')):
            failure = profiles.DiagnosticFailure('JFR_CONFIGURE', error)
            self.assertEqual(str(failure), 'JFR_CONFIGURE/DETAILS_PRIVATE')
        self.assertEqual(profiles.DiagnosticFailure('private-user-input', ValueError()).phase, 'UNCLASSIFIED')

    def test_complete_archive_checks_measurements_profiles_and_all_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            fixture(folder)
            manifest = profiles.check(folder)
            self.assertFalse(manifest['headlineEligible'])
            self.assertEqual(len(manifest['cases']), 5)
            self.assertNotIn('identity', [case['id'] for case in manifest['cases']])
            self.assertEqual(set(profiles.index_for(folder)['files']), profiles.allowed_files() - {'evidence-index.json'})
            for case in profiles.cases():
                directory = folder / 'diagnostics' / case['id']
                self.assertEqual({path.name for path in directory.iterdir()}, profiles.CASE_FILES)
                summary = profiles.read_json(directory / 'jfr-hotspots.json')
                self.assertEqual(summary['gcCollectionCount'], 1)
                self.assertEqual(summary['gcPauseSumMillis'], 1)
                self.assertEqual(summary['sampledAllocationWeightBytes'], 4096)
                text = (directory / 'profiles.md').read_text()
                self.assertIn(profiles.NEW_ALLOCATION, text)
                self.assertNotIn(profiles.OLD_ALLOCATION, text)

    def test_raw_recordings_plain_events_logs_unknown_dirs_and_partial_profiles_fail(self):
        for mutation in ('raw.jfr', 'jfr-events.json', 'build.log', 'unknown-directory', 'missing-svg', 'missing-index'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                directory = folder / 'diagnostics' / profiles.cases()[0]['id']
                if mutation == 'unknown-directory': (directory / mutation).mkdir()
                elif mutation == 'missing-svg': (directory / profiles.SVG_FILES[0]).unlink()
                elif mutation == 'missing-index': (folder / 'evidence-index.json').unlink()
                else: (directory / mutation).write_text('synthetic unapproved file')
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_symlinks_and_index_traversal_cannot_escape_the_archive(self):
        for mutation in ('file', 'directory', 'index-path'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                if mutation == 'file':
                    path = folder / 'diagnostics/manifest.json'
                    path.unlink()
                    path.symlink_to(HERE / 'test_mechanism_evidence.py')
                elif mutation == 'directory':
                    (folder / 'diagnostics/unapproved').symlink_to(HERE, target_is_directory=True)
                else:
                    rewrite_json(folder / 'evidence-index.json', lambda data: data['files'].update({'../outside': 'a' * 64}))
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_content_and_measurement_changes_fail_without_rebinding(self):
        for name in ('diagnostics/manifest.json', 'measurements/summary.md',
                     'diagnostics/camel-caller/cpu-flamegraph.svg'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                # Keep JSON/SVG syntactically valid so this specifically proves
                # byte binding rather than only a parser failure.
                with (folder / name).open('ab') as destination: destination.write(b'\n')
                with self.assertRaisesRegex(ValueError, 'hash mismatch'): profiles.check(folder)

    def test_rehashed_metadata_cannot_change_artifact_scope_or_completeness(self):
        mutations = {
            'artifact': lambda data: data.update(artifactSha256='b' * 64),
            'measurement-manifest': lambda data: data.update(measurementManifestSha256='b' * 64),
            'source': lambda data: data.update(profilerSourceSha256='b' * 64),
            'settings': lambda data: data['settings'].update(forks=1),
            'allocation-throttle': lambda data: data['settings'].update(allocationThrottle='150/s'),
            'headline': lambda data: data.update(headlineEligible=True),
            'boolean-schema': lambda data: data.update(schemaVersion=True),
            'missing-case': lambda data: data['cases'].pop(),
            'tool': lambda data: data['toolSha256'].pop(profiles.TOOLS[0]),
            'extra-env': lambda data: data['environment'].update(secretOption='not allowed'),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                rewrite_json(folder / 'diagnostics/manifest.json', mutate)
                reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_rehashed_case_context_validation_and_stale_renderer_prose_fail(self):
        mutations = {
            'timestamp': ('diagnostic-context.json', lambda d: d.update(recordedAt='not a timestamp')),
            'case': ('diagnostic-context.json', lambda d: d['case'].update(id='identity')),
            'raw': ('diagnostic-context.json', lambda d: d['rawSha256'].pop('settings')),
            'summary': ('jfr-hotspots.json', lambda d: d.update(gcPauseSumMillis=99)),
            'load': ('load.json', lambda d: d.update(p99Micros=123)),
            'partial': ('validation.json', lambda d: d.update(status='partial')),
            'boolean-iteration': ('validation.json', lambda d: d.update(iterationsPerMetric=True)),
        }
        for name, (file, mutate) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                rewrite_json(folder / 'diagnostics/ominicat-caller' / file, mutate)
                reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'evidence'
            fixture(folder)
            path = folder / 'diagnostics/ominicat-caller/profiles.md'
            path.write_text(path.read_text().replace(profiles.NEW_ALLOCATION, profiles.OLD_ALLOCATION))
            reindex(folder)
            with self.assertRaises(ValueError): profiles.check(folder)

    def test_event_allowlist_finite_values_sample_presence_and_privacy_fail_closed(self):
        mutations = {
            'environment': lambda d: d['recording']['events'].append({'type': 'jdk.InitialEnvironmentVariable', 'values': {}}),
            'no-cpu': lambda d: d['recording']['events'].pop(0),
            'zero-allocation': lambda d: d['recording']['events'][1]['values'].update(weight=0),
            'fractional-allocation': lambda d: d['recording']['events'][1]['values'].update(weight=1.5),
            'negative-allocation': lambda d: d['recording']['events'][1]['values'].update(weight=-1),
            'empty': lambda d: d['recording'].update(events=[]),
            'secret': lambda d: d['recording']['events'][0]['values'].update(credential='sk-' + 'a' * 40),
            'personal-path': lambda d: d['recording']['events'][0]['values'].update(file='/Users/synthetic/private'),
            'class-type': lambda d: d['recording']['events'][1]['values'].update(objectClass='malformed'),
            'method-type': lambda d: d['recording']['events'][0]['values']['stackTrace']['frames'][0].update(method=[]),
            'owner-type': lambda d: d['recording']['events'][0]['values']['stackTrace']['frames'][0]['method'].update(type=1),
            'method-name': lambda d: d['recording']['events'][0]['values']['stackTrace']['frames'][0]['method'].update(name=1),
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events.gz'
            for name, mutate in mutations.items():
                with self.subTest(name=name):
                    data = events()
                    mutate(data)
                    path.write_bytes(gzip.compress(json.dumps(data).encode()))
                    with self.assertRaises(ValueError): profiles.selected_events(path)
            for value in ('NaN', '1e999'):
                path.write_bytes(gzip.compress(json.dumps(events()).replace('4096', value).encode()))
                with self.assertRaises(ValueError): profiles.selected_events(path)
            path.write_bytes(gzip.compress(json.dumps(events()).encode()))
            with mock.patch.object(profiles, 'MAX_SELECTED_BYTES', 100):
                with self.assertRaises(ValueError): profiles.selected_events(path)
            path.write_bytes(b'not gzip')
            with self.assertRaises(ValueError): profiles.selected_events(path)

    def test_rehashed_event_type_and_active_svg_are_rejected(self):
        for mutation in ('event', 'external-svg', 'script-svg', 'onload-svg', 'doctype-svg'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary) / 'evidence'
                fixture(folder)
                directory = folder / 'diagnostics/ominicat-caller'
                if mutation == 'event':
                    data = events()
                    data['recording']['events'][0]['type'] = 'jdk.ProcessStart'
                    (directory / 'jfr-events.json.gz').write_bytes(gzip.compress(json.dumps(data).encode()))
                else:
                    content = {'external-svg': '<image href="https://invalid.example/image"/>',
                               'script-svg': '<script>forbidden</script>', 'onload-svg': '<g onload="forbidden"/>',
                               'doctype-svg': '<!DOCTYPE svg>'}[mutation]
                    if mutation == 'doctype-svg': content += '<svg xmlns="http://www.w3.org/2000/svg"/>'
                    else: content = '<svg xmlns="http://www.w3.org/2000/svg">' + content + '</svg>'
                    (directory / 'cpu-flamegraph.svg').write_text(content)
                reindex(folder)
                with self.assertRaises(ValueError): profiles.check(folder)

    def test_template_replacement_requires_both_exactly_once(self):
        text = profiles.OLD_SCOPE + '\n\n' + profiles.OLD_ALLOCATION
        replaced = profiles.replace_renderer_scope(text)
        self.assertIn(profiles.SCOPE, replaced)
        self.assertIn(profiles.NEW_ALLOCATION, replaced)
        for invalid in (profiles.OLD_SCOPE, profiles.OLD_ALLOCATION, text + profiles.OLD_SCOPE,
                        text.replace('exact latency array', 'changed template')):
            with self.assertRaises(ValueError): profiles.replace_renderer_scope(invalid)

    def test_profiled_jmh_settings_origin_runtime_and_metrics_have_independent_checks(self):
        case = profiles.cases()[0]
        java = measurement_fixture.environment()['java']
        arguments = ['-Xms256m', '-Xmx256m', '-Dominicat.mechanism.jarSha256=' + 'a' * 64,
                     '-XX:FlightRecorderOptions=stackdepth=64',
                     '-XX:StartFlightRecording=filename=reports/local/synthetic/recording.jfr,'
                     'settings=reports/local/synthetic/safe.jfc,dumponexit=true']
        origin = '# Warmup Iteration   1: MECHANISM_ORIGIN_SHA256=' + 'a' * 64 + '\n'
        valid = raw_result(case, java, arguments)
        result = profiles.validate_result(valid, case, java, arguments, origin, 'a' * 64)
        self.assertFalse(result['headlineEligible'])
        mutations = {
            'fork': lambda r: r.update(forks=1),
            'boolean': lambda r: r.update(threads=True),
            'runtime': lambda r: r.update(jdkVersion='21.0.9'),
            'params': lambda r: r.update(params={'framework': 'camel', 'execution': 'caller'}),
            'argument': lambda r: r['jvmArgs'].append('-Dunexpected=true'),
            'annotation-heap': lambda r: r['jvmArgs'].__setitem__(-1, '-Xmx512m'),
            'parent-heap': lambda r: r['jvmArgs'].__setitem__(0, '-Xms512m'),
            'missing-annotation': lambda r: r['jvmArgs'].pop(),
            'extra-annotation': lambda r: r['jvmArgs'].extend(profiles.ANNOTATION_HEAP_ARGS),
            'cpu-missing': lambda r: r['secondaryMetrics'].pop('process.cpu.ns/op'),
            'nan': lambda r: r['primaryMetric'].update(score=float('nan')),
            'iteration': lambda r: r['primaryMetric']['rawData'][0].pop(),
            'mean': lambda r: r['primaryMetric'].update(score=13),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                raw = deepcopy(valid)
                mutate(raw[0])
                with self.assertRaises(ValueError): profiles.validate_result(raw, case, java, arguments, origin, 'a' * 64)
        for invalid in ('', origin + origin, origin.replace('a' * 64, 'b' * 64),
                        origin + 'ignored-prefix MECHANISM_ORIGIN_SHA256=' + 'a' * 64):
            with self.assertRaises(ValueError): profiles.validate_result(valid, case, java, arguments, invalid, 'a' * 64)

    def test_main_partial_evidence_returns_failure_without_sensitive_error_echo(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'empty'
            folder.mkdir()
            output, error = io.StringIO(), io.StringIO()
            with redirect_stdout(output), redirect_stderr(error):
                self.assertEqual(profiles.main(['check', str(folder)]), 1)
            self.assertNotIn(str(folder), error.getvalue())
            self.assertNotIn('validated;', output.getvalue())
            with mock.patch.object(profiles, 'check', side_effect=AttributeError('private ' + str(folder))), \
                    redirect_stderr(error):
                self.assertEqual(profiles.main(['check', str(folder)]), 1)
            self.assertNotIn(str(folder), error.getvalue())


if __name__ == '__main__':
    unittest.main()
