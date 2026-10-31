"""Publication boundaries for synthetic, separate diagnostic profiles.

No profiler, framework, JVM, network or plotting dependency is executed. Inputs
are temporary synthetic files; the real validators and allowlist copier run.
"""
from copy import deepcopy
import gzip
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from test_evidence import evidence, load_module, report, suite_fixture, write_suite


diagnostics = load_module("comparison_diagnostics_fixture", "package_diagnostics.py", {"evidence": evidence})


def json_bytes(value):
    return json.dumps(value, sort_keys=True, allow_nan=False).encode() + b"\n"


def java_fixture(folder, suite, engine):
    folder.mkdir(parents=True)
    result = deepcopy(next(item["result"] for item in suite["results"]
                           if item["engine"] == engine and item["stages"] == 4))
    for name in diagnostics.JAVA_FILES:
        if name == "jfr-events.json.gz":
            data = gzip.compress(json_bytes({"recording": {"events": [{"type": "jdk.ExecutionSample", "values": {}}]}}), mtime=0)
        elif name == "result.json":
            data = json_bytes(result)
        elif name == "load.json":
            data = json_bytes({"schemaVersion": 1, "kind": "queued-comparison-jfr-context", "engine": engine,
                               "environment": {"javaVersion": "17.0.21"}})
        elif name.endswith(".svg"):
            data = b'<svg xmlns="http://www.w3.org/2000/svg"><text>synthetic diagnostic</text></svg>\n'
        else:
            data = b"Separate whole-recording diagnostics; instrumented and never headline timing.\n"
        (folder / name).write_bytes(data)
    context = {"schemaVersion": 1, "status": "complete", "kind": "separate-profiled-diagnostics",
               "engine": engine, "stages": 4, "headlineTiming": False,
               "benchmarkArtifactSha256": suite["manifest"]["javaArtifactSha256"], "result": result,
               "artifactSha256": {name: evidence.digest((folder / name).read_bytes()) for name in diagnostics.JAVA_FILES}}
    (folder / "diagnostic-summary.json").write_bytes(json_bytes(context))
    return context


def update_java_hash(folder, context, name, data):
    (folder / name).write_bytes(data)
    context["artifactSha256"][name] = evidence.digest(data)
    (folder / "diagnostic-summary.json").write_bytes(json_bytes(context))


def python_fixture(folder, suite):
    folder.mkdir(parents=True)
    row = {"file": "site-packages/pipecat/processors/frame_processor.py", "line": 20,
           "function": "process_frame", "primitiveCalls": 10, "totalCalls": 10,
           "selfSeconds": .2, "cumulativeSeconds": .3, "selfSharePercent": 100}
    context = {"schemaVersion": 1, "engine": "pipecat", "version": "1.15.0", "profileType": "cProfile",
               "recordedAt": "2026-10-31T00:00:00Z", "scope": "Separate instrumented measure-only diagnostic, never headline timing.",
               "instrumented": True, "headlineEligible": False,
               "adapterSha256": suite["manifest"]["sourceFingerprint"]["files"]["comparison/pipecat/benchmark.py"],
               "capture": {"phases": ["warmup", "measure", "teardown"], "startupIncluded": False,
                           "warmupIncluded": False, "teardownIncluded": False},
               "workload": {"mode": "queued-w1", "stages": 4, "warmupSecondsRequested": 3,
                            "measureSecondsRequested": 5, "measuredFrames": 50, "instrumentedElapsedSeconds": 5.001},
               "runtime": {"implementation": "CPython", "version": "3.12.12", "backend": "asyncio"},
               "totalSelfSeconds": .2, "functionCount": 1, "totalCalls": 10, "primitiveCalls": 10,
               "topSelf": [row], "topCumulative": [deepcopy(row)]}
    (folder / "summary.json").write_bytes(json_bytes(context))
    (folder / "summary.csv").write_text("ranking,rank,file,function,selfSeconds\ntopSelf,1,site-packages/pipecat/processors/frame_processor.py,process_frame,0.2\n")
    (folder / "profile.md").write_text("# Separate instrumented profile\nNever headline timing.\n")
    return context


class DiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.suite = suite_fixture()
        self.java = self.root / "java" / "ominicat"
        self.python = self.root / "python"
        self.java_context = java_fixture(self.java, self.suite, "ominicat")
        self.python_context = python_fixture(self.python, self.suite)

    def validate_java(self):
        # The shared profile guard is imported by the production packager. Keep
        # its search-path adjustment local to this call, even in combined suites.
        with mock.patch.object(sys, "path", sys.path.copy()):
            return diagnostics.validate_java(self.java, "ominicat", self.suite["manifest"]["javaArtifactSha256"],
                                             self.suite["manifest"]["settings"])

    def test_safe_contexts_pass_without_inventing_headline_eligibility(self):
        self.assertIs(self.validate_java()["headlineTiming"], False)
        self.assertIs(diagnostics.validate_python(self.python, self.suite)["headlineEligible"], False)

    def test_changed_java_artifact_is_rejected_before_publication(self):
        for name in ("result.json", "cpu-hotspots.svg", "jfr-events.json.gz"):
            with self.subTest(name=name):
                original = (self.java / name).read_bytes()
                (self.java / name).write_bytes(original + b"tampered")
                with self.assertRaises(ValueError):
                    self.validate_java()
                (self.java / name).write_bytes(original)

    def test_java_must_bind_the_actual_measured_artifact_and_remain_diagnostic(self):
        for key, value in (("benchmarkArtifactSha256", "0" * 64), ("headlineTiming", True),
                           ("kind", "headline"), ("engine", "camel"), ("status", "failed")):
            with self.subTest(key=key):
                changed = deepcopy(self.java_context)
                changed[key] = value
                (self.java / "diagnostic-summary.json").write_bytes(json_bytes(changed))
                with self.assertRaises(ValueError):
                    self.validate_java()
        (self.java / "diagnostic-summary.json").write_bytes(json_bytes(self.java_context))

    def test_unknown_jfr_categories_are_rejected_even_with_a_matching_file_hash(self):
        data = gzip.compress(json_bytes({"recording": {"events": [{"type": "jdk.InitialSystemProperty", "values": {}}]}}), mtime=0)
        update_java_hash(self.java, self.java_context, "jfr-events.json.gz", data)
        with self.assertRaises(ValueError):
            self.validate_java()

    def test_symlink_allowlist_entries_are_not_followed(self):
        for folder, name, validate in ((self.java, "report.md", self.validate_java),
                                       (self.python, "summary.csv", lambda: diagnostics.validate_python(self.python, self.suite))):
            with self.subTest(name=name):
                original = (folder / name).read_bytes()
                target = self.root / "synthetic-private-input"
                target.write_bytes(original)
                (folder / name).unlink()
                (folder / name).symlink_to(target)
                with self.assertRaises(ValueError):
                    validate()

    def test_every_published_java_text_is_privacy_checked_after_integrity_validation(self):
        for name, marker in (("report.md", b"/Users/synthetic-private/example"),
                             ("cpu-hotspots.svg", b"&#x2f;Users&#x2f;synthetic-private&#x2f;example"),
                             ("load.json", b"/Users/synthetic-private/example"),
                             ("jfr-summary.txt", b"/Users/synthetic-private/example")):
            with self.subTest(name=name):
                original = (self.java / name).read_bytes()
                # Rebind the byte hash: this must exercise privacy, not merely
                # the earlier integrity check. Never use an actual private path.
                update_java_hash(self.java, self.java_context, name, original + b"\n" + marker + b"\n")
                with self.assertRaises(ValueError) as error:
                    self.validate_java()
                self.assertNotIn("synthetic-private", str(error.exception))
                update_java_hash(self.java, self.java_context, name, original)

    def test_python_rejects_absolute_traversal_and_windows_code_paths(self):
        for location in ("/Users/synthetic-private/code.py", "/home/synthetic-private/code.py",
                         "../private.py", "C:\\Users\\synthetic-private\\code.py", "C:/Users/synthetic-private/code.py"):
            with self.subTest(location=location):
                changed = deepcopy(self.python_context)
                changed["topSelf"][0]["file"] = location
                (self.python / "summary.json").write_bytes(json_bytes(changed))
                with self.assertRaises(ValueError) as error:
                    diagnostics.validate_python(self.python, self.suite)
                self.assertNotIn("synthetic-private", str(error.exception))

    def test_python_csv_markdown_and_symbols_cannot_bypass_privacy_checks(self):
        for name in ("summary.csv", "profile.md", "summary.json"):
            with self.subTest(name=name):
                original = (self.python / name).read_bytes()
                if name == "summary.json":
                    changed = deepcopy(self.python_context)
                    changed["topSelf"][0]["function"] = "/Users/synthetic-private/callable"
                    data = json_bytes(changed)
                else:
                    data = original + b"\nBearer synthetic-credential-for-negative-test\n"
                (self.python / name).write_bytes(data)
                with self.assertRaises(ValueError) as error:
                    diagnostics.validate_python(self.python, self.suite)
                self.assertNotIn("synthetic-credential", str(error.exception))
                self.assertNotIn("synthetic-private", str(error.exception))
                (self.python / name).write_bytes(original)

    def test_python_requires_measure_only_instrumented_capture_and_bound_source(self):
        for defect in ("adapter", "instrumented", "headline", "phases", "startup", "warmup", "teardown"):
            with self.subTest(defect=defect):
                changed = deepcopy(self.python_context)
                if defect == "adapter":
                    changed["adapterSha256"] = "0" * 64
                elif defect == "instrumented":
                    changed["instrumented"] = False
                elif defect == "headline":
                    changed["headlineEligible"] = True
                elif defect == "phases":
                    changed["capture"]["phases"] = ["measure", "teardown"]
                else:
                    changed["capture"][defect + "Included"] = True
                (self.python / "summary.json").write_bytes(json_bytes(changed))
                with self.assertRaises(ValueError):
                    diagnostics.validate_python(self.python, self.suite)

    def test_instrumented_result_cannot_be_accepted_as_a_headline_fork(self):
        for flag, value in (("instrumented", True), ("headlineEligible", False), ("headlineTiming", False)):
            with self.subTest(flag=flag):
                suite = deepcopy(self.suite)
                suite["results"][0]["result"][flag] = value
                # Guard the publication entry, preserving the validator source
                # that was fingerprinted during the real unprofiled runs.
                with self.assertRaises(ValueError):
                    report.summarize(suite)

    def test_publication_copies_only_allowlisted_files_and_generated_figures(self):
        source = self.root / "suite"
        source.mkdir()
        write_suite(source, self.suite)
        java_fixture(self.root / "java" / "camel", self.suite, "camel")
        for folder in (self.java, self.root / "java" / "camel", self.python):
            for forbidden in ("recording.jfr", "python.prof", "adapter.log", "gc.log", "jfr-events.json", "unreviewed.txt"):
                (folder / forbidden).write_text("synthetic raw input must remain local")
        output = self.root / "published"

        def plot(context, destination):
            (destination / "call-hotspots.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg"/>')
            (destination / "call-hotspots.png").write_bytes(b"synthetic generated figure")

        arguments = ["package_diagnostics.py", "--suite", str(source), "--java-input", str(self.root / "java"),
                     "--python-input", str(self.python), "--output", str(output)]
        with mock.patch.object(sys, "argv", arguments), mock.patch.object(sys, "path", sys.path.copy()), \
                mock.patch.object(diagnostics, "plot_python", side_effect=plot), mock.patch("sys.stdout", new_callable=io.StringIO):
            diagnostics.main()
        expected = {f"{engine}/{name}" for engine in ("ominicat", "camel")
                    for name in diagnostics.JAVA_FILES + ("diagnostic-summary.json",)}
        expected.update("pipecat/" + name for name in diagnostics.PYTHON_FILES + ("call-hotspots.svg", "call-hotspots.png"))
        expected.update(("README.md", "SHA256SUMS"))
        actual = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
        self.assertEqual(actual, expected)
        self.assertIn("do not replace any of the 45 unprofiled timing results", (output / "README.md").read_text())
        for engine in ("ominicat", "camel"):
            original = json.loads((self.root / "java" / engine / "result.json").read_text())
            published = json.loads((output / engine / "result.json").read_text())
            self.assertIs(published["instrumented"], True)
            self.assertIs(published["headlineEligible"], False)
            for key in ("measuredFrames", "elapsedSeconds", "throughputFramesPerSecond", "latencyMicros", "processCpuSeconds"):
                self.assertEqual(published[key], original[key], "postprocessing must label diagnostics without changing measured values")
        for line in (output / "SHA256SUMS").read_text().splitlines():
            checksum, name = line.split("  ", 1)
            self.assertEqual(evidence.digest((output / name).read_bytes()), checksum)


if __name__ == "__main__":
    unittest.main()
