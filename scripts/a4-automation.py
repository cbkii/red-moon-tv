#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded ADB automation runner. Dry-run by default; no TV staging files or token logging.
Actual tvQuickActions invocation is recorded separately from shell/provider validation.
"""
import argparse
import datetime as dt
import importlib.util
import json
import pathlib
import re
import shlex
import sys
import time
import uuid

spec = importlib.util.spec_from_file_location('a4_validate', pathlib.Path(__file__).with_name('a4-validate.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
COMMANDS = {'brightness_pause', 'brightness_resume', 'brightness_phase', 'brightness_set', 'brightness_adjust',
            'warmth_enable', 'warmth_set', 'warmth_adjust', 'preset_apply', 'snooze', 'resume_auto',
            'cancel_transition', 'reconcile', 'show_status', 'open_controls'}
TERMINAL = {'applied', 'cancelled', 'rejected', 'failed', 'partial'}


def object_from_output(output):
    # content query/call and ordered-broadcast resultData all carry one JSON object.
    start = output.find('{"')
    if start < 0:
        raise RuntimeError('No structured app response; transport outcome unknown')
    return json.JSONDecoder().raw_decode(output[start:])[0]


def redact(value, token):
    text = json.dumps(value, ensure_ascii=False)
    return json.loads(text.replace(token, '[REDACTED]')) if token else value


def broadcast_args(package, data):
    args = ['am', 'broadcast', '--receiver-foreground', '-n', package + '/com.jmstudios.redmoon.tv.TvControlReceiver',
            '-a', 'com.cbkii.redmoontv.AUTOMATION']
    for key, value in data.items():
        if not re.fullmatch(r'[a-z_]{1,32}', key):
            raise ValueError('Invalid extra key')
        if type(value) is bool:
            args += ['--ez', key, 'true' if value else 'false']
        elif type(value) is int:
            args += ['--ei', key, str(value)]
        elif type(value) is str and len(value) <= 256:
            args += ['--es', key, value]
        else:
            raise ValueError('Only bounded typed primitive extras')
    return base.shell_args(args)


class AutomationRunner(base.Validator):
    token = ''

    def checked(self, args, timeout=20):
        # Do not use Validator.checked: it records the unredacted command and output.
        code, out, err = self.run(args, timeout)
        event = redact({'command': args, 'exit_code': code, 'stdout': out[:16384], 'stderr': err[:4096]}, self.token)
        if self.destination.is_dir():
            name = 'automation-action-%04d.json' % len(self.records)
            (self.destination / name).write_text(json.dumps(event, indent=2), encoding='utf-8')
            self.records.append({'file': name, 'exit_code': code})
        if code or 'Exception' in out or 'Error:' in out:
            raise RuntimeError('ADB/app request failed without replay: ' + str(redact(err or out, self.token))[:1000])
        return out

    def pairing(self):
        # Fixed shell-only call; deliberately bypass all recording for this sensitive result.
        code, out, _ = self.run(base.shell_args(['content', 'call', '--uri', 'content://' + self.package + '.diagnostics',
                                              '--method', 'automation_pairing']), 12)
        if code:
            raise RuntimeError('Protected pairing query unavailable')
        value = object_from_output(out)
        if not value.get('enabled'):
            raise RuntimeError('Enable paired automation on the TV first; this runner never grants consent')
        self.token = value['token']

    def state(self, label='state'):
        output = self.checked(base.shell_args(['content', 'query', '--uri',
                              'content://' + self.package + '.diagnostics/automation']))
        value = object_from_output(output)
        (self.destination / ('automation-%s.json' % label)).write_text(json.dumps(redact(value, self.token), indent=2), encoding='utf-8')
        return value

    def request(self, command, data=None, via='broadcast', request_id=None, wait=True, token=None, timeout=45):
        if command not in COMMANDS:
            raise ValueError('Unknown automation command')
        body = dict(data or {})
        if any(k in body for k in ('token', 'request_id', 'command', 'api_version')):
            raise ValueError('Reserved command-envelope fields')
        body.update(api_version=1, command=command, token=self.token if token is None else token,
                    request_id=request_id or str(uuid.uuid4()))
        if via == 'broadcast':
            output = self.checked(broadcast_args(self.package, body))
            # am escapes JSON inside quoted resultData; actual outcome comes from protected history.
            rejected = 'result=0' in output and 'rejected' in output
            if rejected:
                return {'request_id': body['request_id'], 'state': 'rejected', 'transport': 'ordered_broadcast'}
        else:
            output = self.checked(base.shell_args(['content', 'call', '--uri', 'content://' + self.package + '.diagnostics',
                                   '--method', 'automation', '--arg', json.dumps(body, separators=(',', ':'))]))
        if not wait:
            return {'request_id': body['request_id'], 'state': 'queued'}
        return self.wait_request(body['request_id'], timeout)

    def wait_request(self, request_id, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            report = self.state('poll')
            row = next((r for r in report.get('results', []) if r.get('request_id') == request_id), None)
            if row and row.get('state') in TERMINAL:
                self.results.append(row)
                return row
            time.sleep(0.5)
        raise RuntimeError('Automation outcome unknown at deadline; not replayed: ' + request_id)

    def expect(self, row, *states):
        if row.get('state') not in states:
            raise RuntimeError('Unexpected automation result: ' + json.dumps(row))
        return row

    def contracts(self):
        self.expect(self.request('show_status'), 'applied')
        request_id = str(uuid.uuid4())
        first = self.expect(self.request('show_status', request_id=request_id), 'applied')
        again = self.expect(self.request('show_status', request_id=request_id), 'applied')
        if first != again:
            raise RuntimeError('Duplicate request changed original result')
        self.expect(self.request('show_status', token='revoked-test-token'), 'rejected')
        self.expect(self.request('brightness_phase', {'phase': 'INVALID'}), 'rejected')
        self.expect(self.request('brightness_set', {'value': '10'}), 'rejected')
        self.expect(self.request('show_status', {'backend': 'root'}), 'rejected')
        self.expect(self.request('reconcile', request_id=request_id), 'rejected')
        # Query + equivalent trusted-provider route exercises the same shared parser.
        self.expect(self.request('show_status', via='provider'), 'applied')

    def controls(self):
        self.expect(self.request('brightness_pause'), 'applied')
        self.expect(self.request('warmth_enable', {'enabled': True}), 'applied')
        self.expect(self.request('warmth_set', {'value': 20, 'fade_seconds': 0, 'expiry': 'duration', 'duration_seconds': 3}), 'applied')
        time.sleep(5)
        if self.state('expired')['warmth'] is not None:
            raise RuntimeError('Timed warmth override did not expire')
        self.expect(self.request('preset_apply', {'phase': 'DIM', 'scope': 'both', 'fade_seconds': 0}), 'applied')
        self.expect(self.request('brightness_adjust', {'delta': 2}), 'applied')
        self.expect(self.request('brightness_adjust', {'delta': -2}), 'applied')
        floor = self.provider('dim-caps')['settings']['DIM']['level']
        self.expect(self.request('brightness_set', {'value': max(10, floor), 'fade_seconds': 0}), 'applied')
        self.expect(self.request('warmth_adjust', {'delta': 5}), 'applied')
        for phase in ('DAY', 'DUSK', 'DIM'):
            self.expect(self.request('brightness_phase', {'phase': phase, 'fade_seconds': 0}), 'applied')
        self.expect(self.request('snooze', {'scope': 'both', 'duration_seconds': 3}), 'applied')
        time.sleep(5)
        self.expect(self.request('cancel_transition', {'scope': 'both'}), 'applied')
        self.expect(self.request('resume_auto', {'scope': 'both'}), 'applied')
        self.expect(self.request('brightness_resume'), 'applied')
        self.expect(self.request('warmth_enable', {'enabled': False}), 'applied')
        self.expect(self.request('reconcile'), 'applied')
        self.expect(self.request('open_controls'), 'applied')

    def rapid(self):
        self.expect(self.request('brightness_phase', {'phase': 'DAY', 'fade_seconds': 0}), 'applied')
        first = self.state('rapid-before')['brightness']['value']
        ids = [self.request('brightness_adjust', {'delta': -1}, wait=False)['request_id'] for _ in range(8)]
        rows = [self.wait_request(i) for i in ids]
        if any(r['state'] not in ('applied', 'cancelled') for r in rows):
            raise RuntimeError('Unexpected rapid-press failure')
        after = self.state('rapid-after')['brightness']['value']
        if after != max(70, first - 8):
            raise RuntimeError('Rapid adjustments did not accumulate from the desired target')

    def fade_cancel(self):
        self.expect(self.request('brightness_phase', {'phase': 'DIM', 'fade_seconds': 0}), 'applied')
        fade = self.request('brightness_set', {'value': 20, 'fade_seconds': 30}, wait=False)
        time.sleep(2)
        self.expect(self.request('cancel_transition', {'scope': 'brightness'}), 'applied')
        self.expect(self.wait_request(fade['request_id']), 'cancelled')
        before = self.provider('cancel-before')
        self.wait(5, 'cancel-settle')
        after = self.provider('cancel-after')
        if before['metrics']['writes'] != after['metrics']['writes']:
            raise RuntimeError('Stale writes continued after cancellation')


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('-s', '--serial', required=True)
    p.add_argument('--package', choices=base.collect.PACKAGES, default=base.collect.PACKAGES[0])
    p.add_argument('--command', choices=sorted(COMMANDS))
    p.add_argument('--data', default='{}', help='JSON primitive arguments, excluding token/envelope fields')
    p.add_argument('--via', choices=('broadcast', 'provider'), default='broadcast')
    p.add_argument('--suite', choices=('contracts', 'controls', 'rapid', 'fade_cancel', 'tvqa'), default='contracts')
    p.add_argument('--timeout', type=int, default=45)
    p.add_argument('--execute', action='store_true')
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path.cwd())
    args = p.parse_args(argv)
    data = json.loads(args.data)
    if not isinstance(data, dict) or len(args.data) > 2048 or not 1 <= args.timeout <= 950:
        p.error('Bounded object data and timeout 1..950 required')
    if args.suite == 'tvqa' and args.execute and not sys.stdin.isatty():
        p.error('tvqa requires a terminal for remote-only observations')
    if not args.execute:
        print(json.dumps({'command': args.command, 'data': data, 'suite': args.suite, 'package': args.package,
                          'via': args.via, 'physical_validation': 'unverified'}, indent=2)); return 0
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run = AutomationRunner(args.serial, args.output / ('a4-automation-' + stamp), args.package)
    run.results = []; saved = False; interrupted = False
    if run.checked(['get-state']).strip() != 'device':
        raise RuntimeError('Selected ADB transport unavailable')
    device = run.checked(['shell', 'getprop', 'ro.product.device']).strip()
    if not device.startswith('xiaoyushan'):
        raise RuntimeError('Selected device is not the established A4')
    run.destination.mkdir(parents=True, exist_ok=False)
    try:
        run.pairing(); run.state('before'); run.apk(); run.provider('before')
        if args.command:
            run.expect(run.request(args.command, data, args.via, timeout=args.timeout), 'applied')
        elif args.suite == 'tvqa':
            run.ask('tvqa-setup', 'In tvQuickActions create a Red Moon shortcut and assign it to a remote button. Press Enter once saved.')
            run.state('tvqa-before')
            run.ask('tvqa-invoke', 'Start real playback and invoke that assigned action with the physical remote. Note any pause/focus change. Press Enter afterwards.')
            report = run.state('tvqa-after')
            run.ask('tvqa-observation', 'Describe the visible brightness/warmth effect and playback/focus behaviour. Numeric results are already collected.')
            run.results.append({'test': 'tvquickactions_invocation', 'software': 'recorded',
                                'physical': 'operator_observation', 'results_count': len(report.get('results', []))})
        elif args.suite == 'contracts':
            run.contracts()
        else:
            if run.state('preflight')['brightness'] is not None or run.state('preflight')['warmth'] is not None:
                raise RuntimeError('Clear existing temporary automation before reversible experiments')
            report = run.provider('preflight')
            if not report or report.get('recovery_pending') or not report.get('native_acknowledged'):
                raise RuntimeError('Resolve recovery and qualify native output first; no automatic acknowledgement')
            run.command('save_config'); saved = True
            run.command('pause')
            if report.get('journal_present'): run.command('restore')
            {'controls': run.controls, 'rapid': run.rapid, 'fade_cancel': run.fade_cancel}[args.suite]()
    except (KeyboardInterrupt, EOFError, RuntimeError, ValueError) as error:
        interrupted = True
        (run.destination / 'failure.txt').write_text(str(redact(str(error), run.token)) + '\n', encoding='utf-8')
        print(str(redact(str(error), run.token)), file=sys.stderr)
    finally:
        if saved:
            try:
                run.expect(run.request('resume_auto', {'scope': 'warmth'}), 'applied')
                run.expect(run.request('brightness_pause'), 'applied')
                report = run.provider('cleanup')
                if report and report.get('journal_present'): run.command('restore')
                run.command('restore_config')
            except RuntimeError as error:
                interrupted = True
                (run.destination / 'cleanup-needed.txt').write_text(str(redact(str(error), run.token)) + '\nSaved configuration retained; reconcile in Diagnostics.\n')
        try:
            run.state('final'); run.provider('final')
            run.save('automation-log.txt', ['logcat', '-d', '-t', '250', '-v', 'threadtime', '-s', 'RedMoonAutomation:I', 'RedMoonTV:I'])
        except RuntimeError:
            interrupted = True
        (run.destination / 'results.json').write_text(json.dumps(run.results, indent=2), encoding='utf-8')
        run.archive(stamp, 'automation', device, 0, args.suite == 'tvqa', interrupted)
    return 1 if interrupted else 0


if __name__ == '__main__':
    sys.exit(main())
