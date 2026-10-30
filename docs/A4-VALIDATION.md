# Physical A4 acceptance

This is the Toshiba 50C350KP / internal 50C351P, xiaoyushan A4, Android TV 9/API 28, armeabi-v7a. The native menu's PWM path is established; control from this APK is **not yet verified**. Use an unchanged signed artifact and record its source SHA, package, APK SHA-256, signer, firmware fingerprint and build ID before testing.

## 1. Installed read and small probe

Install the TESTING APK normally (ADB `install -r` is sufficient). Open Red Moon TV → Diagnostics. Backlight scheduling and warmth default off; root is not requested. Select **Read native backlight**. Copy the bounded report. A valid result must identify native level/range, APK UID/ABI, installed class source/loader and backend apk-jni-v2. JNI-load/service/permission failures are diagnostic outcomes, not reasons to enable scheduling.

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
