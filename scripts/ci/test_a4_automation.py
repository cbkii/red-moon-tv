"""Automation ADB transport tests; do not mistake shell acceptance for tvQuickActions evidence."""
import importlib.util
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).parents[2]
spec = importlib.util.spec_from_file_location('automation', ROOT / 'scripts/a4-automation.py')
automation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(automation)


class AutomationTests(unittest.TestCase):
    def test_broadcast_preserves_typed_extras_and_literal_shell_text(self):
        value = "x'; $(id); `id`\nquoted"
        args = automation.broadcast_args('com.cbkii.redmoontv.testing', {'value': 5, 'feedback': True, 'token': value})
        command = shlex.split(args[1])
        self.assertEqual(['--ei', 'value', '5', '--ez', 'feedback', 'true', '--es', 'token', value], command[-9:])
        self.assertIn('com.cbkii.redmoontv.testing/com.jmstudios.redmoon.tv.TvControlReceiver', command)
        self.assertEqual('com.cbkii.redmoontv.AUTOMATION', command[command.index('-a') + 1])

    def test_broadcast_rejects_untyped_unbounded_and_unknown_key_shapes(self):
        for value in ({'value': []}, {'value': 2.5}, {'token': 'x' * 257}, {'a;id': 'x'}):
            with self.assertRaises(ValueError): automation.broadcast_args('pkg', value)

    def test_redaction_covers_commands_outputs_and_nested_structures(self):
        value = {'command': ['--es', 'token', 'sensitive'], 'stdout': 'token=sensitive', 'nested': {'v': 'sensitive'}}
        result = automation.redact(value, 'sensitive')
        self.assertNotIn('sensitive', json.dumps(result))
        self.assertIn('[REDACTED]', json.dumps(result))

    def test_structured_query_call_and_missing_json(self):
        for text in ('Row: 0 report={"api_version":1}', 'Bundle[{result={"api_version":1}}]'):
            self.assertEqual(1, automation.object_from_output(text)['api_version'])
        with self.assertRaises(RuntimeError): automation.object_from_output('transport disconnected')

    def test_retry_uses_exact_request_id_and_does_not_replay_unknown_outcome(self):
        with tempfile.TemporaryDirectory() as d:
            run = automation.AutomationRunner('tv', pathlib.Path(d), 'com.cbkii.redmoontv.testing'); run.token = 'private'; run.results = []
            with patch.object(run, 'checked', return_value='Broadcast completed: result=-1') as send, \
                 patch.object(run, 'wait_request', side_effect=RuntimeError('unknown')):
                with self.assertRaises(RuntimeError): run.request('show_status', request_id='same-request')
                self.assertEqual(1, send.call_count)
                self.assertIn('same-request', shlex.split(send.call_args.args[0][1]))

    def test_protected_pairing_bypasses_recording_and_never_enables_consent(self):
        with tempfile.TemporaryDirectory() as d:
            run = automation.AutomationRunner('tv', pathlib.Path(d), 'pkg')
            with patch.object(run, 'run', return_value=(0, 'Bundle[{result={"enabled":true,"token":"private"}}]', '')) as query, \
                 patch.object(run, 'checked') as log:
                run.pairing(); log.assert_not_called(); self.assertEqual('private', run.token)
                query.assert_called_once_with(automation.base.shell_args(['content', 'call', '--uri',
                    'content://pkg.diagnostics', '--method', 'automation_pairing']), 12)
            self.assertEqual([], list(pathlib.Path(d).iterdir()))
            with patch.object(run, 'run', return_value=(0, 'Bundle[{result={"enabled":false,"token":"private"}}]', '')):
                with self.assertRaisesRegex(RuntimeError, 'Enable paired'): run.pairing()

    def test_no_request_envelope_can_be_overridden_by_data(self):
        run = automation.AutomationRunner('tv', pathlib.Path('/tmp'), 'pkg')
        for key in ('token', 'request_id', 'command', 'api_version'):
            with self.assertRaises(ValueError): run.request('show_status', {key: 'anything'})

    def test_dry_run_requires_no_adb(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/a4-automation.py'), '-s', 'tv', '--suite', 'controls'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('controls', json.loads(result.stdout)['suite'])
        self.assertEqual('unverified', json.loads(result.stdout)['physical_validation'])

    def test_terminal_wait_checks_request_and_each_scope_result(self):
        with tempfile.TemporaryDirectory() as d:
            run = automation.AutomationRunner('tv', pathlib.Path(d), 'pkg'); run.results = []
            expected = {'request_id': 'id', 'state': 'partial', 'parts': {'warmth': {'state': 'applied'}, 'brightness': {'state': 'failed'}}}
            with patch.object(run, 'state', return_value={'results': [{'request_id': 'unrelated', 'state': 'applied'}, expected]}):
                self.assertEqual(expected, run.wait_request('id'))
            with self.assertRaises(RuntimeError): run.expect(expected, 'applied')

    def test_checked_never_persists_the_pairing_token(self):
        with tempfile.TemporaryDirectory() as d:
            run = automation.AutomationRunner('tv', pathlib.Path(d), 'pkg'); run.token = 'private'
            run.destination.mkdir(parents=True, exist_ok=True)
            with patch.object(run, 'run', return_value=(0, 'result=private', '')):
                self.assertEqual('result=private', run.checked(['shell', '--es', 'token', 'private']))
            combined = ''.join(p.read_text() for p in pathlib.Path(d).iterdir())
            self.assertTrue(list(pathlib.Path(d).glob('automation-action-*.json')))
            self.assertIn('[REDACTED]', combined)
            self.assertNotIn('private', combined)

    def test_real_cli_contract_suite_uses_exact_broadcasts_and_redacts_archive(self):
        import os
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); adb = root / 'adb'
            adb.write_text('#!' + sys.executable + '\n' + '''
import sys,os,pathlib,json,shlex
args=sys.argv[3:];root=pathlib.Path(os.environ['FAKE_ROOT']);state=root/'ledger.json'
rows=json.loads(state.read_text()) if state.exists() else []
if args==['get-state']:print('device');sys.exit()
w=shlex.split(args[1]) if len(args)==2 and args[0]=='shell' else args[1:]
if w==['getprop','ro.product.device']:print('xiaoyushan');sys.exit()
if w[:2]==['pm','path']:print('package:/installed/base.apk');sys.exit()
if args and args[0]=='pull':pathlib.Path(args[-1]).write_bytes(b'fake installed apk');sys.exit()
if w[:2]==['content','query']:
 uri=w[w.index('--uri')+1]
 report={'api_version':1,'enabled':True,'brightness':None,'warmth':None,'results':rows} if uri.endswith('/automation') else {'schema':5,'package':'com.cbkii.redmoontv.testing','runtime':{'pending':0}}
 print('Row: 0 report='+json.dumps(report));sys.exit()
if w[:2]==['content','call'] and w[w.index('--method')+1]=='automation_pairing':
 print('Bundle[{result='+json.dumps({'enabled':True,'token':'private-token'})+'}]');sys.exit()
if w[:2]==['am','broadcast'] or (w[:2]==['content','call'] and 'automation' in w):
 if w[:2]==['am','broadcast']:
  assert w[w.index('-a')+1]=='com.cbkii.redmoontv.AUTOMATION'
  assert w[w.index('-n')+1]=='com.cbkii.redmoontv.testing/com.jmstudios.redmoon.tv.TvControlReceiver'
 data={}
 if w[0]=='am':
  for i,v in enumerate(w[:-2]):
   if v in ('--es','--ei','--ez'):data[w[i+1]]=int(w[i+2]) if v=='--ei' else (w[i+2]=='true' if v=='--ez' else w[i+2])
 else:data=json.loads(w[w.index('--arg')+1])
 old=next((r for r in rows if r['request_id']==data['request_id']),None)
 rejected=data.get('token')!='private-token' or data.get('phase')=='INVALID' or isinstance(data.get('value'),str) or 'backend' in data or (old and old['command']!=data['command'])
 if rejected:print('Broadcast completed: result=0, data="rejected"');sys.exit()
 if not old:
  old={'request_id':data['request_id'],'command':data['command'],'state':'applied','parts':{'control':{'state':'applied'}}};rows.append(old);state.write_text(json.dumps(rows))
 print('Broadcast completed: result=-1' if w[0]=='am' else 'Bundle[{result='+json.dumps(old)+'}]');sys.exit()
print('unavailable')
''')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'], FAKE_ROOT=d)
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/a4-automation.py'), '-s', 'tv', '--execute',
                                     '--suite', 'contracts', '--output', d], capture_output=True, text=True, env=env, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(next(root.glob('*.zip'))) as z:
                files = [z.read(n) for n in z.namelist() if not n.endswith('.apk')]
                self.assertNotIn(b'private-token', b'\n'.join(files))
                self.assertTrue(any(n.endswith('/results.json') for n in z.namelist()))
            self.assertNotIn('private-token', result.stdout + result.stderr)
