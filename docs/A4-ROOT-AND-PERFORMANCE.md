# A4 native fallback and performance qualification — 31 October 2026

Source implementation, not a physical compatibility claim. Both JNI backends still require a successful installed read and visible change/restoration on the Toshiba A4. The latest supplied device evidence is the earlier failed JNI read, not a successful root test.

## Seven-point implementation

| Area | Implementation and acceptance |
|---|---|
| Private JNI worker | Installed bindings, pinned loader/first failure, exact 0–100 range, read-only diagnostics even when boot/picture context is unavailable. A validated kernel boot UUID is preferred; Settings boot_count is a fallback only. Context is still mandatory for writes. |
| Root fallback | Explicit selection in Diagnostics, then Read. One persistent 32-bit worker loaded directly from the installed APK. Fixed binary requests over private stdin/stdout pipes; no published Binder/socket endpoint or shell command API. Root UID, response sequence, random connection token and worker identity are checked. |
| Failure containment | Maximum 20 seconds for initial root permission/handshake, seven seconds for subsequent IPC; the root worker exits itself after a five-second blocked operation. Parent pipe closure exits the worker and releases the same abstract panel lease as the APK backend. No automatic backend failover, setter replay, daemon, module or temporary executable staging. |
| Persistence and cost | Existing quantised native fades remain capped at two writes/second and schedule only the next changed integer. Repeated-write safety still requires vendor persistence investigation before enabling fades. JNI return values/readback and the atomic pending/confirmed journal are retained. Native-call counts/latency are recorded in memory only. |
| Lifecycle/context | Standby cancels transitions and closes the selected worker, freeing its process/lease while retaining recovery evidence. Backend switching is serialised, requires paused scheduling and no saved journal, and resets qualification. Backend identity is also in both qualification and recovery context. Input/HDR identity remains unqualified; no invented vendor keys. |
| Independent warmth | Retains one click-through overlay, next-change interpolation, independent enable/schedule/emergency off and failure containment. No frame ticker, extra colour daemon or speculative SurfaceFlinger transaction. Validate real video planes separately. |
| Exact artifact/diagnostics | Schema-3 report includes backend, first failure/stage, call latency/counts, heap, uptime and pending/original/confirmed recovery values. On-demand dumpsys and a bounded read-only ADB collector support paired playback/memory measurements and installed APK hashing. Stable artifact acceptance remains mandatory. |

The ordinary APK worker skips the unused phone-era profile migrations/theme initialisation in its private process. No dependency or toolchain upgrades are needed for root. The root backend is experimental and selected by the user; a permission grant is not proof of JNI/linker/HIDL access.

## Backend procedure

1. In Diagnostics, use **Read native backlight** on the default APK backend and copy the report. Missing boot/picture identity may permit a numeric read but must block the probe.
2. If that fails, pause and Restore or Keep any existing saved session after checking the TV's native Backlight setting. **Select root fallback** does not start root or write the panel. Select Read; allow Magisk when prompted if desired.
3. Record root UID/worker, native range, level, first error/stage and loading time. A retained failure requires explicit Restart worker; no automatic retry after an uncertain setter.
4. Only after a successful read/context, run the existing five-point, 15-second probe. Observe the real panel and native slider, then restoration. Interrupted rollback still requires explicit recovery; power loss/force-stop cannot promise restoration.
5. Repeat source/SDR/HDR/manual-adjustment/standby tests for the selected backend. A switch requires fresh native and fade qualification. Never run two build variants as writers.

Root's installed APK/classpath may naturally reside in Android-managed installation storage. The implementation creates no staged JAR, executable, shared result file or explicit temporary path there or in shared storage. Normal Android preferences and the atomic recovery record remain app-managed.

## Collector

Use an existing Python 3 + ADB installation on Termux or the workstation. It never installs, starts, force-stops, changes settings, clears logcat or writes TV files. The exact transport is mandatory and must report xiaoyushan before collection. Every command is bounded and failures remain in manifest.json; transport timeouts are not silently retried.

```sh
python3 scripts/a4-collect.py -s TV_IP:5555 --label apk-read --apk
python3 scripts/a4-collect.py -s TV_IP:5555 --label root-read --root --apk
python3 scripts/a4-collect.py -s TV_IP:5555 --label playback-fade --duration 30
```

The ZIP contains installed package metadata, bounded app dumpsys/report, app and worker memory/processes, filtered CPU/vendor logs and command exit statuses. `--apk` pulls the installed application's exact bytes and records SHA-256. `--root` runs one fixed read-only identity/HIDL/public-library check and can prompt Magisk. Labels and file names are sanitised. Review the archive before sharing. No screenshots are treated as PWM evidence.

For performance compare the same playback/source with Red Moon disabled, APK backend steady, root backend steady, and a qualified long fade. Wait for the same playback state before each sample. Capture both before/after memory/CPU and native counters; look for growing process counts, resident memory, pending work, latency, ANRs and decoder errors. Two samples are measurements, not a statistical benchmark or evidence of causation. Record optical panel response, source transitions and restoration separately.

If native persistence cannot be established, leave fades unqualified and use immediate phase changes. A lack of flash-related logcat lines does not prove volatile writes. If both JNI backends fail, preserve their first errors and obtain the installed vendor binding/JNI for offline signature inspection before implementing typed HIDL; advertised service names alone are insufficient.

## Reference implementations and reuse

These are API/lifecycle references, not A4 qualification. No code or vendor binaries from the other-device projects are bundled. Their platform-specific commands/paths/update flags are not copied.

- [Mimonitor Toolbox, MtkDirectTool.java](https://github.com/YiHoooong/Mimonitor_Toolbox/blob/master/tools/mtk_direct/MtkDirectTool.java): explicit PathClassLoader library search, g_disp__disp_back_light, separate native and Settings state. Independently reimplemented for 32-bit A4; no Xiaomi service executor, raw TVNative signature assumptions, file results or unverified isUpdate values.
- [cinetv-launcher](https://github.com/AlptugDag/cinetv-launcher): installed MtkTvConfig capability/reflection concept. No PolyForm-licensed implementation copied.
- [Picture Settings for Android TV](https://github.com/alexrcq/picture-settings-for-android-tv): independent warmth/backlight policy and remote interaction ideas. Its settings-database setter is not used.
- [MidnightBeam DimService](https://github.com/MadeinTaly/midnightbeam/blob/main/app/src/dev/midnightbeam/DimService.java): one click-through view, live changes and foreground lifecycle ownership. Red Moon retains its existing single-overlay/event scheduling implementation; no server, audio effects or minute polling is added.
- [Circadian Display CurveEngine](https://github.com/tomar-mohit/circadian-display/blob/main/core/curve/src/main/java/com/circadiandisplay/core/curve/CurveEngine.kt): pure interpolation and domain-test boundary. Existing Red Moon quantised transitions preserve midnight/DST and strict phase caps without a curve UI or extra framework.
- [OpenLumen](https://github.com/SysAdminDoc/OpenLumen): separate colour engines, failure/recovery and optical device matrix. A4 compositor transaction/video-plane compatibility is unproven, so no hardcoded SurfaceFlinger mutation is introduced.
- [libsu RootService 3.1.1](https://github.com/topjohnwu/libsu/blob/3.1.1/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java) and its IPCClient/IPCServer were inspected for persistent-worker/death handling. That version stages main.jar in app cache and can wait indefinitely for startup; the small pipe worker avoids those constraints without adding another dependency.

All new implementation code uses the repository's GPL-3.0-or-later licence. The inspected references informed design; no third-party source was incorporated requiring additional redistribution notices.

## Cross-file review map

| Contract | Producer/consumer and validation |
|---|---|
| Fixed root IPC | RootProtocol ↔ A4RootMain ↔ A4RootBacklightBackend. JVM tests reject invalid commands/values, non-root/stale/malformed replies, oversized output and truncated frames. Physical root denial/late approval/EOF/watchdog tests remain required. |
| Read vs write context | A4NativeBridge → either worker → BacklightController. A missing identity permits Read but not begin/write; regression tests exercise this and cross-backend restore rejection. |
| Selection/qualification | TvPreferences/TvActivity → serial TvDisplayService → selected BacklightBackend; RecoveryJournal blocks switching with pending ownership. Protocol/source/vendor/split-APK/backend identity invalidates previous qualification. |
| Runtime lifetime | Screen receiver → cancellation → queued close; probe/journal retained across sleep. NativeCallStats has no sampler or persistence. APK/root worker death must release the cross-variant lease physically. |
| Diagnostics/packaging | DiagnosticsExporter → clipboard/dumpsys/ADB collector. Android CI builds/lints/tests debug, TESTING and release. Artifact identity is embedded; source/build success is distinct from signed/physical acceptance. |
