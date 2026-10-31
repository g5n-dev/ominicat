#!/usr/bin/env python3
"""Explain the pinned queued paths and derive a CPU/stage figure from Linux raw results.

Rendering uses matplotlib from comparison/requirements.lock. --check uses only
the standard library and revalidates all 90 raw process files and their hashes.
Run render_doc_previews.py after rendering to publish compatible PNG previews.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'comparison'))
from evidence import load_suite
from report import summarize
from render_architecture import Plate, TEXT, MUTED, TEAL, BLUE, AMBER, PANEL, INNER

ASSETS = ROOT / 'docs/assets'
DATA = ASSETS / 'framework-costs.json'
CHART = ASSETS / 'framework-cost-scaling.svg'
TARGETS = {
    'ubuntu': 'reports/comparison/linux-ubuntu24-java17-2026-10-31',
    'centos': 'reports/comparison/linux-centos9-java17-2026-10-31',
}


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode()


def derive():
    targets = []
    for name, relative in TARGETS.items():
        folder = ROOT / relative
        suite = load_suite(folder)
        summary = summarize(suite)
        ci = summary['environment']['ci']
        if summary['environment']['os'] != 'Linux' or ci['target'] != (
                'ubuntu-24.04' if name == 'ubuntu' else 'centos-stream-9'):
            raise ValueError('Expected the recorded Linux environment for this target.')
        lookup = {(c['engine'], c['stages']): c['metrics'] for c in summary['cells']}
        ratios = []
        for stages in (1, 4, 16):
            value = lookup['ominicat', stages]['throughput']['median']
            ratios.append({'stages': stages, **{f'ominicat_over_{peer}':
                value / lookup[peer, stages]['throughput']['median'] for peer in ('pipecat', 'camel')}})
        targets.append({'target': name, 'source': relative,
            'suite_sha256': hashlib.sha256((folder / 'suite.json').read_bytes()).hexdigest(),
            'manifest_sha256': summary['manifestSha256'], 'source_revision': ci['sourceSha'],
            'environment': summary['environment'], 'settings': summary['settings'],
            'validated_outputs': sum(item['result']['measuredFrames'] for item in suite['results']),
            'cells': summary['cells'], 'throughput_ratio_of_medians': ratios})
    return {'schemaVersion': 1,
        'scope': 'Recorded queued-W1 loops, including native runtime and harness costs; no models or codecs.',
        'cpuDefinition': 'Measured-phase whole-process CPU seconds × 1e6 / validated measured frames.',
        'intervalMeaning': 'Median and observed min–max across five fresh processes; not confidence intervals.',
        'ratioMeaning': 'Ratio of independent-process throughput medians, not paired ratios or a causal decomposition.',
        'targets': targets}


def topology():
    p = Plate('framework-paths.svg', 1130, '04 / COMPARED PATHS', 'Three frameworks, three execution paths',
        'The actual synchronous numeric adapters: native queued APIs, one outstanding frame.',
        'OminiCat uses one session FIFO then same-worker synchronous calls. Camel uses one SEDA queue, Exchange copy and route processors, with completion acknowledgment. Pipecat uses queued application processors with two queues and two processing tasks per stage; source and sink use direct mode. Different native acknowledgment paths remain in measured whole-loop throughput. This is a source-backed topology, not an optimization ablation.')
    p.box(56, 190, 1328, 64, INNER)
    p.text(80, 229, 'PINNED MODES', 14, TEAL, 700)
    p.text(260, 229, 'Java 17 platform workers  /  CPython 3.12 asyncio  /  N = 1, 4, 16', 20, TEXT)

    p.box(56, 286, 1328, 182, PANEL)
    p.text(80, 322, 'OminiCat', 26, TEAL, 700)
    p.text(342, 322, 'PipelineSession · one owner for the synchronous chain', 19, MUTED)
    for x, width, label in ((80, 160, 'Input frame'), (284, 190, 'Session FIFO'),
                            (518, 502, 'Worker: P1 → P2 → … → PN'), (1064, 296, 'Terminal + ack')):
        p.box(x, 347, width, 55, INNER)
        p.text(x + 16, 382, label, 19, TEXT, 700)
    for start, end in ((240, 284), (474, 518), (1020, 1064)):
        p.path(f'M{start} 375H{end}')
    p.text(80, 443, 'No queue between synchronous stages. Generation checks, scoped sinks and allocations remain.', 18, MUTED)

    p.box(56, 500, 1328, 182, PANEL)
    p.text(80, 536, 'Apache Camel 4.22.2', 26, AMBER, 700)
    p.text(440, 536, 'One SEDA ingress · general Exchange / route machinery', 19, MUTED)
    for x, width, label in ((80, 238, 'Request + Exchange'), (362, 190, 'SEDA queue'),
                            (596, 424, 'Route: P1 → P2 → … → PN'), (1064, 296, 'Completion + reply')):
        p.box(x, 561, width, 55, INNER)
        p.text(x + 16, 596, label, 19, TEXT, 700)
    for start, end in ((318, 362), (552, 596), (1020, 1064)):
        p.path(f'M{start} 589H{end}', AMBER)
    p.text(80, 657, 'Exchange copy + request/reply synchronization per request; not one SEDA queue per stage.', 18, MUTED)

    p.box(56, 714, 1328, 238, PANEL)
    p.text(80, 750, 'Pipecat 1.15.0', 26, BLUE, 700)
    p.text(360, 750, 'Queued application processors · enable_direct_mode=False', 19, MUTED)
    p.node(80, 788, 220, 106, 'Worker input FIFO', ('Native PipelineWorker', 'Source / sink: direct'), BLUE, 19, 16)
    p.node(344, 788, 776, 106, 'Application stage 1 → … → application stage N',
           ('Each: input queue → input task → FrameQueue → process task',
            'Native Frame + immutable numeric-payload adapter'), BLUE, 21, 17)
    p.node(1164, 788, 196, 106, 'Output event', ('Async event handler', 'Caller acknowledgment'), BLUE, 19, 14)
    for start, end in ((300, 344), (1120, 1164)):
        p.path(f'M{start} 841H{end}', BLUE)
    p.text(80, 926, 'Queue/task transitions are cooperative asyncio work; they are not one OS-thread switch per stage.', 18, MUTED)

    p.text(56, 995, 'All three include input construction, immutable transformations, output validation and native acknowledgment.', 18, TEXT)
    p.text(56, 1027, 'The topology explains cost boundaries. Cross-framework timings do not isolate the gain from any one mechanism.', 17, MUTED)
    p.footer('04 / Source links, profiles and trade-offs: docs/performance.md', 'Selected adapters · not whole-framework rankings')
    p.save()


def cpu_chart(data):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FixedLocator, ScalarFormatter, NullLocator
    from report import COLORS, LABELS
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11,
        'text.color': TEXT, 'axes.labelcolor': MUTED, 'xtick.color': MUTED, 'ytick.color': MUTED,
        'axes.facecolor': PANEL, 'figure.facecolor': '#0b1220', 'savefig.facecolor': '#0b1220',
        'axes.edgecolor': '#2a3f53', 'svg.fonttype': 'none', 'svg.hashsalt': hashlib.sha256(encoded(data)).hexdigest()})
    fig, axes = plt.subplots(1, 2, figsize=(14.4, 8.1))
    fig.subplots_adjust(left=.08, right=.97, top=.70, bottom=.23, wspace=.23)
    fig.text(.055, .945, 'OMINICAT / EXPLAINING THE MEASURED PATH', color=TEAL, size=11, weight='bold')
    fig.text(.055, .88, 'CPU cost as stage count grows', size=27, weight='bold')
    fig.text(.055, .829, 'Measured CPU per validated frame · numeric queued-W1 loop · models and codecs excluded', size=12, color=MUTED)
    samples = [cell['metrics']['cpuMicrosPerFrame'] for target in data['targets'] for cell in target['cells']]
    low, high = min(v['min'] for v in samples) * .6, max(v['max'] for v in samples) * 1.3
    ticks = [factor * 10 ** exponent for exponent in range(math.floor(math.log10(low)), math.ceil(math.log10(high)) + 1)
             for factor in (1, 2, 5) if low <= factor * 10 ** exponent <= high]
    for axis, target in zip(axes, data['targets']):
        lookup = {(c['engine'], c['stages']): c['metrics'] for c in target['cells']}
        for engine in ('ominicat', 'pipecat', 'camel'):
            values = [lookup[engine, n]['cpuMicrosPerFrame'] for n in (1, 4, 16)]
            mid = [v['median'] for v in values]
            axis.errorbar((0, 1, 2), mid,
                yerr=([v['median'] - v['min'] for v in values], [v['max'] - v['median'] for v in values]),
                color=COLORS[engine], label=LABELS[engine], marker='o', markersize=6, capsize=4, linewidth=2)
            for x, y in enumerate(mid):
                # Camel and OminiCat are close; place their labels on opposite sides.
                axis.annotate(f'{y:.1f}', (x, y), xytext=(0, -16 if engine == 'ominicat' else 9),
                    textcoords='offset points', ha='center', color=COLORS[engine], size=10)
        axis.set_title('Ubuntu 24.04 · native' if target['target'] == 'ubuntu' else 'CentOS Stream 9 · user space',
                       color=TEXT, loc='left', pad=18, weight='bold')
        axis.set_yscale('log'); axis.set_ylim(low, high)
        axis.yaxis.set_major_locator(FixedLocator(ticks))
        axis.yaxis.set_major_formatter(ScalarFormatter())
        axis.yaxis.set_minor_locator(NullLocator())
        axis.set_xticks((0, 1, 2), ('1', '4', '16')); axis.set_xlim(-.15, 2.15)
        axis.set_xlabel('Application stages', labelpad=12)
        axis.set_ylabel('Process CPU µs / frame · log scale', labelpad=10)
        axis.grid(axis='y', color='#2a3f53', alpha=.65)
        axis.spines[['top', 'right']].set_visible(False)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper center', bbox_to_anchor=(.51, .794), ncol=3, frameon=False, labelcolor=TEXT)
    fig.text(.08, .125, 'Five fresh processes / point. Lines join medians; whiskers show min–max, not confidence intervals.', color=MUTED, size=11)
    fig.text(.08, .085, 'Whole-process CPU includes runtime, framework and harness work. It is not stage self-time or JFR sampling.', color=MUTED, size=11)
    fig.text(.08, .045, 'Independent Linux hosts: compare frameworks within each panel. Same 90-process recording; no new benchmark run.', color=MUTED, size=10)
    fig.savefig(CHART, format='svg', metadata={'Date': None})
    plt.close(fig)
    text = CHART.read_text()
    position = text.index('>', text.index('<svg')) + 1
    marker = hashlib.sha256(encoded(data)).hexdigest()
    text = text[:position] + f'\n<metadata id="ominicat-data-sha256">{marker}</metadata>' + text[position:]
    CHART.write_text('\n'.join(line.rstrip() for line in text.splitlines()) + '\n')


def check():
    data = derive()
    if DATA.read_bytes() != encoded(data):
        raise ValueError('Derived performance data is stale; regenerate the explanation.')
    marker = ET.parse(CHART).getroot().find('{http://www.w3.org/2000/svg}metadata[@id="ominicat-data-sha256"]')
    if marker is None or marker.text != hashlib.sha256(encoded(data)).hexdigest():
        raise ValueError('CPU/stage figure is not bound to the derived data.')
    print('Validated 90 raw Linux processes, six stage comparisons and the CPU/stage figure binding.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        if not args.check:
            data = derive()
            DATA.write_bytes(encoded(data))
            topology()
            cpu_chart(data)
        check()
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f'{error}\n')
