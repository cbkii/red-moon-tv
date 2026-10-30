"""Collector safety contracts; no ADB/device required."""
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('a4_collect', pathlib.Path(__file__).parents[1] / 'a4-collect.py')
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)


class CollectorTest(unittest.TestCase):
    def test_transport_is_explicit_and_timeout_never_retries(self):
        with tempfile.TemporaryDirectory() as directory:
            probe = collector.Collector('192.0.2.2:5555', pathlib.Path(directory), collector.PACKAGES[0])
            with patch.object(collector.subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 12)) as run:
                self.assertEqual(124, probe.run(['get-state'])[0])
                self.assertEqual(1, run.call_count)
                self.assertEqual(['adb', '-s', '192.0.2.2:5555', 'get-state'], run.call_args.args[0])

    def test_wrong_device_stops_before_root_or_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(collector.Collector, 'run', side_effect=[(0, 'device\n', ''), (0, 'pixel\n', '')]) as run:
                self.assertEqual(2, collector.main(['-s', 'wrong', '--root', '--output', directory]))
                self.assertEqual(2, run.call_count)
                self.assertEqual([], list(pathlib.Path(directory).iterdir()))

    def test_default_capture_is_read_only_bounded_and_contains_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            commands = []
            def execute(probe, args, timeout=12):
                commands.append(args)
                if args == ['get-state']: return 0, 'device\n', ''
                if args == ['shell', 'getprop', 'ro.product.device']: return 0, 'xiaoyushan_emT\n', ''
                if args[:3] == ['shell', 'pm', 'path']:
                    return (0, 'package:/system/example.apk\n', '') if args[3] == collector.PACKAGES[0] else (1, '', '')
                if args[:2] == ['shell', 'pidof']: return 1, '', ''
                if args[0] == 'logcat': return 0, 'PRIVATE irrelevant\nRedMoonTV native test\n', ''
                return 1, 'unavailable\n', 'Permission denied'
            with patch.object(collector.Collector, 'run', execute):
                self.assertEqual(0, collector.main(['-s', 'tv', '--output', directory]))
            self.assertFalse(any('su' in str(args) or 'start' in args or 'install' in args or 'push' in args for args in commands))
            folders = [p for p in pathlib.Path(directory).iterdir() if p.is_dir()]
            self.assertEqual(1, len(folders))
            self.assertNotIn('PRIVATE', (folders[0] / 'vendor-log.txt').read_text())
            self.assertIn('Permission denied', (folders[0] / 'before-memory.txt').read_text())
            self.assertTrue(next(pathlib.Path(directory).glob('*.zip')).exists())

    def test_ambiguous_variants_stop_before_root_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            def execute(probe, args, timeout=12):
                if args == ['get-state']: return 0, 'device', ''
                if args == ['shell', 'getprop', 'ro.product.device']: return 0, 'xiaoyushan', ''
                self.assertEqual(['shell', 'pm', 'path'], args[:3])
                return 0, 'package:/installed/base.apk', ''
            with patch.object(collector.Collector, 'run', execute):
                self.assertEqual(2, collector.main(['-s', 'tv', '--root', '--output', directory]))
            self.assertEqual([], list(pathlib.Path(directory).iterdir()))

    def test_report_extraction_rejects_another_package_and_preserves_native_error(self):
        wrong = dict(schema=3, package='another.app')
        right = dict(schema=3, package=collector.PACKAGES[0], first_initialisation_error='linker failure')
        text = 'SERVICE\n' + json.dumps(wrong) + '\n' + json.dumps(right, indent=2) + '\npending_native=0'
        self.assertEqual(right, collector.extract_report(text, collector.PACKAGES[0]))
        self.assertIsNone(collector.extract_report('{"schema":3, broken', collector.PACKAGES[0]))

    def test_failed_apk_pull_cannot_be_presented_as_an_exact_installed_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            probe = collector.Collector('tv', root, collector.PACKAGES[0])
            def execute(args, timeout=12):
                if args[0] == 'pull':
                    (root / 'installed.apk').write_bytes(b'partial download')
                    return 124, '', 'Command timed out; not retried'
                return 0, 'package:/installed/base.apk', ''
            with patch.object(probe, 'run', side_effect=execute):
                probe.apk()
            self.assertFalse((root / 'installed.apk').exists())
            self.assertEqual(124, probe.records[-1]['exit_code'])
            self.assertIsNone(probe.records[-1]['sha256'])

    def test_guided_requires_terminal_without_contacting_device(self):
        with patch.object(collector.sys.stdin, 'isatty', return_value=False), patch.object(collector.Collector, 'run') as run:
            self.assertEqual(2, collector.main(['-s', 'tv', '--guided']))
            run.assert_not_called()

    def guided_capture(self, answers, interrupted=False):
        with tempfile.TemporaryDirectory() as directory:
            commands = []
            def execute(probe, args, timeout=12):
                commands.append(args)
                if args == ['get-state']: return 0, 'device', ''
                if args == ['shell', 'getprop', 'ro.product.device']: return 0, 'xiaoyushan', ''
                if args[:3] == ['shell', 'pm', 'path']: return 0, 'package:/installed/base.apk', ''
                if args[:4] == ['shell', 'dumpsys', 'activity', 'service']:
                    return 0, 'SERVICE\n' + json.dumps(dict(schema=3, package=probe.package,
                        source_sha='a'*40, first_initialisation_error='original JNI error')), ''
                return 0, '', ''
            with patch.object(collector.Collector, 'run', execute), patch.object(collector.sys.stdin, 'isatty', return_value=True), patch('builtins.input', side_effect=answers):
                code = collector.main(['-s', 'tv', '--package', collector.PACKAGES[0], '--guided', '--output', directory])
            self.assertEqual(130 if interrupted else 0, code)
            archive = next(pathlib.Path(directory).glob('*.zip'))
            with zipfile.ZipFile(archive) as z:
                manifest = json.loads(z.read(next(n for n in z.namelist() if n.endswith('/manifest.json'))))
                self.assertEqual('interrupted' if interrupted else 'complete', manifest['collection_status'])
                self.assertTrue(manifest['read_only'])
                self.assertTrue(manifest['guided_manual_actions'])
                self.assertFalse(any(word in args for args in commands for word in
                    ('su', 'am', 'broadcast', 'push', 'install', 'force-stop', 'reboot', 'setprop')))
                return manifest, z.namelist()

    def test_failed_guided_read_skips_probe_and_keeps_first_error_report(self):
        manifest, files = self.guided_capture(['APK backend, level 66', 'no', 'Read failed'])
        self.assertIn('read', manifest['app_reports'])
        self.assertTrue(any(n.endswith('/read-report.json') for n in files))
        self.assertFalse(any(n.endswith('/probe-report.json') for n in files))

    def test_guided_probe_records_operator_observations_without_sending_writes(self):
        manifest, files = self.guided_capture(['APK backend, level 66', 'yes', 'yes',
            'probe observed', 'yes', 'yes', '66 restored', 'No more notes'])
        self.assertIn('probe', manifest['app_reports'])
        self.assertTrue(any(n.endswith('/observations.json') for n in files))
        self.assertIn('Not inferred', manifest['physical_validation'])

    def test_interruption_keeps_completed_checkpoints_in_an_archive(self):
        manifest, files = self.guided_capture([KeyboardInterrupt()], interrupted=True)
        self.assertIn('before', manifest['app_reports'])
        self.assertTrue(any(n.endswith('/before-app.txt') for n in files))

    def test_actual_cli_entrypoint_with_fake_adb_hashes_the_installed_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            adb = root / 'adb'
            adb.write_text('#!' + sys.executable + '\n' + '''
import json,pathlib,sys
a=sys.argv[3:]
if a==['get-state']: print('device')
elif a==['shell','getprop','ro.product.device']: print('xiaoyushan_emT')
elif a[:3]==['shell','pm','path']:
    if a[3]=='com.cbkii.redmoontv.debug': print('package:/installed/base.apk')
elif a[:4]==['shell','dumpsys','activity','service']:
    print('SERVICE\\n'+json.dumps(dict(schema=3,package='com.cbkii.redmoontv.debug',source_sha='a'*40)))
elif a[0]=='pull': pathlib.Path(a[2]).write_bytes(b'fixture installed APK')
elif a[0]=='logcat': print('PRIVATE unrelated\\nRedMoonTV original failure')
''')
            adb.chmod(0o755)
            output = root / 'results'
            result = subprocess.run([sys.executable, str(SPEC.origin), '-s', 'fixture-tv', '--apk', '--output', str(output)],
                capture_output=True, text=True, timeout=10, env=dict(os.environ, PATH=str(root)+os.pathsep+os.environ.get('PATH', '')))
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(next(output.glob('*.zip'))) as z:
                names = z.namelist()
                manifest = json.loads(z.read(next(n for n in names if n.endswith('/manifest.json'))))
                self.assertEqual('com.cbkii.redmoontv.debug', manifest['package'])
                self.assertEqual('complete', manifest['collection_status'])
                for name, digest in manifest['files'].items():
                    member = next(n for n in names if n.endswith('/'+name))
                    self.assertEqual(digest, collector.hashlib.sha256(z.read(member)).hexdigest())
                self.assertNotIn(b'PRIVATE', z.read(next(n for n in names if n.endswith('/vendor-log.txt'))))
