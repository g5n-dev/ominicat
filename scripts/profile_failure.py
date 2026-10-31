#!/usr/bin/env python3
"""Print bounded, privacy-checked failure diagnostics; never certify a profile."""

import argparse
from pathlib import Path
import re
import sys

from package_profiles import privacy_check


LOGS = ("build.log", "jmh.log", "load.log", "jfr-config.log")
TAIL_LINES = 60
LINE_CHARACTERS = 2000


def diagnose(directory, output=None):
    output = output if output is not None else sys.stdout
    print("Profile failure diagnostics only; these are not validated performance evidence.", file=output)
    if directory.is_symlink():
        print("Diagnostic directory withheld: symlinks are refused.", file=output)
        return 0
    found = False
    for name in LOGS:
        path = directory / name
        try:
            if path.is_symlink():
                found = True
                print(f"{name}: withheld; symlinks are refused.", file=output)
                continue
            if not path.exists():
                continue
            found = True
            if not path.is_file():
                print(f"{name}: withheld; not a regular diagnostic log.", file=output)
                continue
            # Check the whole file before selecting its tail. A secret earlier in
            # the log withholds the entire log, even if it falls outside the tail.
            data = path.read_bytes().replace(str(Path.cwd()).encode("utf-8"), b"[repository]")
            text = privacy_check(name, data)
        except (OSError, UnicodeError, ValueError):
            # Never print an exception: it may contain a path or rejected input.
            print(f"{name}: withheld; unavailable or not safe for publication.", file=output)
            continue
        lines = text.splitlines()[-TAIL_LINES:]
        print(f"{name}: safe diagnostic tail ({len(lines)} lines maximum {TAIL_LINES}).", file=output)
        for line in lines:
            # Logs must not inject GitHub Actions workflow commands. Keep the
            # diagnostic bounded even when a tool emits a very long single line.
            line = re.sub(r":(?=:)", ": ", line)
            if len(line) > LINE_CHARACTERS:
                line = line[:LINE_CHARACTERS] + " [line truncated]"
            print("    " + line, file=output)
    if not found:
        print("No diagnostic logs are available.", file=output)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path, default=Path("reports/local/ci"))
    return diagnose(parser.parse_args().directory)


if __name__ == "__main__":
    sys.exit(main())
