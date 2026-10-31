"""Deterministic packaging oracles; no JVM, git, network or real profile required."""

from contextlib import redirect_stderr, redirect_stdout
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import package_profiles


COMMIT = "a" * 40
EXPECTED = {
    "report.md", "profiles.md", "cpu-flamegraph.svg", "allocation-flamegraph.svg",
    "jmh.json", "jmh.log", "load.json", "source-sha256.json",
    "jfr-hotspots.json", "jfr-events.json.gz", "gc.log", "jfr-summary.txt",
    "run-manifest.json",
}


class PackageProfilesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.output = self.root / "publish"
        self.summary = self.root / "summary.md"
        for name in EXPECTED:
            (self.source / name).write_text("synthetic profile\n", encoding="utf-8")
        self.write_json("load.json", {
            "recordedAt": "2026-10-31T10:20:30Z",
            "environment": {"javaVersion": "17.0.21.0.3", "workerBackend": "platform"},
            "sessions": 16, "stages": 4, "inflightPerSession": 32, "rejected": 0,
            "elapsedSeconds": 7.5, "throughputFramesPerSecond": 1234.5,
            "latencyMicros": {"p50": 2.0, "p95": 3.0, "p99": 4.0, "max": 5.0},
        })
        self.write_json("jmh.json", [{
            "benchmark": "io.ominicat.benchmarks.SessionBenchmark.roundTrip",
            "params": {"stages": "4"},
            "primaryMetric": {"score": 7.1, "scoreUnit": "us/op"},
            "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 488.0}},
        }])
        self.write_json("source-sha256.json", {"algorithm": "SHA-256", "aggregate": "b" * 64})
        self.write_json("run-manifest.json", {
            "schemaVersion": 1, "mode": "quick", "benchmarkArtifact": "ominicat-benchmarks.jar",
            "benchmarkArtifactSha256": "c" * 64, "sourceSha256": "b" * 64,
            "benchmarkPattern": "io.ominicat.benchmarks.*Benchmark.*",
            "expectedCases": [{"benchmark": "io.ominicat.benchmarks.SessionBenchmark.roundTrip",
                               "params": {"stages": "4"}}],
            "expectedJmh": {"forks": 2, "warmupIterations": 3, "measurementIterations": 5, "threads": 1},
            "expectedLoad": {"sessions": 16, "stages": 4, "inflightPerSession": 32,
                             "warmupFrames": 100000, "measuredFrames": 25000000, "maxHeapBytes": 536870912},
        })
        self.write_json("jfr-hotspots.json", {"eventCounts": {"jdk.GarbageCollection": 1}})
        self.write_events([{"type": "jdk.GarbageCollection", "values": {"sumOfPauses": "PT0.00125S"}}])

    def write_json(self, name, value):
        (self.source / name).write_text(json.dumps(value), encoding="utf-8")

    def write_events(self, events):
        (self.source / "jfr-events.json.gz").write_bytes(gzip.compress(
            json.dumps({"recording": {"events": events}}).encode(), mtime=0))

    def run_package(self, *extra, commit=COMMIT, environment=None):
        args = ["package_profiles.py", str(self.source), str(self.output), *extra]
        env = {key: value for key, value in os.environ.items() if not key.startswith("GITHUB_")}
        if commit is not None:
            env["GITHUB_SHA"] = commit
        env.update(environment or {})
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, env, clear=True), patch("sys.argv", args), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            status = package_profiles.main()
        return status, stdout.getvalue(), stderr.getvalue()

    def assert_failure_without_output(self, *extra, **kwargs):
        status, _, error = self.run_package(*extra, **kwargs)
        self.assertEqual(1, status, error)
        self.assertIn("Profile packaging failed:", error)
        self.assertFalse(self.output.exists())
        return error

    def test_exact_allowlist_byte_preservation_metadata_and_checksums(self):
        # Poison extras prove packaging never scans or imports raw/unrelated files.
        for name in ("kernel.jfr", "jfr-events.json", "build.log", "env", "private.txt"):
            (self.source / name).write_text("Bearer do-not-read-or-publish\n")
        (self.source / "unrelated-link").symlink_to(self.root / "nonexistent")
        self.summary.write_text("earlier step\n")
        status, _, error = self.run_package("--summary", str(self.summary), environment={
            "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "example/project",
            "GITHUB_RUN_ID": "12345",
        })
        self.assertEqual(0, status, error)
        self.assertEqual(EXPECTED | {"metadata.json", "SHA256SUMS"},
                         {path.name for path in self.output.iterdir()})
        for name in EXPECTED:
            self.assertEqual((self.source / name).read_bytes(), (self.output / name).read_bytes())
        metadata = json.loads((self.output / "metadata.json").read_text())
        self.assertEqual(COMMIT, metadata["gitCommit"])
        self.assertEqual("17.0.21.0.3", metadata["javaVersion"])
        self.assertEqual("platform", metadata["workerBackend"])
        self.assertEqual("c" * 64, metadata["benchmarkArtifactSha256"])
        self.assertEqual("quick", metadata["profileMode"])
        self.assertEqual("2026-10-31T10:20:30Z", metadata["collectedAtUtc"])
        self.assertRegex(metadata["packagedAtUtc"], r"^\d{4}-\d{2}-\d{2}T.*Z$")
        self.assertEqual("https://github.com/example/project/actions/runs/12345", metadata["githubRunUrl"])
        self.assertTrue(metadata["synthetic"])
        self.assertFalse(metadata["crossRunPerformanceGate"])
        checksums = {}
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ", 1)
            checksums[name] = digest
            self.assertEqual(digest, hashlib.sha256((self.output / name).read_bytes()).hexdigest())
        self.assertEqual(EXPECTED | {"metadata.json"}, set(checksums))
        markdown = self.summary.read_text()
        self.assertTrue(markdown.startswith("earlier step\n"))
        self.assertIn("1,234.500 frames/s", markdown)
        self.assertIn("pause sum **1.250 ms**", markdown)
        self.assertIn("scores are means", markdown)
        self.assertIn("not a capacity guarantee or cross-run performance gate", markdown)

    def test_each_allowlisted_file_is_required(self):
        for name in EXPECTED:
            with self.subTest(name=name):
                path = self.source / name
                content = path.read_bytes()
                path.unlink()
                self.assert_failure_without_output()
                path.write_bytes(content)

    def test_each_allowlisted_file_must_be_nonempty(self):
        for name in EXPECTED:
            with self.subTest(name=name):
                path = self.source / name
                content = path.read_bytes()
                path.write_bytes(b"")
                self.assert_failure_without_output()
                path.write_bytes(content)

    def test_existing_empty_directory_is_accepted(self):
        self.output.mkdir()
        status, _, error = self.run_package()
        self.assertEqual(0, status, error)

    def test_nonempty_destination_preserves_old_files(self):
        self.output.mkdir()
        sentinel = self.output / "sentinel.txt"
        sentinel.write_text("leave this untouched")
        status, _, error = self.run_package()
        self.assertEqual(1, status, error)
        self.assertEqual([sentinel], list(self.output.iterdir()))
        self.assertEqual("leave this untouched", sentinel.read_text())

    def test_source_cannot_be_output_even_through_path_alias(self):
        self.output = self.source / "."
        before = {path.name: path.read_bytes() for path in self.source.iterdir()}
        status, _, error = self.run_package()
        self.assertEqual(1, status, error)
        self.assertIn("different directories", error)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.source.iterdir()})

    def test_allowlisted_symlink_is_rejected_without_reading_target(self):
        target = self.root / "private.txt"
        target.write_text("Bearer private-value")
        (self.source / "jmh.log").unlink()
        (self.source / "jmh.log").symlink_to(target)
        error = self.assert_failure_without_output()
        self.assertIn("symlinks are refused", error)
        self.assertNotIn("private-value", error)

    def test_output_symlink_is_rejected(self):
        target = self.root / "elsewhere"
        target.mkdir()
        self.output.symlink_to(target, target_is_directory=True)
        status, _, error = self.run_package()
        self.assertEqual(1, status, error)
        self.assertFalse(any(target.iterdir()))

    def test_privacy_failures_do_not_echo_secret_or_create_output(self):
        for content in ("/Users/private-person/work/project", "/home/private-person/project",
                        r"C:\Users\private-person\project", "Bearer private-secret",
                        "Authorization: Basic cHJpdmF0ZTpzZWNyZXQ=", "OMINICAT_TOKEN=private-secret",
                        "OPENAI_API_KEY=private-secret", "github_pat_" + "x" * 30,
                        "sk-" + "x" * 30, "sk-proj-" + "x" * 30, "sk-svcacct-" + "x" * 30,
                        "-----BEGIN OPENSSH PRIVATE KEY-----"):
            with self.subTest(kind=content[:12]):
                (self.source / "jmh.log").write_text(content)
                error = self.assert_failure_without_output()
                self.assertNotIn("private-person", error)
                self.assertNotIn("private-secret", error)
                self.assertNotIn("cHJpdmF0ZTpzZWNyZXQ=", error)

    def test_compressed_events_are_privacy_checked(self):
        self.write_events([{"type": "jdk.ExecutionSample", "values": {"name": "/Users/private-person"}}])
        error = self.assert_failure_without_output()
        self.assertNotIn("private-person", error)

    def test_escaped_json_cannot_hide_home_path_or_key(self):
        for content in (r'{"path":"\/Users\/private-person"}',
                        r'{"path":"\u002fUsers\u002fprivate-person"}',
                        r'{"key":"\u0073k-proj-' + "x" * 30 + '"}'):
            with self.subTest(kind=content[:18]):
                (self.source / "jmh.log").write_text(content)
                self.assert_failure_without_output()

    def test_normal_source_symbols_do_not_trigger_key_detection(self):
        (self.source / "jmh.log").write_text(
            "io.ominicat.core.WorkerThreads$TaskExecutor\nSynchronousSink.accept\n"
            "/opt/homebrew/openjdk.jdk/Contents/Home/bin/java\n/opt/java/bin/java\n")
        status, _, error = self.run_package()
        self.assertEqual(0, status, error)

    def test_bad_recorded_timestamp_is_not_echoed(self):
        load = json.loads((self.source / "load.json").read_text())
        load["recordedAt"] = "private-recorded-value"
        self.write_json("load.json", load)
        error = self.assert_failure_without_output()
        self.assertNotIn("private-recorded-value", error)

    def test_unapproved_event_categories_are_rejected(self):
        self.write_events([{"type": "jdk.InitialEnvironmentVariable", "values": {}}])
        self.assert_failure_without_output()

    def test_empty_decompressed_content_is_rejected(self):
        (self.source / "jfr-events.json.gz").write_bytes(gzip.compress(b" \n", mtime=0))
        self.assert_failure_without_output()

    def test_commit_is_required_and_local_override_supported(self):
        self.assert_failure_without_output(commit=None)
        self.assert_failure_without_output(commit="short")
        status, _, error = self.run_package("--commit", "c" * 40, commit=None)
        self.assertEqual(0, status, error)
        self.assertEqual("c" * 40, json.loads((self.output / "metadata.json").read_text())["gitCommit"])

    def test_nonfinite_json_and_metric_scores_are_rejected(self):
        path = self.source / "jmh.json"
        original = path.read_text()
        for replacement in ("NaN", "Infinity", "1e999", '"NaN"', "-0.00001"):
            with self.subTest(replacement=replacement):
                path.write_text(original.replace('"score": 7.1', f'"score": {replacement}'))
                self.assert_failure_without_output()

    def test_manifest_source_and_artifact_provenance_are_checked(self):
        original = json.loads((self.source / "run-manifest.json").read_text())
        for changes in ({"sourceSha256": "d" * 64}, {"benchmarkArtifactSha256": "not-a-hash"},
                        {"benchmarkArtifact": "../private.jar"}, {"mode": "unknown"}):
            with self.subTest(changes=changes):
                self.write_json("run-manifest.json", dict(original, **changes))
                self.assert_failure_without_output()

    def test_invalid_load_metrics_are_rejected(self):
        path = self.source / "load.json"
        original = json.loads(path.read_text())
        for key, value in (("sessions", True), ("stages", 0), ("rejected", -1),
                           ("elapsedSeconds", 0), ("throughputFramesPerSecond", float("nan"))):
            with self.subTest(key=key):
                self.write_json("load.json", dict(original, **{key: value}))
                self.assert_failure_without_output()

    def test_summary_cannot_modify_source_or_invalidate_package_checksums(self):
        for path in (self.source / "report.md", self.output / "jmh.log"):
            with self.subTest(path=path.name):
                self.assert_failure_without_output("--summary", str(path))

    def test_run_url_cannot_include_credentials_or_query(self):
        for url in ("https://private-user:secret@github.com/example/project/actions/runs/1",
                    "https://github.com/example/project/actions/runs/1?token=secret"):
            with self.subTest(query="?" in url):
                error = self.assert_failure_without_output("--run-url", url)
                self.assertNotIn("secret", error)


if __name__ == "__main__":
    unittest.main()
