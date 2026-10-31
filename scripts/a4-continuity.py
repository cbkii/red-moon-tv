#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""ADB-only startup/liveness experiments. Dry-run by default; Python 3.8+ and existing adb."""
import argparse
import datetime as dt
import importlib.util
import json
import pathlib
import re
import shlex
import sys
import time

spec = importlib.util.spec_from_file_location('a4_validate', pathlib.Path(__file__).with_name('a4-validate.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
SCENARIOS = ('status', 'boot', 'standby', 'restart', 'worker', 'overlay', 'readiness', 'access', 'uncertain', 'upgrade', 'force-stop', 'disabled', 'soak', 'all')
MODULE = '/data/adb/modules/redmoontv_supervisor'  # Magisk module operations only.


class Continuity(base.Validator):
    def root(self, words, timeout=25):
        if not self.allow_root:
            raise RuntimeError('This experiment requires explicit --root')
        if self.checked(base.shell_args(['su', '-c', 'id -u'])).strip() != '0':
            raise RuntimeError('Selected TV root not granted')
        return self.checked(base.shell_args(['su', '-c', shlex.join(words)]), timeout)

    def report(self, label):
        result = self.provider(label)
        if not result or result.get('schema', 0) < 5:
            raise RuntimeError('Update to the startup-continuity APK (report schema 5)')
        return result

    def expect(self, label, field, expected):
        result = self.report(label)
        actual = result
        for key in field.split('.'):
            actual = actual.get(key) if isinstance(actual, dict) else None
        self.results.append({'test': label, 'field': field, 'expected': expected, 'actual': actual,
                             'software': 'passed' if actual == expected else 'failed', 'physical': 'unverified'})
        if actual != expected:
            raise RuntimeError('%s: %s is %r, expected %r' % (label, field, actual, expected))
        return result

    def reconnect(self, old_boot=None, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            code, state, _ = self.run(['get-state'], timeout=5)
            if code == 0 and state.strip() == 'device':
                code, boot, _ = self.run(['shell', 'cat', '/proc/sys/kernel/random/boot_id'], timeout=5)
                if code == 0 and boot.strip() and (old_boot is None or boot.strip() != old_boot):
                    return boot.strip()
            if re.fullmatch(r'[A-Za-z0-9_.-]+:[0-9]{1,5}', self.adb[2]):
                self.run(['connect', self.adb[2]], timeout=5)
            time.sleep(2)
        raise RuntimeError('ADB did not reconnect to the expected boot; evidence retained')

    def wait_running(self, label, seconds=360):
        # Observe ActivityManager first. Provider queries themselves can start the process.
        began = time.monotonic()
        deadline = began + seconds
        while time.monotonic() < deadline:
            code, dump, _ = self.run(['shell', 'dumpsys', 'activity', 'services', self.package], timeout=10)
            if code == 0 and 'TvDisplayService' in dump and re.search(r'isForeground=true', dump):
                report = self.report(label)
                if report.get('service_running') and report.get('runtime', {}).get('operation') == 'idle':
                    self.results.append({'test': label, 'software': 'passed', 'physical': 'unverified',
                                         'recovery_seconds': round(time.monotonic() - began, 3),
                                         'session': report.get('runtime', {}).get('session')})
                    return report
            time.sleep(2)
        raise RuntimeError('Service did not recover in the observation window')

    def kill_named(self, worker=False):
        if not self.allow_root:
            raise RuntimeError('Process-death tests require --root; force-stop is a different experiment')
        names = [self.package + ':a4-root', self.package + ':backlight'] if worker else [self.package]
        killed = []
        for name in names:
            code, output, _ = self.run(['shell', 'pidof', name])
            if code: continue
            for raw in output.split():
                if not re.fullmatch(r'[1-9][0-9]{0,8}', raw): raise RuntimeError('Invalid PID')
                args = self.root(['cat', '/proc/' + raw + '/cmdline']).split('\x00')[0].strip()
                if args != name: raise RuntimeError('PID identity changed; no kill performed: ' + repr(args))
                self.root(['kill', '-9', raw]); killed.append({'pid': int(raw), 'name': name})
        if not killed: raise RuntimeError('No matching live process; nothing was killed')
        (self.destination / ('killed-worker.json' if worker else 'killed-main.json')).write_text(json.dumps(killed))

    def restrictions(self):
        self.save('appops.txt', ['shell', 'cmd', 'appops', 'get', self.package])
        self.save('power.txt', ['shell', 'dumpsys', 'power'])
        self.save('alarms.txt', ['shell', 'dumpsys', 'alarm'],
                  lambda text: '\n'.join(line for line in text.splitlines() if self.package in line or 'redmoontv' in line))
        self.save('services.txt', ['shell', 'dumpsys', 'activity', 'services', self.package])

    def exercise(self, scenario, seconds, apk=None):
        if scenario == 'boot':
            old = self.checked(['shell', 'cat', '/proc/sys/kernel/random/boot_id']).strip()
            self.checked(['reboot']); self.reconnect(old)
            self.wait_running('cold-boot'); self.wait(3, 'boot-settle')
            self.expect('boot-storage', 'device_protected_storage', True)
        elif scenario == 'standby':
            self.checked(['shell', 'input', 'keyevent', '223']); time.sleep(3)
            before = self.expect('standby-state', 'runtime.screen_on', False)
            writes = before.get('metrics', {}).get('writes')
            self.wait(10, 'standby')
            self.expect('standby-no-writes', 'metrics.writes', writes)
            self.checked(['shell', 'input', 'keyevent', '224']); self.wait(5, 'wake')
            self.expect('wake-state', 'runtime.screen_on', True)
        elif scenario == 'restart':
            self.kill_named(); self.wait_running('process-recovery')
        elif scenario == 'worker':
            self.kill_named(True)
            self.wait(70, 'worker-recovery')
            self.expect('worker-still-enabled', 'backlight_enabled', True)
            self.expect('worker-no-pending', 'recovery_pending', False)
        elif scenario == 'overlay':
            self.command('fault', {'fault': 'detach_overlay'})
            self.expect('overlay-detached', 'runtime.overlay_attached', False)
            self.wait(65, 'overlay-repair')
            self.expect('overlay-attached', 'runtime.overlay_attached', True)
        elif scenario == 'readiness':
            self.command('fault', {'fault': 'read_unavailable'}); self.command('read')
            self.wait(10, 'readiness-retry')
            self.expect('readiness-recovered', 'runtime.brightness_state', 'ACTIVE')
        elif scenario == 'access':
            self.command('fault', {'fault': 'access_denied'}); self.command('read')
            self.expect('access-gated', 'access_required', True)
            self.expect('access-warmth-independent', 'runtime.overlay_attached', True)
            self.command('resume'); self.wait(3, 'explicit-access-resume')
            self.expect('access-resumed', 'access_required', False)
        elif scenario == 'uncertain':
            self.command('pause'); current = self.report('uncertain-before')
            if current.get('journal_present'): self.command('restore')
            baseline = self.command('read')['native_level']
            self.command('fault', {'fault': 'lost_write_reply'})
            self.command('test_fade', {'seconds': 0, 'delta': -5 if baseline > 50 else 5})
            pending = self.expect('uncertain-retained', 'recovery_pending', True)
            writes = pending.get('metrics', {}).get('writes')
            self.command('ensure_running')
            self.expect('uncertain-not-replayed', 'metrics.writes', writes)
            self.command('restore')
            self.expect('uncertain-restored', 'native_level', baseline)
            self.command('resume')
        elif scenario == 'upgrade':
            if apk is None: raise RuntimeError('--upgrade-apk is required')
            before = self.report('upgrade-before')
            self.checked(['install', '-r', str(apk.resolve())], timeout=90)
            after = self.wait_running('package-replaced')
            self.expect('upgrade-user-intent', 'backlight_enabled', before['backlight_enabled'])
            self.results.append({'test':'upgrade-identity', 'before':before.get('native_compatibility'),
                                 'after':after.get('native_compatibility'), 'software':'recorded', 'physical':'unverified'})
        elif scenario == 'force-stop':
            self.checked(['shell', 'am', 'force-stop', self.package])
            time.sleep(3)
            # Do not query the provider or call ensure_running: those are explicit starts.
            output = self.checked(['shell', 'dumpsys', 'package', self.package])
            if not re.search(r'User 0:.*stopped=true', output): raise RuntimeError('Package stopped state not recorded')
            self.results.append({'test':'force-stop-respected','software':'passed','physical':'unverified'})
            self.checked(['shell', 'am', 'start', '-W', '-n', self.package + '/com.jmstudios.redmoon.tv.TvActivity'])
            self.wait_running('explicit-relaunch')
        elif scenario == 'disabled':
            self.command('pause'); report = self.report('disable-before')
            if report.get('journal_present'): self.command('restore')
            self.command('warmth_off'); time.sleep(4)
            self.expect('disabled-service', 'service_running', False)
        elif scenario == 'soak':
            end=time.monotonic()+seconds; index=0
            while time.monotonic()<end:
                time.sleep(min(60,max(0,end-time.monotonic())))
                index+=1
                self.wait_running('soak-%04d'%index,30)
                if index%10==0:self.sample('soak-mem-%04d'%index)
            self.sample('soak-after')
        self.sample('after-' + scenario)

    def supervisor(self, operation):
        if operation == 'status':
            return self.root(['ls', '-l', MODULE + '/module.prop', MODULE + '/selected-package'])
        if operation == 'disable':
            # Config is fixed module state, never shell-sourced. The running loop checks it.
            return self.root(['rm', '-f', MODULE + '/selected-package'])
        if operation == 'enable':
            self.root(['test', '-f', MODULE + '/module.prop'])
            script = "printf '%s\\n' " + shlex.quote(self.package) + ' > ' + shlex.quote(MODULE + '/selected-package')
            if not self.allow_root: raise RuntimeError('--root required')
            self.checked(base.shell_args(['su','-c',script]))
            self.checked(base.shell_args(['su','-c','nohup sh ' + shlex.quote(MODULE + '/service.sh') + ' >/dev/null 2>&1 &']))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('-s', '--serial', required=True)
    p.add_argument('--package', choices=base.collect.PACKAGES, default=base.collect.PACKAGES[0])
    p.add_argument('--scenario', choices=SCENARIOS, default='status')
    p.add_argument('--seconds', type=int, default=3600, help='Soak length, 60..86400; progress captured every minute')
    p.add_argument('--upgrade-apk', type=pathlib.Path)
    p.add_argument('--supervisor', choices=('status','enable','disable'))
    p.add_argument('--root', action='store_true', help='Authorise bounded root process-death or module configuration actions')
    p.add_argument('--execute', action='store_true')
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path.cwd())
    args = p.parse_args(argv)
    if not 60 <= args.seconds <= 86400: p.error('--seconds must be 60..86400')
    if not args.execute:
        print(json.dumps({'scenario':args.scenario,'root':args.root,'package':args.package,
                          'supervisor':args.supervisor,'seconds':args.seconds,
                          'effects':'ADB experiments; saves/restores configuration, no optical pass inferred'},indent=2)); return 0
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run = Continuity(args.serial, args.output / ('a4-continuity-' + stamp), args.package)
    run.allow_root = args.root; run.results = []; saved = False; interrupted = False
    if run.checked(['get-state']).strip() != 'device': raise RuntimeError('ADB transport unavailable')
    device = run.checked(['shell','getprop','ro.product.device']).strip()
    if not device.startswith('xiaoyushan'): raise RuntimeError('Selected device is not the established A4')
    run.destination.mkdir(parents=True, exist_ok=False)
    try:
        run.apk(); before = run.sample('before'); run.restrictions()
        if args.supervisor: print(run.supervisor(args.supervisor))
        elif args.scenario != 'status':
            if not before or before.get('schema',0) < 5: raise RuntimeError('Install startup-continuity APK')
            if before.get('recovery_pending'): raise RuntimeError('Resolve uncertain recovery before experiments')
            if not before.get('native_acknowledged'): raise RuntimeError('Use ADB read/probe/acknowledge first; optical acknowledgement is never fabricated')
            run.command('save_config'); saved = True
            run.command('pause')
            if before.get('journal_present'): run.command('restore')
            clock = run.checked(['shell','date','+%H:%M']).strip(); h,m = map(int,clock.split(':')); minute=h*60+m
            run.command('configure',{'warmth':True,'linked':False,'warmth_start':(minute-1)%1440,
                'warmth_end':(minute+120)%1440,'warmth_fade':0,'strength':35,
                'phases':[{'phase':phase,'level':before['settings'][phase]['level'],
                           'start':before['settings'][phase]['start'],'fade':0} for phase in ('DAY','DUSK','DIM')]})
            run.command('resume')
            suite = ('overlay','readiness','access','uncertain','standby','worker','restart','force-stop','boot','disabled') if args.scenario == 'all' else (args.scenario,)
            for scenario in suite:
                print('Experiment: ' + scenario, flush=True)
                run.exercise(scenario,args.seconds,args.upgrade_apk)
    except (KeyboardInterrupt,EOFError,RuntimeError,ValueError) as error:
        interrupted=True; (run.destination/'failure.txt').write_text(str(error)+'\n'); print(str(error),file=sys.stderr)
    finally:
        if saved:
            try:
                run.command('fault',{'fault':'clear'}); run.command('pause')
                final=run.report('cleanup-state')
                if final.get('journal_present'): run.command('restore')
                final=run.report('cleanup-restored')
                if final.get('journal_present') or final.get('recovery_pending'): raise RuntimeError('Journal retained; explicit recovery required')
                run.command('restore_config')
            except RuntimeError as error:
                interrupted=True; (run.destination/'cleanup-needed.txt').write_text(str(error)+'\nSaved configuration remains in the app.\n')
        run.sample('final'); run.restrictions(); run.logs('vendor-log.txt')
        (run.destination/'results.json').write_text(json.dumps(run.results,indent=2))
        run.archive(stamp,'continuity',device,0,False,interrupted)
    return 1 if interrupted else 0


if __name__ == '__main__':
    sys.exit(main())
