import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=pathlib.Path(__file__).parents[2]
spec=importlib.util.spec_from_file_location('continuity',ROOT/'scripts/a4-continuity.py')
continuity=importlib.util.module_from_spec(spec);spec.loader.exec_module(continuity)

class ContinuityTests(unittest.TestCase):
    def test_dry_run_never_uses_adb_or_root(self):
        r=subprocess.run([sys.executable,str(ROOT/'scripts/a4-continuity.py'),'-s','tv','--scenario','all','--root'],capture_output=True,text=True,timeout=5)
        self.assertEqual(0,r.returncode,r.stderr);self.assertEqual('all',json.loads(r.stdout)['scenario'])

    def test_provider_activation_cannot_fake_foreground_recovery(self):
        with tempfile.TemporaryDirectory() as d:
            r=continuity.Continuity('tv',pathlib.Path(d),continuity.base.collect.PACKAGES[0]);r.results=[]
            with patch.object(r,'run',return_value=(0,'ServiceRecord TvDisplayService isForeground=false','')), \
                 patch.object(r,'provider') as query,patch.object(continuity.time,'sleep'), \
                 patch.object(continuity.time,'monotonic',side_effect=[0,0,1,5]):
                with self.assertRaises(RuntimeError):r.wait_running('dead',2)
                query.assert_not_called()

    def test_kill_checks_exact_cmdline_and_refuses_reused_pid(self):
        with tempfile.TemporaryDirectory() as d:
            r=continuity.Continuity('tv',pathlib.Path(d),continuity.base.collect.PACKAGES[0]);r.allow_root=True
            with patch.object(r,'run',return_value=(0,'123','')),patch.object(r,'root',return_value='other.process\x00') as root:
                with self.assertRaisesRegex(RuntimeError,'identity changed'):r.kill_named()
                self.assertEqual(1,root.call_count)

    def test_force_stop_observation_does_not_unstop_package_before_explicit_launch(self):
        with tempfile.TemporaryDirectory() as d:
            r=continuity.Continuity('tv',pathlib.Path(d),continuity.base.collect.PACKAGES[0]);r.results=[]
            commands=[]
            def send(args,**kwargs):commands.append(args);return 'User 0: stopped=true'
            with patch.object(r,'checked',side_effect=send),patch.object(r,'wait_running'), \
                 patch.object(r,'sample'),patch.object(r,'provider') as provider,patch.object(continuity.time,'sleep'):
                r.exercise('force-stop',60)
                provider.assert_not_called()
            self.assertEqual('force-stop',commands[0][2]);self.assertEqual('dumpsys',commands[1][1]);self.assertEqual('start',commands[2][2])

    def test_supervisor_stopped_package_never_queries_provider(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);script=(ROOT/'extras/a4-supervisor/service.sh').read_text()
            tools=root/'bin';tools.mkdir();marker=root/'content-called'
            for name,body in {'dumpsys':"echo 'User 0: stopped=true enabled=0'",'content':'touch '+str(marker),'log':':'}.items():
                f=tools/name;f.write_text('#!/bin/sh\n'+body+'\n');f.chmod(0o755)
            script=script.replace('/system/bin/',str(tools)+'/')
            f=root/'service.sh';f.write_text(script);(root/'selected-package').write_text('com.cbkii.redmoontv.testing\n')
            result=subprocess.run(['sh',str(f)],capture_output=True,text=True,timeout=5)
            self.assertEqual(0,result.returncode,result.stderr);self.assertFalse(marker.exists())

    def test_supervisor_rejects_unrecognised_package_without_execution(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);f=root/'service.sh';f.write_text((ROOT/'extras/a4-supervisor/service.sh').read_text())
            (root/'selected-package').write_text('anything; touch BAD\n')
            result=subprocess.run(['sh',str(f)],capture_output=True,text=True,timeout=5)
            self.assertEqual(0,result.returncode,result.stderr);self.assertFalse((root/'BAD').exists())
