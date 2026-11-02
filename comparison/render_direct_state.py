#!/usr/bin/env python3
"""Render validated paired measurements and an archived-source structural diagram.

No benchmark is executed. Both figures require the complete measurement archive
and its two original Pipeline.java files; --check never modifies those inputs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import direct_state_evidence as evidence

NS = 'http://www.w3.org/2000/svg'
ET.register_namespace('', NS)
BG, CARD, LINE = '#0b1521', '#112330', '#294052'
TEXT, MUTED, MINT, BLUE, AMBER = '#edf5fb', '#b8cbd1', '#64d8c3', '#76adf7', '#e9b96e'
DISPATCH = b'    void dispatch(int index, Frame frame, FrameSink output) throws Exception {'
SESSION = b'    void dispatchSession(int index, Frame frame, FrameSink output, BooleanSupplier valid) throws Exception {'

# Exact token shapes bind the recorded candidates, not an optimistic
# Java parser. Comments/formatting may differ, but unpictured behavior is rejected.
BASELINE_DIRECT = '''
    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else invoke(processors[index], frame, emitted ->
                dispatch(index + 1, Objects.requireNonNull(emitted, "emitted frame"), output));
    }
'''
CANDIDATE_DIRECT = '''
    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else {
            DirectInvocation invocation = new DirectInvocation(this, index + 1, output);
            try { processors[index].process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }
    private static final class DirectInvocation implements FrameSink {
        private Thread owner = Thread.currentThread();
        private Pipeline pipeline;
        private final int nextIndex;
        private FrameSink output;
        DirectInvocation(Pipeline pipeline, int nextIndex, FrameSink output) {
            this.pipeline = pipeline;
            this.nextIndex = nextIndex;
            this.output = output;
        }
        @Override public void accept(Frame frame) throws Exception {
            Thread invocationOwner = owner;
            if (invocationOwner == null) {
                throw new IllegalStateException("synchronous sink is no longer valid after process returns");
            }
            if (Thread.currentThread() != invocationOwner) {
                throw new IllegalStateException("synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks");
            }
            pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);
        }
        void retire() {
            owner = null;
            pipeline = null;
            output = null;
        }
    }
'''
CANDIDATE_DIRECT_V2 = '''
    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else invokeDirect(processors[index], frame, this, index + 1, output);
    }
    private static void invokeDirect(Processor processor, Frame frame, Pipeline pipeline,
                                     int nextIndex, FrameSink output) throws Exception {
        DirectInvocation invocation = new DirectInvocation(pipeline, nextIndex, output);
        try { processor.process(frame, invocation); }
        finally { invocation.retire(); }
    }
    private static final class DirectInvocation implements FrameSink {
        private Thread owner = Thread.currentThread();
        private Pipeline pipeline;
        private final int nextIndex;
        private FrameSink output;
        DirectInvocation(Pipeline pipeline, int nextIndex, FrameSink output) {
            this.pipeline = pipeline;
            this.nextIndex = nextIndex;
            this.output = output;
        }
        @Override public void accept(Frame frame) throws Exception {
            Thread invocationOwner = owner;
            if (invocationOwner == null) {
                throw new IllegalStateException("synchronous sink is no longer valid after process returns");
            }
            if (Thread.currentThread() != invocationOwner) {
                throw new IllegalStateException("synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks");
            }
            dispatchNext(frame);
        }
        private void dispatchNext(Frame frame) throws Exception {
            pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);
        }
        void retire() {
            owner = null;
            pipeline = null;
            output = null;
        }
    }
'''
CANDIDATE_DIRECT_V3 = '''
    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else directStages[index].invoke(frame, output);
    }
    private static final class DirectStage {
        private final Processor processor;
        private final DirectStage next;
        DirectStage(Processor processor, DirectStage next) {
            this.processor = processor;
            this.next = next;
        }
        void invoke(Frame frame, FrameSink output) throws Exception {
            DirectInvocation invocation = new DirectInvocation(next, output);
            try { processor.process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }
    private static final class DirectInvocation implements FrameSink {
        private Thread owner = Thread.currentThread();
        private DirectStage nextStage;
        private FrameSink output;
        DirectInvocation(DirectStage nextStage, FrameSink output) {
            this.nextStage = nextStage;
            this.output = output;
        }
        @Override public void accept(Frame frame) throws Exception {
            Thread invocationOwner = owner;
            if (invocationOwner == null) {
                throw new IllegalStateException("synchronous sink is no longer valid after process returns");
            }
            if (Thread.currentThread() != invocationOwner) {
                throw new IllegalStateException("synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks");
            }
            dispatchNext(frame);
        }
        private void dispatchNext(Frame frame) throws Exception {
            Frame emitted = Objects.requireNonNull(frame, "emitted frame");
            DirectStage next = nextStage;
            if (next == null) output.accept(emitted);
            else next.invoke(emitted, output);
        }
        void retire() {
            owner = null;
            nextStage = null;
            output = null;
        }
    }
'''
CANDIDATE_SHAPES = {'direct-state-v1-inline': CANDIDATE_DIRECT,
                    'direct-state-v2-helpers': CANDIDATE_DIRECT_V2,
                    'direct-state-v3-prebound-plan': CANDIDATE_DIRECT_V3}
CLASS_OPEN = b'public final class Pipeline {'
PUBLIC_START = b'    /** Begin building a processor chain. An empty chain passes frames through unchanged. */'
BASELINE_INITIALIZATION = '''
    private final Processor[] processors;
    private final boolean hasAsync;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private Pipeline(List<Processor> processors) {
        this.processors = processors.toArray(Processor[]::new);
        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
    }
'''
CANDIDATE_INITIALIZATION_V3 = '''
    private final Processor[] processors;
    private final boolean hasAsync;
    private final DirectStage[] directStages;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private Pipeline(List<Processor> processors) {
        this.processors = processors.toArray(Processor[]::new);
        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
        this.directStages = hasAsync ? null : new DirectStage[this.processors.length];
        if (directStages != null) {
            DirectStage next = null;
            for (int index = this.processors.length - 1; index >= 0; index--) {
                next = new DirectStage(this.processors[index], next);
                directStages[index] = next;
            }
        }
    }
'''
INVOKE = '''
    private static void invoke(Processor processor, Frame frame, FrameSink next) throws Exception {
        SynchronousSink invocation = new SynchronousSink(next);
        try { processor.process(frame, invocation); }
        finally { invocation.invalidate(); }
    }
'''
JAVA_TOKEN = re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\s+|[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|->|==|!=|.', re.S)


def tokens(source):
    """Preserve string literals while discarding Java whitespace and comments."""
    return tuple(token for token in JAVA_TOKEN.findall(source)
                 if not token.isspace() and not token.startswith(('//', '/*')))


def prebound_prefix_proof(baseline, candidate):
    """Allow the one known constructor/field edit; preserve every other byte."""
    parts = []
    for source in (baseline, candidate):
        evidence.require(source.count(CLASS_OPEN) == 1 and source.count(PUBLIC_START) == 1,
                         'unrecognized initialization/public source boundaries')
        header, rest = source.split(CLASS_OPEN)
        initialization, public = rest.split(PUBLIC_START)
        parts.append((header, initialization, public))
    a, b = parts
    evidence.require(a[0] == b[0] and a[2] == b[2], 'class header or public-method source changed')
    evidence.require(tokens(a[1].decode('utf-8')) == tokens(BASELINE_INITIALIZATION),
                     'unexpected baseline initialization')
    evidence.require(tokens(b[1].decode('utf-8')) == tokens(CANDIDATE_INITIALIZATION_V3),
                     'unexpected prebound-plan initialization')
    return {'publicEntrySourceSha256': evidence.digest(PUBLIC_START + a[2]),
            'classHeaderSha256': evidence.digest(a[0] + CLASS_OPEN),
            'baselineInitializationSha256': evidence.digest(a[1]),
            'candidateInitializationSha256': evidence.digest(b[1]),
            'initializationCheck': 'exact Java tokens excluding whitespace and comments'}


def source_proof(folder, manifest):
    directory = folder / 'sources'
    evidence.require(not directory.is_symlink() and directory.is_dir(), 'missing regular archived source directory')
    sources = {}
    for side in evidence.SIDES:
        path = directory / (side + '-Pipeline.java')
        data = evidence.read_bytes(path)
        expected = manifest['sources'][side]['files'][evidence.PIPELINE]
        evidence.require(evidence.digest(data) == expected, 'archived Pipeline source hash mismatch')
        data.decode('utf-8')
        evidence.require(data.count(DISPATCH) == 1 and data.count(SESSION) == 1,
                         'unrecognized direct/session source boundaries')
        start, end = data.index(DISPATCH), data.index(SESSION)
        evidence.require(start < end, 'reordered direct/session source boundaries')
        sources[side] = (data[:start], data[start:end], data[end:])
    a, b = (sources[side] for side in evidence.SIDES)
    evidence.require(a[2] == b[2], 'session/async source suffix changed')
    evidence.require(tokens(a[1].decode('utf-8')) == tokens(BASELINE_DIRECT), 'unexpected baseline direct implementation')
    candidate_tokens = tokens(b[1].decode('utf-8'))
    shapes = [name for name, source in CANDIDATE_SHAPES.items() if candidate_tokens == tokens(source)]
    evidence.require(len(shapes) == 1, 'unexpected candidate direct implementation')
    if shapes[0] == 'direct-state-v3-prebound-plan':
        prefix = prebound_prefix_proof(a[0], b[0])
    else:
        evidence.require(a[0] == b[0], 'public entry-point prefix changed')
        prefix = {'publicPrefixSha256': evidence.digest(a[0])}
    # This unchanged helper is where the baseline wraps its continuation. The
    # external SynchronousSink source remains in the unchanged core inventory.
    evidence.require(' '.join(tokens(INVOKE)) in ' '.join(tokens(a[2].decode('utf-8'))),
                     'missing unchanged synchronous invocation helper')
    return {'baselinePipelineSha256': manifest['sources']['baseline']['files'][evidence.PIPELINE],
            'candidatePipelineSha256': manifest['sources']['candidate']['files'][evidence.PIPELINE],
            'baselineShape': 'captured-continuation-and-synchronous-sink', 'candidateShape': shapes[0],
            'publicPrefixIdentical': a[0] == b[0], **prefix, 'sessionSuffixSha256': evidence.digest(a[2]),
            'directBodyCheck': 'exact Java tokens excluding whitespace and comments',
            'scope': 'Source-shape verification, not a formal semantic-equivalence or heap-object proof.'}


def load(folder):
    """Validate every raw fork before inspecting or drawing any derived value."""
    measurements = folder / 'evidence/measurements'
    manifest, suite = evidence.check(measurements)
    summary = evidence.summarize(manifest, suite['results'])
    proof = source_proof(folder, manifest)
    binding = {'schemaVersion': 1, 'protocol': evidence.PROTOCOL,
               'measurementManifestSha256': evidence.digest(evidence.read_bytes(measurements / 'manifest.json')),
               'summarySha256': evidence.digest(evidence.read_bytes(measurements / 'summary.json')),
               'sourceProof': proof,
               'sources': {side: {'revision': manifest['sources'][side]['revision'],
                                  'sourceSha256': manifest['sources'][side]['aggregate'],
                                  'artifactSha256': manifest['artifacts'][side]['sha256']}
                           for side in ('baseline', 'candidate', 'probe')},
               'generatorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    return summary, binding


class Figure:
    def __init__(self, width, height, title, description, binding):
        self.root = ET.Element('svg', {'xmlns': NS, 'width': str(width), 'height': str(height),
                                      'viewBox': f'0 0 {width} {height}', 'role': 'img',
                                      'aria-labelledby': 'title desc'})
        self.node('title', {'id': 'title'}, title)
        self.node('desc', {'id': 'desc'}, description)
        self.node('metadata', {'id': 'ominicat-direct-state-source'},
                  json.dumps(binding, sort_keys=True, separators=(',', ':'), allow_nan=False))
        self.rect(0, 0, width, height, BG, radius=22)

    def node(self, tag, attributes=None, text=None, parent=None):
        item = ET.SubElement(self.root if parent is None else parent, tag,
                             {key: str(value) for key, value in (attributes or {}).items()})
        item.text = text
        return item

    def text(self, x, y, value, size=18, fill=MUTED, weight=400, **extra):
        return self.node('text', dict(x=x, y=y, fill=fill, **{'font-size': size, 'font-weight': weight,
                         'font-family': 'Arial,Helvetica,DejaVu Sans,sans-serif'}, **extra), value)

    def rect(self, x, y, width, height, fill=CARD, radius=12, **extra):
        return self.node('rect', dict(x=x, y=y, width=width, height=height, rx=radius, fill=fill, **extra))

    def path(self, d, stroke=LINE, width=1, **extra):
        return self.node('path', {'d': d, 'stroke': stroke, 'fill': 'none', 'stroke-width': width, **extra})

    def finish(self):
        return ET.tostring(self.root, encoding='utf-8', xml_declaration=True) + b'\n'


def ratio_text(cell, metric):
    values = cell['ratioSummary'][metric]['blockRatios']
    if any(value is None for value in values):
        return 'Paired C/B: undefined (positive allocation / zero baseline)'
    return 'Paired C/B: ' + ' / '.join(f'{value:.4f}' for value in values)


def axis(values, left, right, logarithmic):
    """Return a safe mapping and no more than nine ticks, including zero for B/op."""
    if logarithmic:
        low = math.floor(math.log10(min(values)))
        high = max(low + 1, math.ceil(math.log10(max(values))))
        step = max(1, math.ceil((high - low) / 8))
        powers = sorted(set(range(low, high + 1, step)) | {high})
        position = lambda value: left + (math.log10(value) - low) / (high - low) * (right - left)
        ticks = [(left + (power - low) / (high - low) * (right - left),
                  f'{10 ** power:g}' if -3 <= power <= 5 else f'1e{power}') for power in powers]
    else:
        high = max(values) or 1
        exponent = math.floor(math.log10(high))
        step = 10.0 ** exponent
        rounded = math.ceil(high / step) * step if step else high
        high = rounded if math.isfinite(rounded) else high
        position = lambda value: left + (value / high) * (right - left)
        ticks = [(left + part / 4 * (right - left), f'{high * (part / 4):.6g}') for part in range(5)]
    return position, ticks


def costs_svg(summary, binding):
    f = Figure(1680, 1330, 'Direct invocation state: paired time and allocation',
               'Seven scenarios on the same Ubuntu host and Java 17 JVM. Each side has six independent fork means. '
               'Lines span those six means, never the thirty iterations. Three candidate/baseline block ratios are '
               'shown separately. The session scenario is an unchanged negative control; ranges are not confidence intervals.', binding)
    f.text(56, 53, 'OMINICAT / PAIRED STRUCTURAL EXPERIMENT', 15, MINT, 700)
    f.text(56, 109, 'Same contract. One direct-path change.', 38, TEXT, 700)
    f.text(56, 152, 'Ubuntu 24.04 / Java 17 / 256 MiB heap / same probe JAR / one request in flight', 20)
    f.text(56, 191, '84 fresh JVMs; three ABBA blocks; six forks per side and scenario.', 19)
    f.node('circle', {'cx': 63, 'cy': 226, 'r': 5, 'fill': BLUE})
    f.text(77, 232, 'Baseline', 16, BLUE, 700)
    f.node('circle', {'cx': 201, 'cy': 226, 'r': 5, 'fill': MINT})
    f.text(215, 232, 'Candidate', 16, MINT, 700)
    f.text(355, 232, 'Dots: fork means / line: fork range / diamond: mean of six forks', 17)
    cells = {row['scenario']: row for row in summary['cells']}
    order = ('raw-direct-1', 'raw-direct-4', 'raw-direct-16', 'envelope-direct-4',
             'audio-direct-4', 'image-direct-4', 'raw-session-4')
    labels = ('Raw / 1 stage', 'Raw / 4 stages', 'Raw / 16 stages', 'Envelope / 4 stages',
              'Audio / 4 stages', 'Image / 4 stages', 'Session / 4 stages')
    ys = (359, 471, 583, 695, 807, 919, 1089)
    for scenario, label, y in zip(order, labels, ys):
        control = scenario == 'raw-session-4'
        f.rect(40, y - 39, 1600, 103, '#1d242b' if control else CARD)
        f.rect(56, y - 22, 3, 57, AMBER if control or scenario == 'raw-direct-4' else LINE, radius=1)
        f.text(75, y - 9, label, 19, TEXT, 700)
        f.text(75, y + 17, scenario, 14)
        if scenario == 'raw-direct-4':
            f.text(75, y + 42, 'PRIMARY ALLOCATION CASE', 11, AMBER, 700)
    f.text(56, 1025, 'UNCHANGED SESSION PATH / NEGATIVE CONTROL ONLY', 14, AMBER, 700)
    metrics = (('timeNsPerOp', 'Time / ns per operation', True, 355, 845, 876),
               ('allocatedBytesPerOp', 'Allocation / B per operation', False, 1000, 1490, 1521))
    for metric, label, logarithmic, left, right, numeric_x in metrics:
        f.text(left, 280, label, 20, TEXT, 700)
        f.text(left, 305, 'Log scale' if logarithmic else 'Linear scale from zero', 15)
        values = [value for scenario in order for side in evidence.SIDES
                  for value in cells[scenario][side][metric]['forkMeans']]
        position, ticks = axis(values, left, right, logarithmic)
        for x, value in ticks:
            f.path(f'M{x:.4f} 319 V 982', width=1)
            f.path(f'M{x:.4f} 1050 V 1152', width=1)
            f.text(x, 1178, value, 13, **{'text-anchor': 'middle'})
        for scenario, y in zip(order, ys):
            cell = cells[scenario]
            for side, color, offset in (('baseline', BLUE, -15), ('candidate', MINT, 15)):
                item = cell[side][metric]
                means = item['forkMeans']
                start, end = min(means), max(means)
                line = f.path(f'M{position(start):.4f} {y + offset} H{position(end):.4f}', color, 3,
                              **{'stroke-linecap': 'round', 'data-statistic': 'fork-range',
                                 'data-scenario': scenario, 'data-metric': metric, 'data-side': side,
                                 'data-min': repr(start), 'data-max': repr(end)})
                f.node('title', text=f'{start!r} - {end!r}', parent=line)
                for index, value in enumerate(means):
                    dot = f.node('circle', {'cx': f'{position(value):.4f}', 'cy': y + offset + (index - 2.5) * 2.5,
                                           'r': 3.5, 'fill': color, 'stroke': BG, 'stroke-width': .7,
                                           'data-statistic': 'fork-mean', 'data-fork': index + 1})
                    f.node('title', text=repr(value), parent=dot)
                x = position(item['meanForkMean'])
                f.path(f'M{x:.4f} {y + offset - 6} l5 6 l-5 6 l-5 -6 Z', color, 1.5, fill=BG)
                f.text(numeric_x, y + offset + 5, f"{item['meanForkMean']:.5g}", 15, color,
                       **{'text-anchor': 'start'})
            f.text(left, y + 49, ratio_text(cell, metric), 14, AMBER if cell['negativeControl'] else MUTED)
    f.text(56, 1221, 'Paired C/B: candidate block mean / baseline block mean; values show blocks 1 / 2 / 3.', 18)
    f.text(56, 1254, 'Observed fork and block ranges are not confidence intervals. Iterations are nested within forks.', 18)
    f.text(56, 1287, 'Time and B/op include the harness and exact oracle. Media constructors and codecs are not timed.', 18)
    return f.finish()


def structure_svg(binding):
    shape = binding['sourceProof']['candidateShape']
    prebound = shape == 'direct-state-v3-prebound-plan'
    representation = ('an immutable per-pipeline DirectStage plan and a fresh DirectInvocation with owner, nextStage '
                      'and output references' if prebound else
                      'a private static DirectInvocation with owner, pipeline, nextIndex and output fields')
    f = Figure(1600, 1240 if prebound else 1110, 'Direct invocation state: one bounded structural change',
               'The baseline wraps a captured continuation in SynchronousSink. The candidate uses ' + representation +
               ', clearing its three invocation references in finally. Public entry-point and session/async source regions match byte for byte. Source expressions '
               'are not physical heap-object counts; guard equivalence also relies on lifecycle tests.', binding)
    f.text(56, 53, 'OMINICAT / SOURCE-BOUND STRUCTURE', 15, MINT, 700)
    f.text(56, 110, 'Combine the state. Preserve the boundary.', 37, TEXT, 700)
    f.text(56, 154, 'One production file changes; public entry-point and session / async source remains byte-identical.', 20)
    f.rect(56, 191, 1488, 47, '#142634')
    f.text(78, 221, 'Pipeline.process → dispatch → processor.process → guarded output → next stage', 21, TEXT, 700)
    candidate_subtitle = {'direct-state-v1-inline': 'Combined state / inline direct dispatch',
                          'direct-state-v2-helpers': 'Combined state / private forwarding helpers',
                          'direct-state-v3-prebound-plan': 'Immutable plan / fresh guarded invocation'}[shape]
    for x, title, subtitle, color in ((56, 'BASELINE', 'Captured continuation + synchronous boundary', BLUE),
                                       (826, 'CANDIDATE', candidate_subtitle, MINT)):
        f.rect(x, 269, 718, 617 if prebound else 487, CARD, stroke=LINE, **{'stroke-width': 1})
        f.text(x + 24, 306, title, 14, color, 700)
        f.text(x + 24, 340, subtitle, 22, TEXT, 700)
    f.rect(84, 370, 662, 128, '#142c3e', stroke=BLUE)
    f.text(108, 404, 'SynchronousSink', 25, TEXT, 700)
    f.text(108, 437, 'Fields: owner / delegate', 19, BLUE)
    f.text(108, 469, 'Invocation thread and lifetime checks', 18)
    f.path('M415 499 V534', BLUE, 2)
    f.path('M409 528 L415 534 L421 528', BLUE, 2)
    f.rect(84, 543, 662, 112, '#122538', stroke=BLUE)
    f.text(108, 578, 'Captured continuation', 24, TEXT, 700)
    f.text(108, 609, 'Captured values: pipeline / index / output', 18, BLUE)
    f.text(108, 638, 'dispatch(index + 1, emitted, output)', 18)
    f.text(108, 699, 'finally → invocation.invalidate()', 21, BLUE, 700)
    f.text(108, 731, 'Detach the owner and downstream delegate.', 18)

    if prebound:
        f.text(108, 800, 'No prebound direct-stage plan.', 20, BLUE, 700)
        f.text(108, 837, 'The builder still snapshots processor references.', 18)
        f.rect(854, 370, 662, 165, '#143330', stroke=MINT)
        f.text(878, 407, 'Per-pipeline immutable DirectStage plan', 23, TEXT, 700)
        f.text(878, 440, 'Source plan: N nodes + N-reference array', 19, MINT)
        f.text(878, 472, 'Final fields: processor / next', 19, MINT)
        f.text(878, 502, 'No per-call frame, output or owner fields.', 17)
        f.text(878, 525, 'No plan when hasAsync is true.', 17)
        f.rect(854, 555, 662, 166, '#143330', stroke=MINT)
        f.text(878, 589, 'Fresh DirectInvocation implements FrameSink', 23, TEXT, 700)
        f.text(878, 623, 'References: owner / nextStage / output', 20, MINT)
        f.text(878, 655, 'Invocation thread and lifetime checks', 18)
        f.text(878, 685, 'Live owner + nextStage = null → terminal output.', 18)
        f.text(878, 710, 'owner = null identifies a retired invocation.', 17)
        f.text(878, 767, 'finally → invocation.retire()', 21, MINT, 700)
        f.text(878, 804, 'owner = null / nextStage = null / output = null', 19, MINT)
        f.text(878, 840, 'Plan setup cost is measured separately.', 19, AMBER, 700)
        f.text(878, 869, 'A retired handle is never pooled or reactivated.', 17)
    else:
        f.rect(854, 370, 662, 218, '#143330', stroke=MINT)
        f.text(878, 407, 'DirectInvocation implements FrameSink', 24, TEXT, 700)
        for x, y, value in ((878, 450, 'owner : Thread'), (1197, 450, 'pipeline : Pipeline'),
                            (878, 487, 'nextIndex : int'), (1197, 487, 'output : FrameSink')):
            f.text(x, y, value, 20, MINT)
        f.text(878, 532, 'Invocation thread and lifetime checks', 18)
        f.text(878, 566, 'pipeline.dispatch(nextIndex, frame, output)', 19)
        f.path('M1185 589 V622', MINT, 2)
        f.path('M1179 616 L1185 622 L1191 616', MINT, 2)
        f.text(878, 659, 'finally → invocation.retire()', 21, MINT, 700)
        f.text(878, 694, 'owner = null / pipeline = null / output = null', 20, MINT)
        f.text(878, 731, 'A retired handle is never pooled or reactivated.', 18)

    offset = 130 if prebound else 0
    f.rect(56, 791 + offset, 1488, 124, '#172732')
    f.text(80, 825 + offset, 'PRESERVED BEHAVIOR / VERIFIED BY LIFECYCLE FIXTURES', 14, AMBER, 700)
    f.text(80, 861 + offset, 'Same invocation thread', 20, TEXT, 700)
    f.text(450, 861 + offset, 'Reject stale or null output', 20, TEXT, 700)
    f.text(830, 861 + offset, 'Filtering + ordered fan-out', 20, TEXT, 700)
    f.text(1205, 861 + offset, 'Retire in finally', 20, TEXT, 700)
    f.text(80, 892 + offset, 'Nested calls, post-output exceptions and retained handles remain part of the correctness boundary.', 18)
    boundary = ('Checked source: identical public methods / known plan setup / identical session and async suffix.' if prebound else
                'Checked source boundary: identical public prefix / direct-only edit / identical session and async suffix.')
    f.text(56, 962 + offset, boundary, 20)
    f.text(56, 1004 + offset, 'Source expressions do not count physical heap objects; inlining and escape analysis may reshape allocation.', 19)
    f.text(56, 1041 + offset, 'Use paired JMH time and B/op for measured effects. This source check is not a formal equivalence proof.', 19)
    f.text(56, 1078 + offset, 'No sink pooling, session scheduler change, generation-fence change or media-constructor change.', 19)
    return f.finish()


def render(folder, *, check=False):
    folder = Path(folder)
    evidence.require(not folder.is_symlink() and folder.is_dir(), 'report root must be a regular directory')
    summary, binding = load(folder)
    expected = {'costs.svg': costs_svg(summary, binding), 'structure.svg': structure_svg(binding)}
    # Finish validating/building both figures before touching either output.
    for name, data in expected.items():
        target = folder / name
        evidence.require(not target.is_symlink(), 'figure output must not be a symlink')
        if check:
            evidence.require(target.is_file() and target.read_bytes() == data, 'stale direct-state figure')
    if not check:
        for name, data in expected.items():
            (folder / name).write_bytes(data)
    return expected


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args(argv)
    try:
        render(args.folder, check=args.check)
        print('Checked two direct-state figures against complete paired evidence and archived source bytes.')
        return 0
    except (OSError, ValueError, KeyError, TypeError, OverflowError) as error:
        # Input contents and machine paths do not belong in public diagnostics.
        print('Direct-state figures rejected: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
