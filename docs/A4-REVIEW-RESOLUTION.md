# A4 review resolution — 30 October 2026

Inputs: `a4-redmoon-early-review.md` (audited c89/e1 heads) and actual `rmd.txt` from signed TESTING source `9fa0afac2a9439218fc46e8df15a412413cb29ab`, run `41041255395-1`. Current hardware report: API 28, armeabi-v7a/armeabi, Toshiba build 01.03.00.03, application UID 10078. No backlight write/restoration or fade qualification is established by that report.

| Finding | Classification and implementation |
|---|---|
| Force/wake bypasses write prohibition | Confirmed. `NativeWriteGate` separates recalculation from permission. Native failures persist until explicit validated Resume; manual holds expire only at their recorded next boundary or explicit Resume. Recovery has precedence over both. JVM tests cover wake/time progression/restart representation and recovery. |
| Stale binding/PID on reconnect | Confirmed. Each binding has its own nonce and fresh server handshake. Retired bindings are unbound, outstanding waits released and late responses rejected. The client no longer terminates any numeric PID; the private service terminates its own process on last unbind. `WorkerSession` tests enforce connection, UID, process and response identity. Physical repeated-death/binding-count checks remain required. |
| Delayed fade retained after destruction | Confirmed executor behaviour. Own/cancel the ScheduledFuture on replacement, pause, screen-off and destruction; remove cancelled tasks, reject delayed tasks after shutdown. Real executor tests prove an 888-second timer does not prevent termination or retain its closure. |
| Hashing on UI thread | Confirmed. Hash installed bindings/JNI on one background worker. UI and service expose a checking state and cannot enable native scheduling until identity is ready. No source file hashing occurs in qualification getters. Physical StrictMode/trace measurement remains unperformed. |
| Legacy location Lint failures | Already fixed. Disabled obsolete collection code without restoring location permissions. Debug, snapshot and release lint/assembly passed on 9fa. |
| Optional external commands open to installed apps | Deliberate trust model. Off by default; enabling now explicitly discloses the all-installed-apps audience and fixed allowed commands. Private IPC, probes, qualification, restore and arbitrary/root commands are not exported. Malformed/duplicate commands remain bounded. Physical sender-app validation remains required. |
| Frame-driven warmth | Replaced with elapsed-time quantised integer transitions, at most five updates per second, scheduling only the next changed value. No ValueAnimator/all-day warmth ticker. Pure transition tests cover zero/no-op and 888 seconds. Optical coverage and playback cost still need the TV. |
| Vendor compatibility | Still open. rmd is a real failed read, not a passed compatibility test. Updated native initialisation must be physically read/probed again. |

## Actual JNI failure

The report shows the JNI library already owned by one TV-APK classloader while another loader attempts to open it. The previous worker recreated `A4NativeBridge` after constructor failure, which could replace the original error with a later duplicate-loader error. The update pins the installed API class/defining loader and latches the first initialisation success or failure for the worker lifetime. Repeated Read cannot create a new loader after failure. Diagnostics retain the first error and initialisation stage. An explicit read-only Restart worker operation disposes the owned process before a new attempt; it never clears pending recovery or authorises a panel write.

A fresh non-executing DEX inspection of the supplied TvSettingsPlus APK confirms:

- `MtkTvConfig.getInstance()` delegates to its base configuration class.
- Public instance get/set/min-max signatures match the bridge.
- `getMinValue(int)` and `getMaxValue(int)` are public static methods in MtkTvConfigBase.
- `TVNative` loads `com_mediatek_twoworlds_tv_jni` in its class initialiser.
- `TVNativeWrapper.is_emulator()` reads `vendor.mtk.inside`; no property is changed by Red Moon.

This corrects the verified loader-lifetime defect and preserves the earliest error for diagnosis. It does not prove that the first native initialisation or a panel write succeeds on the physical TV. Do not interpret duplicate-library errors as permission denial or automatically add root/HIDL fallback.

Platform reference: [AOSP linker namespace tests](https://android.googlesource.com/platform/cts/+/master/tests/tests/jni/src/android/jni/cts/LinkerNamespacesHelper.java). No vendor source/binaries are redistributed.
