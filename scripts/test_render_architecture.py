"""Failure controls for read-only architecture source freshness checks."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import render_architecture as renderer


class ArchitectureFreshnessTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(renderer, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    def test_check_accepts_all_plates_without_rewriting_them(self):
        plates = (renderer.overview, renderer.execution, renderer.bounded,
                  renderer.locking, renderer.lifecycle)
        with patch.object(renderer, 'CHECK_ONLY', False):
            for render in plates:
                render()
        files = sorted(self.root.rglob('*.svg'))
        self.assertEqual(len(files), 5)
        before = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in files}
        with patch.object(renderer, 'CHECK_ONLY', True):
            for render in plates:
                render()
        self.assertEqual(before, {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in files})

    def test_missing_plate_fails_without_creating_output(self):
        with patch.object(renderer, 'CHECK_ONLY', True):
            with self.assertRaisesRegex(ValueError, 'Stale architecture figure'):
                renderer.locking()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_changed_plate_is_rejected_and_preserved_for_review(self):
        with patch.object(renderer, 'CHECK_ONLY', False):
            renderer.lifecycle()
        source = self.root / 'docs/assets/session-lifecycle.svg'
        altered = source.read_text().replace('DRAINING', 'UNREVIEWED')
        source.write_text(altered)
        with patch.object(renderer, 'CHECK_ONLY', True):
            with self.assertRaisesRegex(ValueError, 'Stale architecture figure'):
                renderer.lifecycle()
        self.assertEqual(source.read_text(), altered)


if __name__ == '__main__':
    unittest.main()
