"""Independent chart/data oracles and a fake-command collection flow; no JVM runs."""
import collections
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import summarize_profiles as charts


ROOT = Path(__file__).resolve().parents[1]
NS = {"s": "http://www.w3.org/2000/svg"}
FIGURES = {"cpu-flamegraph.svg", "allocation-flamegraph.svg", "cpu-hotspots.svg",
           "allocation-hotspots.svg", "gc-pauses.svg"}


def frame(name):
    return {"method": {"type": {"name": "example/" + name}, "name": "work"}}


def sample(kind="jdk.ExecutionSample", depth=1, weight=1, truncated=False):
    return {"type": kind, "values": {"startTime": "2026-10-31T00:00:00Z",
        "stackTrace": {"frames": [frame("Level" + str(i)) for i in range(depth)], "truncated": truncated},
        "weight": weight, "objectClass": {"name": "example/Allocated"}}}


def gc(start="2026-10-31T00:00:00.500Z", pause="PT0.00125S"):
    return {"type": "jdk.GarbageCollection", "values": {"startTime": start,
            "sumOfPauses": pause, "duration": "PT10S", "gcId": 7, "name": "G1New", "cause": "synthetic"}}


class ProfileChartsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)

    def inputs(self, events, compressed=False):
        data = json.dumps({"recording": {"events": events}}).encode()
        if compressed:
            (self.folder / "jfr-events.json.gz").write_bytes(gzip.compress(data, mtime=0))
        else:
            (self.folder / "jfr-events.json").write_bytes(data)
        (self.folder / "load.json").write_text(json.dumps({"environment": {"javaVersion": "17.0.21"}}))
        return data

    def test_every_supplied_frame_survives_beyond_old_depth_limits_and_fits_canvas(self):
        svg = charts.flamegraph([sample(depth=80, truncated=True)], "jdk.ExecutionSample", False)
        root = ET.fromstring(svg)
        groups = root.findall("s:g", NS)
        self.assertEqual(81, len(groups))
        self.assertEqual(80, max(int(node.get("data-depth")) for node in groups))
        titles = [node.find("s:title", NS).text for node in groups]
        self.assertTrue(any("Level0.work" in title for title in titles))
        self.assertTrue(any("Level79.work" in title for title in titles))
        self.assertIn("source-marked truncated stacks 1", svg)
        width, height = float(root.get("width")), float(root.get("height"))
        for node in groups:
            rect = node.find("s:rect", NS)
            x, y = float(rect.get("x")), float(rect.get("y"))
            self.assertGreaterEqual(y, 145)
            self.assertGreater(float(rect.get("width")), 0)
            self.assertLessEqual(x + float(rect.get("width")), width + 1e-6)
            self.assertLessEqual(y + float(rect.get("height")), height)
            text = node.find("s:text", NS)
            if text is not None:
                self.assertGreaterEqual(int(text.get("font-size")), 12)

    def test_subpixel_stacks_and_unavailable_stacks_retain_their_weight(self):
        large = sample("jdk.ObjectAllocationSample", weight=10 ** 12)
        tiny = sample("jdk.ObjectAllocationSample", weight=1)
        tiny["values"]["stackTrace"]["frames"] = [frame("Tiny")]
        missing = sample("jdk.ObjectAllocationSample", depth=0, weight=3)
        root = ET.fromstring(charts.flamegraph([large, tiny, missing], "jdk.ObjectAllocationSample", True))
        groups = root.findall("s:g", NS)
        by_title = {g.find("s:title", NS).text: g for g in groups}
        tiny_group = next(g for title, g in by_title.items() if "Tiny.work" in title)
        self.assertGreater(float(tiny_group.find("s:rect", NS).get("width")), 0)
        self.assertLess(float(tiny_group.find("s:rect", NS).get("width")), 1)
        self.assertEqual("0", tiny_group.find("s:rect", NS).get("stroke-width"))
        self.assertTrue(any("[stack unavailable]: 3" in title for title in by_title))
        self.assertEqual(10 ** 12 + 4, int(groups[0].get("data-weight")))

    def test_empty_and_zero_weight_figures_do_not_pretend_to_have_samples(self):
        svg = charts.flamegraph([], "jdk.ExecutionSample", False)
        self.assertIn("No sampled events", svg)
        self.assertFalse(ET.fromstring(svg).findall("s:g", NS))
        unavailable = charts.flamegraph([sample(depth=0)], "jdk.ExecutionSample", False)
        self.assertIn("observed depth 0", unavailable)
        self.assertIn("[stack unavailable]", unavailable)
        self.assertIn("No positive sampled allocation weight", charts.flamegraph(
            [sample("jdk.ObjectAllocationSample", weight=0)], "jdk.ObjectAllocationSample", True))
        gc_svg, pauses = charts.gc_timeline([])
        self.assertEqual([], pauses)
        self.assertIn("No GC collection events recorded", gc_svg)
        self.assertFalse(ET.fromstring(gc_svg).findall("s:g", NS))

    def test_svg_identifiers_and_tooltips_are_escaped(self):
        event = sample()
        event["values"]["stackTrace"]["frames"] = [frame('A<&"quoted')]
        svg = charts.flamegraph([event], "jdk.ExecutionSample", False)
        root = ET.fromstring(svg)
        self.assertIn('example.A<&"quoted.work', " ".join(node.text or "" for node in root.findall(".//s:title", NS)))
        self.assertNotIn('<A<', svg)
        self.assertIn('&lt;', svg)
        summary = charts.hotspot_chart(collections.Counter({'<script>&"': 2}), "Safe", "Synthetic", "samples")
        ET.fromstring(summary)
        self.assertNotIn('<script>', summary)

    def test_compact_top_eight_plus_other_conserves_samples_and_uses_integer_counts(self):
        counter = collections.Counter({"method-" + str(i): i + 1 for i in range(20)})
        svg = charts.hotspot_chart(counter, "CPU", "Leaf counts, not duration", "samples")
        groups = ET.fromstring(svg).findall("s:g", NS)
        self.assertEqual(9, len(groups))
        self.assertEqual(sum(counter.values()), sum(int(g.get("data-weight")) for g in groups))
        self.assertTrue(any(g.find("s:title", NS).text.startswith("Other:") for g in groups))
        self.assertIn("total 210 samples", svg)
        self.assertNotIn("210.000 samples", svg)
        allocation = charts.hotspot_chart(collections.Counter({"Class": 1024 ** 2}), "Allocation", "Weights", "MiB", 1024 ** 2)
        self.assertIn("1.000 MiB", allocation)

    def test_gc_uses_pause_sum_and_supplied_event_timeline_instead_of_gc_duration(self):
        first, last = gc(), gc("2026-10-31T08:00:01.500+08:00", "PT0S")
        svg, pauses = charts.gc_timeline([last, sample(), first])
        self.assertEqual([0, 1.25], pauses)
        groups = ET.fromstring(svg).findall("s:g", NS)
        self.assertEqual([0.5, 1.5], [float(g.get("data-start-seconds")) for g in groups])
        self.assertEqual([1.25, 0], [float(g.get("data-pause-ms")) for g in groups])
        self.assertIn("not GC duration", svg)
        self.assertIn("Positions do not identify the measured-load interval", svg)
        self.assertIn("pause sum 1.250 ms", svg)

    def test_duration_and_time_validation_do_not_silently_make_bad_data_zero(self):
        self.assertEqual(61.5, charts.seconds("PT1M1.5S"))
        for invalid in ("PT", "PTNaNS", "not-a-duration", None, "PT" + "9" * 400 + "S"):
            with self.subTest(duration=invalid):
                with self.assertRaises(ValueError):
                    charts.gc_timeline([gc(pause=invalid)])
        with self.assertRaises(ValueError):
            charts.gc_timeline([gc(start="2026-10-31T00:00:00")])

    def test_gzip_only_regeneration_outputs_all_five_figures_and_preserves_input(self):
        events = [sample(depth=9), sample("jdk.ObjectAllocationSample", depth=12, weight=900), gc()]
        data = self.inputs(events, compressed=True)
        charts.main(self.folder)
        self.assertEqual(data, gzip.decompress((self.folder / "jfr-events.json.gz").read_bytes()))
        for name in FIGURES:
            ET.parse(self.folder / name)
            self.assertIn(name, (self.folder / "profiles.md").read_text())
        self.assertIn("not available on this runtime", (self.folder / "profiles.md").read_text())
        self.assertNotIn("[GC log]", (self.folder / "profiles.md").read_text())

    def test_invalid_or_unsafe_input_is_rejected_before_creating_new_outputs(self):
        for events in ([gc(pause="invalid")], [{"type": "jdk.InitialEnvironmentVariable", "values": {}}],
                       [sample("jdk.ObjectAllocationSample", weight=-1)]):
            self.inputs(events)
            with self.assertRaises(ValueError):
                charts.main(self.folder)
            self.assertFalse((self.folder / "profiles.md").exists())
            self.assertFalse(any(self.folder.glob("*.svg")))
        self.inputs([sample()])
        (self.folder / "jfr-events.json").write_text('{"private":"Bearer synthetic-private-value"}')
        with self.assertRaisesRegex(ValueError, "refused"):
            charts.main(self.folder)
        self.assertFalse(any(self.folder.glob("*.svg")))

    def test_cli_bad_gzip_or_structure_has_no_traceback_input_or_private_path(self):
        bad_stack = sample()
        bad_stack["values"]["stackTrace"] = "private-invalid-stack"
        bad_class = sample("jdk.ObjectAllocationSample")
        bad_class["values"]["objectClass"]["name"] = 42
        cases = [b"\x1f\x8b", b"\x1f\x8b\x08\x00" + b"\x00" * 6 + b"\xff" * 10]
        for event in (bad_stack, bad_class):
            cases.append(gzip.compress(json.dumps({"recording": {"events": [event]}}).encode(), mtime=0))
        for index, content in enumerate(cases):
            with self.subTest(case=index):
                (self.folder / "jfr-events.json.gz").write_bytes(content)
                result = subprocess.run([sys.executable, str(ROOT / "scripts/summarize_profiles.py"), str(self.folder)],
                                        cwd=self.folder, capture_output=True, text=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Profile summary failed", result.stderr)
                for private in ("Traceback", str(ROOT), str(self.folder), "private-invalid-stack"):
                    self.assertNotIn(private, result.stderr)
                self.assertFalse((self.folder / "profiles.md").exists())
                self.assertFalse(any(self.folder.glob("*.svg")))

    def test_nonlocal_collection_generates_before_moving_raw_and_can_regenerate_from_gzip(self):
        """Exercise actual shell/report/render flow using only fake command producers."""
        root = self.folder
        scripts = root / "scripts"
        scripts.mkdir()
        for name in ("profile.sh", "report.py", "profile_evidence.py", "summarize_profiles.py", "package_profiles.py"):
            shutil.copy2(ROOT / "scripts" / name, scripts / name)
        for module in ("ominicat-core", "ominicat-benchmarks"):
            source = root / module / "src/main/java/Fixture.java"
            source.parent.mkdir(parents=True)
            source.write_text("synthetic source fixture, never compiled")
            (root / module / "pom.xml").write_text("synthetic fixture")
        (root / "pom.xml").write_text("synthetic fixture")
        for name in ("jmh.json", "load.json"):
            shutil.copy2(ROOT / "reports/java17/kernel" / name, root / ("fixture-" + name))
        (root / "fixture-events.json").write_text(json.dumps({"recording": {"events": [sample(), gc()]}}))
        mvn = root / "mvnw"
        mvn.write_text("#!/usr/bin/env python3\nfrom pathlib import Path\np=Path('ominicat-benchmarks/target/ominicat-benchmarks.jar')\np.parent.mkdir(parents=True)\np.write_bytes(b'synthetic artifact never executed')\n")
        mvn.chmod(0o755)
        jdk = root / "jdk/bin"
        jdk.mkdir(parents=True)
        java = jdk / "java"
        java.write_text('''#!/usr/bin/env python3
import json,sys
from pathlib import Path
args=sys.argv[1:]
root=Path.cwd()
if '-lp' in args:
    print('Benchmarks:')
    for name in ('PipelineBenchmark.dispatch','SessionBenchmark.roundTrip'):
        print('io.ominicat.benchmarks.'+name)
        print('  param "stages" = {1, 4, 16}')
elif '-rff' in args:
    Path(args[args.index('-rff')+1]).write_bytes((root/'fixture-jmh.json').read_bytes())
else:
    assert '-XX:FlightRecorderOptions=stackdepth=64' in args
    Path(args[args.index('--output')+1]).write_bytes((root/'fixture-load.json').read_bytes())
''')
        java.chmod(0o755)
        jfr = jdk / "jfr"
        jfr.write_text('''#!/usr/bin/env python3
import sys
from pathlib import Path
args=sys.argv[1:]
if args[0]=='configure':
    Path(args[args.index('--output')+1]).write_text('synthetic JFC fixture')
elif args[0]=='print':
    assert args[args.index('--stack-depth')+1]=='64'
    print(Path('fixture-events.json').read_text())
else:
    print('Synthetic JFR summary')
''')
        jfr.chmod(0o755)
        environment = dict(os.environ, JAVA_HOME=str(root / "jdk"))
        result = subprocess.run(["bash", str(scripts / "profile.sh"), "quick", "reports/latest"],
                                cwd=root, env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        destination = root / "reports/latest"
        self.assertFalse((destination / "jfr-events.json").exists())
        self.assertTrue((root / "reports/local/latest/jfr-events.json").exists())
        for name in FIGURES | {"jfr-events.json.gz", "profiles.md"}:
            self.assertTrue((destination / name).is_file(), name)
        rerun = subprocess.run([sys.executable, str(scripts / "summarize_profiles.py"), str(destination)],
                               cwd=root, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, rerun.returncode, rerun.stdout + rerun.stderr)


if __name__ == "__main__":
    unittest.main()
