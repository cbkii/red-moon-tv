# Red Moon TV: A4 backlight-first implementation plan

Updated 26 October 2026, Australia/Sydney. Supersedes the earlier plan’s feature priority, brightness design and feasibility gate. This is an investigated design, not an implemented or physically qualified APK.

## 1. Product decision

Make native panel backlight control the first deliverable. Add three daily brightness phases, each with an editable preferred value and incoming fade duration. Retain Red Moon’s colour filtering as an independently enabled and independently scheduled feature. Offer linked timing without coupling their permissions, failure states or on/off switches.

The first useful APK should control and read back the actual TV backlight with warmth completely disabled. Colour-overlay coverage must not block a backlight-only release.

Target: Toshiba 50C350KP / internal 50C351P, HiSmartTV A4, xiaoyushan, MStar MSD6886/m7322, Android TV 9/API 28. This design does not establish compatibility with other Hisense models.

## 2. Evidence and what remains unproven

### Evidence examined

- The linked backlight conversation’s latest retrievable findings; the user’s direct report that generic commands, including root attempts, did not change the screen or native Backlight Level of 66.
- `a4-test.txt`, including the native BackLightFragment and panel/PWM log sequence.
- `a4-brightness-17201.zip`. Its six before/after files contain `cmd: Failure calling service settings: Failed transaction (2147483646)`. They are failed captures, not usable settings snapshots or evidence of unchanged settings.
- The supplied `TvSettingsPlus.apk`, package `com.android.tv.settings`, versionName `1.0`, versionCode `1`, compile SDK 28. SHA-256: `bd4d3399257ca02b4ef2c3007a0e5e6133d8b4b8f198dab8804221d509587a90`.
- APK manifest and targeted Dalvik method inspection with Androguard, cross-checked against independent DEX instruction decoding. This established class/method calls and parcel layouts; no device execution was performed.
- Current fork master, repository tree, branches and open PRs; relevant upstream issues and currently open PRs.

### Findings

| Finding | Evidence level | Consequence |
|---|---|---|
| Red Moon’s current `BrightnessManager` writes `SCREEN_BRIGHTNESS` and automatic-brightness mode, setting brightness to zero | Source verified | Replace this route for A4 backlight; do not retain it as a silent fallback |
| Generic brightness/settings writes previously failed to change the actual TV backlight | User observation | Do not repeat them as the proposed implementation |
| Native UI key is `g_disp__disp_back_light` | APK and device log verified | Use native configuration percentage semantics |
| The UI reaches MtkTvConfig and a native JNI implementation | APK verified | Preferred route to qualify because it mirrors the native slider |
| A separate HisVideoApi percentage getter/setter and HIDL proxy are present | APK verified | A second, concrete candidate; equivalence to the UI route remains unproven |
| Settings declares `android:sharedUserId="android.uid.system"` | Manifest verified | Its access does not establish access for an ordinary independently signed APK |
| UI changes are accompanied by Hisense panel backlight and MStar PWM changes | Device log verified | Native panel control exists and is the right layer |
| UI percentage, vendor brightness percentage and PWM are not identical | Device log verified | Preserve vendor scaling; do not convert UI percentage directly to raw PWM |
| Direct native API access from a Red Moon APK/root helper | Unproven | First physical qualification gate |
| HIDL getter scale, persistence, source/profile scope and permission behaviour | Unproven | Measure before choosing a production backend |

For example, at the low end the log shows UI `1` → vendor `BrightPercent=3` → `mPwmValue=12` → panel `Backlight=30`; UI `0` produces the same values. UI `9` and `10` also share a logged PWM value. These are individual observed samples, not a complete calibration curve. A displayed 1% is a native slider position, not a promise of 1% light output or PWM duty.

## 3. Native integration contract

### Preferred candidate: the exact UI configuration route

The observed Java/Dalvik chain is:

1. `com.android.tv.settings.partnercustomizer.picture.ui.BackLightFragment.onPreferenceChange(...)`
2. `PreferenceCreateUtils.updatePreferenceChanged(...)`
3. `com.android.tv.settings.util.TvConfig.setConfigValue("g_disp__disp_back_light", value)`
4. `com.mediatek.twoworlds.tv.MtkTvConfig.getInstance().setConfigValue(key, value)`
5. Inherited `MtkTvConfigBase` two-argument setter calls `setConfigValue(-1, key, value, 3)`.
6. `TVNativeWrapper.setConfigValue_native(...)` → `TVNative.setConfigValue_native(...)`.
7. `TVNative` loads the device library `libcom_mediatek_twoworlds_tv_jni.so`.

Readback is `MtkTvConfig.getInstance().getConfigValue(key)`. Range discovery is available through `getMinMaxConfigValue(key)` with `getMinValue(...)` / `getMaxValue(...)`.

The JNI library is not bundled in the supplied APK. Its implementation, dependency availability and the meaning of the setter’s flag `3` require qualification. In particular, determine whether repeated calls persist to storage before implementing fades. Do not assume another flag is a volatile-write option.

Important error-handling defect: the APK’s `TvConfig.setConfigValue` discards the lower-layer return and returns zero. A success-looking UI wrapper or shell exit cannot prove application. Call the lower-level API where practical, retain its status, then check native readback and physical effect.

### Second candidate: Hisense HIDL percentage API

The APK contains public static methods:

```java
com.hisense.avmiddleware.HisVideoApi.getBacklightPercent();
com.hisense.avmiddleware.HisVideoApi.setBacklightPercent(int value);
```

They connect to:

```text
vendor.hisense.mw.hs_av_service@1.0::IHisVideoApi/default
```

The supplied HIDL proxy encodes:

| Operation | Transaction | Request after interface token | Reply after verifySuccess |
|---|---:|---|---|
| setBacklightPercent | 10 | One int32 | One int32 return value |
| getBacklightPercent | 11 | No scalar arguments | Two int32 callback values |

The wrapper’s declared percent range is 0–100. The getter wrapper returns its callback-populated value and can return -1 on failure. Decode and retain both callback fields when using the lower-level interface; confirm their status/value meanings. Never clamp a negative error into a valid brightness.

These transaction numbers are specific evidence from this APK, not generic Android service-call commands. HIDL uses HwBinder/HwParcel; ordinary `service call` syntax for framework Binder is not a substitute. Do not use the `ByFactory`, PWM-frequency or min/mid/max calibration setters. The direct HIDL scale may differ from the native UI scale; establish that before using it.

### Backend selection and packaging

Implement an A4 backend interface with capability discovery, native read, native write and diagnostics. Qualify the configuration route first; test HIDL only as an explicitly selected alternative if needed. Never switch write backends silently after a timeout or unknown write outcome.

Try the least privileged runtime that can perform the verified contract. If necessary, use a small root helper from the application’s own code, with a fixed read/set command set and a single serial writer. Verify the actual root, ABI, classpath, native library and service-access context; root alone does not establish success. Test the appropriate 32/64-bit process where the native route requires it.

Load required classes from the installed device package/framework where feasible. Do not distribute or modify the vendor Settings APK, copy its full implementation into the GPL app, request its system UID, replace the system app, install a Magisk module or change SELinux policy as an initial implementation shortcut. Device API contracts can be independently implemented. If dependencies or access prevent the first route, preserve the precise failure and qualify the other candidate.

## 4. Brightness semantics

Use one preferred value per phase, constrained to the user’s ranges. For a simple first release, automation settles at that preferred value. The same value supplies the requested one-sided bound for temporary adjustments; no ambient-light algorithm is implied.

| Phase | Preferred value may be set within | Meaning of preference | Permitted app-issued values while phase is active |
|---|---|---|---|
| Day | 70–100 | Maximum and scheduled target D | 70 ≤ value ≤ D |
| Dusk | 10–80 | Minimum and scheduled target E | E ≤ value ≤ 80 |
| Dim | 1–20 | Minimum and scheduled target N | N ≤ value ≤ 20 |

Validate `D ≥ E ≥ N` as well as individual ranges. Explain conflicting values inline and require correction before applying; do not silently move another phase’s preference. Suggested initial values, subject to user setup: Day 80, Dusk 40, Dim 5. These are proposed product defaults, not evidence of the user’s preferences or physical comfort thresholds.

Backlight values use native UI percentage units. Keep extra overlay dimming separate, off by default. No writes to gamma, black level, contrast, panel calibration or PWM frequency are needed for this feature.

### Hard limits versus smooth fades

Literal hard ranges and uninterrupted cross-phase fades conflict. Day 90 → Dusk 40 cannot remain inside Dusk’s 10–80 band for the entire fade. Dim → Day cannot rise continuously while immediately satisfying Day’s minimum 70.

To preserve the explicit no-out-of-range requirement, use strict incoming-phase limits by default:

1. At a phase boundary, read current native brightness.
2. Clamp the start value into the incoming phase’s permitted band.
3. If necessary, apply that boundary value immediately.
4. Fade from that valid start to the incoming phase’s preferred value, issuing only in-band values.

Examples with D=90, E=40, N=5:

- Day 90 → Dusk: immediate boundary step to 80, then configured fade 80 → 40.
- Dusk 40 → Dim: immediate step to 20, then fade 20 → 5.
- Dim 5 → Day: immediate step to 70, then fade 70 → 90.

The UI should explain this once as “Phase limits apply immediately; fade continues within them.” This preserves the stated constraints but can create a visible boundary step. A fully smooth endpoint-to-endpoint option would require an explicitly different rule allowing temporary out-of-band values during a transition. It is not part of this baseline plan.

The app can guarantee its own validated write requests. It cannot guarantee that another app, the TV’s menu, HDR/picture-mode logic or firmware never changes the panel beyond those values. The first release should avoid a continuous brightness fight with those writers.

## 5. Schedule and transition design

### Three boundaries, not six independent times

Ask for Day starts, Dusk starts and Dim starts. Derive each end from the next phase. This gives complete daily coverage without overlaps or gaps and naturally handles midnight. Show the resulting full windows on screen. Require distinct times in Day → Dusk → Dim order around the daily cycle; do not silently reorder the labels.

Illustrative setup only: Day 07:00–18:30; Dusk 18:30–22:00; Dim 22:00–07:00. Use the TV’s local timezone. Fixed local times are the first release; solar events can be added later without a location service running all day.

Each row has its own incoming fade: 0–888 seconds inclusive, with 0 meaning immediate. Provide remote-friendly shortcuts such as Off, 30 s, 1 min, 5 min and Custom, with an integer-seconds custom editor supporting the whole range. Do not round away 888 seconds.

Reject a fade longer than its destination window rather than letting transitions overlap. If configuration changes or an override interrupts a fade, cancel the old generation before starting the next. A single coordinator owns every write.

### Efficient execution

- Use one-shot scheduling for the next relevant event, not a second-by-second all-day loop.
- Use exact boundary scheduling on API 28 where appropriate; do not wake the TV/panel to execute a brightness change in standby.
- Start the service independently of overlay permission. Keep required foreground lifecycle support while controlling the backlight, with bounded work and no constant CPU wakelock.
- Recompute on boot, package replacement, local time/date/timezone change, standby resume and qualified input/picture-mode changes. Register screen-state events dynamically where Android requires it.
- On wake, apply the current phase from current state; do not replay missed phases or a chain of historical fades. Use the active phase’s configured fade within strict limits. The OS/vendor may light the panel before an app runs; a no-flash wake guarantee is not yet established.
- During an active fade, use elapsed monotonic time and emit only changed integer values. Start with a maximum of two native writes per second, then qualify latency and visible stepping on the TV. Slow transitions need far fewer writes. Never tie vendor calls to a 60-fps animation callback.
- Quantisation, slow IPC and hardware plateaus can prevent a visibly continuous fade, especially over 1–20. Confirm exact endpoints and report finite timing/step limitations honestly.
- If the backend proves to persist each step and no qualified volatile route exists, ship native phase switching first with fades explicitly unavailable; do not silently ignore the requested fade or enable storage-heavy animation.

## 6. Independent warmth with optional timing link

Use two feature switches: Backlight schedule and Warmth schedule.

Warmth timing has “Follow brightness phases” and “Custom schedule”. Following phases shares their boundaries initially, but warmth uses its own profile/strength and fade duration; the user may choose neutral Day, gentle Dusk and warmer Dim. Custom scheduling retains a separate warmth on/off window and profile. Do not require new per-phase warmth editing to use brightness.

“Warmth off” removes tint without restoring daytime backlight. “Backlight schedule paused” leaves warmth running. If an overlay is blocked by an app or lacks permission, backlight scheduling continues. Root access for backlight must not automatically enable the unqualified root colour renderer.

Keep existing colour profile calculations where useful. Add a coordinator rather than extending the existing filter on/off command into an ambiguous global toggle. Remove the A4 path’s dependency on legacy `lower brightness` side effects.

## 7. TV UX and overrides

The main screen should show native backlight readback, active phase, target, fade progress if applicable, warmth status and next scheduled change. Keep native control errors distinct from overlay errors.

Use three focusable phase rows: phase, derived time window, preferred value/bound label and incoming fade. Up/down moves between fields or rows; an explicit edit state uses left/right for values and OK to save. Back cancels an edit and otherwise navigates normally. Provide a strong consistent focus state and no touch-dependent clock dial.

Add quick actions: Apply current phase, Day, Dusk, Dim, Resume schedule and Pause backlight changes. A manual phase selection uses that selected phase’s bounds, remains visibly marked as a temporary override, and expires at the next scheduled phase boundary. While an override is active the UI must not misleadingly label the scheduled phase as controlling the output.

Native menu changes should be respected as temporary manual control when reliably detected and source context is unchanged. Do not continuously force the scheduled target back. Without a qualified event source, promise readback/reconciliation at defined events rather than immediate detection. At the next boundary resume scheduling. An external input/picture/HDR change is a different event: reread scope and reapply only after qualification. Avoid treating every unowned change as a proven user action.

Offer a bounded preview, for example 15 seconds, with Keep/Revert. Preflight restoration before applying it and attempt rollback on expiry. Do not claim a countdown guarantees restoration after force-stop or power loss. Keep a readily accessible “Pause changes and restore saved backlight” action; restore the captured native value only if the app still owns the changed state/context, otherwise preserve the later external value. Restoring/relinquishing control is an explicit override, not an ordinary scheduled write constrained to the current phase. No automatic restore to 100.

Testing and stable packages must not both operate the panel scheduler at once. Document and enforce a single active controller during qualification; package coexistence is not permission for competing writers.

## 8. Architecture and validation

Suggested components:

- `BacklightBackend` / `A4BacklightBackend`: discover, read, write, status, source scope.
- `BrightnessPolicy`: validate phase preferences and enforce every emitted value’s active bounds.
- `DailySchedule`: pure calculation of current/next phase with midnight and DST handling.
- `TransitionController`: one cancellable writer, monotonic timing, clamped start, quantised steps and bounded retries.
- `DisplayCoordinator`: independent backlight/warmth state, overrides and lifecycle reconciliation.
- `TvActivity`: D-pad UI and explicit status/actions.
- Small root bridge only if physical qualification establishes a concrete need.

Retain desired state separately from last requested value, native readback, physical qualification result and last error. Backend failure must show “unavailable/failed”, not “applied”. Do not restore stale cached values into a different input or picture profile. Reconnection is bounded and does not spin during standby.

Meaningful automated tests must cover policy boundaries and invalid configurations, mode ordering, midnight, DST skipped/repeated times, 0/888-second fades, no out-of-band emissions, duplicate suppression, cancellation races, failed readback, error values, restart, overrides and separate warmth/backlight control. Define DST resolution deterministically: skipped local boundaries map to the next valid local instant; repeated boundaries resolve once at the earlier offset, with instant-based event identity. If distinct boundaries collapse onto one instant, reconcile directly to the phase applicable after all crossed boundaries.

Use fake backends to prove the writer invariants. Physical TV tests remain necessary for permission, scale, storage persistence, panel response, standby, source switching, playback and recovery.

## 9. Revised delivery sequence and acceptance gates

| Step | Deliverable | Completion gate |
|---|---|---|
| 0 | Build/signing and TESTING workflow foundation | Reproducible signed APK, exact source/metadata/certificate/checksum verified |
| 1 — highest feature priority | Minimal A4 native-backlight diagnostic build, warmth disabled | Read native UI value; write a small nearby change; verify UI + panel/log response; restore captured value; identify privilege and scale |
| 2 | Native backlight controller and manual D-pad UI | No generic-setting fallback; errors/readback correct; single writer; native menu/input behaviour qualified |
| 3 | Day/Dusk/Dim schedule and strict-limit fades | Bounds, windows, duration limits, cancellations, standby/time changes and overrides validated |
| 4 | Independently scheduled warmth and optional timing link | Tint can toggle/pause/fail without changing native brightness; video coverage tested per renderer |
| 5 | A4 qualification and initial backlight-focused release | Exact signed APK passes installation/upgrade, source/profile, playback, recovery and performance checks |

First hardware probe must capture current backlight, backend range, input/picture context and relevant automatic sensor/dynamic-backlight state. Perform read-only access checks first; then one small nearby reversible change, followed by verified restoration. Do not begin with a sweep to 1 or 100. An automatic light sensor exists in the native UI and is turned off by that UI after manual backlight edits; establish actual state and behaviour before duplicating any side effect. Capture/restore only states intentionally changed. Do not disable unrelated picture features pre-emptively.

Physical acceptance includes Android home/settings, Stremio and Kodi video, Live TV and HDMI, SDR/HDR and picture/input switching where available, standby across boundaries, reboot, lost root/vendor service, external menu adjustment, process termination, update, preview and restoration. Hardware backlight should act on the common panel, but its persistence across vendor reconfiguration still needs testing. Do not claim it is immune to source changes merely because it is a panel control.

For TESTING, retain the requested permanent draft release pattern and separate `.testing` application identity; drafts are owner-accessible rather than ordinary public prereleases. Keep stable and testing signing continuity and inspect existing repository patterns before adapting them. Reuse the established secret names `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` without exposing values. Publish stable only with supported capability and limitations recorded; backlight-only can qualify independently of tint.

At inspection the fork’s `master` was still `d4d1c443bbc676132c767539c942296987787158`, with upstream `.github/workflows/android.yml` and no open fork PRs. This inspection did not establish that the separately requested new TESTING/release pipeline had been implemented. Treat that foundation as pending until its actual remote branch/workflow and artefact are verified. No repository changes, workflow runs or releases were made during this planning task.

## 10. Research informing the design

- [Upstream #33: progressively red-en and dim backlight](https://github.com/LibreShift/red-moon/issues/33), open at inspection. Supports gradual scheduled transitions; not an existing completed three-phase implementation.
- [Upstream #122: intelligent dimming](https://github.com/LibreShift/red-moon/issues/122), open. Explicitly distinguishes backlight from overlay dimming and examines manual changes, different device mappings and restoration. Reuse the separation; avoid its more complex combined-slider compensation for this TV’s first release.
- [Upstream #208: retain low brightness while pausing for secure apps](https://github.com/LibreShift/red-moon/issues/208), open. Directly motivates independence from tint lifecycle.
- [Upstream #75: brightness per filter](https://github.com/LibreShift/red-moon/issues/75), closed. Historical UX discussion; it does not provide a three-phase vendor-backlight scheduler.
- [Upstream #227: Android TV](https://github.com/LibreShift/red-moon/issues/227), open. TV launcher groundwork, not a native A4 backend.
- Upstream open PR inventory contained #357 and #344, both translation updates. No currently open PR supplied this backlight/schedule feature.
- [Twilight official documentation](https://twilight.urbandroid.org/doc/): custom/solar timing, separate backlight control concept and explicit automation commands. These are UX precedents, not proof of A4 vendor compatibility.
- [Night Screen official documentation](https://docs.androxus.com/night-screen/): clear mode selection, automatic scheduling and gradual transitions. Its documented dimming is an overlay, not A4 panel control.
- [Redshift configuration sample](https://github.com/sharpbracket/redshift/blob/master/redshift.conf.sample): separate day/night temperature and brightness settings and manually specified dawn/dusk intervals. Use as a scheduling precedent, not an Android backend.
- [Android TV navigation guidance](https://developer.android.com/training/tv/start/navigation): predictable D-pad movement, obvious focus, reachable controls and normal Back behaviour.
- [Fork BrightnessManager](https://github.com/cbkii/red-moon-tv/blob/d4d1c443bbc676132c767539c942296987787158/app/src/main/java/com/jmstudios/redmoon/manager/BrightnessManager.kt), [FilterService](https://github.com/cbkii/red-moon-tv/blob/d4d1c443bbc676132c767539c942296987787158/app/src/main/java/com/jmstudios/redmoon/service/FilterService.kt) and [ScheduleReceiver](https://github.com/cbkii/red-moon-tv/blob/d4d1c443bbc676132c767539c942296987787158/app/src/main/java/com/jmstudios/redmoon/receiver/ScheduleReceiver.kt): current implementation constraints. Existing alarm/filter interpolation is useful context, but hardware IPC must not be driven by per-frame ValueAnimator updates.

## 11. Outstanding boundary

The concrete vendor APIs and UI call path are now identified. A successful, independently invoked A4 backlight command has not been established by the available evidence. The next implementation milestone is therefore a minimal native-backlight probe/build with read/change/read/restore verification, followed by the scheduler. There is no reason to repeat disproved generic Android brightness commands or defer this priority behind a complete colour-filter UI.
