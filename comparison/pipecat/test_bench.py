"""Dependency-free protocol/oracle checks; not a substitute for native smoke runs."""

from array import array
import contextlib
import io
import math
from pathlib import Path
import random
import tempfile
import time
import unittest

import benchmark as bench


class NativeFrameStandIn:
    def __init__(self):
        self.metadata = {}


class NumericProtocolTest(unittest.TestCase):
    def test_combined_oracle_against_independent_bit_string_rotation(self):
        randomizer = random.Random(0xC47)
        for stages in (1, 4, 16, 64):
            rotation, mask = bench.combined_oracle(stages)
            for sequence in [0, 1, (1 << 63) - 1, 1 << 63, (1 << 64) - 1] + [randomizer.getrandbits(64) for _ in range(100)]:
                expected = sequence
                for index in range(stages):
                    bits = f"{expected ^ ((0x9E3779B97F4A7C15 + index) % (1 << 64)):064b}"
                    expected = int(bits[7:] + bits[:7], 2)
                self.assertEqual(bench.rotate_left(sequence, rotation) ^ mask, expected)

    def test_numeric_fields_are_immutable_and_stage_result_is_a_new_frame(self):
        numeric = bench.make_numeric_frame(NativeFrameStandIn)
        source = numeric(8, 8, 90, -1, 0)
        output = numeric(source.sequence, bench.rotate_left(source.value ^ bench.STAGE_KEY, 7),
                         source.started_ns, source.measurement_index, source.phase)
        self.assertIsNot(source, output)
        self.assertEqual((8, 8, 90, -1, 0), tuple(getattr(source, name) for name in numeric.__slots__))
        for field in numeric.__slots__:
            with self.assertRaises(AttributeError):
                setattr(source, field, 4)
            with self.assertRaises(AttributeError):
                delattr(source, field)
        source.metadata["native"] = "retained"  # Framework lifecycle state is native.
        self.assertEqual(source.metadata, {"native": "retained"})

    def test_exact_quantiles_match_full_sort_for_many_distributions(self):
        randomizer = random.Random(731)
        for count in (1, 2, 3, 10, 100, 1001):
            for repetition in range(20):
                values = [randomizer.randrange(1 if repetition == 0 else 100_000) for _ in range(count)]
                ordered = sorted(values)
                samples = array("Q", values + [999_999] * 7)
                actual = bench.exact_quantiles(samples, count)
                for label, percentile in (("p50", 50), ("p95", 95), ("p99", 99), ("max", 100)):
                    self.assertEqual(ordered[math.ceil(count * percentile / 100) - 1] / 1000, actual[label])
                self.assertEqual(list(samples[count:]), [999_999] * 7)

    def test_rejects_empty_samples_and_invalid_cli_parameters(self):
        with self.assertRaises(bench.BenchmarkError):
            bench.exact_quantiles(array("Q", [0]), 0)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stderr(io.StringIO()):
            output = str(Path(directory) / "result.json")
            base = ["--stages", "4", "--output", output]
            for option, value in (("--measure-seconds", "nan"), ("--warmup-seconds", "0"),
                                  ("--max-samples", "2000001"), ("--max-samples", "0")):
                with self.assertRaises(SystemExit):
                    bench.arguments(base + [option, value])
            Path(output).write_text("old result", encoding="utf-8")
            with self.assertRaises(SystemExit):
                bench.arguments(base)


class TerminalOracleTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.numeric = bench.make_numeric_frame(NativeFrameStandIn)

    def expected_output(self, ledger, **changes):
        pending = ledger.pending
        values = {name: getattr(pending, name) for name in self.numeric.__slots__}
        values["value"] = bench.rotate_left(values["sequence"], ledger.rotation) ^ ledger.combined
        values.update(changes)
        return self.numeric(**values)

    def pending(self, ledger, measured=True):
        ledger.ack.clear()
        ledger.pending = self.numeric(ledger.sequence, ledger.sequence, time.perf_counter_ns(),
                                      ledger.measured_frames if measured else -1, int(measured))

    async def test_warmup_drains_then_measurement_preserves_exact_counts_and_order(self):
        ledger = bench.Ledger(array("Q", [0] * 3), 4, 3)
        for measured in (False, False, True, True, True):
            self.pending(ledger, measured)
            ledger.accept(self.expected_output(ledger))
            await ledger.ack.wait()
            ledger.healthy()
            self.assertIsNone(ledger.pending)
        self.assertEqual((2, 3, 5), (ledger.warmup_frames, ledger.measured_frames, ledger.sequence))
        self.assertTrue(all(value > 0 for value in ledger.samples))

    async def test_wrong_nonce_value_timestamp_phase_and_index_fail_without_success(self):
        for field in self.numeric.__slots__:
            ledger = bench.Ledger(array("Q", [0]), 16, 1)
            self.pending(ledger)
            correct = self.expected_output(ledger)
            ledger.accept(self.expected_output(ledger, **{field: getattr(correct, field) + 1}))
            self.assertTrue(ledger.ack.is_set())
            with self.assertRaises(bench.BenchmarkError):
                ledger.healthy()
            self.assertEqual(0, ledger.measured_frames)
            self.assertEqual(0, ledger.samples[0])

    async def test_duplicate_and_capacity_exhaustion_are_explicit_failures(self):
        ledger = bench.Ledger(array("Q", [0]), 1, 1)
        self.pending(ledger)
        output = self.expected_output(ledger)
        ledger.accept(output)
        ledger.accept(output)
        with self.assertRaises(bench.BenchmarkError):
            ledger.healthy()
        self.assertEqual(1, ledger.measured_frames)
        capped = bench.Ledger(array("Q", [0]), 1, 1)
        self.pending(capped)
        capped.accept(self.expected_output(capped))
        self.pending(capped)
        capped.accept(self.expected_output(capped))
        with self.assertRaises(bench.BenchmarkError):
            capped.healthy()
        self.assertEqual(1, capped.measured_frames)


if __name__ == "__main__":
    unittest.main()
