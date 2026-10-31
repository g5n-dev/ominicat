"""Synthetic statistics/phase fixtures only; no real pipeline or profiler run."""

import csv
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest


spec = importlib.util.spec_from_file_location("profile_python", Path(__file__).resolve().parents[1] / "profile_python.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ProfileSanitizerTest(unittest.TestCase):
    def test_trusted_stdlib_symlink_matches_real_code_without_guessing_version_fragments(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            real = base / "Cellar/runtime/lib/python3.12"
            real.mkdir(parents=True)
            link = base / "opt-python"
            link.symlink_to(real, target_is_directory=True)
            for filename in (real / "asyncio/base_events.py", link / "asyncio/base_events.py"):
                self.assertEqual("stdlib/asyncio/base_events.py", module.sanitize_file(
                    str(filename), root="/known/repo", stdlib_roots=[str(link)]))
            for filename in (base / "private/python3.12/asyncio/base_events.py",
                             real.parent / "python3.12-untrusted/asyncio/base_events.py"):
                self.assertRegex(module.sanitize_file(str(filename), root="/known/repo", stdlib_roots=[str(link)]),
                                 r"^external/[0-9a-f]{16}$")

    def test_known_roots_preserve_only_relative_code_locations(self):
        root = "/Users/example/private/ominicat"
        roots = ["/Library/Frameworks/Python.framework/Versions/3.12/lib/python3.12"]
        cases = {
            root + "/comparison/pipecat/benchmark.py": "comparison/pipecat/benchmark.py",
            root + "/reports/local/venv/lib/python3.12/site-packages/pipecat/pipeline/worker.py": "site-packages/pipecat/pipeline/worker.py",
            roots[0] + "/asyncio/events.py": "stdlib/asyncio/events.py",
            r"C:\Users\example\venv\Lib\site-packages\pipecat\frames\frames.py": "site-packages/pipecat/frames/frames.py",
            "comparison/profile_python.py": "comparison/profile_python.py",
            "~": "[native]", "<frozen _collections_abc>": "<frozen _collections_abc>",
        }
        for source, expected in cases.items():
            self.assertEqual(expected, module.sanitize_file(source, root=root, stdlib_roots=roots))
        self.assertEqual("stdlib/asyncio/tasks.py", module.sanitize_file(
            r"c:\python\lib\asyncio\tasks.py", root=root, stdlib_roots=["C:/Python/Lib"]))

    def test_unknown_private_windows_traversal_and_dynamic_paths_become_opaque(self):
        cases = ["/Users/example/secret.py", "/home/private/person.py", r"C:\Users\example\private.py",
                 "/other/site-packages/../../../Users/example/private.py", "../../private.py",
                 "<string /Users/example/private.py>", "private\nfilename.py"]
        for source in cases:
            clean = module.sanitize_file(source, root="/known/repo", stdlib_roots=[])
            self.assertRegex(clean, r"^external/[0-9a-f]{16}$")
            self.assertNotIn("example", clean)
        self.assertNotEqual(module.sanitize_file(cases[0]), module.sanitize_file(cases[1]))
        self.assertEqual("<method read at <address>>", module.sanitize_symbol("<method read at 0x123a>"))
        self.assertRegex(module.sanitize_symbol("code /Users/example/private.py"), r"^symbol-[a-f0-9]{16}$")

    def fixture(self):
        return SimpleNamespace(total_tt=1.0, total_calls=9, prim_calls=7, stats={
            ("/Users/example/repo/comparison/pipecat/benchmark.py", 12, "accept"): (2, 3, .2, .8, {("/Users/example/caller.py", 1, "private"): (1, 1, .1, .1)}),
            ("/Users/example/venv/site-packages/pipecat/pipeline/worker.py", 50, "process_frame"): (3, 4, .5, .7, {}),
            ("~", 0, "<built-in method read>"): (2, 2, .3, .3, {}),
        })

    def test_rankings_retain_exact_stats_and_never_export_callers(self):
        result = module.summarize(self.fixture(), root="/Users/example/repo", stdlib_roots=[], top=2)
        self.assertEqual(["process_frame", "<built-in method read>"], [row["function"] for row in result["topSelf"]])
        self.assertEqual(["accept", "process_frame"], [row["function"] for row in result["topCumulative"]])
        self.assertEqual(50, result["topSelf"][0]["selfSharePercent"])
        self.assertEqual(.8, result["topCumulative"][0]["cumulativeSeconds"])
        encoded = json.dumps(result)
        self.assertNotIn("/Users", encoded)
        self.assertNotIn("example", encoded)
        self.assertNotIn("caller", encoded)

    def test_nonfinite_and_negative_stats_fail_instead_of_publishing(self):
        for invalid in (float("nan"), float("inf"), -1.0):
            stats = self.fixture()
            stats.stats[("~", 0, "bad")] = (1, 1, invalid, .1, {})
            with self.assertRaises(module.ProfileError):
                module.summarize(stats)
        stats = self.fixture()
        stats.total_tt = 0
        with self.assertRaises(module.ProfileError):
            module.summarize(stats)

    def test_csv_and_json_have_scope_no_raw_binary_or_personal_paths(self):
        summary = {"scope": module.SCOPE, **module.summarize(self.fixture(), root="/Users/example/repo", stdlib_roots=[])}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            module.write_summaries(summary, output)
            self.assertEqual({"summary.json", "summary.csv", "profile.md"}, {path.name for path in output.iterdir()})
            for path in output.iterdir():
                content = path.read_text(encoding="utf-8")
                self.assertNotIn("/Users", content)
                self.assertNotIn("example", content)
            rows = list(csv.DictReader(io.StringIO((output / "summary.csv").read_text())))
            self.assertEqual(6, len(rows))
            self.assertEqual({"topSelf", "topCumulative"}, {row["ranking"] for row in rows})
        self.assertEqual("'=formula", module._csv_cell("=formula"))

    def test_raw_output_rejects_public_locations_and_nonempty_reuse(self):
        with self.assertRaises(module.ProfileError):
            module.local_output(module.ROOT / "reports/public/profile")
        with tempfile.TemporaryDirectory(dir=module.ROOT / "reports/local") as directory:
            output = Path(directory)
            self.assertEqual(output, module.local_output(output))
            (output / "old.prof").write_text("old", encoding="utf-8")
            with self.assertRaises(module.ProfileError):
                module.local_output(output)


class PhaseBoundaryTest(unittest.TestCase):
    def test_only_measure_enables_and_teardown_disables_before_io(self):
        events = []
        profiler = SimpleNamespace(enable=lambda: events.append("enable"), disable=lambda: events.append("disable"))
        capture = module.PhaseCapture(profiler, lambda phase: events.append("flush:" + phase))
        capture("warmup")
        self.assertFalse(capture.enabled)
        capture("measure")
        self.assertTrue(capture.enabled)
        capture("teardown")
        capture.complete()
        self.assertEqual(["flush:warmup", "flush:measure", "enable", "disable", "flush:teardown"], events)

    def test_failure_stop_disables_capture_and_incomplete_sequence_cannot_succeed(self):
        events = []
        profiler = SimpleNamespace(enable=lambda: events.append("enable"), disable=lambda: events.append("disable"))
        capture = module.PhaseCapture(profiler, lambda phase: None)
        with self.assertRaises(module.ProfileError):
            capture("measure")
        capture("warmup")
        capture("measure")
        capture.stop()
        capture.stop()
        self.assertEqual(["enable", "disable"], events)
        with self.assertRaises(module.ProfileError):
            capture.complete()


if __name__ == "__main__":
    unittest.main()
