#!/usr/bin/env python3
"""Render a source-bound view of validated mechanism measurements; no benchmark runs."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

import mechanism_evidence as evidence


def chart(folder):
    measurements = folder / 'evidence/measurements'
    manifest, suite = evidence.check(measurements)
    summary = evidence.summarize(manifest, suite['results'])
    cells = {row['id']: row for row in summary['cells']}
    order = ('ominicat-caller', 'camel-caller', 'ominicat-handoff', 'camel-handoff',
             'identity', 'correlated-copy')
    labels = ('OminiCat / caller', 'Camel / caller', 'OminiCat / common FIFO', 'Camel / common FIFO',
              'Identity / prepared Exchange', 'Correlated copy / prepared Exchange')
    colors = ('#64d8c3', '#76adf7', '#64d8c3', '#76adf7', '#9badc2', '#e9b96e')
    root = ET.Element('svg', {'xmlns': 'http://www.w3.org/2000/svg', 'width': '1680', 'height': '1110',
                             'viewBox': '0 0 1680 1110', 'role': 'img', 'aria-labelledby': 'title desc'})
    ET.SubElement(root, 'title', {'id': 'title'}).text = 'Controlled mechanisms: execution, handoff and Exchange copying'
    ET.SubElement(root, 'desc', {'id': 'desc'}).text = (
        'Java 17 on one Ubuntu 24.04 host. Four path cells share the same four-stage result oracle; '
        'two copy cells use one prepared Exchange. Dots are independent fork means; lines span all measured '
        'iteration means, not confidence intervals. Component costs are not additive explanations of SEDA.')

    def node(tag, attributes, text=None):
        item = ET.SubElement(root, tag, {key: str(value) for key, value in attributes.items()})
        item.text = text
        return item

    def text(x, y, value, size=18, fill='#b8cbd1', weight=400, **extra):
        return node('text', dict(x=x, y=y, fill=fill, **{'font-size': size, 'font-weight': weight,
                    'font-family': 'Arial,Helvetica,DejaVu Sans,sans-serif'}, **extra), value)

    node('rect', dict(width=1680, height=1110, rx=22, fill='#0b1521'))
    text(56, 57, 'OMINICAT / CONTROLLED EVIDENCE', 15, '#64d8c3', 700)
    text(56, 113, 'Measure the mechanism, keep the contract.', 38, '#edf5fb', 700)
    text(56, 155, 'Ubuntu 24.04 / Java 17 / same JAR / four stages / one request in flight', 20)
    text(56, 198, 'Two fresh forks per cell; dots retain both means and whiskers retain every measured iteration.', 19)

    metrics = (('timeNsPerOp', 'Wall time / ns per operation', True),
               ('processCpuNsPerOp', 'Whole-process CPU / ns per operation', True),
               ('allocatedBytesPerOp', 'Allocated bytes / operation', False))
    ys = (344, 426, 508, 590, 782, 864)
    text(56, 272, 'PUBLIC SYNCHRONOUS PATH / SHARED HANDOFF', 14, '#64d8c3', 700)
    text(56, 710, 'PREBUILT EXCHANGE COMPONENT / SEPARATE SCOPE', 14, '#e9b96e', 700)
    for row, (label, color, y) in enumerate(zip(labels, colors, ys)):
        node('rect', dict(x=40, y=y-33, width=1600, height=66, rx=10,
                          fill='#112330' if row % 2 == 0 else '#101d2a'))
        node('rect', dict(x=56, y=y-18, width=3, height=36, rx=1, fill=color))
        text(73, y+6, label, 16, '#e4edf8', 700)

    for panel, (metric, title, logarithmic) in enumerate(metrics):
        left = 390 + 420 * panel
        right = left + 350
        text(left, 234, title, 18, '#edf5fb', 700)
        text(left, 258, 'Log scale' if logarithmic else 'Linear scale', 15)
        values = [value for name in order for value in cells[name]['metrics'][metric]['iterations']]
        if logarithmic:
            positive = [value for value in values if value > 0]
            low = math.floor(math.log10(min(positive))) if positive else -1
            high = max(low+1, math.ceil(math.log10(max(positive)))) if positive else 1
            ticks = [(10 ** power, (power-low)/(high-low)) for power in range(low, high+1)]
            position = lambda value: left if value <= 0 else left+(math.log10(value)-low)/(high-low)*(right-left)
        else:
            maximum = max(values)
            step = 10 ** math.floor(math.log10(maximum)) if maximum > 0 else 1
            high = math.ceil(maximum/step)*step or 1
            ticks = [(high*part/4, part/4) for part in range(5)]
            position = lambda value: left+value/high*(right-left)
        for value, fraction in ticks:
            x = left+fraction*(right-left)
            node('path', dict(d=f'M{x:.4f} 294 V 910', stroke='#294052', **{'stroke-width': 1}))
            text(x, 933, f'{value:g}', 13, **{'text-anchor': 'middle'})
        for name, color, y in zip(order, colors, ys):
            item = cells[name]['metrics'][metric]
            start, end = item['iterationMin'], item['iterationMax']
            line = node('path', dict(d=f'M{position(start):.4f} {y} H{position(end):.4f}', stroke=color,
                                    **{'stroke-width': 3, 'stroke-linecap': 'round'}))
            ET.SubElement(line, 'title').text = f'{start:.6g} - {end:.6g}'
            for fork, value in enumerate(item['forkMeans']):
                dot = node('circle', dict(cx=f'{position(value):.4f}', cy=y+(-5 if fork == 0 else 5), r=5,
                                         fill=color if fork == 0 else '#0b1521', stroke=color, **{'stroke-width': 2}))
                ET.SubElement(dot, 'title').text = f'{value:.6g}'
    text(56, 981, '3 x 1 s warmup + 5 x 1 s measurement / fork; fixed 256 MiB heap; exact per-operation checks.', 19)
    text(56, 1018, 'CPU and allocation include the JVM and harness. This is neither p99 nor framework-exclusive allocation.', 18)
    text(56, 1055, 'Copy excludes source construction, SEDA queueing, completion synchronization and route execution.', 18)
    node('metadata', {'id': 'ominicat-mechanism-source'}, json.dumps({
        'measurementManifestSha256': hashlib.sha256((measurements / 'manifest.json').read_bytes()).hexdigest(),
        'summarySha256': hashlib.sha256((measurements / 'summary.json').read_bytes()).hexdigest(),
        'generatorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}, sort_keys=True))
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        expected = chart(args.folder)
        target = args.folder / 'costs.svg'
        if args.check:
            evidence.require(target.is_file() and target.read_bytes() == expected, 'stale mechanism chart')
        else:
            target.write_bytes(expected)
        print('Checked controlled-mechanism chart and its complete source bindings.')
    except (OSError, ValueError, KeyError, TypeError, OverflowError) as error:
        parser.exit(1, str(error) + '\n')
