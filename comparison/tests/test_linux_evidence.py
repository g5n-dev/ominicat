"""Synthetic Linux CI provenance, reporting and fixed resource-read boundaries.

No Linux host, reports/local directory, framework, build or network is needed.
Every output is confined to an independently owned TemporaryDirectory.
"""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from test_ci_context import UBUNTU, ci, collect
from test_evidence import collector_fixture, evidence, report, rebind_manifest, suite_fixture, write_suite


TARGETS = ("ubuntu-22.04", "ubuntu-24.04", "centos-stream-9")


def linux_fixture(target="ubuntu-24.04"):
    if target == "ubuntu-22.04":
        context = collect(target, expected_version="22.04", os_release_text=UBUNTU.replace("24.04", "22.04"))
    else:
        context = collect(target)
    suite = suite_fixture()
    suite["manifest"]["sourceRevision"] = context["sourceSha"]
    suite["manifest"]["sourceTreeDirty"] = False
    environment = suite["manifest"]["environment"]
    environment.update({"os": "Linux", "osRelease": context["kernelRelease"], "arch": "x86_64",
                        "logicalProcessors": 4, "cpuModel": "Synthetic Xeon", "cpuAffinityCount": 2,
                        "physicalMemoryBytes": 8 * 1024 ** 3, "libc": ["glibc", "2.34"],
                        "cgroupV2Limits": {"cpuQuotaMicros": 200_000, "cpuPeriodMicros": 100_000,
                                           "memoryMaxBytes": 512 * 1024 ** 2},
                        "context": "GitHub-hosted shared runner; no exclusive CPU isolation", "ci": context})
    for item in suite["results"]:
        if item["engine"] != "pipecat":
            item["result"]["environment"].update({"os": "Linux", "architecture": "amd64"})
    rebind_manifest(suite)
    return suite


class LinuxEvidenceTest(unittest.TestCase):
    def test_each_legal_linux_profile_roundtrips_with_bound_source_without_mutation(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                suite = linux_fixture(target)
                before = deepcopy(suite)
                with mock.patch.dict(sys.modules, {"ci_context": ci}):
                    self.assertIs(evidence.validate_suite(suite), suite)
                self.assertEqual(suite, before)
                folder = Path(temporary)
                write_suite(folder, suite)
                raw_before = {path.relative_to(folder): path.read_bytes() for path in folder.rglob("*") if path.is_file()}
                with mock.patch.dict(sys.modules, {"ci_context": ci}):
                    self.assertEqual(evidence.load_suite(folder), suite)
                self.assertEqual({path.relative_to(folder): path.read_bytes() for path in folder.rglob("*") if path.is_file()}, raw_before)

    def test_rehashed_manifest_cannot_hide_source_kernel_arch_or_os_conflicts(self):
        for target in TARGETS:
            for defect in ("context-source", "manifest-source", "context-kernel", "manifest-kernel",
                           "context-arch", "manifest-arch", "os", "extra-context"):
                with self.subTest(target=target, defect=defect):
                    suite = linux_fixture(target)
                    manifest = suite["manifest"]
                    environment = manifest["environment"]
                    context = environment["ci"]
                    if defect == "context-source":
                        context["sourceSha"] = "b" * 40
                    elif defect == "manifest-source":
                        manifest["sourceRevision"] = "b" * 40
                    elif defect == "context-kernel":
                        context["kernelRelease"] = "6.14.0-1014-azure"
                    elif defect == "manifest-kernel":
                        environment["osRelease"] = "6.14.0-1014-azure"
                    elif defect == "context-arch":
                        context["machine"] = "aarch64"
                    elif defect == "manifest-arch":
                        environment["arch"] = "aarch64"
                    elif defect == "os":
                        environment["os"] = "Darwin"
                    else:
                        context["unapprovedMetadata"] = "synthetic-unapproved-value"
                    # A valid outer SHA must not bypass semantic provenance.
                    rebind_manifest(suite)
                    self.assertEqual(evidence.digest(evidence.canonical(manifest)), suite["manifestSha256"])
                    self.assertTrue(all(item["manifestSha256"] == suite["manifestSha256"] for item in suite["results"]))
                    with mock.patch.dict(sys.modules, {"ci_context": ci}), self.assertRaises(ValueError):
                        evidence.validate_suite(suite)

    def test_original_mac_fixture_remains_compatible_without_ci_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            suite = suite_fixture()
            self.assertNotIn("ci", suite["manifest"]["environment"])
            before = deepcopy(suite)
            self.assertIs(evidence.validate_suite(suite), suite)
            self.assertEqual(suite, before)
            folder = Path(temporary)
            write_suite(folder, suite)
            self.assertEqual(evidence.load_suite(folder), suite)

    def test_linux_reports_name_actual_platform_and_pin_the_source_and_workflow_links(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                suite = linux_fixture(target)
                summary = report.summarize(suite)
                folder = Path(temporary)
                report.write_report(summary, suite, folder)
                content = (folder / "README.md").read_text()
                context = suite["manifest"]["environment"]["ci"]
                self.assertIn(context["distro"]["prettyName"], content)
                self.assertIn(context["kernelRelease"], content)
                self.assertIn("Synthetic Xeon", content)
                self.assertIn("[Workflow run](" + context["githubRunUrl"] + ")", content)
                repository = context["githubRunUrl"].split("/actions/runs/")[0]
                self.assertIn(repository + "/blob/" + context["sourceSha"] + "/comparison/README.md", content)
                self.assertNotIn("shared development host", content.lower())
                self.assertIn("independent hosts", content)
                if target == "centos-stream-9":
                    self.assertIn("container user space", content)
                    self.assertIn("kernel is shared with the runner", content)
                    self.assertIn(context["containerImage"], content)
                    self.assertNotIn("CentOS native kernel", content)
                else:
                    version = context["distro"]["version"]
                    self.assertIn("Native Ubuntu " + version, content)
                    other = "24.04" if version == "22.04" else "22.04"
                    self.assertNotIn("Native Ubuntu " + other, content)

    def test_ci_job_summary_retains_three_medians_artifact_scope_and_no_private_paths(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                suite = linux_fixture(target)
                for item in suite["results"]:
                    engine = evidence.ENGINES.index(item["engine"])
                    result = item["result"]
                    result["measuredFrames"] = 500 * (item["fork"] + engine)
                    result["throughputFramesPerSecond"] = result["measuredFrames"] / result["elapsedSeconds"]
                    result["latencyMicros"]["p99"] = 30 + item["fork"] + engine
                    item["resources"]["measuredPeakRssBytes"] = 20_000_000 + (item["fork"] + engine) * 1_000_000
                summary = report.summarize(suite)
                destination = Path(temporary) / "job-summary.md"
                report.write_ci_summary(summary, suite, destination)
                content = destination.read_text()
                context = suite["manifest"]["environment"]["ci"]
                self.assertIn("45 fresh processes", content)
                self.assertIn("90,000 measured outputs", content)
                for row in ("| OminiCat | 300 | 33.00 | 21.9 |", "| Pipecat | 400 | 34.00 | 22.9 |",
                            "| Apache Camel | 500 | 35.00 | 23.8 |"):
                    self.assertIn(row, content)
                self.assertIn("Five-process medians", content)
                self.assertIn("Artifacts section", content)
                self.assertIn("checksummed artifact", content)
                self.assertIn("profiled timings are excluded", content)
                self.assertIn("independent hosts", content)
                self.assertIn(context["kernelScope"], content)
                self.assertIn(context["sourceSha"], content)
                self.assertIn(context["githubRunUrl"], content)
                # Public per-process JSON is legitimate evidence. Private raw
                # recordings/logs and their local filesystem locations are not.
                for private in ("/Users/", "/home/", "/private/", "reports/local", "recording.jfr", "python.prof", "adapter.log"):
                    self.assertNotIn(private, content)
                self.assertNotIn("shared development host", content.lower())


class LinuxResourceTest(unittest.TestCase):
    def test_resource_probe_reads_only_fixed_public_fields_and_reports_limits_separately(self):
        collector = collector_fixture()
        inputs = {"/proc/cpuinfo": "processor : 0\nmodel name : Synthetic Xeon @ 2.40GHz\nprivateField : never-export\n",
                  "/sys/fs/cgroup/cpu.max": "200000 100000\n", "/sys/fs/cgroup/memory.max": "536870912\n"}
        reads = []

        def read(path, *args, **kwargs):
            name = path.as_posix()
            reads.append(name)
            self.assertIn(name, inputs)
            return inputs[name]

        with mock.patch.object(collector.platform, "system", return_value="Linux"), \
                mock.patch.object(collector.platform, "processor", return_value="unknown"), \
                mock.patch.object(collector.platform, "libc_ver", return_value=("glibc", "2.34")), \
                mock.patch.object(collector.os, "sched_getaffinity", return_value={2, 3}, create=True), \
                mock.patch.object(Path, "read_text", new=read):
            result = collector.host_resources()
        self.assertEqual(reads, list(inputs))
        self.assertEqual(result["cpuModel"], "Synthetic Xeon @ 2.40GHz")
        self.assertEqual(result["cpuAffinityCount"], 2)
        self.assertEqual(result["libc"], ["glibc", "2.34"])
        self.assertEqual(result["cgroupV2Limits"], {"cpuQuotaMicros": 200_000, "cpuPeriodMicros": 100_000,
                                                  "memoryMaxBytes": 536_870_912})
        self.assertNotIn("never-export", json.dumps(result))
        self.assertNotIn("privateField", json.dumps(result))

    def test_unreadable_cgroups_are_unknown_instead_of_zero_resource_budgets(self):
        collector = collector_fixture()
        with mock.patch.object(collector.platform, "system", return_value="Linux"), \
                mock.patch.object(collector.platform, "processor", return_value="unknown"), \
                mock.patch.object(collector.platform, "libc_ver", return_value=("glibc", "2.34")), \
                mock.patch.object(collector.os, "sched_getaffinity", return_value={0, 1}, create=True), \
                mock.patch.object(Path, "read_text", side_effect=OSError("synthetic unavailable field")):
            result = collector.host_resources()
        self.assertEqual(result["cgroupV2Limits"], {"cpuQuotaMicros": None, "cpuPeriodMicros": None, "memoryMaxBytes": None})
        self.assertIn("unlimited or unavailable", result["resourceScope"])
        self.assertEqual(result["cpuAffinityCount"], 2)


if __name__ == "__main__":
    unittest.main()
