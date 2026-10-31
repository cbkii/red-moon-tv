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

    def test_real_status_cli_with_fake_adb_stays_read_only_and_hashes_apk(self):
        import os,zipfile
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);adb=root/'adb';log=root/'calls.jsonl'
            adb.write_text('#!'+sys.executable+'\n'+'''
import sys,json,pathlib,os
args=sys.argv[3:]
with open(os.environ['ADB_LOG'],'a') as f:f.write(json.dumps(args)+'\\n')
if args==['get-state']:print('device')
elif args==['shell','getprop','ro.product.device']:print('xiaoyushan')
elif args[:3]==['shell','pm','path']:print('package:/installed/base.apk')
elif args[0]=='pull':pathlib.Path(args[-1]).write_bytes(b'test installed apk')
elif args[:3]==['shell','content','query']:print('Row: 0 report='+json.dumps({'schema':5,'package':'com.cbkii.redmoontv.testing','service_running':False,'runtime':{'pending':0},'native_level':66,'sample_current_process':False}))
else:print('unavailable')
''');adb.chmod(0o755)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],ADB_LOG=str(log))
            r=subprocess.run([sys.executable,str(ROOT/'scripts/a4-continuity.py'),'-s','tv','--scenario','status','--execute','--output',d],env=env,capture_output=True,text=True,timeout=15)
            self.assertEqual(0,r.returncode,r.stderr)
            calls=[json.loads(line) for line in log.read_text().splitlines()]
            self.assertFalse(any(any(word in [part for arg in args for part in __import__('shlex').split(arg)] for word in ('su','install','force-stop','reboot','call','push','setprop')) for args in calls))
            with zipfile.ZipFile(next(root.glob('*.zip'))) as z:
                manifest=json.loads(z.read(next(n for n in z.namelist() if n.endswith('/manifest.json'))))
                self.assertTrue(manifest['read_only'])
                self.assertTrue(any(n.endswith('/installed.apk') for n in z.namelist()))

    def test_validation_package_has_closed_hashes_and_executable_module_scripts(self):
        import zipfile,hashlib
        spec=importlib.util.spec_from_file_location('a4_pack',ROOT/'scripts/a4-package-validation.py')
        pack=importlib.util.module_from_spec(spec);spec.loader.exec_module(pack)
        with tempfile.TemporaryDirectory() as d,patch.object(pack.subprocess,'check_output',side_effect=['a'*40,'']):
            bundle,module=pack.package(ROOT,pathlib.Path(d))
            with zipfile.ZipFile(module) as z:
                self.assertEqual({'module.prop','skip_mount','customize.sh','service.sh'},set(z.namelist()))
                self.assertEqual(0o100755,z.getinfo('service.sh').external_attr>>16)
            with zipfile.ZipFile(bundle) as z:
                manifest=json.loads(z.read('manifest.json'))
                self.assertEqual(set(z.namelist())-{'manifest.json'},set(manifest['sha256']))
                for name,sha in manifest['sha256'].items():self.assertEqual(sha,hashlib.sha256(z.read(name)).hexdigest())
        with tempfile.TemporaryDirectory() as d,patch.object(pack.subprocess,'check_output',side_effect=['a'*40,' M source']):
            with self.assertRaisesRegex(RuntimeError,'Commit all source'):pack.package(ROOT,pathlib.Path(d))

    def test_service_presence_and_cached_levels_cannot_pass_effect_recovery(self):
        ready={'backlight_enabled':True,'warmth_enabled':True,'scheduled_level_applied':True,
               'sample_current_process':True,'sample_age_ms':100,
               'runtime':{'brightness_state':'ACTIVE','warmth_state':'ACTIVE','overlay_attached':True,'overlay_strength':35}}
        self.assertTrue(continuity.Continuity.effects_ready(ready))
        for key,value in [('scheduled_level_applied',False),('sample_current_process',False),('sample_age_ms',None),('sample_age_ms',120000)]:
            wrong=dict(ready);wrong[key]=value;self.assertFalse(continuity.Continuity.effects_ready(wrong))
        for key,value in [('brightness_state','STARTING'),('warmth_state','ACCESS_REQUIRED'),('overlay_attached',False)]:
            wrong=dict(ready,runtime=dict(ready['runtime'],**{key:value}));self.assertFalse(continuity.Continuity.effects_ready(wrong))
        day=dict(ready,runtime=dict(ready['runtime'],warmth_state='DISABLED',overlay_strength=0,overlay_attached=False))
        self.assertTrue(continuity.Continuity.effects_ready(day))

    def test_inactive_supervisor_status_is_a_read_only_success(self):
        with tempfile.TemporaryDirectory() as d:
            r=continuity.Continuity('tv',pathlib.Path(d),continuity.base.collect.PACKAGES[0]);r.allow_root=True
            with patch.object(r,'root',return_value='inactive') as root:
                self.assertEqual('inactive',r.supervisor('status'))
                words=root.call_args.args[0]
                self.assertEqual(['sh','-c'],words[:2]);self.assertIn('printf inactive',words[2])
                self.assertNotIn('rm ',words[2]);self.assertNotIn('> ',words[2])
