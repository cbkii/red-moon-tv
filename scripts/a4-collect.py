#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A4 ADB collection and optional guided validation. Python 3.8+ stdlib + existing adb."""
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time
import zipfile

PACKAGES = ('com.cbkii.redmoontv.testing', 'com.cbkii.redmoontv', 'com.cbkii.redmoontv.debug')
RELEVANT = re.compile(r'RedMoonTV|redmoontv|libcom_mediatek_twoworlds|UnsatisfiedLinkError|HS_Set_Panel_Backlight|Backlight_Value|DISPOUT.*Backlight|avc:.*(?:mtktv|hisense)', re.I)
LIMIT = 65536


def extract_report(text, package):
    """Android prefixes service dumps with its own text; keep only our JSON object."""
    for match in re.finditer(r'\{\s*"schema"\s*:', text):
        try:
            report, _ = json.JSONDecoder().raw_decode(text[match.start():])
        except ValueError:
            continue
        if report.get('schema') in (2, 3, 4, 5) and report.get('package') == package:
            return report
    return None


class Collector:
    def __init__(self, serial, destination, package):
        self.adb = ['adb', '-s', serial]
        self.destination = destination
        self.package = package
        self.records = []
        self.observations = []
        self.reports = {}

    def run(self, args, timeout=12):
        try:
            result = subprocess.run(self.adb + args, capture_output=True, timeout=timeout, check=False)
            return result.returncode, result.stdout.decode('utf-8', 'replace'), result.stderr.decode('utf-8', 'replace')
        except subprocess.TimeoutExpired as error:
            partial = error.stdout or b''
            out = partial.decode('utf-8', 'replace') if isinstance(partial, bytes) else partial
            return 124, out, 'Command timed out; not retried'
        except OSError as error:
            return 127, '', str(error)

    def save(self, name, args, transform=None, timeout=12):
        code, out, err = self.run(args, timeout)
        if transform:
            out = transform(out)
        text = out[:LIMIT] + ('\n[truncated]\n' if len(out) > LIMIT else '')
        if err:
            text += '\nSTDERR:\n' + err[:4096]
        (self.destination / name).write_text(text, encoding='utf-8')
        self.records.append({'file': name, 'exit_code': code, 'command': args})
        if code:
            print('  Warning: %s exited %s (retained in archive)' % (name, code), flush=True)
        return code, out

    def sample(self, name):
        print('Collecting %s checkpoint...' % name, flush=True)
        package = self.package
        self.save(name + '-memory.txt', ['shell', 'dumpsys', 'meminfo', package])
        self.save(name + '-processes.txt', ['shell', 'ps', '-A'],
                  lambda text: '\n'.join(line for line in text.splitlines() if package in line or 'PID' in line))
        self.save(name + '-cpu.txt', ['shell', 'dumpsys', 'cpuinfo'],
                  lambda text: '\n'.join(line for line in text.splitlines() if package in line or 'TOTAL' in line or 'CPU usage' in line))
        report = self.provider(name)
        _, dump = self.save(name + '-app.txt', ['shell', 'dumpsys', 'activity', 'service',
                  package + '/com.jmstudios.redmoon.tv.TvDisplayService'])
        report = report or extract_report(dump[:LIMIT], package)
        if report is not None:
            self.reports[name] = report
            (self.destination / (name + '-report.json')).write_text(
                json.dumps(report, indent=2), encoding='utf-8')
        # Root and private APK workers must also be counted in the memory comparison.
        code, pids, _ = self.run(['shell', 'pidof', package + ':backlight', package + ':a4-root'])
        for pid in pids.split()[:4]:
            if re.fullmatch(r'[1-9][0-9]{0,8}', pid):
                self.save(name + '-worker-' + pid + '.txt', ['shell', 'dumpsys', 'meminfo', pid])
        return report

    def provider(self, name, timeout=12):
        # This endpoint is guarded by Android's DUMP permission and never starts JNI.
        code, dump = self.save(name + '-provider.txt', ['shell', 'content', 'query', '--uri',
                              'content://' + self.package + '.diagnostics/report'], timeout=timeout)
        return extract_report(dump[:LIMIT], self.package) if not code else None

    def logs(self, name):
        self.save(name, ['logcat', '-d', '-t', '1800', '-v', 'threadtime'],
                  lambda text: '\n'.join(line for line in text.splitlines() if RELEVANT.search(line)))
        # Filter by tag on the device before taking the final lines. Firmware channel
        # spam must not displace the app's first failure from an unrelated global tail.
        self.save(name[:-4] + '-app.txt', ['logcat', '-d', '-v', 'threadtime', '-s', 'RedMoonTV:*'],
                  lambda text: '\n'.join(text.splitlines()[-400:]))

    def ask(self, key, message):
        answer = input(message + '\n> ').strip()[:2000]
        self.observations.append({'step': key, 'utc': dt.datetime.now(dt.timezone.utc).isoformat(),
                                  'answer': answer})
        # Preserve each answer immediately, including if the next step is interrupted.
        (self.destination / 'observations.json').write_text(
            json.dumps(self.observations, indent=2), encoding='utf-8')
        return answer

    def guided(self):
        print('\nGUIDED SESSION: selected package %s' % self.package)
        print('Use the TV remote. This script sends no app commands or backlight writes.\n'
              'Open this variant, pause backlight scheduling, and leave qualification toggles off.\n'
              'Do not clear a pending recovery record just to continue. Check native TV Settings.\n'
              'Keep input/picture/HDR state unchanged during the initial read and probe.')
        self.ask('baseline', 'Open the selected app on the TV. On Home, connect/read the backlight.\n'
                 'Wait for the result, then press Enter. Native values and errors are collected automatically.\n'
                 'Optional notes only; do not copy the report or type numeric readback.')
        report = self.sample('read')
        self.logs('read-vendor-log.txt')
        passed = bool(report and report.get('native_state') == 'ready' and
                      isinstance(report.get('native_level'), int) and 0 <= report['native_level'] <= 100 and
                      report.get('write_context_ready') and not report.get('recovery_pending'))
        if report:
            print('App read: state=%s; level=%s; write context=%s; backend=%s' %
                  (report.get('native_state', 'legacy'), report.get('native_level'),
                   report.get('write_context_ready'), report.get('selected_backend')))
            if not passed:
                print('App error: ' + str(report.get('last_native_error') or report.get('first_initialisation_error') or report.get('status')))
        else:
            print('No automatic app report. This installed APK may predate the ADB exporter; update the APK.\n'
                  'The archive retains the provider/service errors and app logs. No manual readback entry is requested.')
        if passed:
            probe = self.ask('probe_choice', 'Optional visible five-point probe: perform it now? [y/N]\n'
                             'Default is skip. This does not qualify fades or source/HDR changes.')
            if probe.lower() in ('yes', 'y'):
                self.ask('probe_started', 'On the TV select Test & restore and confirm when ready.\n'
                         'Press Enter once the test starts. Native values and restoration are collected automatically.\n'
                         'Watch the physical panel; if interrupted, native TV Settings is the recovery path.')
                probe_report = self.sample('probe')
                deadline = time.monotonic() + 25
                progress = 0
                while probe_report and probe_report.get('probe_active') and time.monotonic() < deadline:
                    time.sleep(min(2, max(0, deadline - time.monotonic())))
                    remaining = deadline - time.monotonic()
                    if remaining <= 0: break
                    progress += 1
                    probe_report = self.provider('probe-progress-%02d' % progress, timeout=min(6, remaining))
                    # A failed transport/report ends observation; it is never silently retried.
                restored = self.sample('probe-restored')
                print('Automatic restore result: restored=%s; native level=%s; recovery pending=%s' %
                      (restored.get('probe_restored'), restored.get('native_level'), restored.get('recovery_pending'))
                      if restored else 'Restoration report unavailable; inspect the captured errors.')
                self.logs('probe-vendor-log.txt')
                self.ask('visible_panel_change', 'Did the physical panel visibly change? yes / no / unknown')
        else:
            print('Probe skipped: retain the first native error. Do not enable scheduling/fades.')
        self.ask('other_notes', 'Any remaining error, recovery, playback or warmth observations? Enter to finish.')
        self.sample('final')

    def archive(self, stamp, label, device, duration, guided, interrupted, read_only=True):
        failed = [r['file'] for r in self.records if r['exit_code']]
        status = 'interrupted' if interrupted else 'warnings' if failed else 'complete'
        if not self.reports:
            status = 'interrupted' if interrupted else 'warnings'
        summary = ['Collection: ' + status, 'Package: ' + self.package,
                   'Command failures: ' + (', '.join(failed) or 'none'),
                   'App JSON reports: ' + (', '.join(self.reports) or 'unavailable'),
                   ('Reports available; inspect sample_at for freshness.' if self.reports else
                    'No app report: inspect provider errors and verify the installed APK supports the report endpoint.'),
                   'Collection success is NOT native/PWM/fade/playback qualification.',
                   'observations.json contains operator reports, not automatic test verdicts.',
                   'Review logs, notes and package metadata before sharing. installed.apk is optional.']
        (self.destination / 'README.txt').write_text('\n'.join(summary) + '\n', encoding='utf-8')
        manifest = {'schema': 2, 'utc_started': stamp, 'label': label, 'package': self.package,
                    'device': device, 'duration_seconds': duration, 'read_only': read_only,
                    'guided_manual_actions': guided, 'collection_status': status,
                    'physical_validation': 'Not inferred; operator observations are separate evidence',
                    'app_reports': list(self.reports), 'commands': self.records,
                    'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(self.destination.iterdir()) if p.is_file()}}
        (self.destination / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        archive = self.destination.with_suffix('.zip')
        with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
            for path in sorted(self.destination.iterdir()):
                bundle.write(path, arcname=self.destination.name + '/' + path.name)
        print('\n%s: %s' % (status.upper(), archive.resolve()))
        print('No physical qualification inferred. Review the archive before sharing.')
        return archive

    def apk(self):
        print('Pulling installed APK and calculating SHA-256...', flush=True)
        code, out, _ = self.run(['shell', 'pm', 'path', self.package])
        paths = [line[len('package:'):] for line in out.splitlines() if line.startswith('package:')]
        if code or len(paths) != 1 or not paths[0].startswith('/') or not paths[0].endswith('.apk'):
            self.records.append({'file': 'installed.apk', 'exit_code': 1, 'error': 'Expected one installed base APK'})
            return
        target = self.destination / 'installed.apk'
        code, _, err = self.run(['pull', paths[0], str(target)], timeout=45)
        if not code and (not target.is_file() or not target.stat().st_size):
            code, err = 1, 'ADB reported success without a nonempty APK'
        if code and target.exists():
            target.unlink()  # Only this capture's incomplete local pull, never a TV file.
        self.records.append({'file': 'installed.apk', 'exit_code': code, 'error': err[:2048] if code else '',
                             'sha256': hashlib.sha256(target.read_bytes()).hexdigest() if code == 0 and target.exists() else None})


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('-s', '--serial', required=True, help='Exact A4 transport from adb devices; never selects another device')
    p.add_argument('--package', choices=PACKAGES, help='Required if several Red Moon variants are installed; otherwise detect the only installed variant')
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path.cwd())
    p.add_argument('--label', default='read', help='Comparison label, e.g. baseline, root-read, fade or playback')
    p.add_argument('--duration', type=int, choices=range(0, 61), default=0, metavar='0..60', help='Second sample after N seconds; no periodic polling')
    p.add_argument('--root', action='store_true', help='One fixed read-only su identity/HIDL/library check; may prompt Magisk')
    p.add_argument('--apk', action='store_true', help='Pull the installed app APK and calculate its exact SHA-256')
    p.add_argument('--guided', action='store_true', help='Interactive remote-operated Read and optional probe; record observations/checkpoints, never send setters')
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    if args.guided and not sys.stdin.isatty():
        print('--guided requires an interactive terminal. Omit it for unattended collection.', file=sys.stderr)
        return 2
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    label = re.sub(r'[^a-zA-Z0-9_-]', '_', args.label)[:40] or 'read'
    destination = args.output / ('a4-' + label + '-' + stamp)
    # Preflight before any evidence directory or root operation.
    probe = Collector(args.serial, destination, args.package)
    code, state, err = probe.run(['get-state'])
    if code or state.strip() != 'device':
        print('Selected ADB transport unavailable: ' + err.strip(), file=sys.stderr)
        return 2
    code, device, err = probe.run(['shell', 'getprop', 'ro.product.device'])
    if code or not device.strip().startswith('xiaoyushan'):
        print('Selected device is not the established A4; no collection performed.', file=sys.stderr)
        return 2
    installed = []
    for package in (args.package,) if args.package else PACKAGES:
        code, paths, _ = probe.run(['shell', 'pm', 'path', package])
        if not code and any(line.startswith('package:') for line in paths.splitlines()):
            installed.append(package)
    if len(installed) != 1:
        print('Choose exactly one installed Red Moon package using --package. Found: ' +
              (', '.join(installed) or 'none for this selection'), file=sys.stderr)
        return 2
    probe.package = installed[0]
    print('Selected A4 %s / %s' % (args.serial, probe.package), flush=True)
    destination.mkdir(parents=True, exist_ok=False)
    interrupted = False
    try:
        probe.save('identity.txt', ['shell', "date -u; getprop ro.build.fingerprint; getprop ro.product.cpu.abilist; getprop ro.build.version.sdk; cat /proc/sys/kernel/random/boot_id; settings get global boot_count"])
        probe.save('package.txt', ['shell', 'dumpsys', 'package', probe.package])
        probe.sample('before')
        if args.root:
            fixed = 'id; lshal 2>/dev/null | grep -F IMtkTvApiConfig; ls -lZ /vendor/lib/libcom_mediatek_twoworlds_tv_jni.so; grep -F twoworlds /vendor/etc/public.libraries.txt'
            probe.save('root-vendor.txt', ['shell', shlex.join(['su', '-c', fixed])], timeout=25)
        if args.apk:
            probe.apk()
        if args.guided:
            probe.guided()
        if args.duration:
            print('Capture started. Keep the same playback/source; second sample in %d seconds.' % args.duration, flush=True)
            time.sleep(args.duration)
            probe.sample('after')
        probe.logs('vendor-log.txt')
    except (KeyboardInterrupt, EOFError):
        interrupted = True
        print('\nInterrupted; preserving evidence collected so far.', file=sys.stderr)
    probe.archive(stamp, label, device.strip(), args.duration, args.guided, interrupted)
    return 130 if interrupted else 0


if __name__ == '__main__':
    sys.exit(main())
