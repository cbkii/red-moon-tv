"""Collector safety contracts; no ADB/device required."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
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
