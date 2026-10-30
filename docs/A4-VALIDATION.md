# Physical A4 acceptance

This is the Toshiba 50C350KP / internal 50C351P, xiaoyushan A4, Android TV 9/API 28, armeabi-v7a. The native menu's PWM path is established; control from this APK is **not yet verified**. Use an unchanged signed artifact and record its source SHA, package, APK SHA-256, signer, firmware fingerprint and build ID before testing.

## ADB collection: start here

The standalone [a4-collect.py](../scripts/a4-collect.py) runs on **Termux on your phone or on a workstation, outside `adb shell`**. It needs Python 3.8+ and an existing ADB connection; the TV needs neither Python nor an uploaded script. It uses only the Python standard library. On Termux, install missing prerequisites with `pkg install python android-tools`. If you are currently at the TV's `xiaoyushan:/ $` prompt, run `exit` first.

Use the collector from this application branch/PR; the default branch does not contain all of the A4 application work while physical qualification is pending. From a repository checkout, use `python3 scripts/a4-collect.py`. For a single-file download:

```sh
curl --fail --location --connect-timeout 15 --max-time 60 \
  --output a4-collect.py \
  https://raw.githubusercontent.com/cbkii/red-moon-tv/codex/a4-backlight-tv/scripts/a4-collect.py
python3 a4-collect.py --help
adb devices -l
```

For network ADB, first run `adb connect TV_IP:5555` using the TV's actual address and accept its existing USB-debugging authorisation dialog. Use the **exact serial from `adb devices -l`**, such as `192.168.1.50:5555` or the USB serial. The collector never connects, picks an arbitrary device or retries timed-out commands. It checks that the selected device identifies as xiaoyushan.

Start the guided session, substituting the real serial:

```sh
python3 a4-collect.py -s TV_IP:5555 --guided --apk --output ./a4-results
```

There is no need for executable permission or `su`. All results go to the machine running Python. On Termux, `./a4-results` is in your current directory; to save in shared Downloads instead, run `termux-setup-storage` once and use `--output "$HOME/storage/downloads"`. No TV staging files, `/data/*` shell writes, log clearing, installation, force-stop or automatic backlight setters are performed.

### Choose the installed build, not the latest-looking label

If exactly one Red Moon TV variant is installed, it is detected. If several are installed, collection stops before creating an output directory and prints their package names. Repeat with the exact package you opened:

| Installed variant | `--package` value |
|---|---|
| Permanent signed TESTING | `com.cbkii.redmoontv.testing` |
| CI DEBUG, including experimental root-backend builds | `com.cbkii.redmoontv.debug` |
| Stable-package candidate | `com.cbkii.redmoontv` |

For example:

```sh
python3 a4-collect.py -s TV_IP:5555 \
  --package com.cbkii.redmoontv.debug --guided --apk --output ./a4-results
```

An older signed TESTING APK does not acquire newer root/backend features by running a newer collector. Record the installed source/build from Diagnostics. `--apk` pulls the selected installed base APK and hashes its actual bytes; it never executes or installs that file. A missing or split APK is reported instead of inventing a hash. Compare the hash with the exact release/CI record before using it as acceptance evidence. The collector does not independently verify the signing certificate.

### What the guided session does

1. Captures device/package identity and initial app/worker memory, process, CPU and service diagnostics. Optional `--apk` records the installed APK SHA-256.
2. Asks you to open the selected app, pause scheduling, record native Backlight/input/picture/SDR-HDR/backend, select **Read native backlight**, and enter the result. Captures that checkpoint and bounded vendor logs immediately.
3. Offers the existing five-point probe **only if you report a successful read with valid write context**. It defaults to skip. You initiate and confirm the probe with the TV remote; the collector sends no control broadcast, service-start command or setter.
4. Captures the probe result and your separate observations of the real panel, native slider and restoration. A failed/unknown read skips the probe. It never sets qualification acknowledgements.
5. Captures the final checkpoint and writes one uniquely named ZIP. Ctrl+C/EOF after collection begins preserves completed checkpoints and notes in an interrupted ZIP.

The guided flag intentionally prompts and requires a terminal. Omit it for unattended snapshots. If a read fails, preserve the first error before selecting Restart worker; do not enable scheduling/fades or clear recovery just to continue. Probe interruption does not guarantee restoration; use native TV Settings for manual recovery.

### What to send back

Send the printed `a4-<label>-<UTC timestamp>.zip`, plus any visible behaviour the notes do not capture. Its contents include:

| File | Evidence |
|---|---|
| `README.txt`, `manifest.json` | Collection status, selected package, command exit codes, file hashes and captured report checkpoints |
| `identity.txt`, `package.txt` | Device UTC clock, firmware/API/ABI/boot identity and installed package metadata |
| `*-app.txt`, `*-report.json` | Raw service dumpsys and extracted schema-2/3 Red Moon JSON when available: source/build/protocol, backend, first error, recovery and runtime counters |
| `*-memory.txt`, `*-processes.txt`, `*-cpu.txt`, `*-worker-*.txt` | Bounded app/worker samples; these are not a trace or a statistical benchmark |
| `*-vendor-log.txt`, `vendor-log.txt` | Relevant lines from the latest 1,800 logcat lines per checkpoint; the global buffer is never cleared |
| `observations.json` | Timestamped operator notes from guided mode, kept separate from automatic evidence |
| `installed.apk` | Optional exact selected APK; its SHA-256 is recorded, not a newly built APK |

Service JSON requires an installed build with the dumpsys exporter and a running display service. If it is unavailable, open the selected variant and perform Read, then collect again. Older APKs may only expose **Copy bounded diagnostic report**; send that copied text alongside the ZIP. Missing/denied diagnostics remain visible as warnings, not a passed hardware test.

Exit `0` means a ZIP was produced; its status may be `COMPLETE` or `WARNINGS`. Exit `2` means preflight stopped (transport, device, package or noninteractive guided input). Exit `130` means interrupted collection with partial evidence saved when possible. **COMPLETE never means PWM/native/fade qualification passed.** Review the ZIP before sharing: logs, firmware/package metadata, observations and optional APK may contain information you do not want to disclose. Keep an unmodified original ZIP when preparing a redacted copy.

## Repeatable snapshots and playback comparison

Keep the same source, video, picture mode, playback position/state and measurement interval. Record the selected backend and warmth state in your notes. The script does not disable, enable or launch anything for you.

```sh
# Prepare each state manually; use the same exact package for each comparison.
python3 a4-collect.py -s TV_IP:5555 --package com.cbkii.redmoontv.debug \
  --label playback-paused --duration 30 --output ./a4-results
python3 a4-collect.py -s TV_IP:5555 --package com.cbkii.redmoontv.debug \
  --label playback-native-steady --duration 30 --output ./a4-results
python3 a4-collect.py -s TV_IP:5555 --package com.cbkii.redmoontv.debug \
  --label playback-qualified-fade --duration 30 --output ./a4-results
```

`--duration 0..60` adds one after-sample after the wait; it does not poll continuously or measure the full 888-second fade. Run snapshots at chosen points during a qualified long fade. CPU dumpsys is an OS accounting window, not exact per-call timing. Compare native counters, worker counts, pending queue/recovery, memory, decoder/ANR errors and observed playback separately. Temporal coincidence alone does not prove causation.

Optional `--root` adds one **fixed, read-only shell identity/HIDL/library metadata check** and may prompt Magisk for the ADB shell. This is separate from granting root to the Red Moon app. It neither selects the app's root backend nor calls the native API, changes SELinux, installs a module or proves root-native access. Ordinary app collection needs no root flag.

## Physical test record

Use a new labelled ZIP after each relevant action and keep the native original/final values and optical observations. Leave an unperformed or uncertain row **unverified**; never infer acceptance from a successful collection.

| Gate | Manual action and evidence required |
|---|---|
| Read and five-point probe | Record backend, level/range/context, panel/slider response, original/final level and recovery state. Follow the read-first procedure below. |
| Day/Dusk/Dim and editor | Check the 70–100 / 10–80 / 1–20 bands, Day maximum and Dusk/Dim minimum preferences, ordered levels, cyclic times, atomic Save/Cancel, focus and presets/custom 0–888 seconds. |
| Manual/context changes | Change native level, picture mode, input and SDR/HDR deliberately; verify yield/no stale restoration. Record light-sensor/dynamic-backlight state. |
| Standby and recovery | Capture before/after sleep/wake, boot asleep, process death/relaunch, explicit force-stop/relaunch and package replacement. Confirm bounded worker/queue/lease counts and explicit pending recovery handling. |
| Variant/backend ownership | Pause/transfer deliberately; verify only one writer. Backend changes require resolved recovery and fresh qualification. |
| Repeated writes/fades | Establish persistence safety first, then test 0/1/30/888 seconds, cancellation, strict incoming bands, no redundant setters and at most two native writes/second. |
| Root failure containment | When testing that optional backend, record denial/revocation, late permission approval, worker death/deadline/pipe closure and standby cleanup. No automatic setter replay is acceptable. |
| Warmth/video | Linked/custom schedules, denied/revoked overlay permission, independent off, real coverage of TV UI/Stremio/Kodi/HDMI/live TV and paired playback measurements. |
| Stable exact bytes | Repeat acceptance with the actual stable package/UID and signed APK hash, not just TESTING/DEBUG. Complete the acceptance record before promotion. |

## 1. Installed read and small probe

Install the chosen diagnostic APK normally (ADB `install -r` is sufficient); match its package in the collector. Open Red Moon TV → Diagnostics. Backlight scheduling and warmth default off. The default APK backend does not request root. Select **Read native backlight** and copy the bounded report. A valid result must identify native level/range, UID/ABI, installed class source/loader and the selected backend/protocol. Older reports use `apk-jni-v2`; newer builds identify `apk-jni-v3/root-pipe-v1` and their selected backend. JNI-load/service/permission failures are diagnostic outcomes, not reasons to enable scheduling.

Before writes, note the native TV Settings backlight level and ensure no conflicting controller/dynamic backlight automation. Select the nearby-level test. It captures the original, journals a five-point change, verifies native readback and attempts restoration after 15 seconds. Observe the physical panel and native slider; screenshots do not measure backlight. Leave source/input/picture unchanged during this first probe. Revert is available immediately. Do not mark qualification on numeric readback alone.

If interrupted, reopen Diagnostics, Read, then explicitly Restore or Keep after checking native TV Settings. The journal survives app process death, but force-stop/power loss cannot guarantee timed rollback. Complete input/HDR context is not yet observable; automatic restoration after a restart is deliberately paused. Native Settings is the manual recovery path. Keep clears the saved original without writing the panel.

Optional bounded vendor corroboration from Termux (outside adb shell), immediately after the probe:

```sh
adb logcat -d -t 1500 -v time | grep -Ei 'RedMoonTV|HS_Set_Panel_Backlight|Backlight_Value|DISPOUT.*Backlight|AndroidRuntime|twoworlds' | tail -160
```

No temporary TV files or `/data/*` staging directories are required. Red Moon cannot read unrestricted vendor logcat itself. Match timestamps; do not infer causation from unrelated old errors.

## 2. Controller, context and lifecycle

After the first successful visible test/restoration, verify operating bands at low/mid/high values conservatively. Test native manual changes during a fade: Red Moon must yield when its next read detects them. It cannot atomically exclude a vendor Settings write between read/set. Check picture modes, input changes, SDR/HDR, dynamic backlight and light-sensor modes without silently disabling native features.

Test screen-off during a preview/fade, wake, boot while asleep, ordinary process death/restart, explicit force-stop/relaunch, package replacement, blocked/unavailable vendor service and a second installed app variant. No concurrent writers, stale lease or unbounded retry is acceptable. Use Pause and transfer control in the old variant and wait for its native operation to settle before resuming the new variant; this clears the old saved restoration record. Force-stop requires manual relaunch.

Record whether input transitions can occur without a boot/picture context change. If they can, full automatic restoration/context compatibility remains unqualified until a verified vendor source identity/callback is implemented. Do not fabricate source keys.

## 3. Fades and independent warmth

Inspect the real native setter persistence before qualifying repeated writes. Neither lack of obvious log messages nor survival across reboot proves write-frequency safety. Keep fades unqualified if evidence is inconclusive; immediate changes are only an interim feature. When qualified, test 0/1/30/888 seconds, strict incoming-band entry, cancellation, delayed responses, duplicate suppression and next-phase interruption.

Check warmth independently: linked times, custom window, denied/revoked overlay permission, invalid stored window, attachment/removal failure and emergency off. Warmth off must not alter backlight. Verify UI, Stremio, Kodi, HDMI and live TV independently: hardware video planes may bypass the overlay. Colour labels are approximate, not a measured white point.

Use the same playback source/session with and without Red Moon, including during a long fade. Record app/native latency, relevant ANRs/decoder errors, process/memory behaviour and repeatability. Do not blame existing Stremio/decoder faults on timing alone. Steady state should be near idle with no ticker, queue growth or growing helper count.

## 4. Stable-package acceptance

After code/default-branch validation, prepare the signed `com.cbkii.redmoontv` draft candidate. Test those exact bytes separately: the package/UID differs from TESTING. Complete [A4-ACCEPTANCE.json](A4-ACCEPTANCE.json) with dated evidence for every gate. Promote consumes that record and rechecks remote hashes, signer, package/source/tag and current master, then publishes unchanged assets. It never builds or re-signs. An acknowledgement checkbox cannot replace this acceptance.

Do not call the result fully compatible until all declared native, context/lifecycle, fade, warmth, playback and stable artifact gates pass. Current physical results: **not run in the development workspace**.

## Latest reported device result and retest

The signed 9fa / 41041255395-1 APK was installed and produced `rmd.txt`: native initialisation failed with a duplicate TV-APK classloader/JNI ownership error; recovery was not pending and no physical native-control/fade qualification was acknowledged. This is a failed native read, superseding the earlier absence of runtime evidence. The updated source pins one loader/first initialisation result and exposes the original failure stage. Test the updated signed APK with Read first; if it reports a retained failure, copy Diagnostics before using the explicit read-only Restart worker operation. See A4-REVIEW-RESOLUTION.md. No claim of physical compatibility follows from the code fix.

## Root fallback and performance evidence

The new root backend is explicitly selectable after pausing and resolving saved recovery; it is not automatic failover. Requalify each backend separately. See [root/performance procedure and collector](A4-ROOT-AND-PERFORMANCE.md). Before declaring root functional, test denied/revoked Magisk access, delayed permission approval beyond the 20-second client deadline, root/JNI death and the five-second worker deadline, pipe closure on app death, standby cleanup, package replacement and cross-variant lease exclusion. Check that failed/late sessions never perform an unexpected setter. Both backends load the installed library and can still fail at namespace/service permissions.
