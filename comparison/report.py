#!/usr/bin/env python3
"""Publish validated, complete comparison evidence and export standalone figures."""
import argparse
from datetime import date
import json
from pathlib import Path
import shutil
import statistics

from evidence import ENGINES, digest, load_suite

LABELS = {'ominicat': 'OminiCat', 'pipecat': 'Pipecat', 'camel': 'Apache Camel'}
COLORS = {'ominicat': '#64d8c3', 'pipecat': '#76adf7', 'camel': '#e9b96e'}
BG, PANEL, TEXT, MUTED, GRID = '#0b1220', '#111d2e', '#e4edf8', '#98abc2', '#2a3a50'


def summarize(suite):
    settings = suite['manifest']['settings']
    if set(settings['engines']) != set(ENGINES) or set(settings['stages']) != {1, 4, 16} or settings['forks'] != 5:
        raise ValueError('published comparison requires all three engines, all three stages and five fresh processes')
    if settings['warmupSeconds'] != 3 or settings['measureSeconds'] != 5:
        raise ValueError('smoke durations cannot become published performance evidence')
    cells = []
    for stages in (1, 4, 16):
        for engine in ENGINES:
            records = [r for r in suite['results'] if r['engine'] == engine and r['stages'] == stages]
            metrics = {}
            for key, getter in {
                'throughput': lambda r: r['result']['throughputFramesPerSecond'],
                'p50Micros': lambda r: r['result']['latencyMicros']['p50'],
                'p99Micros': lambda r: r['result']['latencyMicros']['p99'],
                'cpuMicrosPerFrame': lambda r: r['result']['processCpuSeconds'] * 1e6 / r['result']['measuredFrames'],
                'measuredRssMiB': lambda r: r['resources']['measuredPeakRssBytes'] / 1048576,
            }.items():
                values = [getter(r) for r in records]
                metrics[key] = {'median': statistics.median(values), 'min': min(values), 'max': max(values), 'forkValues': values}
            cells.append({'engine': engine, 'stages': stages, 'metrics': metrics})
    return {'schemaVersion': 1, 'manifestSha256': suite['manifestSha256'], 'settings': settings,
            'recordedAt': suite['manifest']['recordedAt'],
            'intervalMeaning': 'observed minimum and maximum across fresh processes; not confidence intervals', 'cells': cells}


def render(summary, destination):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FuncFormatter
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'text.color': TEXT,
        'axes.labelcolor': MUTED, 'xtick.color': MUTED, 'ytick.color': MUTED,
        'axes.facecolor': PANEL, 'figure.facecolor': BG, 'savefig.facecolor': BG,
        'axes.edgecolor': GRID, 'grid.color': GRID, 'grid.alpha': .55, 'svg.fonttype': 'none',
        'svg.hashsalt': summary['manifestSha256']})
    lookup = {(c['engine'], c['stages']): c['metrics'] for c in summary['cells']}

    def save(fig, name):
        for extension in ('svg', 'png'):
            fig.savefig(destination / f'{name}.{extension}', dpi=180, bbox_inches='tight', pad_inches=.25,
                        metadata={'Date': summary['recordedAt']})
        plt.close(fig)

    def clean(ax):
        ax.spines[['top', 'right']].set_visible(False)
        ax.grid(axis='x', zorder=0)
        ax.set_axisbelow(True)
        ax.tick_params(length=0, pad=9)

    def point(ax, metric, stages=4, labels=True):
        for y, engine in enumerate(ENGINES):
            value = lookup[engine, stages][metric]
            mid = value['median']
            ax.errorbar(mid, 2-y, xerr=[[mid-value['min']], [value['max']-mid]],
                color=COLORS[engine], fmt='o', markersize=9, elinewidth=2, capsize=5, zorder=3)
        ax.set_yticks([2, 1, 0], [LABELS[e] for e in ENGINES] if labels else ['', '', ''])
        ax.set_ylim(-.6, 2.6)
        clean(ax)

    fig, ax = plt.subplots(figsize=(12, 5.5))
    for offset, engine in enumerate(ENGINES):
        values = [lookup[engine, stage]['throughput']['median'] for stage in (1, 4, 16)]
        lows = [v-lookup[engine, stage]['throughput']['min'] for stage, v in zip((1, 4, 16), values)]
        highs = [lookup[engine, stage]['throughput']['max']-v for stage, v in zip((1, 4, 16), values)]
        ax.errorbar((1, 4, 16), values, yerr=[lows, highs], marker='o', markersize=7,
            linewidth=2, capsize=4, label=LABELS[engine], color=COLORS[engine])
        for x, y in zip((1, 4, 16), values):
            ax.annotate(f'{y:,.0f}', (x, y), xytext=(0, 10), textcoords='offset points',
                        ha='center', color=COLORS[engine], fontsize=10)
    ax.set_xscale('log', base=2); ax.set_yscale('log')
    ax.set_xticks((1, 4, 16), ('1 stage', '4 stages', '16 stages'))
    ax.yaxis.set_major_formatter(FuncFormatter(lambda value, _: f'{value/1000:g}k'))
    ax.set_ylabel('Validated frames / second · W=1 loop · log scale')
    ax.set_title('Native queued execution paths', loc='left', pad=27, fontsize=22, fontweight='bold')
    ax.legend(frameon=False, loc='lower left', labelcolor=TEXT)
    ax.grid(axis='both', color=GRID); ax.spines[['top', 'right']].set_visible(False)
    fig.text(.125, -.01, 'Median of 5 fresh processes; whiskers = observed range. No model, codec or network.', color=MUTED, fontsize=10)
    save(fig, 'throughput')

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.7), gridspec_kw={'wspace': .46})
    for ax, metric, title in zip(axes, ('p50Micros', 'p99Micros'), ('Median terminal latency (p50)', 'Tail terminal latency (p99)')):
        point(ax, metric); ax.set_xscale('log'); ax.set_xlabel('Microseconds · log scale · lower is better')
        ax.set_title(title, loc='left', pad=20, fontsize=14)
    fig.suptitle('4 stages / 1 outstanding frame', x=.125, ha='left', y=1.05, fontsize=21, fontweight='bold')
    fig.text(.125, -.04, 'Per-input timer ends after terminal validation, before caller acknowledgment. Ranges span 5 processes.', color=MUTED, fontsize=10)
    save(fig, 'latency')

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.7), gridspec_kw={'wspace': .46})
    point(axes[0], 'cpuMicrosPerFrame'); axes[0].set_xscale('log'); axes[0].set_xlabel('Process CPU µs / validated frame · log scale')
    axes[0].set_title('CPU cost of the measured process', loc='left', pad=20, fontsize=14)
    point(axes[1], 'measuredRssMiB'); axes[1].set_xlim(left=0); axes[1].set_xlabel('Sampled measurement-phase peak RSS · MiB')
    axes[1].set_title('Whole-process resident memory', loc='left', pad=20, fontsize=14)
    fig.suptitle('Resources / 4 stages / W=1', x=.125, ha='left', y=1.05, fontsize=21, fontweight='bold')
    fig.text(.125, -.04, 'RSS includes JVM/Python, dependencies and a pre-touched 16 MiB harness buffer. 50 ms sampling can miss brief peaks.', color=MUTED, fontsize=9)
    save(fig, 'resources')

    fig = plt.figure(figsize=(14, 9))
    fig.text(.055, .95, 'OMINICAT / PERFORMANCE EVIDENCE', color=COLORS['ominicat'], fontsize=11, fontweight='bold')
    fig.text(.055, .895, 'Framework overhead, measured.', fontsize=28, fontweight='bold')
    fig.text(.055, .855, 'Native queued paths · 4 application stages · one outstanding frame · models and network excluded', color=MUTED, fontsize=11)
    for i, engine in enumerate(ENGINES):
        left = .055 + i*.305
        ax = fig.add_axes([left, .575, .28, .225], facecolor=PANEL)
        for spine in ax.spines.values(): spine.set_edgecolor(GRID)
        ax.set_xticks([]); ax.set_yticks([])
        metrics = lookup[engine, 4]
        ax.text(.07, .81, LABELS[engine], color=COLORS[engine], fontsize=14, fontweight='bold', transform=ax.transAxes)
        ax.text(.07, .48, f"{metrics['throughput']['median']/1000:,.1f}k", fontsize=31, fontweight='bold', transform=ax.transAxes)
        ax.text(.07, .30, 'validated frames / second', fontsize=10, color=MUTED, transform=ax.transAxes)
        ax.text(.07, .10, f"p99 {metrics['p99Micros']['median']:,.1f} µs   /   RSS {metrics['measuredRssMiB']['median']:,.0f} MiB",
                fontsize=10, color=TEXT, transform=ax.transAxes)
    ax1 = fig.add_axes([.055, .16, .405, .32]); point(ax1, 'throughput')
    ax1.set_xscale('log'); ax1.set_xlabel('W=1 frame rate · log scale'); ax1.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f'{v/1000:g}k'))
    ax1.set_title('Throughput with fork-to-fork variation', loc='left', pad=18, fontsize=13)
    ax2 = fig.add_axes([.585, .16, .35, .32]); point(ax2, 'p99Micros')
    ax2.set_xscale('log'); ax2.set_xlabel('Terminal p99 · microseconds · log scale')
    ax2.set_title('Tail latency with fork-to-fork variation', loc='left', pad=18, fontsize=13)
    fig.text(.055, .075, '5 fresh processes per cell  /  3 s warm-up + 5 s measured  /  whiskers: min–max, not confidence intervals', color=MUTED, fontsize=10)
    fig.text(.055, .045, 'Same host; JVM/Python, native scheduling, input/stage allocations and validation included. W=1 is not saturated capacity.', color=MUTED, fontsize=9)
    save(fig, 'overview')


def write_report(summary, suite, folder):
    env = suite['manifest']['environment']
    rows = []
    for c in summary['cells']:
        m = c['metrics']
        interval = lambda key: f"{m[key]['median']:,.2f} ({m[key]['min']:,.2f}–{m[key]['max']:,.2f})"
        rows.append(f"| {LABELS[c['engine']]} | {c['stages']} | {interval('throughput')} | {interval('p50Micros')} | {interval('p99Micros')} | {interval('measuredRssMiB')} |")
    content = f'''# Model-free framework comparison

Recorded {suite['manifest']['recordedAt'][:10]} on {env['os']} / {env['arch']}, {env['logicalProcessors']} logical processors. The [manifest](manifest.json) binds every result to the protocol, measured JAR, verified installed Python dependency inventory and exact source fingerprints. All **{len(suite['results'])} fresh processes** passed output, count, rejection and cleanup checks; no failed fork was omitted.

![Framework comparison overview](overview.svg)

## What this measures

One process, one pipeline/session, **one outstanding data frame**. Each of 1 / 4 / 16 application stages creates the same immutable numeric transformation. Throughput covers the complete client loop; terminal latency ends after output validation, before acknowledgment returns to the caller. Startup and teardown are excluded. Every process warms for 3 seconds and measures for 5 seconds. Values below are medians, followed by observed min–max ranges across five independent processes; these are **not confidence intervals**.

| Framework | Stages | Frames/s · W=1 | p50 µs | p99 µs | Peak phase RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
''' + '\n'.join(rows) + '''

![Stage scaling](throughput.svg)

![Terminal latency](latency.svg)

![Process resources](resources.svg)

## Profile boundaries

| Engine | Tested path | Execution scope |
| --- | --- | --- |
| OminiCat 0.1.0-SNAPSHOT | PipelineSession, capacity 1, terminal callback + caller acknowledgment queue | Java 17 platform session worker; exact source hash is in the manifest |
| Pipecat 1.15.0 | PipelineWorker / WorkerRunner, default queued FrameProcessor mode, native downstream output hook | CPython 3.12 / ordinary asyncio; explicit RTVI, turn tracking, metrics, usage, tracing and heartbeats off; native output-event dispatch remains included |
| Apache Camel 4.22.2 LTS | SEDA, size 1, one consumer, no discard, request/reply acknowledgment | Java 17 platform consumer; Exchange, Message and native request/reply costs included |

Pipecat is the multimodal frame-pipeline peer. Camel is a Java routing-framework comparison, not a voice-quality equivalent. Different runtimes, scheduler architectures, native frame metadata and public APIs remain part of each measured path. A common output oracle and W=1 limit do not make their internal queue/backpressure or lifecycle contracts identical. Pipecat's internal queues are not bounded by this experiment; the harness bounds admitted work.

The figures do not compare OminiCat's synchronous dispatch headline against another framework's queued path. They do not measure models, codecs, transport, audio quality, interruption latency, saturation or production session capacity. This controlled synthetic profile cannot establish universal framework superiority.

RSS is sampled using the same OS collector every 50 ms during measurement and includes runtime, imported dependencies and a pre-touched 16 MiB sample buffer. Java uses -Xms128m -Xmx512m; Python has no equivalent managed-heap limit. RSS is not framework-only allocation, retained heap, native footprint or B/op, and brief peaks may be missed. The shared development host has no exclusive CPU isolation. Engine order rotates between repetitions; JIT, GC, host load and thermal effects remain confounders.

## Reproduce and inspect

See [benchmark instructions](../../../comparison/README.md), the [protocol](../../../comparison/protocol.md), [complete suite](suite.json) and per-process [raw JSON](raw/). Collection rejects nonzero exits, stale output, bad values, missing/duplicate cases, mixed manifests, short phases and missing resource samples. Report generation revalidates raw-result hashes before drawing.

Official sources: [Pipecat release](https://github.com/pipecat-ai/pipecat/releases/tag/v1.15.0), [queued processor source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/processors/frame_processor.py), [worker source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/pipeline/worker.py), [Camel 4.22.2 / Java compatibility](https://camel.apache.org/releases/release-4.22.2/), [SEDA semantics](https://camel.apache.org/components/4.22.x/seda-component.html).
'''
    (folder / 'README.md').write_text(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    suite = load_suite(args.input)
    summary = summarize(suite)
    destination = args.output
    if destination.exists() and any(destination.iterdir()):
        existing = destination / 'summary.json'
        if not existing.exists() or json.loads(existing.read_text()).get('manifestSha256') != suite['manifestSha256']:
            raise ValueError('refusing to overwrite different comparison evidence')
    destination.mkdir(parents=True, exist_ok=True)
    for name in ('manifest.json', 'suite.json'):
        target = destination / name
        raw = (args.input / name).read_bytes()
        if target.exists() and target.read_bytes() != raw:
            raise ValueError('refusing to replace existing evidence')
        target.write_bytes(raw)
    raw_dir = destination / 'raw'; raw_dir.mkdir(exist_ok=True)
    for item in suite['results']:
        path = args.input / item['file']; target = destination / item['file']
        if target.exists() and target.read_bytes() != path.read_bytes():
            raise ValueError('refusing to replace a raw result')
        shutil.copyfile(path, target)
    (destination / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    render(summary, destination)
    write_report(summary, suite, destination)
    checksums = []
    for path in sorted(destination.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS':
            checksums.append(f'{digest(path.read_bytes())}  {path.relative_to(destination).as_posix()}')
    (destination / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
    print(f'Validated and rendered {len(suite["results"])} processes into {destination}')


if __name__ == '__main__':
    main()
