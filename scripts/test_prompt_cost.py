"""Synthetic paired-evidence failure controls; no Java, Maven or network required."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import prompt_cost as cost


class PromptCostEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.manifest = {
            'schemaVersion': 1,
            'order': list(cost.ORDER),
            'probe_sha256': 'e' * 64,
            'collector_sha256': 'f' * 64,
            'context': {
                'system': 'Linux', 'machine': 'x86_64', 'kernel': '6.8.0-synthetic',
                'distribution': {'ID': 'ubuntu', 'VERSION_ID': '24.04'},
                'java': 'openjdk version "17.0.21"',
                'availableProcessors': 2, 'cpu': 'synthetic fixture, not a measurement',
            },
            'sources': {}, 'runtimes': {}, 'runs': [], 'sha256': {},
        }
        for index, side in enumerate(('baseline', 'candidate')):
            self.manifest['sources'][side] = {
                'revision': str(index + 1) * 40,
                'files': {
                    'ominicat-core/src/main/java/io/ominicat/core/Frame.java': '1' * 64,
                    'ominicat-conversation/src/main/java/io/ominicat/conversation/PromptTemplateProcessor.java': '2' * 64,
                    'ominicat-benchmarks/src/main/java/io/ominicat/benchmarks/SyntheticProbe.java': '3' * 64,
                    **{path: '4' * 64 for path in ('pom.xml', 'ominicat-core/pom.xml',
                        'ominicat-conversation/pom.xml', 'ominicat-benchmarks/pom.xml')},
                },
            }
            self.manifest['runtimes'][side] = {
                'conversation_sha256': ('a' if index == 0 else 'c') * 64,
                'core_sha256': ('b' if index == 0 else 'd') * 64,
            }
        for index, side in enumerate(cost.ORDER):
            runtime = self.manifest['runtimes'][side]
            rows = []
            for max_turns, history in sorted(cost.CASES):
                value = (100 if side == 'baseline' else 60) + index
                allocated = (4000 if side == 'baseline' else 1000) + index
                rows.append({
                    'jmhVersion': '1.37', 'benchmark': cost.PROBE, 'mode': 'avgt',
                    'params': {'maxTurns': max_turns, 'history': history},
                    'forks': 1, 'threads': 1, 'warmupIterations': 3,
                    'measurementIterations': 5, 'warmupTime': '1 s', 'measurementTime': '1 s',
                    'jdkVersion': '17.0.21', 'vmName': 'OpenJDK 64-Bit Server VM',
                    'vmVersion': '17.0.21+9', 'jvm': 'synthetic-java17',
                    'jvmArgs': ['-Xms512m', '-Xmx512m',
                        '-Dominicat.probe.conversationSha256=' + runtime['conversation_sha256'],
                        '-Dominicat.probe.coreSha256=' + runtime['core_sha256'],
                        '-Dominicat.probe.harnessSha256=' + self.manifest['probe_sha256']],
                    'primaryMetric': self.metric(value, 'us/op'),
                    'secondaryMetrics': {'gc.alloc.rate.norm': self.metric(allocated, 'B/op')},
                })
            json_name, log_name = f'{index}-{side}.json', f'{index}-{side}.log'
            cost.write(self.folder / json_name, rows)
            log = ''.join(
                'PROBE_CONVERSATION_SHA256=' + runtime['conversation_sha256'] + '\n'
                + 'PROBE_CORE_SHA256=' + runtime['core_sha256'] + '\n'
                + 'PROBE_HARNESS_SHA256=' + self.manifest['probe_sha256'] + '\n'
                for _ in rows)
            (self.folder / log_name).write_text(log, encoding='utf-8')
            self.manifest['runs'].append({'index': index, 'side': side, 'json': json_name, 'log': log_name})
            self.rehash(json_name)
            self.rehash(log_name)
        self.save_manifest()

    @staticmethod
    def metric(value, unit):
        return {'score': value, 'scoreError': 0.1, 'scoreConfidence': [value - 0.1, value + 0.1],
                'scoreUnit': unit, 'rawData': [[value - 1, value + 1, value, value, value]]}

    def rehash(self, name):
        self.manifest['sha256'][name] = cost.sha(self.folder / name)

    def save_manifest(self):
        cost.write(self.folder / 'manifest.json', self.manifest)

    def mutate_rows(self, change, index=0, rehash=True):
        name = self.manifest['runs'][index]['json']
        rows = json.loads((self.folder / name).read_text())
        change(rows)
        cost.write(self.folder / name, rows)
        if rehash:
            self.rehash(name)
            self.save_manifest()

    def assert_rejected(self):
        with self.assertRaises(ValueError):
            cost.check(self.folder)

    def test_complete_abba_matrix_has_two_distinct_fork_means_per_side(self):
        rows = cost.check(self.folder)
        self.assertEqual(9, len(rows))
        self.assertEqual({(int(n), history) for n, history in cost.CASES},
                         {(row['maxTurns'], row['history']) for row in rows})
        for row in rows:
            self.assertEqual([100, 103], row['baseline_micros']['forkMeans'])
            self.assertEqual([61, 62], row['candidate_micros']['forkMeans'])
            self.assertEqual([1000, 1003], row['candidate_bytes']['iterationRange'])
            self.assertAlmostEqual(100 * (1 - 61.5 / 101.5), row['micros_reduction_percent'])

    def test_missing_case_is_rejected(self):
        self.mutate_rows(lambda rows: rows.pop())
        self.assert_rejected()

    def test_duplicate_case_is_rejected(self):
        self.mutate_rows(lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])))
        self.assert_rejected()

    def test_unknown_case_is_rejected(self):
        self.mutate_rows(lambda rows: rows[0]['params'].update(maxTurns='999'))
        self.assert_rejected()

    def test_missing_process_group_is_rejected(self):
        self.manifest['runs'].pop()
        self.save_manifest()
        self.assert_rejected()

    def test_reusing_a_candidate_group_does_not_count_as_an_independent_fork(self):
        self.manifest['runs'][2].update({key: self.manifest['runs'][1][key] for key in ('json', 'log')})
        self.save_manifest()
        self.assert_rejected()

    def test_wrong_abba_order_is_rejected(self):
        self.manifest['order'] = ['baseline', 'candidate', 'baseline', 'candidate']
        self.save_manifest()
        self.assert_rejected()

    def test_wrong_group_side_or_index_is_rejected(self):
        for key, wrong in (('side', 'candidate'), ('index', 3)):
            with self.subTest(key=key):
                original = self.manifest['runs'][0][key]
                self.manifest['runs'][0][key] = wrong
                self.save_manifest()
                self.assert_rejected()
                self.manifest['runs'][0][key] = original
        self.save_manifest()

    def test_wrong_jmh_settings_are_rejected(self):
        name = self.manifest['runs'][0]['json']
        original = (self.folder / name).read_bytes()
        for field, wrong in (('forks', 2), ('threads', 2), ('warmupIterations', 2),
                             ('measurementIterations', 4), ('warmupTime', '2 s'),
                             ('measurementTime', '2 s'), ('jdkVersion', '21.0.13'), ('mode', 'thrpt')):
            with self.subTest(field=field):
                (self.folder / name).write_bytes(original)
                self.mutate_rows(lambda rows: rows[0].update({field: wrong}))
                self.assert_rejected()

    def test_missing_or_conflicting_jvm_properties_are_rejected(self):
        name = self.manifest['runs'][0]['json']
        original = (self.folder / name).read_bytes()
        modifications = (
            lambda args: args.remove('-Xmx512m'),
            lambda args: args.append('-Xmx256m'),
            lambda args: args.append('-Xms256m'),
            lambda args: args.append('-Dominicat.probe.coreSha256=' + '0' * 64),
            lambda args: args.append('-Dominicat.probe.conversationSha256=' + '0' * 64),
            lambda args: args.append('-Dominicat.probe.harnessSha256=' + '0' * 64),
            lambda args: args.pop(),
        )
        for index, change in enumerate(modifications):
            with self.subTest(modification=index):
                (self.folder / name).write_bytes(original)
                self.mutate_rows(lambda rows: change(rows[0]['jvmArgs']))
                self.assert_rejected()

    def test_wrong_runtime_or_harness_hash_in_a_fork_log_is_rejected(self):
        name = self.manifest['runs'][0]['log']
        original = (self.folder / name).read_text()
        for label in ('CONVERSATION', 'CORE', 'HARNESS'):
            with self.subTest(label=label):
                lines = original.splitlines()
                position = next(i for i, line in enumerate(lines) if line.startswith('PROBE_' + label + '_'))
                lines[position] = 'PROBE_' + label + '_SHA256=' + '0' * 64
                (self.folder / name).write_text('\n'.join(lines) + '\n')
                self.rehash(name)
                self.save_manifest()
                self.assert_rejected()

    def test_manifest_schema_hashes_and_sources_are_required(self):
        original = copy.deepcopy(self.manifest)
        changes = (
            lambda m: m.update(schemaVersion=2),
            lambda m: m.update(probe_sha256='bad'),
            lambda m: m.update(collector_sha256='bad'),
            lambda m: m.update(sources={}),
            lambda m: m['sources']['baseline'].update(revision='not-a-revision'),
            lambda m: m['sources']['candidate'].update(files={}),
            lambda m: m['sources']['baseline']['files'].update({'pom.xml': 'bad'}),
            lambda m: m['runtimes']['baseline'].update(core_sha256='bad'),
        )
        for index, change in enumerate(changes):
            with self.subTest(modification=index):
                self.manifest = copy.deepcopy(original)
                change(self.manifest)
                self.save_manifest()
                self.assert_rejected()

    def test_nonfinite_or_negative_reported_scores_and_samples_are_rejected(self):
        name = self.manifest['runs'][0]['json']
        original = (self.folder / name).read_bytes()
        for field in ('primaryMetric', 'allocation'):
            for value in (float('nan'), float('inf'), -1, 'NaN', True):
                for sample in (False, True):
                    with self.subTest(field=field, value=value, sample=sample):
                        (self.folder / name).write_bytes(original)
                        def change(rows):
                            metric = rows[0]['primaryMetric'] if field == 'primaryMetric' else rows[0]['secondaryMetrics']['gc.alloc.rate.norm']
                            if sample:
                                metric['rawData'][0][0] = value
                            else:
                                metric['score'] = value
                        self.mutate_rows(change)
                        self.assert_rejected()

    def test_missing_gc_samples_are_rejected(self):
        self.mutate_rows(lambda rows: rows[0]['secondaryMetrics']['gc.alloc.rate.norm']['rawData'][0].pop())
        self.assert_rejected()

    def test_wrong_allocation_units_are_rejected(self):
        self.mutate_rows(lambda rows: rows[0]['secondaryMetrics']['gc.alloc.rate.norm'].update(scoreUnit='MB/sec'))
        self.assert_rejected()

    def test_score_must_match_the_preserved_iteration_samples(self):
        self.mutate_rows(lambda rows: rows[0]['primaryMetric'].update(score=1))
        self.assert_rejected()

    def test_modified_raw_json_or_log_without_matching_hash_is_rejected(self):
        for field in ('json', 'log'):
            with self.subTest(field=field):
                path = self.folder / self.manifest['runs'][0][field]
                original = path.read_bytes()
                path.write_bytes(original + b'\n')
                self.assert_rejected()
                path.write_bytes(original)

    def test_wrong_collection_platform_is_rejected(self):
        self.manifest['context']['distribution']['VERSION_ID'] = '22.04'
        self.save_manifest()
        self.assert_rejected()

    def test_a_real_regression_remains_valid_evidence_without_a_performance_gate(self):
        for index in (1, 2):
            def change(rows):
                for row in rows:
                    row['primaryMetric'] = self.metric(1000, 'us/op')
                    row['secondaryMetrics']['gc.alloc.rate.norm'] = self.metric(8000, 'B/op')
            self.mutate_rows(change, index=index)
        rows = cost.check(self.folder)
        self.assertTrue(all(row['micros_reduction_percent'] < 0 for row in rows))
        self.assertTrue(all(row['bytes_reduction_percent'] < 0 for row in rows))

    def test_finite_inputs_cannot_produce_nonfinite_reduction_percentages(self):
        originals = {entry['json']: (self.folder / entry['json']).read_bytes()
                     for entry in self.manifest['runs']}
        for field in ('primaryMetric', 'allocation'):
            with self.subTest(field=field):
                for entry in self.manifest['runs']:
                    (self.folder / entry['json']).write_bytes(originals[entry['json']])
                for index, side in enumerate(cost.ORDER):
                    value = 1e-308 if side == 'baseline' else 1e308
                    def change(rows):
                        for row in rows:
                            metric = row['primaryMetric'] if field == 'primaryMetric' else row['secondaryMetrics']['gc.alloc.rate.norm']
                            metric.update(score=value, rawData=[[value] * 5])
                    self.mutate_rows(change, index=index)
                self.assert_rejected()


if __name__ == '__main__':
    unittest.main()
