#!/usr/bin/env python3
"""Collect separate whole-recording Java JFR diagnostics, never headline timings.

Only reports/local destinations are accepted. No build, installation, networking,
or public copying is performed. See profile_java.md before publishing any output.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
EVENTS = ("jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection",
          "jdk.JavaMonitorEnter", "jdk.VirtualThreadPinned")
DISABLED = ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty",
            "jdk.InitialSecurityProperty", "jdk.SecurityPropertyModification",
            "jdk.SystemProcess", "jdk.ProcessStart", "jdk.OSInformation", "jdk.JVMInformation")
SCOPE = ("Whole selected recording: JVM/framework startup, setup, 3-second warmup, "
         "5-second W=1 measurement, harness and teardown. No phase filtering. "
         "Separate profiled diagnostics; not headline timings, framework-only allocation, "
         "retained heap, or a shared cross-language bytes-per-operation axis.")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    with Path(path).open('x', encoding='utf-8') as destination:
        json.dump(value, destination, indent=2, allow_nan=False)
        destination.write('\n')


def stop_owned(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=3)


def command_to_file(command, output, stderr, timeout=60):
    """Tool commands are bounded and their unsanitized diagnostics stay local."""
    with Path(output).open('xb') as out, Path(stderr).open('xb') as err:
        subprocess.run(command, cwd=ROOT, stdout=out, stderr=err, check=True, timeout=timeout)


def collect_phases(command, folder, timeout):
    launched_utc, launched_ns = utc_now(), time.monotonic_ns()
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, bufsize=1, start_new_session=True)
    phases, logs, errors = [], [], []
    total_bytes = 0
    lock = threading.Lock()

    def reader(stream, stdout):
        nonlocal total_bytes
        try:
            for line in stream:
                observed_utc, observed_ns = utc_now(), time.monotonic_ns()
                with lock:
                    total_bytes += len(line.encode('utf-8'))
                    if total_bytes > 262144:
                        errors.append('adapter diagnostics exceed bound')
                        continue
                    logs.append(line)
                    if stdout:
                        try:
                            event = json.loads(line)
                            if isinstance(event, dict) and event.get('event') == 'phase':
                                phases.append({'phase': event.get('phase'), 'observedAtUtc': observed_utc,
                                               'parentElapsedSeconds': (observed_ns - launched_ns) / 1e9})
                        except json.JSONDecodeError:
                            pass  # JFR startup information is not a phase event.
        except (OSError, UnicodeError):
            with lock:
                errors.append('adapter output could not be read')
        finally:
            stream.close()

    readers = [threading.Thread(target=reader, args=(process.stdout, True), daemon=True),
               threading.Thread(target=reader, args=(process.stderr, False), daemon=True)]
    for thread in readers:
        thread.start()
    try:
        process.wait(timeout=timeout)
        exited_utc, exited_seconds = utc_now(), (time.monotonic_ns() - launched_ns) / 1e9
        for thread in readers:
            thread.join(timeout=2)
        if any(thread.is_alive() for thread in readers):
            raise ValueError('adapter output readers did not terminate')
        if process.returncode != 0 or errors:
            raise ValueError('profiled adapter failed; inspect local diagnostics')
        if [entry['phase'] for entry in phases] != ['warmup', 'measure', 'teardown']:
            raise ValueError('missing, duplicate, or unordered phase events')
        points = [{'phase': 'startup', 'observedAtUtc': launched_utc, 'parentElapsedSeconds': 0.0}] + phases
        end = {'observedAtUtc': exited_utc, 'parentElapsedSeconds': exited_seconds}
        windows = []
        for index, point in enumerate(points):
            following = points[index + 1] if index + 1 < len(points) else end
            windows.append({'phase': point['phase'], 'startObservedAtUtc': point['observedAtUtc'],
                            'endObservedAtUtc': following['observedAtUtc'],
                            'parentElapsedStartSeconds': point['parentElapsedSeconds'],
                            'parentElapsedEndSeconds': following['parentElapsedSeconds']})
        return {'observation': 'Parent receipt/launch/exit UTC; asynchronous observations, not exact child boundaries.',
                'processStartedAtUtc': launched_utc, 'processExitedAtUtc': exited_utc,
                'phaseEvents': phases, 'phaseWindows': windows}
    finally:
        stop_owned(process)
        for thread in readers:
            thread.join(timeout=2)
        (folder / 'adapter.log').write_text(''.join(logs), encoding='utf-8')


def render_profiles(folder, context):
    # Reuse the checked-in renderer and privacy guard, not a second stack/GC parser.
    sys.path.insert(0, str(ROOT / 'scripts'))
    import summarize_profiles
    from package_profiles import parse_json, pause_ms, privacy_check

    write_json(folder / 'load.json', {'schemaVersion': 1, 'kind': 'queued-comparison-jfr-context',
               'engine': context['engine'], 'mode': 'queued-w1', 'stages': 4, 'scope': SCOPE,
               'environment': {'javaVersion': context['result']['environment']['version']}})
    summarize_profiles.main(folder)
    # The general renderer has kernel/JMH-specific prose; replace it explicitly
    # for this adapter without modifying the common renderer or inventing JMH data.
    profiles = folder / 'profiles.md'
    text = profiles.read_text(encoding='utf-8')
    original = ('Generated from the selected JFR events for the bounded kernel load. Samples include the load harness '
                'and warmup; they are not a model or network profile.')
    if original not in text:
        raise ValueError('profile renderer scope wording changed; review before publishing')
    text = text.replace(original, SCOPE)
    text = text.replace('Use JMH `gc.alloc.rate.norm` for per-operation allocation estimates.',
                        'No per-operation allocation estimate is inferred from this recording.')
    profiles.write_text(text, encoding='utf-8')
    raw = (folder / 'jfr-events.json').read_bytes()
    payload = parse_json('selected JFR events', privacy_check('selected JFR events', raw))
    events = payload['recording']['events']
    counts = Counter(event['type'] for event in events)
    pauses = [pause_ms(event['values']['sumOfPauses']) for event in events if event['type'] == 'jdk.GarbageCollection']
    return {'selectedEventCounts': dict(sorted(counts.items())),
            'gcCollectionCount': len(pauses), 'gcPauseSumMillis': sum(pauses),
            'maxCollectionPauseSumMillis': max(pauses, default=0)}


def profile_engine(args, engine, java, jfr, artifact, artifact_hash, output):
    folder = output / engine
    folder.mkdir()
    safe = folder / 'safe-profile.jfc'
    command_to_file([str(jfr), 'configure', '--input', 'profile.jfc', '--output', str(safe)]
                    + [event + '#enabled=false' for event in DISABLED]
                    + ['jdk.ExecutionSample#period=1ms'],
                    folder / 'jfr-config.log', folder / 'jfr-config-error.log')
    raw_jfr = folder / 'recording.jfr'
    result_path = folder / 'result.json'
    command = [str(java), '-Xms128m', '-Xmx512m', '-XX:FlightRecorderOptions=stackdepth=64',
               f'-XX:StartFlightRecording=filename={raw_jfr},settings={safe},dumponexit=true',
               f'-Xlog:gc*:file={folder / "gc.log"}:uptime,level,tags',
               '-jar', str(artifact), '--engine', engine, '--stages', '4',
               '--warmup-seconds', '3', '--measure-seconds', '5', '--max-samples', '2000000',
               '--output', str(result_path)]
    phases = collect_phases(command, folder, args.timeout)
    from evidence import validate_result
    result = validate_result(json.loads(result_path.read_text(encoding='utf-8')),
                             {'engine': engine, 'stages': 4},
                             {'warmupSeconds': 3, 'measureSeconds': 5, 'maxSamples': 2_000_000,
                              'versions': {'ominicat': '0.1.0-SNAPSHOT', 'camel': '4.22.2'}})
    if not result['environment']['version'].startswith('17.'):
        raise ValueError('diagnostic run requires actual Java 17')
    result.update({'instrumented': True, 'headlineEligible': False, 'profileType': 'JFR'})
    result_path.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    if sha256(artifact) != artifact_hash or not raw_jfr.is_file() or raw_jfr.stat().st_size == 0:
        raise ValueError('artifact changed or recording is missing')
    command_to_file([str(jfr), 'summary', str(raw_jfr)], folder / 'jfr-summary.txt', folder / 'jfr-summary-error.log')
    command_to_file([str(jfr), 'print', '--json', '--stack-depth', '64', '--events', ','.join(EVENTS), str(raw_jfr)],
                    folder / 'jfr-events.json', folder / 'jfr-print-error.log')
    context = {'schemaVersion': 1, 'status': 'complete', 'engine': engine, 'stages': 4,
               'kind': 'separate-profiled-diagnostics', 'headlineTiming': False, 'scope': SCOPE,
               'recordedAtUtc': utc_now(), 'benchmarkArtifact': artifact.name,
               'benchmarkArtifactSha256': artifact_hash, 'stackDepthRequested': 64,
               'executionSamplePeriodRequested': '1 ms',
               'exportStackDepth': 64, 'selectedEventTypes': list(EVENTS),
               'profileWrapperSha256': sha256(__file__), 'observedPhases': phases, 'result': result}
    context.update(render_profiles(folder, context))
    report = ('# Separate Java queued-path diagnostic\n\n' + SCOPE + '\n\n'
              f'Engine: **{engine}**; four application stages; Java 17; one outstanding item.\n\n'
              'The JFR-instrumented result is retained only to validate this diagnostic workload. '
              'It must not replace any of the 45 unprofiled comparison results. Parent-observed phase windows '
              'record stdout receipt, not exact JVM event boundaries; the charts do not filter by these windows.\n\n'
              '[CPU/allocation/GC figures](profiles.md) · [Diagnostic context](diagnostic-summary.json) · '
              '[Profiled validation result](result.json)\n\n'
              'Raw JFR, uncompressed event exports, configuration and logs stay under ignored `reports/local`. '
              'No automatic public copy is performed. Sampled allocation weights are neither retained memory nor '
              'framework-only or cross-language B/op. Zero sampled events do not establish absence.\n')
    (folder / 'report.md').write_text(report, encoding='utf-8')
    names = ('report.md', 'profiles.md', 'cpu-flamegraph.svg', 'allocation-flamegraph.svg',
             'cpu-hotspots.svg', 'allocation-hotspots.svg', 'gc-pauses.svg', 'jfr-events.json.gz',
             'load.json', 'result.json', 'jfr-summary.txt')
    context['artifactSha256'] = {name: sha256(folder / name) for name in names}
    write_json(folder / 'diagnostic-summary.json', context)
    return {'engine': engine, 'file': engine + '/diagnostic-summary.json',
            'sha256': sha256(folder / 'diagnostic-summary.json')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, help='new empty folder beneath reports/local')
    parser.add_argument('--java', required=True, help='actual Java 17 executable; sibling jfr is used')
    parser.add_argument('--jar', default='comparison/benchmarks/target/benchmark.jar')
    parser.add_argument('--expected-jar-sha256', help='optional exact hash from the unprofiled suite manifest')
    parser.add_argument('--engines', default='ominicat,camel', help='comma-separated ominicat and/or camel')
    parser.add_argument('--timeout', type=float, default=90)
    args = parser.parse_args()
    if not 15 <= args.timeout <= 600:
        parser.error('timeout must be between 15 and 600 seconds')
    engines = args.engines.split(',')
    if not engines or len(set(engines)) != len(engines) or any(engine not in ('ominicat', 'camel') for engine in engines):
        parser.error('invalid engine list')
    output = Path(args.output).resolve()
    if not output.is_relative_to((ROOT / 'reports/local').resolve()) or ',' in str(output):
        parser.error('output must be beneath reports/local and contain no comma')
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error('choose a new empty output folder')
    executable = shutil.which(args.java)
    if executable is None:
        parser.error('Java executable was not found')
    java = Path(executable).resolve()
    jfr = java.with_name('jfr')
    artifact = Path(args.jar).resolve()
    if not jfr.is_file() or not artifact.is_file():
        parser.error('matching JDK jfr or benchmark artifact was not found')
    artifact_hash = sha256(artifact)
    expected = args.expected_jar_sha256
    if expected is not None and (re.fullmatch('[0-9a-f]{64}', expected) is None or expected != artifact_hash):
        parser.error('benchmark artifact hash does not match expected measurement artifact')
    output.mkdir(parents=True, exist_ok=True)
    records = [profile_engine(args, engine, java, jfr, artifact, artifact_hash, output) for engine in engines]
    if sha256(artifact) != artifact_hash:
        raise ValueError('benchmark artifact changed during diagnostics')
    write_json(output / 'summary.json', {'schemaVersion': 1, 'status': 'complete',
               'kind': 'separate-profiled-diagnostics', 'headlineTiming': False, 'scope': SCOPE,
               'benchmarkArtifactSha256': artifact_hash, 'profiles': records})
    print('Completed separate Java JFR diagnostics; all raw recordings and logs remain local.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as failure:
        print(f'Java diagnostics failed ({type(failure).__name__}); no complete suite summary was produced.', file=sys.stderr)
        sys.exit(1)
