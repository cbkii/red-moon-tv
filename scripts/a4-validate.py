#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Execute bounded A4 validation through ADB; optical results remain separate observations.
Python 3.8+ stdlib, existing adb. Run outside adb shell. No TV staging files.
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

spec = importlib.util.spec_from_file_location('a4_collect', pathlib.Path(__file__).with_name('a4-collect.py'))
collect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collect)

COMMANDS = {'read', 'read_restart', 'use_root_read', 'use_apk_read', 'probe', 'restore', 'keep', 'pause',
            'resume', 'phase', 'warmth', 'warmth_off', 'boundary', 'test_fade', 'configure', 'save_config',
            'restore_config', 'acknowledge', 'close_worker', 'ensure_running', 'fault'}
KEYS = {'HOME': 3, 'BACK': 4, 'UP': 19, 'DOWN': 20, 'LEFT': 21, 'RIGHT': 22, 'OK': 23,
        'SETTINGS': 176, 'INPUT': 178, 'TV': 170, 'HDMI1': 243, 'SLEEP': 223, 'WAKE': 224}


def shell_args(values):
    # adb joins shell arguments; quote once for the remote shell, including JSON/URIs.
    return ['shell', ' '.join(shlex.quote(str(v)) for v in values)]


def validate_plan(steps):
    if not isinstance(steps, list) or len(steps) > 200:
        raise ValueError('Expected at most 200 declarative steps')
    for step in steps:
        if not isinstance(step, dict) or 'action' not in step:
            raise ValueError('Each step needs an action')
        action = step['action']
        fields = {'command': {'name', 'data'}, 'key': {'key'}, 'wait': {'seconds'},
                  'checkpoint': {'label'}, 'observe': {'path'}, 'launch': {'component', 'uri'},
                  'schedule_nearby': {'fade'}, 'wait_boundary': {'grace'}, 'remote': {'purpose'}, 'assert': {'field', 'equals'}, 'force_stop': set(), 'relaunch': set(), 'reboot': set()}
        if action not in fields or set(step) - fields[action] - {'action'}:
            raise ValueError('Unknown action or field')
        if action == 'command' and step.get('name') not in COMMANDS:
            raise ValueError('Unknown app command')
        if action == 'command' and not isinstance(step.get('data', {}), dict):
            raise ValueError('Command data must be an object')
        if action == 'schedule_nearby' and (type(step.get('fade', 5)) is not int or not 0 <= step.get('fade', 5) <= 30):
            raise ValueError('Nearby schedule fade must be 0..30 seconds')
        if action == 'wait_boundary' and (type(step.get('grace', 10)) is not int or not 0 <= step.get('grace', 10) <= 30):
            raise ValueError('Boundary grace must be 0..30 seconds')
        if action == 'assert' and not re.fullmatch(r'[a-z_]+(?:\.[a-z_]+)*', step.get('field', '')):
            raise ValueError('Assertion needs a bounded report field')
        if action == 'key' and step.get('key') not in KEYS:
            raise ValueError('Unknown remote key')
        if action == 'wait' and (type(step.get('seconds')) is not int or not 0 <= step['seconds'] <= 950):
            raise ValueError('Wait must be 0..950 seconds')
        if action == 'launch' and not re.fullmatch(r'[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+', step.get('component', '')):
            raise ValueError('An exact component is required')
        if len(json.dumps(step)) > 8192:
            raise ValueError('Step exceeds bounded request size')
    return steps


class Validator(collect.Collector):
    def archive(self, stamp, label, device, duration, guided, interrupted):
        return super().archive(stamp, label, device, duration, guided, interrupted, read_only=False)

    def checked(self, args, timeout=20):
        code, out, err = self.run(args, timeout)
        name = 'action-%04d.json' % len(self.records)
        event = {'utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'command': args, 'exit_code': code,
                 'stdout': out[:4096], 'stderr': err[:4096]}
        if self.destination.is_dir():
            (self.destination / name).write_text(json.dumps(event, indent=2), encoding='utf-8')
        self.records.append({'file': name, 'exit_code': code, 'command': args})
        if code or 'Exception' in out or 'Error:' in out:
            raise RuntimeError('ADB command failed without retry: ' + (err or out)[:1500])
        return out

    def command(self, name, data=None):
        if name not in COMMANDS:
            raise ValueError('Unknown command')
        out = self.checked(shell_args(['content', 'call', '--uri', 'content://' + self.package + '.diagnostics',
                                      '--method', name, '--arg', json.dumps(data or {}, separators=(',', ':'))]))
        found = re.search(r'"request_id"\s*:\s*"([a-f0-9-]+)"', out)
        if not found:
            raise RuntimeError('App did not acknowledge the request: ' + out[:1500])
        request = found.group(1)
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            time.sleep(0.5)
            report = self.provider('command-' + request)
            if report is None:
                raise RuntimeError('Report transport failed; command outcome unknown, not retried')
            if report.get('adb_request') == request:
                if report.get('adb_error') not in (None, 'none'):
                    raise RuntimeError('App rejected command: ' + report['adb_error'])
                if report.get('runtime', {}).get('pending', 0) == 0:
                    return report
        raise RuntimeError('Command deadline exceeded; collect and reconcile, do not replay')

    def wait(self, seconds, prefix='wait'):
        deadline = time.monotonic() + seconds
        index = 0
        while time.monotonic() < deadline:
            time.sleep(min(5, max(0, deadline - time.monotonic())))
            index += 1
            report = self.provider('%s-%03d' % (prefix, index))
            if report is None:
                raise RuntimeError('ADB/report failure during observation')
            self.reports[prefix] = report
            if index % 6 == 0:
                print('%s: %ss remaining; level=%s; state=%s' %
                      (prefix, round(deadline - time.monotonic()), report.get('native_level'), report.get('session_state')), flush=True)
        return self.reports.get(prefix)

    def fade(self, seconds):
        before = self.command('read')
        if before.get('native_state') != 'ready' or not before.get('write_context_ready'):
            raise RuntimeError('Native context/read unavailable; preserving diagnostics')
        level = before['native_level']
        self.command('test_fade', {'seconds': seconds, 'delta': -5 if level > 50 else 5})
        self.wait(seconds + 20, 'fade-%s' % seconds)
        after = self.sample('fade-%s-restored' % seconds)
        trace = after.get('trace', []) if after else []
        started = after.get('test', {}).get('at', 0) if after else 0
        writes = [e for e in trace if e.get('at', 0) >= started and e.get('operation') == 'write']
        target = after.get('test', {}).get('target') if after else None
        intervals = [b['at'] - a['at'] for a, b in zip(writes, writes[1:])]
        passed = bool(after and after.get('probe_restored') and after.get('native_level') == level
                      and not after.get('recovery_pending') and any(e.get('level') == target for e in writes)
                      and len(writes) <= 5 and all(ms >= 450 for ms in intervals))
        self.results.append({'test': 'fade-%s' % seconds, 'software': 'passed' if passed else 'failed',
                             'physical': 'unverified', 'baseline': level, 'writes': len(writes), 'write_intervals_ms': intervals,
                             'evidence': 'fade-%s-restored-report.json' % seconds})
        if not passed:
            raise RuntimeError('Fade/restoration failed; inspect recovery before any further test')

    def plan(self, steps):
        for index, step in enumerate(validate_plan(steps)):
            name = step['action']
            print('Step %d: %s' % (index + 1, name), flush=True)
            if name == 'schedule_nearby':
                clock = self.checked(['shell', 'date', '+%H:%M']).strip()
                if not re.fullmatch(r'[0-2][0-9]:[0-5][0-9]', clock): raise RuntimeError('TV clock unavailable')
                h, m = map(int, clock.split(':')); minute = h * 60 + m
                phases = [{'phase':p, 'level':level, 'start':(minute + offset) % 1440, 'fade':step.get('fade', 5)}
                          for p,level,offset in [('DAY',80,-1),('DUSK',40,3),('DIM',5,6)]]
                self.command('configure', {'phases':phases})
            elif name == 'wait_boundary':
                report = self.provider('next-boundary-%03d' % index)
                if not report: raise RuntimeError('No boundary report')
                remaining = (report.get('schedule', {}).get('next_at', 0) - report.get('report_at', 0)) / 1000 + step.get('grace', 10)
                if not 0 <= remaining <= 950: raise RuntimeError('Next boundary outside bounded test window; use schedule_nearby')
                self.wait(remaining, 'boundary-%03d' % index)
            elif name == 'remote':
                if not sys.stdin.isatty(): raise RuntimeError('Remote actions require an interactive terminal')
                print(step.get('purpose', 'Operate the TV through ADB keys, then enter DONE.'))
                print('Keys: ' + ', '.join(KEYS) + '; DONE continues; no other commands accepted.')
                while True:
                    key = input('ADB remote> ').strip().upper()
                    if key == 'DONE': break
                    if key in KEYS: self.checked(['shell', 'input', 'keyevent', str(KEYS[key])])
            elif name == 'assert':
                report = self.provider('assert-%03d' % index)
                value = report
                for key in step['field'].split('.'):
                    value = value.get(key) if isinstance(value, dict) else None
                passed = value == step['equals']
                self.results.append({'test': 'assert-' + step['field'], 'software': 'passed' if passed else 'failed', 'actual': value, 'expected': step['equals']})
                if not passed: raise RuntimeError('Assertion failed: ' + step['field'])
            elif name == 'command': self.command(step['name'], step.get('data'))
            elif name == 'wait': self.wait(step['seconds'], 'step-%03d' % index)
            elif name == 'checkpoint': self.sample(re.sub('[^A-Za-z0-9_-]', '_', step['label'])[:50])
            elif name == 'key': self.checked(['shell', 'input', 'keyevent', str(KEYS[step['key']])])
            elif name == 'launch':
                args = ['am', 'start', '-W', '-n', step['component']]
                if step.get('uri'): args += ['-a', 'android.intent.action.VIEW', '-d', step['uri']]
                self.checked(shell_args(args))
            elif name == 'force_stop': self.checked(['shell', 'am', 'force-stop', self.package])
            elif name == 'relaunch':
                self.checked(['shell', 'am', 'start', '-W', '-n', self.package + '/com.jmstudios.redmoon.tv.TvActivity'])
                self.wait(5, 'relaunch-%03d' % index)
            elif name == 'reboot':
                self.checked(['reboot'])
                self.checked(['wait-for-device'], timeout=120)
                self.wait(30, 'reboot-%03d' % index)
            elif name == 'observe':
                self.sample('path-' + re.sub('[^A-Za-z0-9_-]', '_', step['path'])[:35])
                if not sys.stdin.isatty():
                    self.observations.append({'path': step['path'], 'brightness': 'unknown', 'warmth': 'unknown'})
                else:
                    self.ask(step['path'] + '-brightness', 'Physical native backlight visible on %s? yes/no/unknown' % step['path'])
                    self.ask(step['path'] + '-warmth', 'Warmth visible on %s actual video (not just OSD)? yes/no/unknown' % step['path'])


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('-s', '--serial', required=True)
    p.add_argument('--package', choices=collect.PACKAGES, default=collect.PACKAGES[0])
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path.cwd())
    p.add_argument('--backend', choices=('root', 'apk'), default=None)
    p.add_argument('--fade-seconds', nargs='*', type=int, default=[0, 5, 888])
    p.add_argument('--command', choices=sorted(COMMANDS), help='Single maintenance operation; no automatic configuration changes/cleanup')
    p.add_argument('--data', default='{}', help='JSON object for --command')
    p.add_argument('--plan', type=pathlib.Path, help='Declarative lifecycle/input/playback steps; see docs/a4-validation-plan.json')
    p.add_argument('--execute', action='store_true', help='Run bounded app operations and any explicitly supplied plan steps')
    args = p.parse_args(argv)
    if len(args.fade_seconds) > 12 or any(not 0 <= s <= 888 for s in args.fade_seconds):
        p.error('At most 12 fades of 0..888 seconds')
    steps = validate_plan(json.loads(args.plan.read_text()) if args.plan else [])
    if not args.execute:
        print(json.dumps({'package': args.package, 'fades': args.fade_seconds, 'steps': steps, 'command': args.command, 'data': json.loads(args.data),
                          'changes': 'save settings, pause, restore prior owned level, selected backend read, bounded fades/restoration, restore settings'}, indent=2))
        return 0
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    destination = args.output / ('a4-validation-' + stamp)
    run = Validator(args.serial, destination, args.package)
    if run.checked(['get-state']).strip() != 'device': raise RuntimeError('Selected ADB transport unavailable')
    device = run.checked(['shell', 'getprop', 'ro.product.device']).strip()
    if not device.startswith('xiaoyushan'): raise RuntimeError('Selected device is not the established A4')
    run.checked(['shell', 'pm', 'path', args.package])
    destination.mkdir(parents=True, exist_ok=False)
    run.results = []
    interrupted = False
    saved = False
    single = args.command is not None
    try:
        run.apk(); initial = run.sample('before')
        if not initial or initial.get('schema', 0) < 4: raise RuntimeError('Update to the ADB-validation APK first')
        if initial.get('recovery_pending') and not single: raise RuntimeError('Existing uncertain recovery must be resolved before testing')
        if single:
            run.command(args.command, json.loads(args.data))
        else:
            run.command('save_config'); saved = True
            run.command('pause')
            if initial.get('journal_present'): run.command('restore')
            if args.backend: run.command('use_' + args.backend + '_read')
            for seconds in args.fade_seconds: run.fade(seconds)
            run.plan(steps)
    except (KeyboardInterrupt, EOFError, RuntimeError, ValueError) as error:
        interrupted = True
        (destination / 'failure.txt').write_text(str(error) + '\n', encoding='utf-8')
        print('Stopped: ' + str(error), file=sys.stderr)
    finally:
        try:
            # Commands are acknowledged, never replayed on transport uncertainty.
            if not single: run.command('pause')
            final = run.sample('before-cleanup')
            if not single and final and final.get('probe_active'): run.command('restore')
            final = run.sample('after-restoration')
            if saved and final and not final.get('recovery_pending') and not final.get('journal_present'):
                run.command('restore_config')
            elif saved:
                (destination / 'cleanup-needed.txt').write_text('Configuration backup retained in app. Reconcile native recovery then run restore_config.\n')
        except RuntimeError as error:
            interrupted = True
            (destination / 'cleanup-error.txt').write_text(str(error) + '\n')
        run.sample('final'); run.logs('vendor-log.txt')
        (destination / 'results.json').write_text(json.dumps(run.results, indent=2))
        (destination / 'observations.json').write_text(json.dumps(run.observations, indent=2))
        run.archive(stamp, 'validation', device, 0, bool(steps), interrupted)
    return 1 if interrupted else 0


if __name__ == '__main__':
    sys.exit(main())
