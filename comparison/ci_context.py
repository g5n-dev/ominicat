#!/usr/bin/env python3
"""Collect a small, checked Linux CI context without exporting environment data.

This is a self-reported execution context, not an attestation. Java/Python
versions remain in the comparison manifest's existing runtime fields. CentOS
Stream identifies container user space; its kernel is the shared runner kernel.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shlex
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent
PROFILES = {
    "ubuntu-22.04": ("ubuntu", "22.04", "Ubuntu"),
    "ubuntu-24.04": ("ubuntu", "24.04", "Ubuntu"),
    "centos-stream-9": ("centos", "9", "CentOS Stream"),
}
FIELDS = {"schemaVersion", "target", "distro", "machine", "kernelRelease", "kernelScope",
          "containerImage", "githubRunUrl", "sourceSha"}
OWNER = r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"
RUN_URL = re.compile(r"https://github\.com/" + OWNER + "/" + OWNER + r"/actions/runs/[1-9][0-9]{0,19}\Z")
KERNEL = re.compile(r"[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,3}(?:[-.+_][A-Za-z0-9._+~-]{1,100})?\Z")
CENTOS_IMAGE = re.compile(r"quay\.io/centos/centos:stream9(?:@sha256:[0-9a-f]{64})?\Z")
OS_RELEASE_KEYS = {"ID", "VERSION_ID", "NAME", "PRETTY_NAME"}


class ContextError(ValueError):
    """Invalid context; messages deliberately exclude source values."""


def parse_os_release(text):
    """Parse bounded assignments without shell evaluation or variable expansion."""
    if not isinstance(text, str) or len(text) > 32768 or any(
            ord(char) < 32 and char not in "\n\r\t" for char in text):
        raise ContextError("invalid os-release input")
    result, seen = {}, set()
    lines = text.splitlines()
    if len(lines) > 256:
        raise ContextError("os-release input is too large")
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) > 2048 or "=" not in line:
            raise ContextError("invalid os-release assignment")
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", key) or key in seen:
            raise ContextError("duplicate or invalid os-release key")
        seen.add(key)
        try:
            parsed = shlex.split(value, comments=False, posix=True)
        except ValueError:
            raise ContextError("invalid os-release quoting") from None
        if not parsed and not value.strip():
            parsed = [""]  # Optional os-release assignments may be empty.
        if len(parsed) != 1 or len(parsed[0]) > 1024:
            raise ContextError("invalid os-release value")
        if key in OS_RELEASE_KEYS:
            result[key] = parsed[0]
    if set(result) != OS_RELEASE_KEYS:
        raise ContextError("required os-release fields are missing")
    return result


def validate_context(context):
    """Validate the exact allowlisted schema before embedding it in a manifest."""
    if not isinstance(context, dict) or set(context) != FIELDS:
        raise ContextError("invalid CI context fields")
    if type(context["schemaVersion"]) is not int or context["schemaVersion"] != 1:
        raise ContextError("unsupported CI context schema")
    target = context["target"]
    if not isinstance(target, str) or target not in PROFILES:
        raise ContextError("unsupported CI target")
    expected_id, expected_version, _ = PROFILES[target]
    distro = context["distro"]
    if (not isinstance(distro, dict) or set(distro) != {"id", "version", "prettyName"}
            or distro["id"] != expected_id or distro["version"] != expected_version):
        raise ContextError("distribution does not match the CI target")
    pretty = distro["prettyName"]
    if not isinstance(pretty, str):
        raise ContextError("invalid distribution name")
    if expected_id == "ubuntu":
        if not re.fullmatch(r"Ubuntu " + re.escape(expected_version) + r"(?:\.[0-9]{1,2})?(?: LTS)?", pretty):
            raise ContextError("distribution name does not match the CI target")
    elif pretty != "CentOS Stream 9":
        raise ContextError("CentOS Stream user space is required")
    if context["machine"] != "x86_64":
        raise ContextError("the CI comparison profile requires x86_64")
    if not isinstance(context["kernelRelease"], str) or not KERNEL.fullmatch(context["kernelRelease"]):
        raise ContextError("invalid kernel release")
    if target == "centos-stream-9":
        image = context["containerImage"]
        if not isinstance(image, str) or not CENTOS_IMAGE.fullmatch(image):
            raise ContextError("the controlled CentOS Stream container reference is required")
        expected_scope = "shared-runner-kernel-container-userspace"
    else:
        if context["containerImage"] is not None:
            raise ContextError("the Ubuntu target is a native runner profile")
        expected_scope = "runner-kernel"
    if context["kernelScope"] != expected_scope:
        raise ContextError("kernel scope does not match the execution profile")
    if not isinstance(context["githubRunUrl"], str) or not RUN_URL.fullmatch(context["githubRunUrl"]):
        raise ContextError("invalid GitHub run metadata")
    if not isinstance(context["sourceSha"], str) or not re.fullmatch(r"[0-9a-f]{40}", context["sourceSha"]):
        raise ContextError("invalid GitHub source revision")
    return context


def collect_context(*, target, expected_id, expected_version, container_image=None,
                    os_release_text=None, environment=None, system=None, machine=None, kernel_release=None):
    if container_image is not None and not isinstance(container_image, str):
        raise ContextError("invalid container image reference")
    container_image = container_image or None
    if not isinstance(target, str) or target not in PROFILES:
        raise ContextError("unsupported CI target")
    if (expected_id, expected_version) != PROFILES[target][:2]:
        raise ContextError("expected distribution conflicts with the target")
    if (platform.system() if system is None else system) != "Linux":
        raise ContextError("Linux is required for this CI context")
    if os_release_text is None:
        with Path("/etc/os-release").open("r", encoding="utf-8") as stream:
            os_release_text = stream.read(32769)
    release = parse_os_release(os_release_text)
    if (release["ID"], release["VERSION_ID"], release["NAME"]) != PROFILES[target]:
        raise ContextError("actual distribution does not match the CI target")
    env = os.environ if environment is None else environment
    # Read only named, non-secret GitHub metadata; never copy the environment,
    # runner name/workspace/temp, hostnames, credentials or arbitrary paths.
    if env.get("CI") != "true" or env.get("GITHUB_ACTIONS") != "true":
        raise ContextError("GitHub Actions CI context is required")
    if env.get("GITHUB_SERVER_URL") != "https://github.com":
        raise ContextError("the public GitHub server is required")
    repository, run_id = env.get("GITHUB_REPOSITORY"), env.get("GITHUB_RUN_ID")
    if (not isinstance(repository, str) or not re.fullmatch(OWNER + "/" + OWNER, repository)
            or not isinstance(run_id, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", run_id)):
        raise ContextError("invalid GitHub run metadata")
    context = {
        "schemaVersion": 1, "target": target,
        "distro": {"id": release["ID"], "version": release["VERSION_ID"], "prettyName": release["PRETTY_NAME"]},
        "machine": platform.machine() if machine is None else machine,
        "kernelRelease": platform.release() if kernel_release is None else kernel_release,
        "kernelScope": "shared-runner-kernel-container-userspace" if container_image else "runner-kernel",
        # This is the declared controlled reference, not proof of a resolved
        # image digest. A tag alone does not pin container contents.
        "containerImage": container_image,
        "githubRunUrl": "https://github.com/" + repository + "/actions/runs/" + run_id,
        "sourceSha": env.get("GITHUB_SHA"),
    }
    return validate_context(context)


def output_path(path):
    local = ROOT / "reports/local"
    candidate = path.absolute()
    if (candidate.suffix != ".json" or not candidate.is_relative_to(local)
            or not candidate.resolve().is_relative_to(local.resolve()) or candidate.exists() or candidate.is_symlink()):
        raise ContextError("output must be a new JSON file under reports/local")
    return candidate


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-id", required=True)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--container-image")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    temporary = None
    try:
        destination = output_path(args.output)
        context = collect_context(target=args.target, expected_id=args.expected_id,
                                  expected_version=args.expected_version, container_image=args.container_image or None)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=destination.parent,
                                         prefix=".ci-context-", suffix=".json", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(context, stream, indent=2, allow_nan=False)
            stream.write("\n")
        os.replace(temporary, destination)
        print(json.dumps(context, separators=(",", ":")), flush=True)
    except Exception as error:
        message = str(error) if isinstance(error, ContextError) else "CI context collection failed"
        print(json.dumps({"event": "ci_context_failed", "message": message}), file=sys.stderr, flush=True)
        return 1
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
