#!/usr/bin/env python3
"""Produce bounded, numeric deep-validation evidence from a completed sustained run."""
import json
import pathlib
import shutil
import sys
import re


def main(source, destination, criteria_path=None):
    root = pathlib.Path(source)
    target = pathlib.Path(destination)
    target.mkdir(parents=True, exist_ok=True)
    result = json.loads((root / 'result.json').read_text())
    samples = [json.loads(line) for line in (root / 'samples.jsonl').read_text().splitlines() if line]
    before, after = result['afterWarmupFullGc'], result['afterDrainFullGc']
    resource_keys = ['heapUsedBytes', 'directBufferBytes', 'nettyDirectBytes', 'openFileDescriptors', 'platformThreads', 'loadedClasses']
    comparison = {key: {'afterWarmup': before.get(key), 'afterDrain': after.get(key),
                        'delta': after.get(key, 0) - before.get(key, 0)} for key in resource_keys}
    criteria_file = pathlib.Path(criteria_path) if criteria_path else pathlib.Path(__file__).with_name('soak-criteria.json')
    criteria = json.loads(criteria_file.read_text())
    if criteria_file.resolve() != (target / 'soak-criteria.json').resolve():
        shutil.copyfile(criteria_file, target / 'soak-criteria.json')
    checks = {
        'measuredDuration': result['measuredSeconds'] >= criteria['measuredSeconds'],
        'zeroUnexpectedFailures': result['failures'] == 0,
        'allClientsProgressed': result['allClientsProgressed'],
        'allFrameTypesExercised': all(result['validatedFramesByType'].get(kind, 0) > 0 for kind in criteria['requiredPositiveFrameTypes']),
        'frameTypeConservation': sum(result['validatedFramesByType'].values()) == result['measuredFrames'],
        'latencyCountConservation': result['steadyLatency']['count'] + result['churnLatency']['count'] == result['measuredFrames'],
        'resourcesDrained': all(re.search(r'(?m)^ominicat_' + name + r' 0$', result['drainedMetrics']) for name in criteria['postDrainZeroGauges']),
        'retainedHeapGrowth': comparison['heapUsedBytes']['delta'] <= criteria['maximumPostFullGcHeapGrowthBytes'],
        'directMemoryGrowth': comparison['nettyDirectBytes']['delta'] <= criteria['maximumPostDrainNettyDirectGrowthBytes'],
        'descriptorGrowth': comparison['openFileDescriptors']['delta'] <= criteria['maximumPostDrainDescriptorGrowth'],
        'platformThreadGrowth': comparison['platformThreads']['delta'] <= criteria['maximumPostDrainPlatformThreadGrowth']}
    checks = {name: bool(value) for name, value in checks.items()}
    summary = {'runtime': {key: before.get(key) for key in ('javaVersion', 'workerBackend')},
               'workload': {'measuredSeconds': result['measuredSeconds'], 'warmupSeconds': result['warmupSeconds'],
                           'persistentClients': result['persistentClients'], 'churnClients': result['churnClients'],
                           'heapLimitMiB': 256, 'directMemoryLimitMiB': 128},
               'frames': result['measuredFrames'], 'frameTypes': result['validatedFramesByType'],
               'newConnections': result['newMeasuredConnections'], 'failures': result['failures'],
               'allClientsProgressed': result['allClientsProgressed'], 'steadyLatency': result['steadyLatency'],
               'churnLatency': result['churnLatency'], 'postGcResourceComparison': comparison,
               'drainedMetrics': result['drainedMetrics'], 'checks': checks, 'allCriteriaPassed': all(checks.values())}
    (target / 'soak-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    # Samples contain public metric names and numeric synthetic-workload observations only.
    shutil.copyfile(root / 'samples.jsonl', target / 'soak-samples.jsonl')
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        raise SystemExit('Install matplotlib in a separate validation environment to generate resource plots')
    plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False,
                         'figure.facecolor': '#f8f8f4', 'axes.facecolor': '#f8f8f4'})
    x = [sample['elapsedSeconds'] / 60 for sample in samples]
    fig, axes = plt.subplots(2, 2, figsize=(12, 7), constrained_layout=True)
    for key, label in [('heapUsedBytes', 'Heap used'), ('nettyDirectBytes', 'Netty direct')]:
        axes[0, 0].plot(x, [sample['runtime'][key] / 1024**2 for sample in samples], label=label)
    axes[0, 0].axhline(256, color='#a33', linewidth=.7, linestyle='--', label='Heap limit')
    axes[0, 0].set(ylabel='MiB', title='Memory during sustained traffic'); axes[0, 0].legend()
    for key, label in [('openFileDescriptors', 'Open file descriptors'), ('platformThreads', 'Platform threads')]:
        axes[0, 1].plot(x, [sample['runtime'][key] for sample in samples], label=label)
    axes[0, 1].set(title='Server resource counts', ylabel='Count'); axes[0, 1].legend()
    axes[1, 0].plot(x, [sample['latency']['p99MicrosUpperBound'] / 1000 for sample in samples], color='#286060')
    axes[1, 0].set(ylabel='Milliseconds', title='Cumulative successful-frame p99 bucket upper bound')
    rates = [0] + [(b['frames'] - a['frames']) / max(.001, b['elapsedSeconds'] - a['elapsedSeconds']) for a, b in zip(samples, samples[1:])]
    axes[1, 1].plot(x, rates, color='#52613c'); axes[1, 1].set(ylabel='Validated frames/s', title='Rate-limited workload progress')
    for axis in axes.flat:
        axis.set_xlabel('Measured minutes'); axis.grid(alpha=.2)
    fig.suptitle('OminiCat constrained-memory mixed-media soak', fontsize=15)
    fig.savefig(target / 'soak-resources.svg'); fig.savefig(target / 'soak-resources.png', dpi=160); plt.close(fig)
    print(json.dumps({'frames': summary['frames'], 'connections': summary['newConnections'], 'failures': summary['failures'],
                      'minutes': summary['workload']['measuredSeconds'] / 60, 'resourceComparison': comparison}, indent=2))
    if not summary['allCriteriaPassed']:
        raise SystemExit('One or more predeclared soak criteria failed; inspect soak-summary.json')


if __name__ == '__main__':
    if len(sys.argv) not in (3, 4):
        raise SystemExit('Usage: summarize.py source-run-directory published-report-directory [criteria.json]')
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) == 4 else None)
