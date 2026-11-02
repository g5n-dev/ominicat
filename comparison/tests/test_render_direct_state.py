"""Fail-closed source/measurement binding tests; no benchmark or Java is run."""
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

COMPARISON = Path(__file__).resolve().parents[1]
if str(COMPARISON) not in sys.path:
    sys.path.insert(0, str(COMPARISON))
import render_direct_state as renderer

spec = importlib.util.spec_from_file_location('direct_state_render_fixtures',
                                               COMPARISON / 'tests/test_direct_state_evidence.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
evidence = renderer.evidence
NS = {'svg': renderer.NS}

# Independent compact Java fixture: source text is archived and hashed, not
# compiled. The unchanged region retains the real baseline helper's boundary.
PREFIX = b'''package io.ominicat.core;
public final class Pipeline {
    public void process(Frame frame, FrameSink output) throws Exception {
        dispatch(0, frame, output);
    }
'''
BASELINE = b'''    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else invoke(processors[index], frame, emitted ->
            dispatch(index + 1, Objects.requireNonNull(emitted, "emitted frame"), output));
    }
'''
CANDIDATE = b'''    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else {
            DirectInvocation invocation = new DirectInvocation(this, index + 1, output);
            try { processors[index].process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }
    /** Comments and formatting do not change the checked token shape. */
    private static final class DirectInvocation implements FrameSink {
        private Thread owner = Thread.currentThread();
        private Pipeline pipeline;
        private final int nextIndex;
        private FrameSink output;
        DirectInvocation(Pipeline pipeline, int nextIndex, FrameSink output) {
            this.pipeline = pipeline; this.nextIndex = nextIndex; this.output = output;
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
        void retire() { owner = null; pipeline = null; output = null; }
    }
'''
CANDIDATE_V2 = CANDIDATE.replace(b'''        else {
            DirectInvocation invocation = new DirectInvocation(this, index + 1, output);
            try { processors[index].process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }
''', b'''        else invokeDirect(processors[index], frame, this, index + 1, output);
    }
    private static void invokeDirect(Processor processor, Frame frame, Pipeline pipeline,
                                     int nextIndex, FrameSink output) throws Exception {
        DirectInvocation invocation = new DirectInvocation(pipeline, nextIndex, output);
        try { processor.process(frame, invocation); }
        finally { invocation.retire(); }
    }
''').replace(b'''            pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);
        }
        void retire()''', b'''            dispatchNext(frame);
        }
        private void dispatchNext(Frame frame) throws Exception {
            pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);
        }
        void retire()''')
BASELINE_INIT = b'''
    private final Processor[] processors;
    private final boolean hasAsync;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private Pipeline(List<Processor> processors) {
        this.processors = processors.toArray(Processor[]::new);
        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
    }
'''
CANDIDATE_INIT_V3 = BASELINE_INIT.replace(b'    private final boolean hasAsync;', b'''    private final boolean hasAsync;
    private final DirectStage[] directStages;''').replace(
    b'        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);',
    b'''        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
        this.directStages = hasAsync ? null : new DirectStage[this.processors.length];
        if (directStages != null) {
            DirectStage next = null;
            for (int index = this.processors.length - 1; index >= 0; index--) {
                next = new DirectStage(this.processors[index], next);
                directStages[index] = next;
            }
        }''')
PUBLIC_METHODS = b'''    /** Begin building a processor chain. An empty chain passes frames through unchanged. */
    public static Builder builder() { return new Builder(); }
    public void process(Frame frame, FrameSink output) throws Exception {
        dispatch(0, frame, output);
    }
    public PipelineSession openSession(SessionOptions options, FrameSink output) {
        return new PipelineSession(this, options, output);
    }
'''
V3_BASELINE_PREFIX = b'package io.ominicat.core;\npublic final class Pipeline {' + BASELINE_INIT + PUBLIC_METHODS
V3_CANDIDATE_PREFIX = b'package io.ominicat.core;\npublic final class Pipeline {' + CANDIDATE_INIT_V3 + PUBLIC_METHODS
CANDIDATE_V3 = CANDIDATE_V2.replace(b'''        else invokeDirect(processors[index], frame, this, index + 1, output);
    }
    private static void invokeDirect(Processor processor, Frame frame, Pipeline pipeline,
                                     int nextIndex, FrameSink output) throws Exception {
        DirectInvocation invocation = new DirectInvocation(pipeline, nextIndex, output);
        try { processor.process(frame, invocation); }
        finally { invocation.retire(); }
    }
''', b'''        else directStages[index].invoke(frame, output);
    }
    private static final class DirectStage {
        private final Processor processor;
        private final DirectStage next;
        DirectStage(Processor processor, DirectStage next) {
            this.processor = processor; this.next = next;
        }
        void invoke(Frame frame, FrameSink output) throws Exception {
            DirectInvocation invocation = new DirectInvocation(next, output);
            try { processor.process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }
''').replace(b'''        private Pipeline pipeline;
        private final int nextIndex;''', b'        private DirectStage nextStage;').replace(
    b'''        DirectInvocation(Pipeline pipeline, int nextIndex, FrameSink output) {
            this.pipeline = pipeline; this.nextIndex = nextIndex; this.output = output;
        }''', b'''        DirectInvocation(DirectStage nextStage, FrameSink output) {
            this.nextStage = nextStage; this.output = output;
        }''').replace(
    b'            pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);',
    b'''            Frame emitted = Objects.requireNonNull(frame, "emitted frame");
            DirectStage next = nextStage;
            if (next == null) output.accept(emitted);
            else next.invoke(emitted, output);''').replace(b'pipeline = null', b'nextStage = null')
SUFFIX = b'''    void dispatchSession(int index, Frame frame, FrameSink output, BooleanSupplier valid) throws Exception {
        if (!valid.getAsBoolean()) throw StaleFrameException.INSTANCE;
    }
    private static void invoke(Processor processor, Frame frame, FrameSink next) throws Exception {
        SynchronousSink invocation = new SynchronousSink(next);
        try { processor.process(frame, invocation); }
        finally { invocation.invalidate(); }
    }
}
'''


def bind_sources(folder, manifest, suite):
    for side in evidence.SIDES:
        item = manifest['sources'][side]
        item['files'][evidence.PIPELINE] = evidence.digest((folder / 'sources' / (side + '-Pipeline.java')).read_bytes())
        item['aggregate'] = evidence.digest(evidence.canonical(item['files']))
    fixtures.rebind(folder / 'evidence/measurements', manifest, suite)
    evidence.render(folder / 'evidence/measurements', verify_checksums=False)


def report_fixture(folder):
    manifest, suite = fixtures.archive_fixture(folder / 'evidence/measurements')
    (folder / 'sources').mkdir()
    for side, body in (('baseline', BASELINE), ('candidate', CANDIDATE)):
        (folder / 'sources' / (side + '-Pipeline.java')).write_bytes(PREFIX + body + SUFFIX)
    bind_sources(folder, manifest, suite)
    return manifest, suite


def v3_fixture(folder):
    manifest, suite = report_fixture(folder)
    for side, prefix, body in (('baseline', V3_BASELINE_PREFIX, BASELINE),
                               ('candidate', V3_CANDIDATE_PREFIX, CANDIDATE_V3)):
        (folder / 'sources' / (side + '-Pipeline.java')).write_bytes(prefix + body + SUFFIX)
    bind_sources(folder, manifest, suite)
    return manifest, suite


class DirectStateRenderTest(unittest.TestCase):
    def test_complete_pair_renders_deterministically_with_all_source_bindings(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            manifest, _ = report_fixture(folder)
            with mock.patch.object(evidence, 'check', wraps=evidence.check) as checked:
                expected = renderer.render(folder)
                checked.assert_called_once_with(folder / 'evidence/measurements')
            self.assertEqual(set(expected), {'costs.svg', 'structure.svg'})
            self.assertEqual(renderer.render(folder, check=True), expected)
            self.assertEqual(renderer.render(folder), expected)
            for name, data in expected.items():
                root = ET.fromstring(data)
                self.assertTrue(root.find('svg:title', NS).text)
                self.assertTrue(root.find('svg:desc', NS).text)
                binding = json.loads(root.find('svg:metadata', NS).text)
                self.assertEqual(binding['measurementManifestSha256'],
                                 evidence.digest((folder / 'evidence/measurements/manifest.json').read_bytes()))
                self.assertEqual(binding['summarySha256'],
                                 evidence.digest((folder / 'evidence/measurements/summary.json').read_bytes()))
                self.assertEqual(binding['generatorSha256'], evidence.digest(Path(renderer.__file__).read_bytes()))
                self.assertEqual(binding['sourceProof']['candidateShape'], 'direct-state-v1-inline')
                for side in evidence.SIDES:
                    self.assertEqual(binding['sourceProof'][side + 'PipelineSha256'],
                                     manifest['sources'][side]['files'][evidence.PIPELINE])
                    self.assertEqual(binding['sources'][side]['artifactSha256'], manifest['artifacts'][side]['sha256'])
                self.assertNotIn(temporary, data.decode())
                self.assertEqual((folder / name).read_bytes(), data)

    def test_v2_helpers_have_an_explicit_shape_and_remain_bound_to_their_own_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            manifest, suite = report_fixture(folder)
            path = folder / 'sources/candidate-Pipeline.java'
            path.write_bytes(PREFIX + CANDIDATE_V2 + SUFFIX)
            bind_sources(folder, manifest, suite)
            figures = renderer.render(folder)
            self.assertEqual(renderer.render(folder, check=True), figures)
            root = ET.fromstring(figures['structure.svg'])
            binding = json.loads(root.find('svg:metadata', NS).text)
            self.assertEqual(binding['sourceProof']['candidateShape'], 'direct-state-v2-helpers')
            self.assertEqual(binding['sourceProof']['candidatePipelineSha256'], evidence.digest(path.read_bytes()))
            text = ''.join(root.itertext())
            self.assertIn('private forwarding helpers', text)
            self.assertIn('source remains byte-identical', text)

    def test_v3_binds_only_known_constructor_and_plan_edits_without_claiming_a_four_field_invocation(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            v3_fixture(folder)
            figures = renderer.render(folder)
            self.assertEqual(renderer.render(folder, check=True), figures)
            root = ET.fromstring(figures['structure.svg'])
            binding = json.loads(root.find('svg:metadata', NS).text)
            proof = binding['sourceProof']
            self.assertEqual(proof['candidateShape'], 'direct-state-v3-prebound-plan')
            self.assertFalse(proof['publicPrefixIdentical'])
            self.assertNotIn('publicPrefixSha256', proof)
            self.assertEqual(proof['publicEntrySourceSha256'], evidence.digest(PUBLIC_METHODS))
            text = ' '.join(item.text or '' for item in root.findall('svg:text', NS))
            for expected in ('N nodes + N-reference array', 'References: owner / nextStage / output',
                             'No plan when hasAsync is true.', 'Live owner + nextStage = null → terminal output.',
                             'owner = null identifies a retired invocation.',
                             'Plan setup cost is measured separately.'):
                self.assertIn(expected, text)
            self.assertNotIn('pipeline : Pipeline', text)
            self.assertNotIn('nextIndex : int', text)

    def test_v3_public_source_initialization_topology_and_terminal_mutations_are_rejected(self):
        changes = ((b'dispatch(0, frame, output)', b'dispatch(1, frame, output)'),
                   (b'return new PipelineSession(this, options, output)', b'return null'),
                   (b'hasAsync ? null : new DirectStage', b'new DirectStage'),
                   (b'private final DirectStage[] directStages', b'private DirectStage[] directStages'),
                   (b'private final DirectStage next', b'private DirectStage next'),
                   (b'index--', b'index++'),
                   (b'directStages[index] = next', b'directStages[0] = next'),
                   (b'if (next == null)', b'if (owner == null)'),
                   (b'nextStage = null', b'nextStage = saved'),
                   (b'if (!valid.getAsBoolean())', b'if (valid.getAsBoolean())'))
        for old, new in changes:
            with self.subTest(change=old), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                manifest, suite = v3_fixture(folder)
                path = folder / 'sources/candidate-Pipeline.java'
                self.assertIn(old, path.read_bytes())
                path.write_bytes(path.read_bytes().replace(old, new))
                bind_sources(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    renderer.render(folder)

    def test_a_v3_body_with_an_old_constructor_and_a_v2_body_with_the_plan_constructor_are_not_known_shapes(self):
        for prefix, body in ((V3_BASELINE_PREFIX, CANDIDATE_V3), (V3_CANDIDATE_PREFIX, CANDIDATE_V2)):
            with self.subTest(body=body[:60]), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                manifest, suite = v3_fixture(folder)
                (folder / 'sources/candidate-Pipeline.java').write_bytes(prefix + body + SUFFIX)
                bind_sources(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    renderer.render(folder)

    def test_neither_hybrid_nor_a_changed_v2_helper_is_an_accepted_shape(self):
        changes = ((b'invokeDirect(processors[index], frame, this, index + 1, output)',
                    b'invokeDirect(processors[index], frame, this, index + 2, output)'),
                   (b'private void dispatchNext(Frame frame)', b'public void dispatchNext(Frame frame)'),
                   (b'owner = null', b'owner = Thread.currentThread()'),
                   # Retain the v2 constructor helper but inline its output step:
                   # this otherwise plausible third shape was never registered.
                   (b'dispatchNext(frame);',
                    b'pipeline.dispatch(nextIndex, Objects.requireNonNull(frame, "emitted frame"), output);'))
        for old, new in changes:
            with self.subTest(change=old), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                manifest, suite = report_fixture(folder)
                self.assertIn(old, CANDIDATE_V2)
                path = folder / 'sources/candidate-Pipeline.java'
                path.write_bytes(PREFIX + CANDIDATE_V2.replace(old, new) + SUFFIX)
                bind_sources(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    renderer.render(folder)

    def test_cost_lines_use_six_fork_means_never_thirty_iteration_extrema(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            report_fixture(folder)
            root = ET.fromstring(renderer.render(folder)['costs.svg'])
            lines = root.findall("svg:path[@data-statistic='fork-range']", NS)
            dots = root.findall("svg:circle[@data-statistic='fork-mean']", NS)
            self.assertEqual(len(lines), 7 * 2 * 2)
            self.assertEqual(len(dots), 7 * 2 * 2 * 6)
            first = next(item for item in lines if item.get('data-scenario') == 'raw-direct-1'
                         and item.get('data-metric') == 'timeNsPerOp' and item.get('data-side') == 'baseline')
            self.assertAlmostEqual(float(first.get('data-min')), 99.9)
            self.assertAlmostEqual(float(first.get('data-max')), 100.1)
            text = ''.join(root.itertext())
            self.assertIn('NEGATIVE CONTROL ONLY', text)
            self.assertIn('Paired C/B: 0.8000 / 0.8000 / 0.8000', text)
            self.assertIn('not confidence intervals', text)

    def test_source_tampering_fails_before_any_figure_is_written(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            report_fixture(folder)
            path = folder / 'sources/candidate-Pipeline.java'
            path.write_bytes(path.read_bytes().replace(b'output = null', b'output = saved'))
            with self.assertRaises(ValueError):
                renderer.render(folder)
            self.assertFalse((folder / 'costs.svg').exists())
            self.assertFalse((folder / 'structure.svg').exists())

    def test_rebound_prefix_suffix_or_unpictured_direct_change_still_fails(self):
        changes = ((b'dispatch(0, frame, output)', b'dispatch(1, frame, output)'),
                   (b'if (!valid.getAsBoolean())', b'if (valid.getAsBoolean())'),
                   (b'owner = null', b'owner = Thread.currentThread()'),
                   (b'index + 1, output', b'index + 2, output'),
                   (b'private static final class DirectInvocation', b'private final class DirectInvocation'),
                   (b'invocation.retire();', b'invocation.retire(); output.accept(frame);'))
        for old, new in changes:
            with self.subTest(change=old), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                manifest, suite = report_fixture(folder)
                path = folder / 'sources/candidate-Pipeline.java'
                self.assertIn(old, path.read_bytes())
                path.write_bytes(path.read_bytes().replace(old, new))
                bind_sources(folder, manifest, suite)
                with self.assertRaises(ValueError):
                    renderer.render(folder)

    def test_both_sides_cannot_drop_the_unchanged_synchronous_helper(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            manifest, suite = report_fixture(folder)
            for side in evidence.SIDES:
                path = folder / 'sources' / (side + '-Pipeline.java')
                path.write_bytes(path.read_bytes().replace(b'new SynchronousSink(next)', b'next'))
            bind_sources(folder, manifest, suite)
            with self.assertRaises(ValueError):
                renderer.render(folder)

    def test_missing_or_symlink_source_and_output_are_rejected(self):
        for mode in ('missing-source', 'linked-source', 'linked-directory', 'linked-output'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                report_fixture(folder)
                if mode in ('missing-source', 'linked-source'):
                    path = folder / 'sources/candidate-Pipeline.java'
                    saved = path.read_bytes()
                    path.unlink()
                    if mode == 'linked-source':
                        (folder / 'saved.java').write_bytes(saved)
                        path.symlink_to(folder / 'saved.java')
                elif mode == 'linked-directory':
                    (folder / 'sources').rename(folder / 'saved-sources')
                    (folder / 'sources').symlink_to(folder / 'saved-sources', target_is_directory=True)
                else:
                    (folder / 'untouched.svg').write_bytes(b'unchanged')
                    (folder / 'structure.svg').symlink_to(folder / 'untouched.svg')
                with self.assertRaises(ValueError):
                    renderer.render(folder)
                self.assertFalse((folder / 'costs.svg').exists())

    def test_raw_or_summary_tampering_cannot_become_a_chart(self):
        for name in ('summary.json', evidence.plan()[0]['raw']):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                report_fixture(folder)
                path = folder / 'evidence/measurements' / name
                path.write_bytes(path.read_bytes() + b' ')
                with self.assertRaises(ValueError):
                    renderer.render(folder)
                self.assertFalse((folder / 'costs.svg').exists())

    def test_check_is_read_only_and_rejects_stale_figure(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            report_fixture(folder)
            renderer.render(folder)
            target = folder / 'structure.svg'
            changed = target.read_bytes().replace(b'finally', b'afterwards')
            target.write_bytes(changed)
            with self.assertRaises(ValueError):
                renderer.render(folder, check=True)
            self.assertEqual(target.read_bytes(), changed)

    def test_zero_allocation_and_undefined_ratios_have_finite_svg_coordinates(self):
        manifest = fixtures.manifest_fixture()
        summary = evidence.summarize(manifest, fixtures.records_fixture(manifest))
        for cell in summary['cells']:
            for side in evidence.SIDES:
                cell[side]['allocatedBytesPerOp'].update(forkMeans=[0] * 6, meanForkMean=0)
            cell['ratioSummary']['allocatedBytesPerOp']['blockRatios'] = [None, None, None]
        data = renderer.costs_svg(summary, {})
        self.assertNotIn(b'NaN', data)
        self.assertNotIn(b'Infinity', data)
        self.assertIn(b'undefined (positive allocation / zero baseline)', data)
        self.assertEqual(renderer.axis([0], 100, 500, False)[0](0), 100)
        # The log axis does not overflow when observations span the finite range.
        position, ticks = renderer.axis([1e-300, 1e300], 100, 500, True)
        self.assertEqual(position(1e-300), 100)
        self.assertEqual(position(1e300), 500)
        self.assertLessEqual(len(ticks), 9)

    def test_cli_rejects_missing_data_without_leaking_paths_or_payloads(self):
        with tempfile.TemporaryDirectory(prefix='private-synthetic-') as temporary:
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(renderer.main([temporary]), 1)
            self.assertNotIn(temporary, stderr.getvalue())
            self.assertNotIn('Traceback', stderr.getvalue())
            self.assertFalse(stdout.getvalue())


if __name__ == '__main__':
    unittest.main()
