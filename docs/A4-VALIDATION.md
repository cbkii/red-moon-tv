# A4 qualification and operation

This fork targets Toshiba 50C350KP / internal 50C351P, HiSmartTV A4, xiaoyushan, MStar MSD6886/m7322, Android TV 9. It is not a firmware package. Android 8/API 26 is the APK minimum. Native vendor control remains **unqualified until tested on the physical A4**.

## Native backend

`A4NativeBridge` calls the installed Settings APK's `MtkTvConfig.getInstance()` using the exact UI key `g_disp__disp_back_light`. It reads the native range, captures the lower-level setter result and verifies readback. It uses the installed device JNI library, not an embedded vendor binary. Generic Android `SCREEN_BRIGHTNESS` writes are disabled throughout this TV fork.

The root helper uses the app's installed APK and the installed Settings APK directly as its classpath. It stages no APK/JAR under `/data` or elsewhere. Its only shared files are `controller.lock` and `controller-owner` beneath `/sdcard/Download/RedMoonTV`, preventing testing/debug/stable packages from concurrently owning native writes. Pause control in one installed variant before enabling another. Reboot expires controller ownership; uninstallation alone does not. The controller lock is coordination, not a security boundary against another root app.

Root must be granted to this app. A root permission, zero shell status, settings database value, screenshot or successful compile does not prove panel control. Device JNI/library namespaces and permissions can still reject the operation. A HIDL alternative was identified in the design but is deliberately not a silent write fallback.

## First test

1. Leave both schedules disabled. Note native Backlight Level, current input, picture mode and automatic-light-sensor/dynamic-backlight settings.
2. Open Red Moon TV using the remote. Select **Read native backlight (root)** and compare the result with the actual Backlight slider. The backend refuses an unexpected device, emulator/no-op configuration, unreadable range or negative error value.
3. Select the nearby-level test. It changes by five slider points (adjacent A4 values can share a PWM plateau), waits 15 seconds and attempts to restore. Inspect the real screen and the native slider; capture a short timestamped vendor log around the change if needed. Compare the native slider and timestamped panel log as well as perceived brightness; slider percentages are not linear physical light output.
4. Verify restoration. A killed app, revoked root, power interruption or changed picture context can prevent restoration; use the native TV Settings if necessary. No automatic zero/maximum sweep is used.
5. Qualify native source/picture scope, automatic-sensor interaction, standby and restoration before checking the physical-verification box. That acknowledgement is bound to the firmware fingerprint and Settings package update time; a changed firmware/package invalidates it.
6. Enable scheduled phase switching. Leave fade qualification off until repeated writes are proven suitable. The UI still stores your requested fade; until qualification, it explicitly reports that fades are unavailable and uses immediate phase switching.
7. Determine whether the native setter persists every write. The observed two-argument native setter supplies flags `3`; this implementation does not invent alternate flag meanings or promise volatile writes. Only enable repeated-write qualification after establishing suitable persistence behaviour.

The helper does not alter sensor, dynamic backlight, HDR, contrast, gamma, factory calibration, PWM frequency, SELinux, boot/root persistence, partitions or firmware. The native menu itself turns off its light sensor on manual adjustment; the app does not yet reproduce that side effect. If a sensor overrides writes, preserve evidence and qualify the exact necessary change separately.

## Scheduled behaviour

Day preference is a maximum/target in 70–100. Dusk preference is a minimum/target in 10–80. Dim preference is a minimum/target in 1–20. Values must follow Day ≥ Dusk ≥ Dim. The three start times define non-overlapping daily windows in cyclic Day → Dusk → Dim order. Defaults are 80 at 07:00, 40 at 18:30 and 5 at 22:00; both features start disabled.

Incoming fades accept exactly 0–888 seconds. They cannot exceed the destination window. Hard phase limits apply immediately: a 90→40 Dusk transition first steps to 80, then fades within 40–80. Entering Day first raises to at least 70. This deliberately honours the requested strict ranges. Fade commands are serial, quantised, duplicate-suppressed and capped at one step initiation per 500 ms; actual IPC latency can make them slower. A new generation cancels pending old steps. Readback errors stop the transition and block automatic retries; an explicit read/resume can establish state before another attempt.

Quick phase choices enable scheduling, are labelled temporary overrides and expire at the next scheduled boundary. Their choice/expiry survives service recreation. Pause leaves current brightness unchanged; explicit restore only attempts the saved value if native readback still matches the last owned write and the boot/picture context matches. Native source/input scope still requires physical qualification: picture-mode identity alone is not proof that an unchanged input is active.

No all-day polling, wake alarms, constant wakelocks or Magisk boot module is installed. Screen events are registered dynamically. The service reconciles current time on boot, package replacement, timezone/time change and wake; it does not replay historical phases. Android/vendor startup can illuminate the panel before this app runs, so there is no no-flash wake guarantee.

Native menu changes are not continuously overwritten during an idle phase. The next schedule boundary or explicit resume reapplies the phase. There is no claim of instant detection of external manual changes or automatic follow-up on every vendor input/picture/HDR switch: those vendor events and their scope remain to be qualified. The shared lock prevents two variants from continuously competing.

## Warmth

Warmth uses the existing Red Moon overlay/profile calculations with overlay dimming set to zero, bounded display dimensions and the legacy brightness manager disabled. It has its own enable switch, strength, colour and fade. Timing may follow the brightness phase boundaries or use an independent on/off window. Turning it off, losing overlay permission or encountering an overlay failure does not restore or reset native brightness. Root backlight permission never enables the unqualified root colour-matrix renderer.

Test actual video in Stremio and Kodi, Live TV and HDMI separately from menus. Hardware-composed planes may differ from captures. Backlight qualification does not require successful colour-overlay coverage.

## tvQuickActions and ADB

Enable **Allow explicit tvQuickActions / ADB control intents** first. Send an explicit broadcast to the installed variant, action `com.cbkii.redmoontv.CONTROL`, receiver `com.jmstudios.redmoon.tv.TvControlReceiver`. String extra `command` accepts `read`, `pause`, `resume`, `phase`, `warmth_off`; `phase` also requires uppercase `DAY`, `DUSK` or `DIM`. Unknown commands are ignored. There is no arbitrary shell-command interface.

Example for the testing package:

```sh
adb -s TV_SERIAL shell am broadcast \
  -n com.cbkii.redmoontv.testing/com.jmstudios.redmoon.tv.TvControlReceiver \
  -a com.cbkii.redmoontv.CONTROL --es command phase --es phase DIM
```

Use native readback/status in the app and bounded `logcat -s RedMoonTV` evidence. D-pad and backend functionality must be qualified on the real TV; source checks are not device acceptance.

## Acceptance ledger

| Gate | Required evidence |
|---|---|
| Installation/update | Actual package/version/certificate, fresh install and update preserving TV preferences |
| Native contract | Native UI/readback agree; small change affects panel; verified restoration |
| Persistence/fade | Storage behaviour, quantisation and timing suitable at 0 and 888 seconds |
| Ownership/overrides | Testing/stable conflict rejected; pause, manual native changes and override expiry |
| Lifecycle | Standby over boundaries, reboot, service recreation, time/timezone and DST |
| Source/profile | Android UI, Stremio/Kodi video, tuner/HDMI, applicable SDR/HDR and picture modes |
| Recovery | Lost root/vendor service, unknown write outcome, external change and changed context |
| Warmth independence | Permission loss/off/error never writes native backlight |
| Performance | No playback jank, ANR, idle polling or unbounded memory/retry growth |

Physical outcomes must be recorded against the exact APK SHA-256 and source SHA. Stable publication is gated separately from build/CI success.
