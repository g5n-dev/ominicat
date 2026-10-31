"""Regression fixtures for incomplete or invalid profiling evidence; no JVM required."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from profile_evidence import parse_jmh_plan, prepare_run, validate_evidence


ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "reports/java17/kernel"
PLAN = """Benchmarks:
io.ominicat.benchmarks.PipelineBenchmark.dispatch
  param "stages" = {1, 4, 16}
io.ominicat.benchmarks.SessionBenchmark.roundTrip
  param "stages" = {1, 4, 16}
"""


class ReportValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        for name in ("jmh.json", "load.json", "source-sha256.json"):
            (self.folder / name).write_bytes((HISTORY / name).read_bytes())

    def mutate(self, name, change):
        path = self.folder / name
        value = json.loads(path.read_text())
        change(value)
        path.write_text(json.dumps(value))

    def strict_manifest(self):
        (self.folder / "jmh-plan.txt").write_text(PLAN)
        artifact = self.folder / "ominicat-benchmarks.jar"
        artifact.write_bytes(b"synthetic fixture artifact, not a measurement")
        prepare_run(self.folder, "quick", artifact)
        return artifact

    def test_published_history_remains_valid_in_explicit_compatibility_mode(self):
        for folder in (ROOT / "reports/baseline", HISTORY):
            with self.subTest(folder=folder.name):
                self.assertEqual("historical compatibility", validate_evidence(folder)[3])

    def test_strict_archive_validates_without_original_jar_or_checkout_sources(self):
        artifact = self.strict_manifest()
        manifest = json.loads((self.folder / "run-manifest.json").read_text())
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), manifest["benchmarkArtifactSha256"])
        self.assertEqual(artifact.name, manifest["benchmarkArtifact"])
        artifact.unlink()
        self.assertEqual("strict manifest", validate_evidence(self.folder, strict=True)[3])

    def test_missing_benchmark_case_is_rejected(self):
        self.mutate("jmh.json", lambda rows: rows.pop())
        with self.assertRaisesRegex(ValueError, "complete expected"):
            validate_evidence(self.folder)

    def test_empty_results_are_rejected(self):
        (self.folder / "jmh.json").write_text("[]")
        with self.assertRaisesRegex(ValueError, "empty JMH"):
            validate_evidence(self.folder)

    def test_duplicate_results_are_rejected(self):
        self.mutate("jmh.json", lambda rows: rows.append(copy.deepcopy(rows[0])))
        with self.assertRaisesRegex(ValueError, "duplicate JMH"):
            validate_evidence(self.folder)

    def test_nonfinite_reported_metrics_are_rejected(self):
        original = (self.folder / "jmh.json").read_text()
        for metric, key, invalid in (("primaryMetric", "score", "NaN"),
                                     ("primaryMetric", "scoreError", float("nan")),
                                     ("gc.alloc.rate.norm", "score", float("inf"))):
            with self.subTest(metric=metric, key=key):
                (self.folder / "jmh.json").write_text(original)
                def change(rows):
                    target = rows[0]["primaryMetric"] if metric == "primaryMetric" else rows[0]["secondaryMetrics"][metric]
                    target[key] = invalid
                self.mutate("jmh.json", change)
                with self.assertRaises(ValueError):
                    validate_evidence(self.folder)

    def test_missing_measurement_samples_are_rejected(self):
        self.mutate("jmh.json", lambda rows: rows[0]["primaryMetric"]["rawData"][0].pop())
        with self.assertRaisesRegex(ValueError, "incomplete JMH iterations"):
            validate_evidence(self.folder)

    def test_processed_and_rejected_counters_are_checked(self):
        original = (self.folder / "load.json").read_text()
        for field in ("processedIncludingWarmup", "rejected"):
            with self.subTest(field=field):
                (self.folder / "load.json").write_text(original)
                self.mutate("load.json", lambda load: load.update({field: load[field] + 1}))
                with self.assertRaisesRegex(ValueError, "processed/rejected"):
                    validate_evidence(self.folder)

    def test_missing_environment_or_nonfinite_load_is_rejected(self):
        original = (self.folder / "load.json").read_text()
        for change in (lambda load: load["environment"].pop("javaVersion"),
                       lambda load: load.update(throughputFramesPerSecond="NaN"),
                       lambda load: load["latencyMicros"].update(p99=float("nan"))):
            (self.folder / "load.json").write_text(original)
            self.mutate("load.json", change)
            with self.assertRaises(ValueError):
                validate_evidence(self.folder)

    def test_strict_mode_never_falls_back_on_missing_manifest_or_source(self):
        with self.assertRaisesRegex(ValueError, "requires run-manifest"):
            validate_evidence(self.folder, strict=True)
        self.strict_manifest()
        (self.folder / "source-sha256.json").unlink()
        with self.assertRaisesRegex(ValueError, "source-sha256"):
            validate_evidence(self.folder, strict=True)

    def test_source_hash_corruption_is_rejected(self):
        self.mutate("source-sha256.json", lambda source: source["files"][0].update(sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "aggregate mismatch"):
            validate_evidence(self.folder)

    def test_actual_artifact_hash_and_current_source_are_checked_when_supplied(self):
        artifact = self.strict_manifest()
        artifact.write_bytes(b"different artifact")
        with self.assertRaisesRegex(ValueError, "artifact SHA-256 mismatch"):
            validate_evidence(self.folder, strict=True, artifact=artifact)
        artifact.unlink()
        with self.assertRaisesRegex(ValueError, "sources changed"):
            validate_evidence(self.folder, strict=True, current_source={})

    def test_discovery_expands_future_methods_and_parameter_products(self):
        extra = """io.ominicat.benchmarks.FutureBenchmark.sample
  param "size" = {64, 1024}
  param "wrapped" = {false, true}
"""
        plan = parse_jmh_plan(PLAN + extra)
        self.assertEqual(10, len(plan))
        artifact = self.strict_manifest()
        (self.folder / "jmh-plan.txt").write_text(PLAN + extra)
        prepare_run(self.folder, "quick", artifact)
        def add_cases(rows):
            template = copy.deepcopy(rows[0])
            for case in plan[6:]:
                result = copy.deepcopy(template)
                result.update(case)
                rows.append(result)
        self.mutate("jmh.json", add_cases)
        self.assertEqual(10, len(validate_evidence(self.folder, strict=True)[0]))
        self.mutate("jmh.json", lambda rows: rows.pop())
        with self.assertRaisesRegex(ValueError, "complete expected"):
            validate_evidence(self.folder, strict=True)

    def test_failed_validation_does_not_publish_a_report(self):
        self.strict_manifest()
        (self.folder / "jmh.json").write_text("[]")
        result = subprocess.run([sys.executable, str(ROOT / "scripts/report.py"), "--strict", str(self.folder)],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.folder / "report.md").exists())
        self.assertIn("validation failed", result.stderr)

    def test_profile_refuses_nonempty_destination_before_running_builds(self):
        sentinel = self.folder / "report.md"
        sentinel.write_text("previous immutable evidence")
        result = subprocess.run(["bash", str(ROOT / "scripts/profile.sh"), "quick", str(self.folder)],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("Refusing nonempty", result.stderr)
        self.assertEqual("previous immutable evidence", sentinel.read_text())
        self.assertFalse((self.folder / "build.log").exists())


if __name__ == "__main__":
    unittest.main()
