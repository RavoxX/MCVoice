import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ROOT = Path(__file__).resolve().parents[2]
publisher = load('diagnostics', ROOT / 'tools/ci/publish_diagnostics.py')
merger = load('merge_publication', ROOT / 'tools/ci/merge_publication.py')
downloader = load('download_server', ROOT / 'tools/svc-interop/download-server.py')


class DataBoundaries(unittest.TestCase):
    def test_probe_keeps_existing_output_path_and_other_categories_stay_scoped(self):
        self.assertEqual(publisher.relative_target('probe', '26.3'), Path('26.3'))
        for category in ('builds', 'smoke', 'svc-interop'):
            self.assertEqual(publisher.relative_target(category, '26.3'), Path(category)/'26.3')

    def test_early_publication_failure_preserves_build_truth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = dict(minecraft='26.3', loader='fabric', status='pass', jar='mod.jar', commit='abc')
            file = root / 'fabric.json'
            file.write_text(json.dumps(row))
            merger.merge(root, root / 'missing', 'failure')
            actual = json.loads(file.read_text())
            self.assertEqual(actual['status'], 'pass')
            self.assertEqual(actual['jar'], 'mod.jar')
            self.assertTrue(actual['maven'].startswith('failed:'))

    def test_mismatching_publication_cannot_replace_build_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build, published = root / 'build', root / 'published'
            build.mkdir()
            published.mkdir()
            row = dict(minecraft='26.3', loader='fabric', status='pass', jar='mod.jar', commit='abc')
            (build / 'fabric.json').write_text(json.dumps(row))
            (published / 'fabric.json').write_text(json.dumps(dict(row, commit='other', maven='published')))
            merger.merge(build, published, 'success')
            actual = json.loads((build / 'fabric.json').read_text())
            self.assertEqual(actual['commit'], 'abc')
            self.assertTrue(actual['maven'].startswith('failed:'))

    def test_diagnostics_reject_executable_or_nested_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, destination = Path(tmp) / 'source', Path(tmp) / 'destination'
            source.mkdir()
            (source / 'payload.py').write_text('not executed')
            with self.assertRaises(ValueError):
                publisher.stage(source, destination)

    def test_checksum_mismatch_never_replaces_existing_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'paper.jar'
            target.write_bytes(b'original')
            with patch.object(downloader, 'request', return_value=io.BytesIO(b'tampered')):
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    downloader.verified_download('https://example.test/jar', target, 'sha256', '0' * 64)
            self.assertEqual(target.read_bytes(), b'original')
            self.assertFalse(target.with_suffix('.download').exists())


if __name__ == '__main__':
    unittest.main()
