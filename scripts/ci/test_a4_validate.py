"""ADB validator contracts: quoting, admission, evidence and unknown-outcome behaviour."""
import importlib.util
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('a4_validate', pathlib.Path(__file__).parents[1] / 'a4-validate.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

class ValidatorTests(unittest.TestCase):
    def test_remote_json_is_one_literal_argument_even_with_shell_metacharacters(self):
        data = json.dumps({'text': "x'; $(touch surprise); `id`\nline"})
        args = ['content', 'call', '--arg', data]
        self.assertEqual(args, shlex.split(validator.shell_args(args)[1]))

    def test_unknown_actions_shell_and_unbounded_requests_are_rejected(self):
        bad = [[{'action': 'shell', 'command': 'anything'}],
               [{'action': 'wait', 'seconds': 951}],
               [{'action': 'key', 'key': 'ERASE'}],
               [{'action': 'launch', 'component': 'x;id'}],
               [{'action': 'command', 'name': 'arbitrary-setter'}],
               [{'action': 'command', 'name': 'read', 'unexpected': True}]]
        for plan in bad:
            with self.subTest(plan=plan), self.assertRaises(ValueError): validator.validate_plan(plan)

    def test_unknown_command_result_is_never_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            run = validator.Validator('tv', pathlib.Path(directory), validator.collect.PACKAGES[0])
            with patch.object(run, 'checked', return_value='Bundle[{result={"request_id":"abc-123"}}]') as send, \
                 patch.object(run, 'provider', return_value=None), patch.object(validator.time, 'sleep'):
                with self.assertRaisesRegex(RuntimeError, 'outcome unknown'): run.command('read')
                self.assertEqual(1, send.call_count)

    def test_acknowledged_app_rejection_fails_without_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            run = validator.Validator('tv', pathlib.Path(directory), validator.collect.PACKAGES[0])
            with patch.object(run, 'checked', return_value='Bundle[{result={"request_id":"abc-123"}}]') as send, \
                 patch.object(run, 'provider', return_value={'adb_request':'abc-123','adb_error':'busy'}), \
                 patch.object(validator.time, 'sleep'):
                with self.assertRaisesRegex(RuntimeError, 'busy'): run.command('test_fade', {'seconds':0,'delta':-5})
                self.assertEqual(1, send.call_count)

    def test_fade_requires_target_evidence_and_real_restoration(self):
        before = {'native_state':'ready','native_level':66,'write_context_ready':True}
        after = {'native_level':66,'probe_restored':True,'recovery_pending':False,
                 'test':{'at':1000,'target':61}, 'trace':[{'at':1001,'operation':'write','level':61}]}
        with tempfile.TemporaryDirectory() as directory:
            run = validator.Validator('tv', pathlib.Path(directory), validator.collect.PACKAGES[0]); run.results=[]
            with patch.object(run, 'command', side_effect=[before, {}]), patch.object(run, 'wait'), \
                 patch.object(run, 'sample', return_value=after): run.fade(0)
            self.assertEqual('passed', run.results[-1]['software'])
            after['trace'] = []
            with patch.object(run, 'command', side_effect=[before, {}]), patch.object(run, 'wait'), \
                 patch.object(run, 'sample', return_value=after), self.assertRaises(RuntimeError): run.fade(0)

    def test_dry_run_does_not_require_or_invoke_adb(self):
        result = subprocess.run([sys.executable, str(pathlib.Path(validator.__file__)), '-s','tv','--command','read'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('read', json.loads(result.stdout)['command'])
