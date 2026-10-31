"""Synthetic Linux/GitHub metadata fixtures; no host environment is exported."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("ci_context", Path(__file__).resolve().parents[1] / "ci_context.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)

UBUNTU = 'NAME="Ubuntu"\nID=ubuntu\nVERSION_ID="24.04"\nPRETTY_NAME="Ubuntu 24.04.3 LTS"\nHOME_URL="https://ubuntu.com/"\n'
CENTOS = 'NAME="CentOS Stream"\nID=centos\nVERSION_ID="9"\nPRETTY_NAME="CentOS Stream 9"\nHOME_URL="https://centos.org/"\n'


def github():
    return {"CI": "true", "GITHUB_ACTIONS": "true", "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_REPOSITORY": "g5n-dev/ominicat", "GITHUB_RUN_ID": "12345678", "GITHUB_SHA": "a" * 40,
            "SECRET_TOKEN": "never-export-synthetic-token", "RUNNER_TEMP": "/private/never-export"}


def collect(target="ubuntu-24.04", **changes):
    centos = target == "centos-stream-9"
    options = {"target": target, "expected_id": "centos" if centos else "ubuntu",
               "expected_version": "9" if centos else "24.04", "os_release_text": CENTOS if centos else UBUNTU,
               "environment": github(), "system": "Linux", "machine": "x86_64", "kernel_release": "6.11.0-1018-azure",
               "container_image": "quay.io/centos/centos:stream9" if centos else None}
    options.update(changes)
    return ci.collect_context(**options)


class CiContextTest(unittest.TestCase):
    def test_empty_image_from_optional_ci_input_is_native_but_not_centos(self):
        self.assertIsNone(collect(container_image="")["containerImage"])
        with self.assertRaises(ci.ContextError):
            collect("centos-stream-9", container_image="")
        with self.assertRaises(ci.ContextError):
            collect(container_image=False)

    def test_cli_normalizes_an_explicit_empty_image_flag_before_collection(self):
        expected = collect()
        with tempfile.TemporaryDirectory() as directory, patch.object(ci, "ROOT", Path(directory).resolve()):
            destination = ci.ROOT / "reports/local/context.json"
            self.assertFalse(destination.parent.exists(), "fresh-checkout fixture must have no reports/local")
            with patch.object(ci, "collect_context", return_value=expected) as collector, contextlib.redirect_stdout(io.StringIO()):
                result = ci.main(["--target", "ubuntu-24.04", "--expected-id", "ubuntu", "--expected-version", "24.04",
                                  "--container-image", "", "--output", str(destination)])
            self.assertEqual(0, result)
            self.assertTrue(destination.parent.is_dir(), "the CLI must create its output parents")
            self.assertIsNone(collector.call_args.kwargs["container_image"])
            self.assertEqual(expected, json.loads(destination.read_text()))

    def test_ubuntu_native_allowlist_and_existing_manifest_owns_runtime_versions(self):
        context = collect()
        self.assertEqual(ci.FIELDS, set(context))
        self.assertEqual({"id": "ubuntu", "version": "24.04", "prettyName": "Ubuntu 24.04.3 LTS"}, context["distro"])
        self.assertEqual("runner-kernel", context["kernelScope"])
        self.assertIsNone(context["containerImage"])
        self.assertEqual("https://github.com/g5n-dev/ominicat/actions/runs/12345678", context["githubRunUrl"])
        encoded = json.dumps(context)
        for private in ("SECRET_TOKEN", "never-export", "/private", "javaVersion", "pythonVersion"):
            self.assertNotIn(private, encoded)

    def test_centos_is_actual_stream_userspace_with_explicit_shared_kernel(self):
        context = collect("centos-stream-9")
        self.assertEqual("CentOS Stream 9", context["distro"]["prettyName"])
        self.assertEqual("6.11.0-1018-azure", context["kernelRelease"])
        self.assertEqual("shared-runner-kernel-container-userspace", context["kernelScope"])
        digest = "quay.io/centos/centos:stream9@sha256:" + "b" * 64
        self.assertEqual(digest, collect("centos-stream-9", container_image=digest)["containerImage"])
        with self.assertRaises(ci.ContextError):
            collect("centos-stream-9", os_release_text=UBUNTU)
        with self.assertRaises(ci.ContextError):
            collect("centos-stream-9", os_release_text=CENTOS.replace("CentOS Stream", "CentOS Linux"))
        with self.assertRaises(ci.ContextError):
            collect("centos-stream-9", container_image=None)

    def test_distribution_arch_system_and_declared_profile_must_all_match(self):
        for changes in ({"machine": "aarch64"}, {"machine": "AMD64"}, {"system": "Darwin"},
                        {"expected_id": "centos"}, {"expected_version": "22.04"}, {"target": "other"},
                        {"container_image": "ubuntu:24.04"}, {"kernel_release": "/private/host/kernel"}):
            with self.assertRaises(ci.ContextError):
                collect(**changes)
        text = UBUNTU.replace("24.04", "22.04")
        context = collect("ubuntu-22.04", expected_version="22.04", os_release_text=text)
        self.assertEqual("22.04", context["distro"]["version"])

    def test_github_values_are_strict_and_errors_never_echo_untrusted_data(self):
        bad = {
            "GITHUB_SHA": ["not-a-commit", "b" * 39, "B" * 40, "/private/source"],
            "GITHUB_REPOSITORY": ["../private", "owner/repo?secret", "/private/name", "owner/repo/extra"],
            "GITHUB_SERVER_URL": ["https://github.example", "https://token@github.com", "https://github.com/path"],
            "GITHUB_RUN_ID": ["0", "01", "1/../../private", "7?secret=token"],
            "CI": ["false"], "GITHUB_ACTIONS": ["false"],
        }
        for field, values in bad.items():
            for value in values:
                environment = github()
                environment[field] = value
                with self.assertRaises(ci.ContextError) as error:
                    collect(environment=environment)
                self.assertNotIn(value, str(error.exception))

    def test_os_release_never_expands_commands_or_ambiguous_assignments(self):
        self.assertEqual("ubuntu", ci.parse_os_release(UBUNTU)["ID"])
        self.assertEqual(ci.parse_os_release(UBUNTU), ci.parse_os_release(UBUNTU + "BUILD_ID=\n"))
        for content in (UBUNTU + "ID=centos\n", UBUNTU.replace('"Ubuntu"', '"Ubuntu'),
                        UBUNTU.replace("ID=ubuntu", "ID=$(echo centos)"), UBUNTU + "\x00", UBUNTU * 1000,
                        UBUNTU.replace('VERSION_ID="24.04"', "VERSION_ID=$PRIVATE_VERSION")):
            with self.assertRaises(ci.ContextError):
                parsed = ci.parse_os_release(content)
                ci.validate_context({**collect(), "distro": {"id": parsed["ID"], "version": parsed["VERSION_ID"], "prettyName": parsed["PRETTY_NAME"]}})

    def test_loaded_context_rejects_extra_keys_and_misleading_kernel_scope(self):
        for key, value in (("schemaVersion", True), ("sourceSha", "f" * 64), ("machine", "aarch64"),
                           ("githubRunUrl", "https://github.com/owner/repo/actions/runs/3?secret=x"),
                           ("kernelScope", "centos-native-kernel")):
            context = collect()
            context[key] = value
            with self.assertRaises(ci.ContextError):
                ci.validate_context(context)
        context = collect()
        context["environment"] = {"arbitrary": "private"}
        with self.assertRaises(ci.ContextError):
            ci.validate_context(context)
        context = collect("centos-stream-9")
        context["kernelScope"] = "runner-kernel"
        with self.assertRaises(ci.ContextError):
            ci.validate_context(context)

    def test_output_is_new_local_json_and_rejects_traversal_symlinks_and_stale_data(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(ci, "ROOT", Path(directory).resolve()):
            with self.assertRaises(ci.ContextError):
                ci.output_path(ci.ROOT / "reports/public/context.json")
            parent = ci.ROOT / "reports/local"
            target = parent / "context.json"
            self.assertFalse(parent.exists())
            self.assertEqual(target, ci.output_path(target))
            parent.mkdir(parents=True)
            target.write_text("old", encoding="utf-8")
            with self.assertRaises(ci.ContextError):
                ci.output_path(target)
            link = parent / "link.json"
            link.symlink_to(parent / "absent.json")
            with self.assertRaises(ci.ContextError):
                ci.output_path(link)
            with self.assertRaises(ci.ContextError):
                ci.output_path(parent / "../../public/context.json")


if __name__ == "__main__":
    unittest.main()
