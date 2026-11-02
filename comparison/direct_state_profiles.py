#!/usr/bin/env python3
"""Allowlisted, independent JFR diagnostics for the paired direct-state experiment."""
import argparse
from datetime import datetime, timezone
import gzip
import json
import os
from pathlib import Path
import shutil
import sys
import zlib

import direct_state_evidence as evidence
import mechanism_profiles as shared

common = evidence.common
require, digest, canonical = evidence.require, evidence.digest, evidence.canonical
read_bytes, read_json, write_json = shared.read_bytes, shared.read_json, shared.write_json
PROTOCOL = 'ominicat-direct-state-diagnostics-v1'
TOOLS = ('comparison/direct_state_profiles.py', 'comparison/direct_state_evidence.py',
         'comparison/mechanism_profiles.py', 'comparison/mechanism_evidence.py',
         'scripts/summarize_profiles.py', 'scripts/package_profiles.py')
CASE_FILES = shared.CASE_FILES
SCOPE = ('Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, '
         'two 1-second warmups, two 1-second measurements, harness and teardown. '
         'No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled '
         'to 50 events/second; recording and export retain up to 64 frames. '
         'This is not main timing evidence, per-operation allocation, retained heap, '
         'method duration, p99 latency, RSS or production capacity.')
ALLOCATION_SCOPE = ('Weights estimate sampled allocation distribution, not retained heap or exact object counts. '
                    'Different fixed-duration loops can perform different operation counts. '
                    'This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.')


def cases():
    return [{'id': side + '-' + scenario, 'side': side, 'scenario': scenario}
            for scenario in ('raw-direct-4', 'envelope-direct-4') for side in evidence.SIDES]


def settings():
    return {'jmhVersion': '1.37', 'mode': 'avgt', 'threads': 1, 'forks': 0,
            'warmupIterations': 2, 'measurementIterations': 2,
            'warmupTime': '1 s', 'measurementTime': '1 s',
            'warmupBatchSize': 1, 'measurementBatchSize': 1,
            'heapArgs': ['-Xms256m', '-Xmx256m'], 'profilers': ['gc'],
            'executionSamplePeriod': '10ms', 'allocationThrottle': '50/s',
            'recordingStackDepth': 64, 'exportStackDepth': 64,
            'selectedJsonMaxBytes': shared.MAX_SELECTED_BYTES,
            'selectedEvents': sorted(shared.guard.EVENTS), 'disabledEvents': list(shared.DISABLED)}


def bindings(manifest, raw):
    return {'measurementManifestSha256': digest(raw),
            'artifactSha256': {key: value['sha256'] for key, value in manifest['artifacts'].items()},
            'sourceAggregateSha256': {key: value['aggregate'] for key, value in manifest['sources'].items()}}


def tool_hashes(root):
    return {name: digest(read_bytes(Path(root) / name)) for name in TOOLS}


def selected_events(path):
    result = shared.selected_events(path)
    result['scope'] = SCOPE
    return result


def report_text(case):
    return ('# Paired direct-state diagnostic: ' + case['id'] + '\n\n' + SCOPE + '\n\n'
            'This separate `-f 0` recording does not add a main measurement fork. '
            'The selected side uses its bound core JAR and the same probe JAR. '
            'CPU samples span JVM threads. Allocation weights and GC totals are not normalized '
            'by operations, so they cannot rank per-operation efficiency across these recordings.\n\n'
            '[CPU, allocation and GC figures](profiles.md) · [event summary](jfr-hotspots.json) · '
            '[source context](diagnostic-context.json) · [validation](validation.json)\n\n'
            'Raw JFR, private configuration, uncompressed exports and unreviewed tool/JMH logs '
            'remain outside this publication allowlist.\n')


def renderer_scope(text):
    require(text.count(shared.OLD_SCOPE) == 1 and text.count(shared.OLD_ALLOCATION) == 1,
            'renderer scope changed')
    return text.replace(shared.OLD_SCOPE, SCOPE).replace(shared.OLD_ALLOCATION, ALLOCATION_SCOPE).replace(
        'not the measured-load start', 'not the JMH measurement start').replace(
        '[measurement report](report.md)', '[diagnostic report](report.md)')


def load_context(case, java):
    return {'schemaVersion': 1, 'kind': PROTOCOL, 'case': case, 'instrumented': True,
            'headlineEligible': False, 'scope': SCOPE, 'environment': {'javaVersion': java['version']}}


def validation_for(case, java):
    return {'schemaVersion': 1, 'status': 'complete', 'case': case, 'instrumented': True,
            'headlineEligible': False, 'originVerifiedExactlyOnce': True, 'runtime': java,
            'settings': settings(), 'metrics': sorted(evidence.METRICS), 'scope': SCOPE}


def validate_result(data, case, manifest, arguments, log):
    require(isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict), 'incomplete profile result')
    result = data[0]
    require(result.get('benchmark') == evidence.BENCHMARK
            and result.get('params') == {'scenario': case['scenario']}, 'profile case mismatch')
    for key in ('jmhVersion', 'mode', 'threads', 'forks', 'warmupIterations', 'measurementIterations',
                'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize'):
        require(type(result.get(key)) is type(settings()[key]) and result[key] == settings()[key],
                'profile JMH settings mismatch')
    require(result.get('jvmArgs') == arguments, 'profile JVM options mismatch')
    java = manifest['environment']['java']
    require(java['version'].split('.')[0] == '17', 'profile runtime mismatch')
    for key, name in (('jdkVersion', 'version'), ('vmVersion', 'vmVersion'), ('vmName', 'vmName')):
        require(result.get(key) == java[name], 'profile runtime mismatch')
    evidence.validate_origin(log, manifest['artifacts'], case['side'])
    for name, (key, unit) in evidence.METRICS.items():
        metric = result.get('primaryMetric') if key == 'primaryMetric' else result.get('secondaryMetrics', {}).get(key)
        require(isinstance(metric, dict) and metric.get('scoreUnit') == unit, 'profile metric mismatch')
        raw = metric.get('rawData')
        require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list) and len(raw[0]) == 2,
                'incomplete profile result')
        values = [common.number(value, name, positive=name == 'timeNsPerOp') for value in raw[0]]
        score = common.number(metric.get('score'), name, positive=name == 'timeNsPerOp')
        require(abs(score - sum(values) / 2) <= max(1e-6, abs(score) * 1e-6), 'profile metric mismatch')
    return validation_for(case, java)


def index_for(root):
    root = Path(root)
    return {'schemaVersion': 1, 'protocol': PROTOCOL,
            'files': {path.relative_to(root).as_posix(): digest(read_bytes(path))
                      for path in sorted(root.rglob('*')) if path.is_file() and path.name != 'evidence-index.json'}}


def inventory(root):
    root = Path(root)
    allowed = {'evidence-index.json', 'diagnostics/manifest.json'} | {
        'measurements/' + name for name in evidence.allowed_files()} | {
        'diagnostics/' + case['id'] + '/' + name for case in cases() for name in CASE_FILES}
    actual = set()
    allowed_dirs = {parent.as_posix() for name in allowed for parent in Path(name).parents if parent.as_posix() != '.'}
    require(root.is_dir() and not root.is_symlink(), 'invalid profile inventory')
    for path in root.rglob('*'):
        require(not path.is_symlink(), 'symlink in profile evidence')
        if path.is_file(): actual.add(path.relative_to(root).as_posix())
        else: require(path.is_dir() and path.relative_to(root).as_posix() in allowed_dirs, 'invalid profile inventory')
    require(actual == allowed, 'incomplete or unapproved profile evidence')


def manifest_for(measured, raw, tools):
    return {'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete', 'bindings': bindings(measured, raw),
            'settings': settings(), 'scope': SCOPE, 'cases': cases(), 'toolSha256': tools}


def check(root):
    root = Path(root)
    inventory(root)
    require(canonical(read_json(root / 'evidence-index.json')) == canonical(index_for(root)), 'profile hash mismatch')
    measured, _ = evidence.check(root / 'measurements')
    raw = read_bytes(root / 'measurements/manifest.json')
    manifest = read_json(root / 'diagnostics/manifest.json')
    tools = manifest.get('toolSha256') if isinstance(manifest, dict) else None
    require(isinstance(tools, dict) and set(tools) == set(TOOLS)
            and all(isinstance(value, str) and common.SHA.fullmatch(value) for value in tools.values()),
            'invalid profile tool bindings')
    require(canonical(manifest) == canonical(manifest_for(measured, raw, tools)), 'profile binding mismatch')
    for case in cases():
        folder = root / 'diagnostics' / case['id']
        context = read_json(folder / 'diagnostic-context.json')
        expected_keys = {'schemaVersion', 'protocol', 'status', 'recordedAt', 'case', 'scope', 'instrumented',
                         'headlineEligible', 'bindings', 'settings', 'toolSha256', 'rawSha256', 'eventSummary'}
        require(isinstance(context, dict) and set(context) == expected_keys, 'invalid profile context')
        require(context['schemaVersion'] == 1 and type(context['schemaVersion']) is int
                and context['protocol'] == PROTOCOL and context['status'] == 'complete'
                and context['case'] == case and context['scope'] == SCOPE and context['instrumented'] is True
                and context['headlineEligible'] is False and context['bindings'] == bindings(measured, raw)
                and canonical(context['settings']) == canonical(settings()) and context['toolSha256'] == tools, 'profile context mismatch')
        try:
            instant = datetime.fromisoformat(context['recordedAt'].replace('Z', '+00:00'))
            valid_time = len(context['recordedAt']) <= 64 and instant.tzinfo is not None and instant.utcoffset().total_seconds() == 0
        except (ValueError, AttributeError, TypeError): valid_time = False
        require(valid_time, 'invalid profile timestamp')
        hashes = context['rawSha256']
        require(isinstance(hashes, dict) and set(hashes) == {'recording', 'settings', 'jmhResult', 'jmhLog'}
                and all(isinstance(value, str) and common.SHA.fullmatch(value) for value in hashes.values()),
                'invalid private profile fingerprints')
        summary = selected_events(folder / 'jfr-events.json.gz')
        require(canonical(context['eventSummary']) == canonical(summary)
                and canonical(read_json(folder / 'jfr-hotspots.json')) == canonical(summary),
                'profile event summary mismatch')
        require(canonical(read_json(folder / 'load.json')) == canonical(load_context(case, measured['environment']['java']))
                and canonical(read_json(folder / 'validation.json')) == canonical(validation_for(case, measured['environment']['java'])),
                'profile validation mismatch')
        require(shared.guard.privacy_check('profile report', read_bytes(folder / 'report.md')) == report_text(case),
                'profile report scope mismatch')
        text = shared.guard.privacy_check('profile Markdown', read_bytes(folder / 'profiles.md'))
        require(SCOPE in text and ALLOCATION_SCOPE in text and shared.OLD_SCOPE not in text
                and shared.OLD_ALLOCATION not in text, 'stale profile scope')
        for name in shared.SVG_FILES:
            require('(' + name + ')' in text, 'missing profile figure')
            shared.validate_svg(folder / name)
    return manifest


def collect(baseline, candidate, output):
    phase, case = 'CAPTURE_LAYOUT', None
    try:
        baseline, candidate, output = map(lambda p: Path(p).absolute(), (baseline, candidate, output))
        require(not output.is_symlink(), 'invalid capture root')
        baseline, candidate, output = baseline.resolve(), candidate.resolve(), output.resolve()
        public = output / 'evidence'
        require(not public.is_symlink(), 'invalid publication root')
        private = output / 'raw-direct-state'
        require(not private.exists() and not (public / 'diagnostics').exists(), 'choose a fresh diagnostic capture')
        phase = 'MEASUREMENT_VALIDATION'
        measured, _ = evidence.check(public / 'measurements')
        raw = read_bytes(public / 'measurements/manifest.json')
        for root in (baseline, candidate): evidence.require_clean(root)
        phase = 'SOURCE_BINDING'
        require(evidence.sources_for(baseline, candidate) == measured['sources'], 'diagnostic source mismatch')
        tools = tool_hashes(candidate)
        require(digest(read_bytes(Path(__file__))) == tools[TOOLS[0]], 'collector origin mismatch')
        phase = 'ARTIFACT_BINDING'
        require(evidence.artifacts_for(baseline, candidate) == measured['artifacts'], 'diagnostic artifact mismatch')
        java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java'
        java = java.resolve()
        require(common.host_environment(java) == measured['environment'], 'diagnostic host mismatch')
        jfr = java.with_name('jfr')
        require(jfr.is_file(), 'JFR tool unavailable')
        private.mkdir(parents=True)
        stage = private / 'publish'
        stage.mkdir()
        for case in cases():
            phase = 'PRIVATE_PREPARATION'
            folder = private / case['id']
            folder.mkdir()
            config, recording = folder / 'safe.jfc', folder / 'recording.jfr'
            result, log = folder / 'jmh.json', folder / 'jmh.log'
            relative = lambda p: os.path.relpath(p, candidate)
            phase = 'JFR_CONFIGURE'
            command = [str(jfr), 'configure', '--input', 'profile.jfc', '--output', relative(config)] + [
                event + '#enabled=false' for event in shared.DISABLED] + [
                'jdk.ExecutionSample#period=10ms', 'jdk.ObjectAllocationSample#throttle=50/s']
            shared.command_to_file(command, candidate, folder / 'configure.log', folder / 'configure-error.txt')
            arguments = evidence.jvm_args(measured['artifacts'], case['side']) + [
                '-XX:FlightRecorderOptions=stackdepth=64',
                '-XX:StartFlightRecording=filename=' + relative(recording) + ',settings=' + relative(config) + ',dumponexit=true']
            core = (baseline if case['side'] == 'baseline' else candidate) / evidence.CORE_JAR
            probe = candidate / evidence.PROBE_JAR
            classpath = os.pathsep.join((relative(core), relative(probe)))
            command = [str(java), *arguments, '-cp', classpath, 'org.openjdk.jmh.Main',
                       '^' + evidence.BENCHMARK.replace('.', r'\.') + '$', '-p', 'scenario=' + case['scenario'],
                       '-bm', 'avgt', '-tu', 'ns', '-t', '1', '-f', '0', '-wi', '2', '-i', '2',
                       '-w', '1s', '-r', '1s', '-foe', 'true', '-prof', 'gc', '-rf', 'json', '-rff', relative(result)]
            phase = 'JMH_EXECUTION'
            common.run_command(command, candidate, log)
            phase = 'JMH_VALIDATION'
            validation = validate_result(read_json(result), case, measured, arguments, read_bytes(log))
            render = folder / 'render'
            render.mkdir()
            phase = 'JFR_EXPORT'
            shared.command_to_file([str(jfr), 'print', '--json', '--stack-depth', '64', '--events',
                                    ','.join(sorted(shared.guard.EVENTS)), relative(recording)], candidate,
                                   render / 'jfr-events.json', folder / 'export-error.txt')
            packed = render / 'jfr-events.json.gz'
            packed.write_bytes(gzip.compress(read_bytes(render / 'jfr-events.json', shared.MAX_SELECTED_BYTES), mtime=0))
            summary = selected_events(packed)
            phase = 'PROFILE_RENDER'
            write_json(render / 'load.json', load_context(case, measured['environment']['java']))
            shared.renderer.main(render)
            profile = render / 'profiles.md'
            profile.write_text(renderer_scope(profile.read_text(encoding='utf-8')), encoding='utf-8')
            write_json(render / 'jfr-hotspots.json', summary)
            write_json(render / 'validation.json', validation)
            (render / 'report.md').write_text(report_text(case), encoding='utf-8')
            write_json(render / 'diagnostic-context.json', {
                'schemaVersion': 1, 'protocol': PROTOCOL, 'status': 'complete',
                'recordedAt': datetime.now(timezone.utc).isoformat(), 'case': case, 'scope': SCOPE,
                'instrumented': True, 'headlineEligible': False, 'bindings': bindings(measured, raw),
                'settings': settings(), 'toolSha256': tools, 'eventSummary': summary,
                'rawSha256': {name: digest(read_bytes(path, 256 * 1024 ** 2)) for name, path in (
                    ('recording', recording), ('settings', config), ('jmhResult', result), ('jmhLog', log))}})
            destination = stage / case['id']
            destination.mkdir()
            for name in CASE_FILES: shutil.copyfile(render / name, destination / name)
            phase = 'SOURCE_RECHECK'
            require(evidence.sources_for(baseline, candidate) == measured['sources']
                    and evidence.artifacts_for(baseline, candidate) == measured['artifacts']
                    and tool_hashes(candidate) == tools, 'sources or artifacts changed during profiling')
        case = None
        phase = 'ARCHIVE_PUBLISH'
        write_json(stage / 'manifest.json', manifest_for(measured, raw, tools))
        stage.rename(public / 'diagnostics')
        write_json(public / 'evidence-index.json', index_for(public))
        phase = 'ARCHIVE_VALIDATION'
        try:
            return check(public)
        except Exception:
            (public / 'evidence-index.json').unlink(missing_ok=True)
            raise
    except Exception as error:
        metadata = {'phase': phase, 'case': case['id'] if case in cases() else None,
                    'reason': 'TOOL_OUTPUT_SIZE' if isinstance(error, shared.ToolOutputLimitError) else 'DETAILS_PRIVATE'}
        if isinstance(error, shared.ToolOutputLimitError):
            metadata.update(stdoutBytes=error.stdout_bytes, stderrBytes=error.stderr_bytes,
                            stdoutLimitBytes=shared.MAX_SELECTED_BYTES, stderrLimitBytes=shared.MAX_BYTES)
        raise DiagnosticFailure(metadata) from None


class DiagnosticFailure(ValueError):
    def __init__(self, metadata):
        self.metadata = metadata
        super().__init__('Direct-state diagnostic collection failed')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    collect_parser = sub.add_parser('collect')
    for name in ('baseline', 'candidate', 'output'): collect_parser.add_argument('--' + name, required=True, type=Path)
    sub.add_parser('check').add_argument('folder', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'collect': collect(args.baseline, args.candidate, args.output)
        else: check(args.folder)
        print('Paired direct-state diagnostics validated; instrumented timings are not main measurements.')
        return 0
    except DiagnosticFailure as error:
        print('DIRECT_STATE_DIAGNOSTIC_FAILURE=' + canonical(error.metadata).decode('utf-8'), file=sys.stderr)
        return 1
    except (ValueError, OSError, KeyError, TypeError, AttributeError, OverflowError, RecursionError, zlib.error):
        print('Direct-state diagnostic evidence is incomplete or invalid; private details are not published.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
