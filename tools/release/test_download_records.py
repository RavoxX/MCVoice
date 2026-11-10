import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import download_records


class RecordSelectionTest(unittest.TestCase):
    def test_creation_time_wins_over_artifact_id_or_api_order(self):
        older = {"name": "release-result-26.3", "id": 900, "created_at": "2026-11-04T19:00:00Z"}
        newer = {"name": "release-result-26.3", "id": 100, "created_at": "2026-11-04T20:00:00Z"}
        for rows in ([older, newer], [newer, older]):
            self.assertEqual(download_records.newest(rows)[older["name"]], newer)

    def test_unrelated_artifacts_are_not_records(self):
        self.assertEqual(download_records.newest([{"name": "mc-26.3-jars"}, {"name": "client-core"}]), {})

    def archive(self, entries):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, value in entries:
                archive.writestr(name, value)
        return data.getvalue()

    def test_only_records_are_written_and_duplicates_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "records"
            download_records.unpack_records(self.archive([("fabric.json", "{}"), ("expected-loaders.txt", "fabric"), ("fabric.log", "ignored")]), target)
            self.assertEqual({p.name for p in target.iterdir()}, {"fabric.json", "expected-loaders.txt"})
            with self.assertRaises(FileExistsError):
                download_records.unpack_records(self.archive([("fabric.json", "{}")]), target)

    def test_unsafe_paths_are_rejected_before_writing(self):
        for name in ("../fabric.json", "/fabric.json", "x\\fabric.json", "x/fabric.json"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "records"
                with self.assertRaisesRegex(ValueError, "unsafe artifact path"):
                    download_records.unpack_records(self.archive([(name, "{}")]), target)
                self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
