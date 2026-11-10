import contextlib
import copy
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


class CompletePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.builds = {"26.3": {loader: {"minecraft": "26.3", "loader": loader,
            "status": "pass", "jar": f"mcvoice-0.1.15-mc26.3-{loader}.jar",
            "maven": "published", "maven_sha256": "a" * 64} for loader in ("fabric", "forge")}}
        self.releases = {"26.3": {"minecraft": "26.3", "release": "created"}}
        self.environment = {"EXPECTED_MINECRAFT": '["26.3"]', "TEST_RESULT": "success",
            "BUILD_RESULT": "success", "IMAGES_REQUESTED": "true", "IMAGES_RESULT": "success"}
        (self.root / "expected-loaders.txt").write_text("fabric forge\n")
        self.write_rows()

    def write_rows(self):
        for loader, row in self.builds["26.3"].items():
            (self.root / f"{loader}.json").write_text(json.dumps(row))

    def check(self):
        with patch.dict(os.environ, self.environment):
            report.require_complete(self.root, self.builds, self.releases)

    def test_complete_release_passes(self):
        self.check()

    def test_download_and_upload_failures_cannot_pass(self):
        for status in ("skipped", "failed", "missing"):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "GitHub release not complete"):
                self.releases["26.3"]["release"] = status
                self.check()

    def test_cli_fails_after_rendering_incomplete_publication(self):
        (self.root / "release.json").write_text(json.dumps({"minecraft": "26.3", "tag": "v0.1.15-mc26.3", "release": "skipped"}))
        output = io.StringIO()
        with patch("sys.argv", ["report.py", str(self.root), "--require-complete"]), patch.dict(os.environ, self.environment), contextlib.redirect_stdout(output):
            with self.assertRaisesRegex(ValueError, "GitHub release not complete"):
                report.main()
        self.assertIn("GitHub Releases: **0/1**", output.getvalue())

    def test_missing_whole_version_cannot_shrink_the_denominator(self):
        self.environment["EXPECTED_MINECRAFT"] = '["26.2", "26.3"]'
        with self.assertRaisesRegex(ValueError, "release plan"):
            self.check()

    def test_missing_loader_record_cannot_shrink_the_denominator(self):
        del self.builds["26.3"]["forge"]
        (self.root / "forge.json").unlink()
        with self.assertRaisesRegex(ValueError, "loader records"):
            self.check()

    def test_maven_failure_or_missing_readback_cannot_pass(self):
        for changes in ({"maven": "failed: HTTP 500"}, {"maven_sha256": ""}, {"status": "fail"}):
            original = copy.deepcopy(self.builds)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.builds["26.3"]["forge"].update(changes)
                self.check()
            self.builds = original

    def test_existing_immutable_maven_requires_verified_readback(self):
        row = self.builds["26.3"]["forge"]
        row["maven"] = "exists: immutable Maven version already published; not replaced"
        self.check()
        row["maven_sha256"] = ""
        with self.assertRaisesRegex(ValueError, "Maven read-back"):
            self.check()

    def test_failed_gate_or_requested_images_cannot_pass(self):
        for key in ("TEST_RESULT", "BUILD_RESULT", "IMAGES_RESULT"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                with patch.dict(self.environment, {key: "failure"}):
                    self.check()

    def test_unrequested_images_are_optional(self):
        self.environment.update(IMAGES_REQUESTED="false", IMAGES_RESULT="skipped")
        self.check()


if __name__ == "__main__":
    unittest.main()
