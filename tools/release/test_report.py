import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import report


class ReportLayoutsTest(unittest.TestCase):
    def render(self, nested, failed_maven=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for loader in ("fabric", "forge"):
                folder = root / "mc-26.3-status" if nested else root
                folder.mkdir(exist_ok=True)
                record = {"minecraft": "26.3", "loader": loader, "status": "pass",
                          "jar": f"mcvoice-0.1.6-mc26.3-{loader}.jar",
                          "maven": "failed: Forbidden" if failed_maven and loader == "forge" else "published"}
                (folder / f"{loader}.json").write_text(json.dumps(record))
            folder = root / "release-result-26.3" if nested else root
            folder.mkdir(exist_ok=True)
            (folder / "release-26.3.json").write_text(json.dumps(
                {"minecraft": "26.3", "tag": "v0.1.6-mc26.3", "release": "created"}))
            out = io.StringIO()
            with patch("sys.argv", ["report.py", directory]), patch.dict(os.environ, {"VERSION": "0.1.6"}), contextlib.redirect_stdout(out):
                report.main()
            return out.getvalue()

    def test_single_version_flat_downloads(self):
        text = self.render(False)
        self.assertIn("Jars built and validated: **2/2**", text)
        self.assertIn("GitHub Releases: **1/1**", text)
        self.assertIn("Maven packages published: **2/2**", text)

    def test_nested_downloads_match_flat_downloads(self):
        self.assertEqual(self.render(False), self.render(True))

    def test_failed_maven_is_never_counted_as_published(self):
        text = self.render(False, failed_maven=True)
        self.assertIn("Maven packages published: **1/2**", text)
        self.assertIn("failed: Forbidden", text)


if __name__ == "__main__":
    unittest.main()
