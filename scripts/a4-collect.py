#!/usr/bin/env python3
"""Read-only, device-explicit A4 evidence collector. Python 3 stdlib + existing adb only."""
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
RELEVANT = re.compile(r'RedMoonTV|redmoontv|twoworlds|TV_MtkTvConfig|HS_Set_Panel_Backlight|Backlight_Value|DISPOUT.*Backlight|AndroidRuntime|avc:.*(?:mtktv|hisense)', re.I)
LIMIT = 65536


class Collector:
    def __init__(self, serial, destination, package):
        self.adb = ['adb', '-s', serial]
        self.destination = destination
        self.package = package
        self.records = []

    def run(self, args, timeout=12):
        try:
            result = subprocess.run(self.adb + args, capture_output=True, timeout=timeout, check=False)
            return result.returncode, result.stdout.decode('utf-8', 'replace'), result.stderr.decode('utf-8', 'replace')
        except subprocess.TimeoutExpired:
            return 124, '', 'Command timed out; not retried'
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
        return code, out

    def sample(self, name):
        package = self.package
        self.save(name + '-memory.txt', ['shell', 'dumpsys', 'meminfo', package])
        self.save(name + '-processes.txt', ['shell', 'ps', '-A'],
                  lambda text: '\n'.join(line for line in text.splitlines() if package in line or 'PID' in line))
        self.save(name + '-cpu.txt', ['shell', 'dumpsys', 'cpuinfo'],
                  lambda text: '\n'.join(line for line in text.splitlines() if package in line or 'TOTAL' in line or 'CPU usage' in line))
        self.save(name + '-app.txt', ['shell', 'dumpsys', 'activity', 'service',
                  package + '/com.jmstudios.redmoon.tv.TvDisplayService'])
        # Root and private APK workers must also be counted in the memory comparison.
        code, pids, _ = self.run(['shell', 'pidof', package + ':backlight', package + ':a4-root'])
        for pid in pids.split()[:4]:
            if re.fullmatch(r'[1-9][0-9]{0,8}', pid):
                self.save(name + '-worker-' + pid + '.txt', ['shell', 'dumpsys', 'meminfo', pid])

    def apk(self):
        code, out, _ = self.run(['shell', 'pm', 'path', self.package])
        paths = [line[len('package:'):] for line in out.splitlines() if line.startswith('package:')]
        if code or len(paths) != 1 or not paths[0].startswith('/') or not paths[0].endswith('.apk'):
            self.records.append({'file': 'installed.apk', 'exit_code': 1, 'error': 'Expected one installed base APK'})
            return
        target = self.destination / 'installed.apk'
        code, _, err = self.run(['pull', paths[0], str(target)], timeout=45)
        self.records.append({'file': 'installed.apk', 'exit_code': code, 'error': err[:2048] if code else '',
                             'sha256': hashlib.sha256(target.read_bytes()).hexdigest() if code == 0 and target.exists() else None})


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('-s', '--serial', required=True, help='Exact A4 transport from adb devices; never selects another device')
    p.add_argument('--package', choices=PACKAGES, default=PACKAGES[0])
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path.cwd())
    p.add_argument('--label', default='read', help='Comparison label, e.g. baseline, root-read, fade or playback')
    p.add_argument('--duration', type=int, choices=range(0, 61), default=0, metavar='0..60', help='Second sample after N seconds; no periodic polling')
    p.add_argument('--root', action='store_true', help='One fixed read-only su identity/HIDL/library check; may prompt Magisk')
    p.add_argument('--apk', action='store_true', help='Pull the installed app APK and calculate its exact SHA-256')
    return p


def main(argv=None):
    args = parser().parse_args(argv)
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
    destination.mkdir(parents=True, exist_ok=False)
    probe.save('identity.txt', ['shell', "getprop ro.build.fingerprint; getprop ro.product.cpu.abilist; getprop ro.build.version.sdk; cat /proc/sys/kernel/random/boot_id; settings get global boot_count"])
    probe.save('package.txt', ['shell', 'dumpsys', 'package', args.package])
    probe.sample('before')
    if args.root:
        fixed = 'id; lshal 2>/dev/null | grep -F IMtkTvApiConfig; ls -lZ /vendor/lib/libcom_mediatek_twoworlds_tv_jni.so; grep -F twoworlds /vendor/etc/public.libraries.txt'
        probe.save('root-vendor.txt', ['shell', shlex.join(['su', '-c', fixed])], timeout=25)
    if args.apk:
        probe.apk()
    if args.duration:
        print('Capture started. Keep the same playback/source; second sample in %d seconds.' % args.duration, flush=True)
        time.sleep(args.duration)
        probe.sample('after')
    probe.save('vendor-log.txt', ['logcat', '-d', '-t', '1800', '-v', 'threadtime'],
               lambda text: '\n'.join(line for line in text.splitlines() if RELEVANT.search(line)))
    manifest = {'schema': 1, 'utc_started': stamp, 'label': label, 'package': args.package,
                'device': device.strip(), 'duration_seconds': args.duration, 'read_only': True,
                'physical_pwm_observed': 'User must record separately', 'commands': probe.records}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    archive = destination.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(destination.iterdir()):
            bundle.write(path, arcname=destination.name + '/' + path.name)
    print(archive)
    print('Record visible panel/slider changes and restore outcome separately. Review the archive before sharing.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
