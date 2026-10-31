# A4 startup, recovery and ADB experiments

This extends PR #1's native single-writer controller. New behaviour is implemented, with device qualification reported separately from source/CI results.

## Runtime contract

- `LOCKED_BOOT_COMPLETED`, normal boot, package replacement, wake/display changes, foreground entry, time changes and a five-minute non-wakeup OS alarm reconcile enabled functions. Duplicate events do not duplicate workers or restart an active fade. No activity opens automatically.
- Notification promotion precedes migration/hashing/native work. TV settings and journals use device-protected storage. On the first upgrade the old credential store is migrated after unlock; subsequent boots can start before unlock. Old CE bytes are retained as historical input but never used after the migration marker commits. Interrupted migration resumes; the journal is copied atomically before committing the marker.
- The TV overlay avoids legacy Config/profiles/EventBus, keeps one non-focusable/non-touchable window and repairs detached windows. Startup/wake catch up to the current target without replaying an old fade. Ordinary schedule transitions still use configured, qualified fades.
- Saved enabled flags are not cleared by native failure/recovery. Brightness and warmth expose independent operation states. Root/overlay failure cannot disable the other feature.
- Confirmed schedule adoption is lease-protected and checks compatibility/context/readback. New-boot confirmed records are archived and a fresh baseline is captured. Pending writes, probes, corrupt/unknown records and incompatible identities cannot be adopted. One previous epoch is retained; it is never an automatic restore instruction.
- Native compatibility is generated from the controller/native/policy sources, separate from source/build provenance. UI/documentation-only rebuilds can retain approval; native/vendor/ABI changes invalidate it. This upgrade changes that contract, so read/probe/acknowledgement is required once. No approval is silently fabricated.
- Transient reads/readiness have bounded 1/2/5/10/30-second backoff then coarse recovery. Root-consent failures wait for explicit Connect/Resume; late approvals cannot replay an old command. A pending setter outcome is never automatically retried.
- Awake effect checks run every 60 seconds, coalescing with current readbacks; stable values cause zero writes. The selected worker stays reusable while native control needs it and closes when idle/standby. No permanent CPU/wake lock, new runtime dependency, staged executable or per-step shell process is added.
- Manual brightness is respected to the next phase by default. Optional **Keep scheduled brightness enforced** is available in Settings and ADB (`enforce_schedule`); Pause/uncertain-write/ownership gates still win.

Health alarm intervals are not hard Android/OEM delivery guarantees. The app cannot render over the bootloader or prove HDMI/HDR optical coverage from a screenshot. Ordinary process death and intentional Force stop are separate cases.

## ADB-only preparation

Run on Termux/workstation **outside** adb shell, with the existing network ADB connection. Select the exact variant. Scripts use Python 3.8+ standard library; no TV staging is used by collectors/experiments.

```sh
python3 a4-collect.py -s TV_SERIAL --package com.cbkii.redmoontv.testing --apk
python3 a4-validate.py -s TV_SERIAL --command use_root_read --execute
python3 a4-validate.py -s TV_SERIAL --command probe --execute
```

Observe actual dimming and restoration. Only after that observation, record it explicitly:

```sh
python3 a4-validate.py -s TV_SERIAL --command acknowledge --data '{"native":true}' --execute
```

A fade acknowledgement additionally requires the existing persistence evidence and `fades:true,persistence_checked:true`. The continuity experiments temporarily use zero-second native transitions, so they can test startup without qualifying repeated vendor writes. They do not silently enable fades.

## Collect and execute

```sh
# Dry-run; no ADB call at all:
python3 a4-continuity.py -s TV_SERIAL --scenario all --root
# Read-only collection:
python3 a4-continuity.py -s TV_SERIAL --scenario status --execute
# Complete reversible test sequence, including selected-TV reboot and process kills:
python3 a4-continuity.py -s TV_SERIAL --scenario all --root --execute
# Single experiment; all have dry-run defaults:
python3 a4-continuity.py -s TV_SERIAL --scenario overlay --execute
python3 a4-continuity.py -s TV_SERIAL --scenario worker --root --execute
python3 a4-continuity.py -s TV_SERIAL --scenario boot --execute
python3 a4-continuity.py -s TV_SERIAL --scenario soak --seconds 28800 --execute
# Install/observe an exact compatible/incompatible candidate (signature must match):
python3 a4-continuity.py -s TV_SERIAL --scenario upgrade --upgrade-apk CANDIDATE.apk --execute
```

Available scenarios: `status`, `boot`, `standby`, `restart`, `worker`, `overlay`, `readiness`, `access`, `uncertain`, `upgrade`, `force-stop`, `disabled`, `soak`, `all`. `all` includes boot/reboot, main/worker death, access/readiness failures, lost write reply, overlay loss, standby and disable. Upgrade and long soak are explicit separate runs. The scripts refuse the wrong TV, mismatched PID identity, missing root authorisation and unresolved pending recovery. Configuration backup/restore and numeric restore run automatically where safe; interruption retains partial evidence and any required in-app backup.

Readiness/access/lost-reply/overlay faults are fixed TESTING/debug-only commands behind shell/root UID checks. They do not substitute fake hardware success. Lost-reply first performs a small actual native write, then fails its application response; the test verifies pending recovery and zero replay before explicitly restoring it. Do not confuse simulated permission failure with revoking a real Magisk grant; real permission revoke/regrant and vendor service delay can be operated through the existing ADB remote/Settings plans and collected by `status`.

Reports are schema 5 and retain boot/source/signer, storage migration, user-unlock/restriction state, enabled versus actual states, fresh versus retained sample ages, worker ownership, alarm/retry timing and bounded lifecycle history. Provider queries can create the process: recovery observation checks ActivityManager's foreground service first, avoiding a false liveness pass caused by its own query. ZIPs retain installed APK hash, commands, assertions, timing, logs, AppOps, power/alarm/service samples and memory/CPU. Physical visibility remains `unverified` unless separately observed.

`--scenario force-stop` first checks the stopped flag without querying the provider, then explicitly launches the app. It does not claim that self-recovery from Force stop is supported. A true power-cycle/locked-user boot and different HDMI/tuner/HDR video paths still require the corresponding physical event/input; all evidence is collected through ADB. Use the existing `a4-validation-plan.json` and `a4-manual-override-plan.json` for source/picture/remote experiments. Do not use credential-lock commands blindly on a TV without a verified unlock/recovery route.

## Optional tvQuickActions route

Enable external control in Settings, or configure `external_control:true` via the existing ADB configure command. On the boot/wake event send the explicit broadcast below. It only reconciles already-enabled features; it does not enable them or clear recovery.

```sh
adb -s TV_SERIAL shell am broadcast -n com.cbkii.redmoontv.testing/com.jmstudios.redmoon.tv.TvControlReceiver -a com.cbkii.redmoontv.CONTROL --es command ensure_running
```

## Optional Magisk supervisor, fully packaged but inactive

Build the source-bound script/module bundle:

```sh
python3 scripts/a4-package-validation.py --output OUTPUT_DIRECTORY
```

The included module has no mounts, SELinux rules, property changes or hardware writer. Its late-start loop checks one fixed selected package every five minutes, respects stopped/disabled state and asks the shell/root-only `supervise` endpoint to start a missing enabled controller. It exits when everything is disabled. No package is selected by default. A kernel file lock prevents duplicate supervisors; calls have deadlines. It is an optional independent recovery layer, not required for normal APK operation.

If choosing to install it, use Magisk's normal module ZIP installation (the build script does not install it). An ADB-operated equivalent is:

```sh
adb -s TV_SERIAL push red-moon-tv-supervisor-SOURCE.zip /sdcard/Download/red-moon-tv-supervisor.zip
adb -s TV_SERIAL shell 'su -c "magisk --install-module /sdcard/Download/red-moon-tv-supervisor.zip"'
python3 a4-continuity.py -s TV_SERIAL --supervisor enable --root --execute
python3 a4-continuity.py -s TV_SERIAL --supervisor status --root --execute
python3 a4-continuity.py -s TV_SERIAL --supervisor disable --root --execute
```

All `/data/*` operations in this feature are confined to this explicitly chosen Magisk module; app storage otherwise uses Context APIs. Disable removes the selected-package file; the existing loop exits on its next check (at most its five-minute sleep plus bounded operation time), without killing a numeric PID. Remove the module through Magisk to uninstall it. If Force stop/all-off caused it to exit, explicit Enable or the next enabled-module boot is required to restart it.

## Acceptance and review map

| Contract | Producer/consumer and verification |
|---|---|
| DP migration | `TvStorage`, preferences, journal, APK/root diagnostic writers; boot/status report verifies DE and migration. Existing unknown schema remains blocked. |
| Desired/actual state | service, receiver, Home/setup, diagnostics/provider; process/wake/disabled/access scenarios. |
| Ownership/adoption | controller, journal, native live lease; JVM tests cover lease-held adoption, old boot baseline, identity mismatch and pending/probe rejection. |
| Scheduling/retry | service, OS alarm, runtime display events, external wake; startup/readiness/worker/standby experiments and bounded retry tests. |
| Warmth | TV overlay, service, window permission; detach/repair and independent access tests. Optical source-plane acceptance stays separate. |
| IPC/security | DUMP report, shell/root mutation and supervisor, TESTING-only faults; fixed command validation and PID refusal tests. |
| Packaging | generated compatibility, schema 5 collector, script/module packager, APK manifest; CI plus source-bound bundle checks. |

Run `python3 -m unittest discover -s scripts/ci -p 'test_*.py'` and the canonical debug/TESTING/release JVM/lint/assemble lanes. Record exact source/APK hash for every device run. A green software result is not an invented optical/boot timing result. Keep PR #1 draft until the resulting A4 evidence is accepted; this work does not merge or publish a stable release.
