import importlib.util
from pathlib import Path
import os
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('probe_classes', ROOT/'tools/ci/probe_classes.py')
probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)


class ProbeBoundaries(unittest.TestCase):
    def test_options_descriptors_and_access_cannot_reach_javap(self):
        for name in ('-J-javaagent:j1.jar', '@args', '.Foo', 'p..Foo', 'p/Foo', '[LFoo;', 'Foo\n-J-version'):
            with self.subTest(name=name), patch.object(probe.subprocess, 'run') as run:
                with self.assertRaises(ValueError): probe.javap('.', name, 'public')
                run.assert_not_called()
        with patch.object(probe.subprocess, 'run') as run:
            with self.assertRaises(ValueError): probe.javap('.', 'java.lang.String', 'J-javaagent:j1.jar')
            run.assert_not_called()
        for name in ('java.lang.String', 'p.Foo$1', 'p.Δ$Inner'):
            self.assertTrue(probe.valid_class(name))

    def test_real_jvm_option_injection_is_blocked_but_normal_inspection_works(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root/'ProbeAgent.java'
            source.write_text('public class ProbeAgent { public static void premain(String arg) throws Exception { java.nio.file.Files.write(java.nio.file.Paths.get(arg), new byte[]{1}); } }')
            subprocess.run(['javac', str(source)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            with zipfile.ZipFile(root/'j1.jar','w') as archive:
                archive.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: ProbeAgent\n\n')
                archive.write(root/'ProbeAgent.class','ProbeAgent.class')
                archive.writestr('-J-javaagent:j1.jar=owned.class',b'not a class')
            # A missing class is not a barrier: javap starts the JVM agent first.
            subprocess.run(['javap','-cp','.', '-public','-J-javaagent:j1.jar=owned'],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            marker = root/'owned'; self.assertTrue(marker.exists()); marker.unlink()
            previous = Path.cwd()
            try:
                os.chdir(root)
                with self.assertRaises(ValueError): probe.javap('.', '-J-javaagent:j1.jar=owned', 'public')
                self.assertFalse(marker.exists())
                probe.extract('j1.jar','x1')
                self.assertEqual([name for name,_ in probe.archive_classes(root, '.*')], ['ProbeAgent'])
                output = probe.javap(root/'x1','ProbeAgent','public')
                self.assertIn('premain',output); self.assertFalse(marker.exists())
            finally: os.chdir(previous)

    def test_path_traversal_and_symlinks_cannot_replace_the_validator(self):
        for name in ('../escape.class','/absolute.class','C:/escape.class','a\\escape.class'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                entry = zipfile.ZipInfo(name)
                entry.filename = name  # ZipInfo normalizes backslashes on Windows.
                with zipfile.ZipFile(root/'bad.jar','w') as archive: archive.writestr(entry,b'bad')
                with self.assertRaises(ValueError): probe.extract(root/'bad.jar',root/'x1')
                self.assertFalse((root/'escape.class').exists())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); link=zipfile.ZipInfo('link'); link.create_system=3
            link.external_attr=(stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(root/'bad.jar','w') as archive: archive.writestr(link,'../outside')
            with self.assertRaises(ValueError): probe.extract(root/'bad.jar',root/'x1')

    def test_nested_class_roots_and_probe_publication_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); nested=root/'x1/modules/child.jar_x/pkg'
            nested.mkdir(parents=True); (nested/'Inner$1.class').write_bytes(b'fixture')
            entries=probe.archive_classes(root,'pkg')
            self.assertEqual(entries,[('pkg.Inner$1',nested.parent.resolve())])
        workflow=(ROOT/'.github/workflows/mc-probe.yml').read_text()
        inspection=workflow.split('  probe:\n',1)[1].split('\n  diagnostics:',1)[0]
        self.assertIn('contents: read',inspection); self.assertNotIn('contents: write',inspection)
        self.assertIn('persist-credentials: false',inspection)
        self.assertNotIn('github.token',inspection); self.assertNotIn('git remote',inspection)
        self.assertNotIn('javap -cp',inspection)
        self.assertIn('uses: ./.github/workflows/publish-diagnostics.yml',workflow)
        self.assertIn('category: probe',workflow)


if __name__ == '__main__': unittest.main()
