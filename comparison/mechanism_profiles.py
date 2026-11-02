#!/usr/bin/env python3
"""Collect/check separate, allowlisted JFR diagnostics for the mechanism experiment.

No build or dependency installation is performed. Unreviewed recordings, settings,
JMH JSON and tool logs remain in capture/raw; only evidence/ is publishable.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import gzip
import io
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'comparison'))
sys.path.insert(0, str(ROOT / 'scripts'))
import mechanism_evidence as measurement
import package_profiles as guard
import summarize_profiles as renderer

LEGACY_PROTOCOL = 'ominicat-camel-mechanism-profiles-v1'
PROTOCOL = 'ominicat-camel-mechanism-profiles-v2'
PROFILER_SOURCE = 'comparison/mechanisms/src/main/java/io/ominicat/mechanisms/ProcessCpuProfiler.java'
TOOLS = ('comparison/mechanism_profiles.py', 'comparison/mechanism_evidence.py',
         'scripts/summarize_profiles.py', 'scripts/package_profiles.py')
DISABLED = ('jdk.InitialEnvironmentVariable', 'jdk.InitialSystemProperty',
            'jdk.InitialSecurityProperty', 'jdk.SecurityPropertyModification',
            'jdk.SystemProcess', 'jdk.ProcessStart', 'jdk.OSInformation', 'jdk.JVMInformation')
SVG_FILES = ('cpu-flamegraph.svg', 'allocation-flamegraph.svg', 'cpu-hotspots.svg',
             'allocation-hotspots.svg', 'gc-pauses.svg')
CASE_FILES = frozenset(('report.md', 'profiles.md', 'load.json', 'jfr-hotspots.json',
                        'diagnostic-context.json', 'validation.json', 'jfr-events.json.gz') + SVG_FILES)
ANNOTATION_HEAP_ARGS = ('-Xms256m', '-Xmx256m')
EXECUTION_SAMPLE_PERIOD = '10ms'
MAX_BYTES = 16 * 1024 * 1024
MAX_SELECTED_BYTES = 128 * 1024 * 1024
LEGACY_SCOPE = ('Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes '
         'JVM/framework startup, setup, two 1-second warmup iterations, two 1-second '
         'measurement iterations, harness and teardown. No phase filtering. '
         'CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. '
         'Recording and exported stacks retain up to 64 frames. '
         'These are not headline timings, framework-exclusive CPU, retained heap, '
         'exact object counts, p99 latency or RSS measurements.')
SCOPE = LEGACY_SCOPE.replace('allocation uses profile.jfc defaults.',
                             'allocation sampling is explicitly throttled to 50 events/second.')
OLD_SCOPE = ('Generated from the selected JFR events for the bounded kernel load. Samples include the load harness '
             'and warmup; they are not a model or network profile.')
OLD_ALLOCATION = ('Weights estimate sampled allocation distribution, not retained heap or exact object counts. '
                  'The harness retains an exact latency array; its `long[]` allocation is intentionally visible. '
                  'Use JMH `gc.alloc.rate.norm` for per-operation allocation estimates.')
NEW_ALLOCATION = ('Weights estimate sampled allocation distribution, not retained heap or exact object counts. '
                  'This JMH mechanism experiment has no exact latency array. Use the separate uninstrumented '
                  'JMH measurements for per-operation allocation; no such estimate is inferred from these samples.')
PHASES = frozenset(('CAPTURE_LAYOUT', 'MEASUREMENT_VALIDATION', 'ARTIFACT_BINDING', 'SOURCE_BINDING',
                    'TOOL_ORIGIN', 'RUNTIME_BINDING', 'PRIVATE_PREPARATION', 'JFR_CONFIGURE',
                    'JMH_EXECUTION', 'JMH_VALIDATION', 'JFR_EXPORT', 'PROFILE_RENDER',
                    'SOURCE_RECHECK', 'ARCHIVE_PUBLISH', 'ARCHIVE_VALIDATION'))
SAFE_REASONS = {
    'capture path must not be a symlink': 'CAPTURE_SYMLINK',
    'capture must be under reports/local': 'CAPTURE_OUTSIDE_LOCAL',
    'symlink in capture path': 'CAPTURE_SYMLINK',
    'capture must contain only the completed measurement evidence': 'CAPTURE_CONTENTS',
    'capture must contain only evidence/measurements before profiling': 'EVIDENCE_CONTENTS',
    'expected a regular diagnostic file': 'MISSING_OR_NONREGULAR_FILE',
    'empty or oversized diagnostic file': 'FILE_SIZE',
    'diagnostic artifact differs from measured artifact': 'ARTIFACT_MISMATCH',
    'diagnostic source differs from measured source': 'SOURCE_MISMATCH',
    'collector origin differs from selected root': 'COLLECTOR_ORIGIN_MISMATCH',
    'diagnostic helper origins differ from selected root': 'HELPER_ORIGIN_MISMATCH',
    'JFR tool is unavailable': 'JFR_TOOL_MISSING',
    'diagnostic host/JDK differs from measurements': 'HOST_OR_JDK_MISMATCH',
    'capture path is unsafe for JFR arguments': 'JFR_PATH_SYNTAX',
    'JFR tool failed; inspect private local output': 'JFR_TOOL_EXIT',
    'JFR tool output exceeds bound': 'JFR_OUTPUT_SIZE',
    'JFR tool deadline exceeded': 'JFR_TOOL_DEADLINE',
    'artifact, source or diagnostic tools changed during profiling': 'SOURCE_CHANGED',
}


class ToolOutputLimitError(ValueError):
    """Safe numeric context; the tool's private stdout/stderr are never attached."""
    def __init__(self, stdout_bytes, stderr_bytes):
        if type(stdout_bytes) is not int or type(stderr_bytes) is not int or min(stdout_bytes, stderr_bytes) < 0:
            raise ValueError('invalid tool byte counts')
        self.stdout_bytes, self.stderr_bytes = stdout_bytes, stderr_bytes
        super().__init__('JFR tool output exceeds bound')


class DiagnosticFailure(ValueError):
    """Only constant, allowlisted phase/reason codes may reach the public CLI."""
    def __init__(self, phase, error, case=None):
        self.phase = phase if phase in PHASES else 'UNCLASSIFIED'
        # Never print arbitrary exception text, command arguments or paths.
        self.reason = next((code for text, code in SAFE_REASONS.items()
                            if error.args == (text,)), 'DETAILS_PRIVATE')
        self.case = case if case in {item['id'] for item in cases()} else None
        self.sizes = ({'stdoutBytes': error.stdout_bytes, 'stderrBytes': error.stderr_bytes,
                       'stdoutLimitBytes': MAX_SELECTED_BYTES, 'stderrLimitBytes': MAX_BYTES}
                      if isinstance(error, ToolOutputLimitError) else {})
        super().__init__(self.phase + '/' + self.reason)

    def metadata(self):
        return {'schemaVersion': 1, 'phase': self.phase, 'reason': self.reason, 'case': self.case, **self.sizes}

require = measurement.require
digest = measurement.digest
canonical = measurement.canonical
write_json = measurement.write_json


def cases():
    """Identity is intentionally omitted from diagnostics, not measurements."""
    return [case for case in measurement.cases() if case['id'] != 'identity']


def profile_for_version(version):
    require(type(version) is str and version in (LEGACY_PROTOCOL, PROTOCOL),
            'unsupported diagnostic protocol version')
    return {'protocol': version, 'scope': LEGACY_SCOPE if version == LEGACY_PROTOCOL else SCOPE,
            'allocationThrottle': None if version == LEGACY_PROTOCOL else '50/s'}


def settings(version=PROTOCOL):
    profile = profile_for_version(version)
    result = {'jmhVersion': '1.37', 'mode': 'avgt', 'unit': 'ns/op', 'threads': 1,
            'forks': 0, 'warmupIterations': 2, 'measurementIterations': 2,
            'warmupTime': '1 s', 'measurementTime': '1 s', 'warmupBatchSize': 1,
            'measurementBatchSize': 1, 'fixedApplicationStages': 4,
            'minHeapBytes': 256 * 1024 ** 2, 'maxHeapBytes': 256 * 1024 ** 2,
            'profilers': ['gc', measurement.CPU_PROFILER], 'jfrBase': 'profile.jfc',
            'executionSamplePeriod': EXECUTION_SAMPLE_PERIOD, 'allocationSampling': 'profile.jfc defaults',
            'recordingStackDepth': 64, 'exportStackDepth': 64,
            'selectedEvents': sorted(guard.EVENTS), 'disabledEvents': list(DISABLED),
            'reportedJvmArgsAnnotationSuffix': list(ANNOTATION_HEAP_ARGS),
            'jvmArgsScope': 'The collector starts one JVM with five explicit parent options. '
                           'JMH -f 0 reports those options followed by the two @Fork heap annotation '
                           'values as metadata; no child JVM is started.'}
    if profile['allocationThrottle'] is not None:
        result['allocationSampling'] = 'explicit throttle'
        result['allocationThrottle'] = profile['allocationThrottle']
    return result


def read_bytes(path, limit=MAX_BYTES):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), 'expected a regular diagnostic file')
    require(0 < path.stat().st_size <= limit, 'empty or oversized diagnostic file')
    return path.read_bytes()


def read_json(path):
    return guard.parse_json('diagnostic JSON', guard.privacy_check('diagnostic JSON', read_bytes(path)))


def selected_events(path, version=PROTOCOL):
    profile = profile_for_version(version)
    packed = read_bytes(path)
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:
            raw = stream.read(MAX_SELECTED_BYTES + 1)
    except (OSError, EOFError, zlib.error):
        raise ValueError('invalid selected-event gzip') from None
    require(0 < len(raw) <= MAX_SELECTED_BYTES, 'selected-event export exceeds bound')
    payload = guard.parse_json('selected JFR events', guard.privacy_check('selected JFR events', raw))
    require(isinstance(payload, dict) and set(payload) == {'recording'}, 'invalid selected-event root')
    recording = payload['recording']
    require(isinstance(recording, dict) and set(recording) == {'events'}, 'invalid selected-event recording')
    events = recording['events']
    require(isinstance(events, list) and 0 < len(events) <= 500000, 'missing or excessive selected events')
    counts, leaves, allocations, pauses = Counter(), Counter(), Counter(), []
    for event in events:
        require(isinstance(event, dict) and set(event) == {'type', 'values'}
                and event['type'] in guard.EVENTS and isinstance(event['values'], dict),
                'unapproved or invalid selected event')
        kind, values = event['type'], event['values']
        counts[kind] += 1
        if 'startTime' in values:
            renderer.event_time(values['startTime'])
        stack = values.get('stackTrace')
        require(stack is None or isinstance(stack, dict), 'invalid selected stack')
        frames = (stack or {}).get('frames') or []
        require(isinstance(frames, list) and len(frames) <= 256
                and all(isinstance(frame, dict) for frame in frames), 'invalid selected stack frames')
        for frame in frames:
            method = frame.get('method')
            require(method is None or isinstance(method, dict), 'invalid selected stack method')
            method = method or {}
            owner = method.get('type')
            require(owner is None or isinstance(owner, dict), 'invalid selected method owner')
            require(isinstance(method.get('name', 'unknown'), str)
                    and isinstance((owner or {}).get('name', 'unknown'), str), 'invalid selected method name')
        if kind == 'jdk.ExecutionSample':
            leaves[renderer.method(frames[0]) if frames else '[stack unavailable]'] += 1
        elif kind == 'jdk.ObjectAllocationSample':
            weight = renderer.allocation_weight(event)
            allocated = values.get('objectClass')
            require(allocated is None or isinstance(allocated, dict), 'invalid allocation class metadata')
            name = (allocated or {}).get('name', 'unknown')
            require(isinstance(name, str), 'invalid allocation class')
            allocations[name.replace('/', '.')] += weight
        elif kind == 'jdk.GarbageCollection':
            renderer.event_time(values.get('startTime'))
            pauses.append(guard.pause_ms(values.get('sumOfPauses')))
    require(counts['jdk.ExecutionSample'] > 0 and sum(allocations.values()) > 0,
            'CPU and positive-weight allocation samples are required')
    summary = {'schemaVersion': 1, 'scope': profile['scope'],
               'selectedEventCounts': dict(sorted(counts.items())),
               'cpuLeafSamples': [{'method': name, 'samples': value} for name, value in leaves.most_common(20)],
               'allocationClassWeights': [{'class': name, 'bytes': value} for name, value in allocations.most_common(20)],
               'sampledAllocationWeightBytes': sum(allocations.values()),
               'gcCollectionCount': len(pauses), 'gcPauseSumMillis': sum(pauses),
               'maxCollectionPauseSumMillis': max(pauses, default=0)}
    canonical(summary)  # Reject overflow in aggregate values as well as individual JSON numbers.
    return summary


def replace_renderer_scope(text, version=PROTOCOL):
    profile = profile_for_version(version)
    require(text.count(OLD_SCOPE) == 1 and text.count(OLD_ALLOCATION) == 1,
            'profile renderer wording changed; review scope before publishing')
    return text.replace(OLD_SCOPE, profile['scope']).replace(OLD_ALLOCATION, NEW_ALLOCATION).replace(
        'not the measured-load start', 'not the JMH measurement start').replace(
        '[measurement report](report.md)', '[diagnostic report](report.md)')


def load_context(case, java, version=PROTOCOL):
    profile = profile_for_version(version)
    return {'schemaVersion': 1, 'kind': version, 'case': case, 'instrumented': True,
            'headlineEligible': False, 'scope': profile['scope'], 'environment': {'javaVersion': java['version']}}


def report_text(case, version=PROTOCOL):
    profile = profile_for_version(version)
    return ('# Separate mechanism diagnostic: ' + case['id'] + '\n\n' + profile['scope'] + '\n\n'
            'This is one independent `-f 0` diagnostic JVM, not either of the two fresh measurement forks. '
            'The identity copy control is measured in the main matrix but intentionally not profiled here. '
            'CPU stacks cover all JVM threads; allocation weights are sampled allocated-byte weights. '
            'GC sums cover the selected whole recording, not only the timed measurement interval.\n\n'
            '[CPU, allocation and GC figures](profiles.md) · [compact event summary](jfr-hotspots.json) · '
            '[recording context](diagnostic-context.json) · [validation](validation.json)\n\n'
            'Raw JFR, uncompressed events, JMH output and tool logs remain local under `capture/raw`; '
            'none are included in this diagnostic archive.\n')


def tool_hashes(root):
    return {name: digest(read_bytes(root / name)) for name in TOOLS}


def binding(measured, manifest_bytes):
    require(PROFILER_SOURCE in measured['sourceFingerprint']['files'], 'missing CPU profiler source binding')
    return {'measurementManifestSha256': digest(manifest_bytes),
            'artifactSha256': measured['artifact']['sha256'], 'sourceRevision': measured['sourceRevision'],
            'sourceFingerprintSha256': measured['sourceFingerprint']['aggregate'],
            'profilerSourceSha256': measured['sourceFingerprint']['files'][PROFILER_SOURCE]}


def manifest_for(measured, manifest_bytes, tools, version=PROTOCOL):
    profile = profile_for_version(version)
    return {'schemaVersion': 1, 'protocol': version, 'status': 'complete', 'instrumented': True,
            'headlineEligible': False, 'scope': profile['scope'], **binding(measured, manifest_bytes),
            'toolSha256': tools, 'environment': measured['environment'], 'settings': settings(version), 'cases': cases()}


def allowed_files():
    return {'evidence-index.json'} | {'measurements/' + name for name in measurement.allowed_files()} | {
        'diagnostics/manifest.json'} | {'diagnostics/' + case['id'] + '/' + name
                                     for case in cases() for name in CASE_FILES}


def inventory(folder):
    require(not folder.is_symlink() and folder.is_dir(), 'evidence root must be a real directory')
    allowed = allowed_files()
    directories = {str(parent) for name in allowed for parent in PurePosixPath(name).parents if str(parent) != '.'}
    found = set()
    for path in folder.rglob('*'):
        require(not path.is_symlink(), 'symlink in diagnostic evidence')
        name = path.relative_to(folder).as_posix()
        if path.is_dir():
            require(name in directories, 'unapproved evidence directory')
        else:
            require(path.is_file() and name in allowed, 'unapproved evidence file')
            found.add(name)
    require(found == allowed, 'incomplete evidence file inventory')


def index_for(folder, version=None):
    if version is None:
        version = read_json(folder / 'diagnostics/manifest.json').get('protocol')
    profile_for_version(version)
    files = {name: digest(read_bytes(folder / name)) for name in sorted(allowed_files() - {'evidence-index.json'})}
    return {'schemaVersion': 1, 'protocol': version, 'status': 'complete', 'files': files}


def validate_svg(path):
    raw = read_bytes(path)
    text = guard.privacy_check('profile SVG', raw)
    require('<!DOCTYPE' not in text.upper() and '<!ENTITY' not in text.upper(), 'unsafe SVG declaration')
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        raise ValueError('invalid profile SVG') from None
    require(root.tag == '{http://www.w3.org/2000/svg}svg', 'profile must be SVG')
    for node in root.iter():
        require(node.tag in {'{http://www.w3.org/2000/svg}' + tag for tag in
                            ('svg', 'title', 'desc', 'g', 'rect', 'text', 'line', 'circle')},
                'unapproved SVG element')
        for key, value in node.attrib.items():
            require(not key.lower().startswith('on') and key != 'style' and 'url(' not in value.lower(),
                    'active or external SVG attribute')
            if key.rsplit('}', 1)[-1] == 'href':
                require(value.startswith('#'), 'external SVG dependency')


def check(folder):
    folder = Path(folder)
    inventory(folder)
    manifest = read_json(folder / 'diagnostics/manifest.json')
    require(isinstance(manifest, dict), 'invalid diagnostic manifest')
    version = manifest.get('protocol')
    profile = profile_for_version(version)
    index = read_json(folder / 'evidence-index.json')
    require(canonical(index) == canonical(index_for(folder, version)), 'evidence index or file hash mismatch')
    measured, _ = measurement.check(folder / 'measurements')
    manifest_bytes = read_bytes(folder / 'measurements/manifest.json')
    tools = manifest.get('toolSha256') if isinstance(manifest, dict) else None
    require(isinstance(tools, dict) and set(tools) == set(TOOLS)
            and all(isinstance(value, str) and measurement.SHA.fullmatch(value) for value in tools.values()),
            'missing or invalid diagnostic tool hashes')
    require(canonical(manifest) == canonical(manifest_for(measured, manifest_bytes, tools, version)),
            'diagnostic manifest binding mismatch')
    for case in cases():
        directory = folder / 'diagnostics' / case['id']
        context = read_json(directory / 'diagnostic-context.json')
        expected = {'schemaVersion', 'protocol', 'status', 'recordedAt', 'case', 'scope',
                    'instrumented', 'headlineEligible', 'bindings', 'toolSha256', 'settings',
                    'rawSha256', 'eventSummary'}
        require(isinstance(context, dict) and set(context) == expected, 'unexpected diagnostic context fields')
        require(context['schemaVersion'] == 1 and type(context['schemaVersion']) is int
                and context['protocol'] == version and context['status'] == 'complete'
                and context['instrumented'] is True and context['headlineEligible'] is False
                and context['scope'] == profile['scope'] and context['case'] == case
                and context['bindings'] == binding(measured, manifest_bytes)
                and context['toolSha256'] == tools and canonical(context['settings']) == canonical(settings(version)),
                'diagnostic case or context binding mismatch')
        try:
            instant = datetime.fromisoformat(context['recordedAt'].replace('Z', '+00:00'))
            valid_time = len(context['recordedAt']) <= 64 and instant.tzinfo is not None and instant.utcoffset().total_seconds() == 0
        except (ValueError, AttributeError, TypeError):
            valid_time = False
        require(valid_time, 'diagnostic timestamp must be UTC')
        hashes = context['rawSha256']
        require(isinstance(hashes, dict) and set(hashes) == {'recording', 'settings', 'jmhResult', 'jmhLog'}
                and all(isinstance(value, str) and measurement.SHA.fullmatch(value) for value in hashes.values()),
                'missing private diagnostic fingerprints')
        summary = selected_events(directory / 'jfr-events.json.gz', version)
        require(canonical(context['eventSummary']) == canonical(summary)
                and canonical(read_json(directory / 'jfr-hotspots.json')) == canonical(summary),
                'event summaries disagree with selected events')
        require(canonical(read_json(directory / 'load.json')) == canonical(load_context(case, measured['environment']['java'], version)),
                'unexpected renderer context')
        require(canonical(read_json(directory / 'validation.json')) == canonical(validation_for(case, measured['environment']['java'], version)),
                'incomplete diagnostic validation')
        require(guard.privacy_check('diagnostic report', read_bytes(directory / 'report.md')) == report_text(case, version),
                'diagnostic report scope mismatch')
        text = guard.privacy_check('profile Markdown', read_bytes(directory / 'profiles.md'))
        require(profile['scope'] in text and NEW_ALLOCATION in text and OLD_SCOPE not in text and OLD_ALLOCATION not in text,
                'stale kernel profile scope')
        for name in SVG_FILES:
            require('(' + name + ')' in text, 'missing profile figure link')
            validate_svg(directory / name)
    return manifest


def validation_for(case, java, version=PROTOCOL):
    profile = profile_for_version(version)
    return {'schemaVersion': 1, 'status': 'complete', 'case': case, 'instrumented': True,
            'headlineEligible': False, 'originValidatedExactlyOnce': True, 'runtime': java,
            'jmhSettingsValidated': settings(version), 'metrics': sorted(measurement.METRICS),
            'iterationsPerMetric': 2, 'scope': profile['scope']}


def validate_result(data, case, java, jvm_args, log, jar_sha, version=PROTOCOL):
    """Validate the private JMH output without publishing its instrumented timings."""
    require(isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict), 'expected one diagnostic JMH case')
    result = data[0]
    expected = settings(version)
    require(result.get('benchmark') == case['benchmark'] and result.get('params') == case['params'],
            'diagnostic JMH case mismatch')
    for key in ('jmhVersion', 'mode', 'threads', 'warmupIterations', 'measurementIterations',
                'warmupTime', 'measurementTime', 'warmupBatchSize', 'measurementBatchSize', 'forks'):
        require(type(result.get(key)) is type(expected[key]) and result[key] == expected[key],
                'diagnostic JMH settings mismatch')
    # JMH 1.37 combines current-process arguments with @Fork jvmArgsAppend in
    # result metadata even in non-forked mode. The actual process receives only
    # jvm_args; require this exact suffix rather than accepting arbitrary repeats.
    require(result.get('jvmArgs') == jvm_args + list(ANNOTATION_HEAP_ARGS),
            'diagnostic JVM arguments mismatch')
    for key, name in (('jdkVersion', 'version'), ('vmVersion', 'vmVersion'), ('vmName', 'vmName')):
        require(result.get(key) == java[name], 'diagnostic JDK mismatch')
    require(java['version'].split('.')[0] == '17', 'diagnostics require Java 17')
    measurement.validate_origin(log.encode('utf-8'), jar_sha)
    for name, (key, unit) in measurement.METRICS.items():
        metric = result.get('primaryMetric') if key == 'primaryMetric' else result.get('secondaryMetrics', {}).get(key)
        require(isinstance(metric, dict) and metric.get('scoreUnit') == unit, 'missing diagnostic metric')
        score = measurement.number(metric.get('score'), name, positive=name == 'timeNsPerOp')
        raw = metric.get('rawData')
        require(isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], list) and len(raw[0]) == 2,
                'incomplete diagnostic iteration data')
        values = [measurement.number(value, name, positive=name == 'timeNsPerOp') for value in raw[0]]
        require(abs(score - sum(values) / 2) <= max(1e-6, abs(score) * 1e-6), 'diagnostic metric mean mismatch')
    return validation_for(case, java, version)


def publish_case(directory, case, measured, manifest_bytes, tools, raw_hashes, recorded_at, version=PROTOCOL):
    """Finish a private render directory; callers copy only CASE_FILES afterward."""
    profile = profile_for_version(version)
    write_json(directory / 'load.json', load_context(case, measured['environment']['java'], version))
    renderer.main(directory)
    profiles = directory / 'profiles.md'
    profiles.write_text(replace_renderer_scope(profiles.read_text(encoding='utf-8'), version), encoding='utf-8')
    summary = selected_events(directory / 'jfr-events.json.gz', version)
    write_json(directory / 'jfr-hotspots.json', summary)
    write_json(directory / 'diagnostic-context.json', {
        'schemaVersion': 1, 'protocol': version, 'status': 'complete', 'recordedAt': recorded_at,
        'case': case, 'scope': profile['scope'], 'instrumented': True, 'headlineEligible': False,
        'bindings': binding(measured, manifest_bytes), 'toolSha256': tools,
        'settings': settings(version), 'rawSha256': raw_hashes, 'eventSummary': summary})
    write_json(directory / 'validation.json', validation_for(case, measured['environment']['java'], version))
    (directory / 'report.md').write_text(report_text(case, version), encoding='utf-8')


def command_to_file(command, root, output, error, timeout=60):
    """Bound tool processes and leave all unreviewed output under capture/raw."""
    with output.open('xb') as out, error.open('xb') as err:
        process = subprocess.Popen(command, cwd=root, stdout=out, stderr=err,
                                   env=measurement.child_environment(), start_new_session=True)
        started = time.monotonic()
        try:
            while process.poll() is None:
                check_tool_sizes(output, error)
                if time.monotonic() - started > timeout:
                    raise TimeoutError('JFR tool deadline exceeded')
                time.sleep(.05)
            require(process.returncode == 0, 'JFR tool failed; inspect private local output')
        finally:
            measurement.stop_owned(process)
    check_tool_sizes(output, error)


def check_tool_sizes(output, error):
    stdout_bytes, stderr_bytes = output.stat().st_size, error.stat().st_size
    if stdout_bytes > MAX_SELECTED_BYTES or stderr_bytes > MAX_BYTES:
        raise ToolOutputLimitError(stdout_bytes, stderr_bytes)


def configuration_command(jfr, config, version=PROTOCOL):
    profile = profile_for_version(version)
    command = [str(jfr), 'configure', '--input', 'profile.jfc', '--output', str(config)] + [
        event + '#enabled=false' for event in DISABLED] + [
        'jdk.ExecutionSample#period=' + EXECUTION_SAMPLE_PERIOD]
    if profile['allocationThrottle'] is not None:
        command.append('jdk.ObjectAllocationSample#throttle=' + profile['allocationThrottle'])
    return command


def collect(root, output):
    phase = {'code': 'CAPTURE_LAYOUT', 'case': None}
    try:
        return _collect(root, output, phase)
    except Exception as error:
        raise DiagnosticFailure(phase['code'], error, phase['case']) from None


def _collect(root, output, phase):
    root, output = Path(root).resolve(), Path(output).absolute()
    require(not output.is_symlink(), 'capture path must not be a symlink')
    require(output.is_relative_to(root / 'reports/local'), 'capture must be under reports/local')
    for parent in (output, *output.parents):
        require(not parent.is_symlink(), 'symlink in capture path')
        if parent == root:
            break
    output = output.resolve()
    evidence = output / 'evidence'
    require(output.is_dir() and {path.name for path in output.iterdir()} <= {'evidence'},
            'capture must contain only the completed measurement evidence')
    require(not evidence.is_symlink() and evidence.is_dir()
            and {path.name for path in evidence.iterdir()} == {'measurements'},
            'capture must contain only evidence/measurements before profiling')
    phase['code'] = 'MEASUREMENT_VALIDATION'
    measured, _ = measurement.check(evidence / 'measurements')
    manifest_bytes = read_bytes(evidence / 'measurements/manifest.json')
    phase['code'] = 'ARTIFACT_BINDING'
    jar = root / measurement.JAR
    jar_sha = digest(read_bytes(jar, 256 * 1024 ** 2))
    require(jar_sha == measured['artifact']['sha256'], 'diagnostic artifact differs from measured artifact')
    phase['code'] = 'SOURCE_BINDING'
    source = measurement.source_fingerprint(root)
    require(source == measured['sourceFingerprint'], 'diagnostic source differs from measured source')
    phase['code'] = 'TOOL_ORIGIN'
    tools = tool_hashes(root)
    require(digest(read_bytes(Path(__file__))) == tools[TOOLS[0]], 'collector origin differs from selected root')
    require(digest(read_bytes(Path(renderer.__file__))) == tools['scripts/summarize_profiles.py']
            and digest(read_bytes(Path(guard.__file__))) == tools['scripts/package_profiles.py']
            and digest(read_bytes(Path(measurement.__file__))) == tools['comparison/mechanism_evidence.py'],
            'diagnostic helper origins differ from selected root')
    phase['code'] = 'RUNTIME_BINDING'
    java = (Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin/java').resolve()
    jfr = java.parent / 'jfr'
    require(jfr.is_file(), 'JFR tool is unavailable')
    require(measurement.host_environment(java) == measured['environment'], 'diagnostic host/JDK differs from measurements')
    phase['code'] = 'PRIVATE_PREPARATION'
    private = output / 'raw'
    private.mkdir()
    stage = private / 'publish'
    stage.mkdir()
    for case in cases():
        phase['case'] = case['id']
        phase['code'] = 'PRIVATE_PREPARATION'
        folder = private / case['id']
        folder.mkdir()
        config, recording = folder / 'safe-profile.jfc', folder / 'recording.jfr'
        relative = lambda path: os.path.relpath(path, root)
        require(',' not in relative(folder), 'capture path is unsafe for JFR arguments')
        phase['code'] = 'JFR_CONFIGURE'
        # The JDK tool prints the absolute output path, even for relative --output.
        # These bounded, private logs must not pass through the PUBLIC log checker.
        command_to_file(configuration_command(jfr, relative(config)), root,
                        folder / 'configure.log', folder / 'configure-error.txt', timeout=60)
        jvm_args = ['-Xms256m', '-Xmx256m', '-Dominicat.mechanism.jarSha256=' + jar_sha,
                    '-XX:FlightRecorderOptions=stackdepth=64',
                    '-XX:StartFlightRecording=filename=' + relative(recording) + ',settings=' + relative(config) + ',dumponexit=true']
        result, log = folder / 'jmh.json', folder / 'jmh.log'
        command = [str(java), *jvm_args, '-jar', measurement.JAR, '^' + re.escape(case['benchmark']) + '$',
                   '-bm', 'avgt', '-tu', 'ns', '-t', '1', '-f', '0', '-wi', '2', '-i', '2',
                   '-w', '1s', '-r', '1s', '-foe', 'true', '-prof', 'gc', '-prof', measurement.CPU_PROFILER,
                   '-rf', 'json', '-rff', relative(result)]
        for key, value in sorted(case['params'].items()):
            command.extend(['-p', key + '=' + value])
        phase['code'] = 'JMH_EXECUTION'
        measurement.run_command(command, root, log)
        phase['code'] = 'JMH_VALIDATION'
        validate_result(read_json(result), case, measured['environment']['java'], jvm_args,
                        guard.privacy_check('private JMH log', read_bytes(log)), jar_sha)
        render = folder / 'render'
        render.mkdir()
        phase['code'] = 'JFR_EXPORT'
        command_to_file([str(jfr), 'print', '--json', '--stack-depth', '64', '--events',
                         ','.join(sorted(guard.EVENTS)), relative(recording)], root,
                        render / 'jfr-events.json', folder / 'export-error.txt')
        # Validate untrusted event categories and privacy before invoking any renderer.
        with (render / 'jfr-events.json.gz').open('wb') as stream:
            with gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as zipped:
                zipped.write(read_bytes(render / 'jfr-events.json', MAX_SELECTED_BYTES))
        selected_events(render / 'jfr-events.json.gz')
        raw_hashes = {name: digest(read_bytes(path, 256 * 1024 ** 2)) for name, path in
                      (('recording', recording), ('settings', config), ('jmhResult', result), ('jmhLog', log))}
        phase['code'] = 'PROFILE_RENDER'
        publish_case(render, case, measured, manifest_bytes, tools, raw_hashes, datetime.now(timezone.utc).isoformat())
        destination = stage / case['id']
        destination.mkdir()
        for name in CASE_FILES:
            shutil.copyfile(render / name, destination / name)
        phase['code'] = 'SOURCE_RECHECK'
        require(digest(read_bytes(jar, 256 * 1024 ** 2)) == jar_sha
                and measurement.source_fingerprint(root) == source and tool_hashes(root) == tools,
                'artifact, source or diagnostic tools changed during profiling')
    phase['case'] = None
    phase['code'] = 'ARCHIVE_PUBLISH'
    write_json(stage / 'manifest.json', manifest_for(measured, manifest_bytes, tools))
    stage.rename(evidence / 'diagnostics')
    write_json(evidence / 'evidence-index.json', index_for(evidence))
    try:
        phase['code'] = 'ARCHIVE_VALIDATION'
        return check(evidence)
    except Exception:
        (evidence / 'evidence-index.json').unlink(missing_ok=True)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('collect')
    capture.add_argument('--root', type=Path, required=True)
    capture.add_argument('--output', type=Path, required=True)
    verify = commands.add_parser('check')
    verify.add_argument('evidence', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'collect':
            collect(args.root, args.output)
        else:
            check(args.evidence)
    except DiagnosticFailure as error:
        print('Mechanism diagnostics failed [' + error.phase + '/' + error.reason + ']. '
              'Measurement evidence remains independently checkable; raw diagnostics stay private.', file=sys.stderr)
        print('DIAGNOSTIC_FAILURE=' + canonical(error.metadata()).decode('utf-8'), file=sys.stderr)
        return 1
    except (ValueError, OSError, KeyError, TypeError, AttributeError, IndexError,
            OverflowError, RecursionError, ET.ParseError, subprocess.SubprocessError):
        print('Mechanism diagnostics failed validation; inspect local inputs without publishing raw output.', file=sys.stderr)
        return 1
    print('Mechanism diagnostic evidence validated; instrumented profiles are not headline measurements.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
