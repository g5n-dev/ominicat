"""Failure diagnostics never publish unchecked text or change failed-run status."""

from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import profile_failure


class ProfileFailureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def diagnostics(self):
        output = io.StringIO()
        self.assertEqual(0, profile_failure.diagnose(self.directory, output))
        return output.getvalue()

    def test_only_safe_tail_and_repository_path_are_printed_without_modifying_log(self):
        original = "\n".join(f"safe line {index}" for index in range(80)) + f"\n{Path.cwd()}/pom.xml\n"
        log = self.directory / "jmh.log"
        log.write_text(original, encoding="utf-8")
        text = self.diagnostics()
        self.assertIn("not validated performance evidence", text)
        self.assertNotIn("    safe line 20\n", text)
        self.assertIn("    safe line 21\n", text)
        self.assertIn("    safe line 79\n", text)
        self.assertIn("[repository]/pom.xml", text)
        self.assertNotIn(str(Path.cwd()), text)
        self.assertEqual(60, len([line for line in text.splitlines() if line.startswith("    ")]))
        self.assertEqual(original, log.read_text(encoding="utf-8"))

    def test_any_unsafe_text_withholds_the_whole_log_even_outside_tail(self):
        examples = [
            "Bearer synthetic-private-value",
            "OPENAI_API_KEY=synthetic-private-value",
            "sk-proj-" + "x" * 40,
            "/home/private-user/credentials",
            r"C:\Users\private-user\credentials",
            r"\u0073k-proj-" + "y" * 40,
        ]
        for secret in examples:
            with self.subTest(kind=secret.split("-")[0]):
                (self.directory / "build.log").write_text(secret + "\n" + "safe later line\n" * 80, encoding="utf-8")
                text = self.diagnostics()
                self.assertIn("build.log: withheld", text)
                self.assertNotIn(secret, text)
                self.assertNotIn("safe later line", text)
                self.assertNotIn("private-user", text)

    def test_symlinked_file_is_not_read_and_unknown_logs_are_ignored(self):
        outside = self.directory / "unrelated.txt"
        outside.write_text("unrelated content must never be read", encoding="utf-8")
        (self.directory / "build.log").symlink_to(outside)
        (self.directory / "private.log").write_text("unknown log must never be read", encoding="utf-8")
        text = self.diagnostics()
        self.assertIn("build.log: withheld; symlinks are refused", text)
        self.assertNotIn("unrelated content", text)
        self.assertNotIn("unknown log", text)

    def test_symlinked_directory_is_refused(self):
        target = self.directory / "target"
        target.mkdir()
        (target / "load.log").write_text("symlink target must not be read", encoding="utf-8")
        link = self.directory / "link"
        link.symlink_to(target, target_is_directory=True)
        output = io.StringIO()
        self.assertEqual(0, profile_failure.diagnose(link, output))
        self.assertIn("symlinks are refused", output.getvalue())
        self.assertNotIn("symlink target", output.getvalue())

    def test_empty_or_missing_directory_is_successful_diagnostics_without_success_claim(self):
        for directory in (self.directory, self.directory / "missing"):
            with self.subTest(exists=directory.exists()):
                output = io.StringIO()
                self.assertEqual(0, profile_failure.diagnose(directory, output))
                self.assertIn("No diagnostic logs are available", output.getvalue())
                self.assertIn("not validated performance evidence", output.getvalue())

    def test_read_or_encoding_failures_do_not_echo_exception_or_input(self):
        (self.directory / "load.log").write_bytes(b"invalid UTF-8: \xffprivate-input")
        text = self.diagnostics()
        self.assertIn("load.log: withheld", text)
        self.assertNotIn("private-input", text)
        with patch.object(Path, "read_bytes", side_effect=OSError("private-error-details")):
            text = self.diagnostics()
            self.assertNotIn("private-error-details", text)

    def test_action_commands_are_disabled_and_long_lines_are_bounded(self):
        (self.directory / "jfr-config.log").write_text("::error::synthetic command\n::::error::::\n" + "a" * 5000, encoding="utf-8")
        text = self.diagnostics()
        self.assertNotIn("::error::", text)
        self.assertNotIn("::", text)
        self.assertIn(": :error: :synthetic command", text)
        self.assertIn("[line truncated]", text)
        self.assertLess(len(text), 3000)

    def test_cli_returns_zero_and_only_reads_the_fixed_allowlist(self):
        (self.directory / "kernel.jfr").write_bytes(b"raw recording must never be opened")
        output = io.StringIO()
        with patch("sys.argv", ["profile_failure.py", str(self.directory)]), redirect_stdout(output):
            self.assertEqual(0, profile_failure.main())
        self.assertIn("No diagnostic logs are available", output.getvalue())
        self.assertNotIn("raw recording", output.getvalue())


if __name__ == "__main__":
    unittest.main()
