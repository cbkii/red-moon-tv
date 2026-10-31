# Red Moon TV — A4 engineering, implementation and release plan

**Revision:** 2, 30 October 2026 (Australia/Sydney). Supersedes the earlier 30 October plan and earlier architecture proposals where they conflict.
**Repository:** https://github.com/cbkii/red-moon-tv
**Application work:** https://github.com/cbkii/red-moon-tv/pull/1
**Scope of this revision:** evidence and source review, followed by a development specification. No repository edits, new builds, device tests, merges or releases were performed during this planning pass.

## 1. Outcome and principal decisions

Deliver a normally installed, remote-friendly APK for this exact Toshiba/Hisense A4 which controls the physical panel backlight through the installed vendor interface, runs Day/Dusk/Dim schedules, supports qualified 0–888-second transitions, and offers independent scheduled warmth. It must remain lightweight during playback and recover predictably from standby, process death, manual picture changes and vendor failures.

Retain PR #1 as the application foundation. Its policy, tests, TV controls and release infrastructure are useful; its native execution and lifecycle paths require corrections. First prove a vendor getter from the installed APK. Then prove one nearby reversible hardware change. Only then enable unattended backlight scheduling and separately qualify repeated-write fades.

The preferred backend is the installed MediaTek Java/JNI configuration API called inside an Android app process, with correct class loading and native-library resolution. A small private app service process is preferable if native crashes or blocking calls require containment. Direct MediaTek HIDL is conditional on verified bindings and access. Root is conditional on an evidenced permission boundary. None of these routes is yet physically qualified from Red Moon.

A diagnostic APK and a backlight-only interim release are useful milestones. They do not satisfy the complete requested product if fades or warmth remain unsupported. The final status must name those omissions rather than describe a restricted build as fully compatible.

## 2. Inspected repository baseline

| Item | Live observation on 30 October 2026 |
|---|---|
| Default branch | `master`, PR base `d76fe0f0a4900cc4c4fef35d89a03c47b00643a4` |
| Application PR | #1, open, draft, mergeable; only open PR returned |
| Branch/head | `codex/a4-backlight-tv`, `d3b003300754051ad7dc87a4e5cebbdd91d37a0b` |
| Diff | 35 files; 2,111 additions, 148 deletions |
| Canonical CI | Android CI run `40289159745`, build job `121459944491`: successful, including script tests, Android test/lint/build step and artifact upload |
| Review state | No inline review threads returned; bot comments include an earlier partial-change summary and a generic approval, neither substitutes for full review |
| Releases | Releases endpoint returned an empty collection; no signed publication demonstrated |
| Default-branch workflows | Only `.github/workflows/android.yml`; the new TESTING/manual-release workflows remain on PR #1 |
| Android configuration on PR | minSdk 26, compile/target SDK 32; stable defaults `4.0.1-tv.1` / code 40 |
| Packages | `com.cbkii.redmoontv`, `.testing`, `.debug` |

CI proves the recorded source builds and passes its automated checks. It does not prove vendor class loading, panel changes, release signing continuity, physical playback performance or a successful TESTING publishing run. This pass inspected source and recorded CI steps; it did not rerun Android builds or independently inspect the APK bytes. Recheck heads, policies and checks before implementation or publication. Actions inventory access was unavailable through the generic endpoint; no claim is made about account-level event policies or secret availability.

## 3. Device evidence and boundaries

### Settled identity

Toshiba retail **50C350KP**, internal/OTA **50C351P**, HiSmartTV A4; `xiaoyushan_emT` / `xiaoyushan`, platform A8020EX, MStar MSD6886/m7322, board RSAG7.820.9574/ROH. Android TV 9 / API 28. Confirmed runtime ABI list: **`armeabi-v7a,armeabi`**, with a 32-bit vendor JNI library. This is not a generic modern Android TV or an Amlogic target.

The latest supplied root check reports UID 0 in `u:r:magisk:s0`. Previously recorded firmware is `0100ACL0610` / `01.03.00.03`; Magisk 30.7, permissive SELinux, about 1.87 GiB RAM and `ro.config.low_ram=true` are mutable baselines, not permanent compatibility guarantees. Refresh only relevant software identity at runtime qualification. Firmware upgrades, root changes and SELinux changes are outside this app plan.

### Evidence classification

| Observation | Engineering consequence |
|---|---|
| Native Settings slider visibly changes the panel and produces MStar PWM logs | Genuine panel control exists and is the reference path |
| `BackLightFragment` → preference/configuration layer → `MtkTvConfig.setConfigValue()` → native TV interface; key `g_disp__disp_back_light` | Preserve this exact key and verify installed method signatures |
| `screen_brightness` and tested picture settings database writes produced no physical change or slider update | Do not retry these as hardware backends |
| Kernel backlight reports maximum 1 | Not a usable graded dimmer; do not test its off state |
| Vendor JNI exists at `/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so`, public-list entry present, label `same_process_hal_file` | Ordinary app loading is plausible, conditional on classloader, dependencies and service access |
| Root check advertises `vendor.mediatek.tv.mtktvapi@1.0::IMtkTvApiConfig/default`, with N/A fields | Interface discovery evidence, not proof that a client can obtain a usable service |
| Hisense `IHisVideoApi` appears in the Settings APK but was not found in service discovery | Keep as research evidence; do not build the default backend around it |
| Earlier standalone helper fails with `UnsatisfiedLinkError` for the JNI library | Confirmed failure of that launch arrangement; not proof that every app-process or correctly configured helper route fails |
| D-pad menu automation works, but user rejected it for Red Moon | Excluded from production; native Settings remains a manual recovery route |

The original `a4-test.txt` provides useful scale examples: UI level 1 → `BrightPercent=3` → `mPwmValue=12` → panel byte 30; UI 0 has the same low-end output in that capture. UI 66 maps to 75 / 77 / 196, while UI 75 maps to 78 / 80 / 204. Several adjacent UI settings share one PWM value. These are observations under the captured picture context, not a universal conversion formula or luminance calibration. The app's percentage means **native slider position**.

The public-library mechanism supports the preferred approach, but does not grant vendor Binder/HIDL permissions or make all transitive dependencies accessible. A classloader's native search path is configurable; specifying a directory does not bypass linker namespaces. See references [A1–A3].

## 4. Corrections to the previous plan and source findings

| Priority | Finding | Required change / proof |
|---|---|---|
| P0 | `A4BacklightBackend.execute()` starts `su` and `app_process` for every get/claim/set/release | Replace as the default with an APK-context vendor adapter; retain any helper only after its specific runtime failure is corrected and qualified |
| P0 | Current helper requires UID 0 and rejects absent/zero `vendor.mtk.inside` | Inspect the installed Java branch and actual property before retaining this prerequisite; diagnostics must report the distinction between unsupported hardware and unavailable/no-op vendor API |
| P0 | Normal app class visibility and JNI registration are not established | Resolve exact installed Java bindings, defining classloader, library search path and dependent classes before a write |
| P0 | Preview original/last/context exist only in memory; callbacks disappear on destruction | Add a durable transaction journal before any probe write, restart reconciliation and conditional restoration |
| P0 | Preview assigns `previewLast=target` before confirmed application; errors can leave an ownership claim | Track pending versus confirmed writes separately; release in all completed failure paths, preserving unknown outcomes for reconciliation |
| P0 | Shared `/sdcard/Download/RedMoonTV/controller-owner` persists across same-boot process death and accepts a caller-supplied package name | Replace with a live process/session lease; shared storage is neither authority nor a trusted root IPC channel |
| P0 | Warmth and backlight share a lifecycle service; overlay creation/destruction and invalid custom windows can throw outside the native error handler | Contain warmth failures so they cannot kill backlight scheduling or lose recovery state |
| P1 | Generation checks do not interrupt a setter already in progress | Distinguish cancellation requested from operation completed; reconcile late results before a new writer/restoration |
| P1 | Context currently includes boot identity and `g_video__picture_mode` only | Qualify source/input and SDR/HDR effects; this tuple does not establish full context identity |
| P1 | Manual changes are only noticed at subsequent reads; no vendor event subscription exists | Preserve manual adjustment when detected; add verified callbacks or bounded transition checks, and document detection limits |
| P1 | A receiver skips startup when boot/package update occurs while non-interactive; screen-on is dynamically registered only in a running service | Make enabled schedules establish their lightweight lifecycle owner while screen-off; no native writes until interactive |
| P1 | Qualification signature uses firmware fingerprint and Settings update time only | Include backend implementation/protocol version and installed binding/library identity; invalidate appropriately after changed code/vendor stack |
| P1 | Current release workflow builds/signs/publishes together; physical qualification is only a boolean | Introduce prepare → exact signed stable-package acceptance → promote unchanged assets |
| P1 | New workflows absent from default branch | Bootstrap infrastructure separately before depending on its TESTING workflow |
| P2 | UI largely mixes advanced qualification and everyday controls | Keep a simple status/schedule screen and a separate diagnostics section; make schedule edits atomic |

**Fade estimate correction:** the old plan's estimate of roughly 1,776 root processes per 888-second fade was incorrect. The service may schedule about that many 500 ms ticks, but `TransitionSession` suppresses duplicate integer writes. A monotonic 0–100 transition has at most about 100 changed-value writes, plus reads/claim/release. Per-write JVM startup and possible persistence remain material concerns; the source does not write at every tick. Also inspect the app's own per-step preference writes, not only vendor persistence.

## 5. Architecture and native qualification

### Components and contracts

Keep `BrightnessPolicy` and `TransitionSession` pure and independently testable. Introduce explicit boundaries:

- `VendorApiLoader`: discovers installed bindings and resolves classes/JNI once per worker session.
- `BacklightBackend`: `capabilities()`, `read(context)`, `write(level, expectedContext, operationId)`, session open/close. Return structured state/status; do not represent failure as a brightness number.
- `BacklightController`: serialises commands, owns phase/manual state, recovery, deadlines and write generation.
- `ControllerLease`: prevents the three app variants from controlling the panel concurrently.
- `RecoveryJournal`: captured original, request, confirmed result, context, session/boot identity and restoration state.
- `WarmthController`: separate timing/overlay state and error containment, sharing schedule data only when linked.
- `DiagnosticsExporter`: bounded app-owned records and a user-run ADB companion for vendor logs unavailable to ordinary apps.

Start with one serial worker. If JNI can abort or block indefinitely, host only the vendor adapter in a private `:backlight` Android service process under the app UID. This is not `android:isolatedProcess`, which changes permissions and is unsuitable by default. Keep policy/UI alive if that process dies. Avoid an extra process if qualification demonstrates it is unnecessary on this low-RAM TV; record the trade-off.

### Backend A — installed Java/JNI interface

1. Inspect the exact installed `TvSettingsPlus.apk` and any referenced framework/shared-library JARs. Record hashes, package version/signing identity, required classes, static initialisers, constructor/getInstance behaviour, return codes, min/max packing and any context/callback prerequisites. The prior decompilation identifies the path; it does not replace runtime proof.
2. Resolve the vendor binding from its real installed location, considering the boot/shared-library classpath before loading the APK. Use PackageManager paths and a deliberate `PathClassLoader` where necessary, including a verified 32-bit native search directory. Load all related vendor classes in one compatible defining loader. Do not invent a tiny replacement `TVNative` stub: JNI may register many exact class/method signatures during initialisation.
3. Do not redistribute the proprietary Settings APK or vendor `.so`, request a system UID, change public-library files or modify SELinux. If a reflection/non-SDK restriction occurs, report it as such rather than bypassing it by guessed method names.
4. Read the native value/range with no writes. Report requested package, actual UID/ABI, selected class source, loader, library, native status, context and latency. No root grant is needed unless this path proves it requires one.
5. Add an optional manifest native-library declaration appropriate to the supported runtime. Android 12+ has additional declaration rules; that declaration is not a fix for Android 9 class loading. Keep API 28 qualification separate from newer-device claims [A1–A3].

### Backend B — registered MediaTek configuration HIDL

Use only if A is unavailable for a diagnosed reason. Obtain exact installed interface/generated binding evidence and validate service name, descriptor/version/hash where available, signatures, transport, statuses and scale. Generated Java HIDL may itself depend on restricted platform APIs; do not assume this is a simple ordinary-app SDK replacement. First attempt bounded discovery/read from the actual app UID. A listed service with N/A output is not a successful `getService()` [A4]. No guessed parcel layouts, transaction numbers or generic framework `service call`.

### Backend C — narrowly scoped root session

Only after reproducing a UID/service permission boundary. Correct the loader issue independently; root does not solve it automatically. Use the installed APK as code input, fixed numeric commands, authenticated app/session identity, bounded startup/calls and a lifetime tied to the controller. Never stage executable code or trust control files on shared storage. No explicit `/data/*` staging paths, boot scripts, Magisk module or permanent root daemon. Normal OS-managed Android app storage is not manually staged shell storage.

Do not implement all three production backends in advance. Select and document the first qualified route. After an uncertain write, never silently fail over to another writer.

### Errors, deadlines and recovery

Distinguish unsupported platform, unavailable binding, JNI/dependency load failure, native crash, permission denial, service unavailable/dead, invalid range, failed native status, context change, readback mismatch, timeout/unknown result and ownership conflict. Include operation ID and last confirmed state.

A timeout does not cancel a native Binder operation. Java thread interruption cannot guarantee that a JNI call stopped. Keep only one in-flight write, stop queuing new writes, and reconcile via a fresh read once transport/session status is known. Where a worker process can be terminated, terminate only the owned process and still treat a vendor-side write as potentially completed. Retry read-only discovery at most twice with bounded backoff; do not blindly retry an ambiguous setter.

## 6. Ownership, probes and restoration

For the initial implementation, default every newly installed variant to scheduling off. Use an exclusive live lease shared by the variants, such as an abstract local socket held by the actual controller process, plus signature/UID-checked Binder handover. Validate this mechanism on API 28. The socket is an exclusivity primitive, not proof of caller authority; unknown occupants cause a clear ownership conflict. Kernel lifetime cleanup prevents a stale disk marker from blocking the next app after a crash. If a root worker owns the write session, its lease must remain held until that session is confirmed closed.

Do not introduce shared UID/package signing changes. When stable and TESTING coexist, provide an explicit transfer action that pauses the previous owner, waits for its in-flight command to settle, then enables the new owner. Package-name strings in files do not authenticate a privileged request.

Probe protocol:

1. Scheduling paused, screen interactive, live lease acquired, supported native getter/range and required context verified.
2. Capture original and context; write a durable journal entry before the setter. Choose a nearby five-point change within the verified range, normally downward when possible. Do not start at 0/100 or sweep the range.
3. Record native setter status, native getter and elapsed time. Correlate with timestamped vendor PWM evidence and visible change. Hardware plateaus can make small changes subtle; do not automatically escalate amplitude on uncertainty.
4. Attempt restoration after 15 seconds or an explicit Revert command, conditional on the same verified context and an unchanged owned value. Record restoration readback and release the lease.
5. If manual/vendor state changed, stop and explain why automatic restoration was skipped. If setter outcome is unknown, first read/reconcile; do not use the desired target as a confirmed last-owned value.
6. On worker/app death, preserve the journal. At next allowed startup, reconcile pending recovery before normal schedule writes. Standby defers restoration until interactive. If recovery cannot be established, show manual native Settings recovery and remain paused.

No APK can guarantee a timed rollback after force-stop, power loss, root revocation or service failure. State this precisely. Force-stop requires the user to relaunch the app; uninstall cannot guarantee restoration. Pause retains the current value. “Pause and restore” conditionally restores the captured original. Explicit restoration/probes operate in a separate mode from phase output limits; a legitimate saved original may lie outside a phase band.

## 7. Brightness and schedule product contract

| Phase | Preference range | Meaning | Scheduled app-issued range |
|---|---|---|---|
| Day | 70–100 | Maximum and steady-state target D | 70…D |
| Dusk | 10–80 | Minimum and steady-state target E | E…80 |
| Dim | 1–20 | Minimum and steady-state target N | N…20 |

Retain `D ≥ E ≥ N`. Explain this as the chosen adaptation of the original concept. The app transitions to the preferred value while respecting the one-sided bound; it does not continuously enforce a ceiling/floor against every human adjustment. Limits apply to scheduled app writes, not every possible vendor/human write. Defaults 80/40/5 and 07:00/18:30/22:00 are editable presets, not measured user preferences.

Use three start times defining a complete cyclic Day → Dusk → Dim day; derive ends. Reject duplicate/out-of-order boundaries. Allow each incoming fade from 0 to 888 seconds; when it exceeds the phase window, show the actual constraint instead of silently shortening it. Edit all three rows in a draft and validate/save atomically so users can move boundaries without being trapped by an invalid intermediate order.

Keep strict incoming-band clamping: Day 90 → Dusk 40 steps first to 80, then fades to 40. Dim → Day may similarly step to at least 70. This is a deliberate consequence of strict ranges and cannot also be advertised as a fully smooth cross-band transition. Show a brief preview/explanation. Do not silently change this contract.

Calendar time selects the phase; elapsed realtime drives a running fade. Respect Sydney/TV-selected timezone, midnight, daylight-saving gaps/overlaps and clock corrections. Resolve skipped boundaries to the first valid instant, repeated times once at the earlier offset, and collapsed events to the final applicable phase. Interrupt a fade when a later phase actually begins, including a DST-shortened window. Quick phase overrides expire at the next scheduled boundary; warmth follows scheduled times unless a separate documented product choice changes it.

At startup/wake/restart, apply only the current phase after recovery/context reconciliation. Do not replay missed phases. Resume from native readback, never a presumed preference. During a fade, check for external changes through verified callbacks or bounded read-before-write checks and yield when detected. There is no atomic compare-and-set proven for the vendor API, so document the residual race and do not claim absolute exclusion of TV Settings writes. After a detected manual change, hold until the next scheduled boundary or explicit Resume.

### Fade qualification and implementation

Establish persistence by inspecting the actual setter chain and targeted vendor logs/configuration behaviour around the controlled probe. Absence of obvious file writes is not proof of no flash persistence, and survival after standby/reboot alone does not establish write frequency. Do not trace or sweep broadly. If evidence remains inconclusive, keep repeated-write fades unqualified and allow explicitly labelled immediate phase changes as an interim feature.

When qualified, calculate quantised integer changes from elapsed time, suppress duplicates, skip overdue intermediate steps and schedule the next changed value rather than an all-day ticker. Maximum write rate initially two per second; reduce based on latency. The controller must never overlap writes or flood a delayed vendor queue. Avoid persisting an entire preferences XML on every animation tick; journal only transaction/ownership changes and information needed for safe recovery. Test 0, 1, 30 and 888 seconds, cancellation, no-op and strict band entry.

## 8. Lifecycle, low-RAM performance and warmth

Use a small foreground service while either schedule is enabled. It holds the lifecycle state and registers screen events but does no continuous polling or wake-lock holding. Establish it on enabled boot/package-replace paths even if the screen is off; suspend native operations and overlays until interactive. `START_STICKY` may assist ordinary process recovery but does not guarantee restart or defeat force-stop. Keep disabled installs idle.

Use one non-wakeup next-boundary alarm, recomputed on schedule edits, boot, package replacement, time and timezone changes. Coalesce warmth/backlight boundaries. Non-wakeup alarms can be delayed while suspended; recompute at wake. Android background/broadcast rules require a tested service/receiver design, not a manifest screen-on assumption [A5–A7]. No exact-alarm permission flow is needed on this API 28 target. If newer Android versions are supported later, guard their permission/start restrictions separately.

Treat source/input/picture/HDR changes as context changes only where observable. If an accurate context cannot be obtained, do not automatically restore a stale original across an input transition. Measure vendor overwrite behaviour and qualify a conservative policy; no periodic fight with native dynamic backlight or ambient-light controls. Never silently disable those controls. A known conflicting vendor mode can be explained in setup with a user-directed native setting adjustment.

Warmth remains opt-in, independently scheduled, independently paused, with its own transition duration. Linked mode shares brightness phase times and per-phase strengths. Custom mode has its own start/end and strength. Exceptions in permission checks, overlay construction, attachment, animation or teardown must affect warmth only. An invalid warmth setting must not crash the scheduler or stop physical brightness control. Warmth Off must never brighten/restore the panel. Native read failure must not turn off a working warmth overlay unnecessarily.

Use the existing overlay as the initial warmth method; do not add speculative SurfaceFlinger, Night Light or compositor hacks. Label colour as approximate/uncalibrated warmth, not a measured panel white point. Verify UI, Stremio, Kodi, HDMI and live TV independently: a screenshot is not proof of optical backlight change or hardware-video overlay coverage. Unsupported video planes require an honest limitation; researching another native colour backend is a separate evidenced task.

Playback verification uses the same source/app/session before and during a fade. Capture only relevant app/native latency, frame/ANR/log evidence and memory/process behaviour. The existing Stremio/decoder/low-RAM history makes overhead important, but a later playback fault is not automatically caused by Red Moon. No LMK/ZRAM/governor/firmware tuning belongs in this change. Target steady-state near-idle CPU, bounded logs, no growing queue/process count and no new reproducible ANR or playback degradation; record measured results rather than inventing a universal RAM budget.

## 9. TV UX and integration

Home screen: current scheduled/override phase, confirmed native level, next change, backlight switch, independent warmth switch, quick phases and Pause/Resume. Show stale/unavailable readback distinctly from requested state. Keep JNI/UID/qualification detail in Diagnostics.

Schedule editor: three rows with preferred value, start time and incoming fade. Provide remote-friendly presets (0/5/15/30/60/120/300/600/888 seconds) plus custom entry; Left/Right single steps and accelerated hold; clear focus, stable focus after updates, visible Save/Cancel and no mandatory keyboard/touch. Test hour/minute focus and long numeric changes on the physical remote. Ensure all controls fit the TV safe area and are readable at normal viewing distance.

First-run diagnostics should read automatically only on user request; writes require the explicit reversible test. Separate read capability, successful write/restoration, visible confirmation, lifecycle qualification and fade qualification. Production eligibility must be tied to a recorded firmware/backend/artifact result; a checkbox is acknowledgement, not engineering evidence. Do not require every stable user to perform the developer's full acceptance matrix.

Retain explicit opt-in tvQuickActions intents with fixed commands: Day/Dusk/Dim, Pause, Resume, Warmth Off and Read. Keep Restore within trusted in-app control unless separately designed. Validate component/action/extras and phase values, rate-limit duplicates and make commands idempotent. Explain that the exported third-party automation receiver is controllable by other apps after opt-in; it must not expose a generic vendor key, shell, root command or qualification setter. Keep internal status/boundary IPC scoped to the package and avoid unnecessary permissions inherited from the phone app after auditing callers.

## 10. File-level development work packages

| Batch | Main paths | Concrete output and completion gate |
|---|---|---|
| 0 — evidence/ADR | `docs/A4-BACKLIGHT-PLAN.md`, `docs/A4-VALIDATION.md`, proposed backend ADR/evidence schema | Replace stale baseline, separate facts/proposals and define backend state/results before coding |
| 1 — release bootstrap | `.github/workflows/*`, `scripts/ci/*`, `docs/RELEASES.md`, minimum build/package configuration needed | Infrastructure-only prerequisite on master; unsigned and signed TESTING path proven without enabling unqualified app control |
| 2 — native runtime | `A4BacklightBackend.kt`, `A4NativeBridge.java`, proposed loader/worker, manifest | Installed TESTING read works with exact binding/native status/ABI and bounded errors |
| 3 — controller/recovery | `TvDisplayService.kt`, `TransitionSession.kt`, proposed controller/lease/journal | One writer, crash-safe journal, unknown-outcome handling, conditional probe restore and handover tests |
| 4 — lifecycle/policy | `BrightnessPolicy.kt`, `TvPreferences.kt`, `TvReceiver.kt`, service | Boot asleep/wake, time/DST, manual/context changes, schedule state and qualification invalidation validated |
| 5 — warmth/UI | `TvActivity.kt`, `Overlay.kt`, `BrightnessManager.kt`, manifest/resources | Everyday UI separated from diagnostics; atomic editor; independent error containment and remote operation |
| 6 — physical qualification | `docs/A4-VALIDATION.md`, evidence record/exporter | Controlled real-panel test, lifecycle, fades and playback evidence bound to APK hash |
| 7 — release closure | workflows, scripts, README/changelog/licences and release docs | Exact stable-package candidate accepted and promoted unchanged; source/tag/signature/assets verified |

Preserve useful existing tests and disabled legacy brightness writers. Avoid a broad Kotlin/Gradle/Compose migration. Current JDK 11, Gradle 7.2 and SDK/build-tools 32 are the known baseline. Any necessary upgrade is a separate compatibility-tested change. Keep `armeabi-v7a` if adding native code; a Java/Kotlin-only APK does not need a dummy `.so` just to be installable on a 32-bit TV [A8].

## 11. Release workflow and artifact contract

### Resolve the bootstrap dependency

The earlier “physical qualification before merge, use PR's new TESTING workflow” sequence has a dependency gap: those dispatch workflows are not on master. Extract the minimum reviewed build/sign/TESTING infrastructure into a small prerequisite PR, based on master, and merge that infrastructure when authorised. Keep all application work in PR #1; refresh it against master afterward. This is a prerequisite, not a competing app implementation. If branch restructuring is unsuitable, use a bounded documented unsigned-build/signing bridge, but do not pretend a default-branch workflow already exists.

Retain manual `workflow_dispatch` as the reliable TESTING trigger. Trusted `/testing-apk` comments may remain optional. Remove dependence on automatic `pull_request_target` commit-message triggers: current GitHub documentation describes a default public-repository event policy and upcoming enforcement; actual repository policy was not verified here [G1–G2]. Do not change account policies to make a convenience trigger work. Same-repository source and explicit owner request still need trust checks; never execute PR build code in the signing job.

### Packages, signing and build identity

Keep stable `com.cbkii.redmoontv`; TESTING `.testing` with fixed versionName/code; debug `.debug`. Stable versions increase monotonically. The example stable code 40 is a baseline, not permission to reuse an already published version. TESTING's fixed code works for same-signer replacement but cannot distinguish builds alone: embed source SHA, channel, build/run identity and backend protocol version in the app's About/diagnostics as well as sidecar metadata.

Use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Matching names in other repos do not prove secrets are available here. Verify through a signing run without printing values. Compare the signing certificate against the established expected certificate/previous accepted signed build; the current script merely checks that a certificate digest exists. Freeze the first accepted certificate deliberately, preserving future upgrade continuity. Do not generate a substitute key silently.

Unsigned source builds use the immutable requested SHA, restricted permissions, no signing secrets and no persisted credentials. Signing/publication uses reviewed orchestration from a trusted immutable revision, closed inputs, safe archive/file validation and no execution of source-provided scripts. Reject extra/symlink/path traversal inputs, metadata mismatch and unexpected native ABI. Validate package, version, min/target SDK, launcher/banner, exported components/permissions, absence of bundled proprietary code, zip alignment, signatures and SHA-256.

### TESTING and stable publication

Maintain the owner-accessible draft `000-testing-only`, retaining two complete managed snapshot groups and preserving unrelated assets. Include source and workflow SHAs, run ID **and attempt**, unsigned/signed hashes, signer and qualification status. Existing group names omit attempt, so reruns can collide; either reuse exactly the retained signed bytes or use attempt-specific names. Never overwrite a published release or repoint a conflicting tag.

Add two simple release operations:

1. **Prepare:** build from verified master SHA, test, sign once and create a stable-package draft candidate. Record all artifact metadata. This is the actual `com.cbkii.redmoontv` APK for acceptance, not just a TESTING sibling with a different UID/root grant.
2. **Promote:** accept the existing draft/candidate ID and recorded acceptance tied to its signed APK hash. Recheck remote asset digests, signature identity, source/tag and eligibility; publish the same bytes. Do not rebuild or re-sign. Reject a changed candidate, missing evidence or mismatched head/tag.

After PR merge, the merge/squash SHA may differ from the tested PR head. Verify default-branch CI, then prepare and smoke-test the exact stable candidate. Prior TESTING evidence can support unchanged code behaviour but cannot alone qualify a different package/UID or final APK. A boolean cannot establish that a newly built candidate was tested before it existed.

Releases include APK, `BUILD_INFO`, signer report, checksums and a concise compatibility/acceptance record. Bind to model/build, API/ABI, native backend, control/fade status, warmth playback matrix, source and workflow SHA, signing certificate and signed APK SHA. Stable notes state actual limitations, not an unconditional compatibility claim. Preserve GPL notices and provide corresponding source/build information for distributed binaries.

Rollback: Pause/Warmth Off, conditional native restore or manual TV Settings; reinstall a previous same-signer APK where Android version rules permit. A downgrade may be rejected; prefer a forward version containing the previous known-good code. Do not promise unconditional data-preserving downgrade or uninstall restoration.

## 12. Verification matrix and completion criteria

Retain the existing policy/transition tests. Add meaningful controller and packaging tests rather than source-string assertions.

| Layer | Required cases |
|---|---|
| Policy | All bands/caps/order; every starting native level; exact 0/888 fades; invalid settings; no-op; boundary clamps; midnight, Sydney DST gap/overlap/collapsed events and real-time-shortened windows |
| Controller with fake backend/clock | Single in-flight write; late result after cancellation; setter applied then response lost; readback mismatch; negative native result; context/manual changes; crash before/after confirmed write; journal reconciliation; lease owner death/handover; restore skipped safely |
| Android lifecycle/integration | Foreground startup, boot asleep then wake, process restart, explicit force-stop limitation, package replacement, missing Settings binding, JNI error/crash/hang, denied/revoked root where relevant, screen-off during preview/fade |
| Warmth separation | Denied/revoked permission; overlay attach/detach exception; invalid custom window; emergency off during pending native write; native failure while warmth works |
| TV UI | Complete remote-only setup/editing; atomic schedule Save/Cancel; long fade values; focus retention; quick phases/override expiry; visible error/recovery and duplicate automation commands |
| Artifact/workflow | Safe source selection; no secrets in build; exact source/runtime metadata; signer continuity; incorrect ABI/manifest; tampered handoff; attempt collisions; interrupted upload/retry; managed-draft preservation; promote-only exact candidate |
| Physical A4 | Native read, near change, panel/PWM evidence, restore; low/mid/high operating bands after safe probe; native manual menu, source/SDR/HDR, standby/wake/reboot; 0/short/888 fades if qualified; Stremio/Kodi/HDMI/live-TV warmth and playback impact |

Established repository commands:

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleSnapshot
```

Also run the actual snapshot and release unit/lint/build tasks with their publication version properties. Inspect the final signed APK, not only debug. An API 28 emulator can test UI and lifecycle with a fake backend but cannot qualify this MStar vendor path. Bind every result to commit, APK hash, package, environment and date; distinguish passed/failed/not run.

Acceptance proceeds in order:

1. **Build infrastructure ready:** trusted TESTING dispatch and signing operate; exact package/certificate metadata available.
2. **Native read ready:** actual installed APK obtains a valid vendor value/range/context with no UI automation.
3. **Panel control qualified:** one controlled write has correct native status/readback, visible effect and timestamped vendor corroboration; restoration verified.
4. **Scheduling qualified:** bands, override/manual policy, standby/reboot/source handling and recovery pass.
5. **Fades qualified:** persistence and repeated-call suitability established, duration/cancellation and playback performance pass. Otherwise marked incomplete/disabled, not silently ignored.
6. **Warmth qualified:** independent timing/error handling and coverage matrix pass for the declared use cases; unsupported paths explicitly documented.
7. **Fully release-ready:** current-head review/checks clear, final stable-package signed candidate passes acceptance, hashes/signer/source/evidence match, rollback documented.
8. **Released:** authorised promotion publishes those exact bytes; release/tag/assets are re-read and install/upgrade smoke evidence recorded.

The next executable development step is Batch 0/1 followed by an installed APK **read-only native loader diagnostic**. No further generic brightness commands, speculative HIDL writes, system modifications or device-wide dumps are required to start that work.

## 13. Evidence and source references

User evidence: supplied root/ABI/library check; current `a4checks.txt` (`libfile_218276ed850c81918d528fac739846a8`); original `a4-test.txt` (`libfile_32c939eddcc48191b5a61c21c922f227`); visible conversation's unsuccessful settings/JAR tests, successful native-menu test and installed-APK analysis. The APK analysis is prior work reported in this conversation, not a fresh binary decompilation in this pass. Historical context was checked for superseding decisions; no later successful Red Moon native runtime test was found.

Repository evidence, pinned to `d3b003300754051ad7dc87a4e5cebbdd91d37a0b`:

- [PR #1](https://github.com/cbkii/red-moon-tv/pull/1), [CI run](https://github.com/cbkii/red-moon-tv/actions/runs/40289159745).
- [Native backend](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/app/src/main/java/com/jmstudios/redmoon/tv/A4BacklightBackend.kt), [native bridge](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/app/src/main/java/com/jmstudios/redmoon/tv/A4NativeBridge.java).
- [Controller service](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/app/src/main/java/com/jmstudios/redmoon/tv/TvDisplayService.kt), [policy](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/app/src/main/java/com/jmstudios/redmoon/tv/BrightnessPolicy.kt), [transition](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/app/src/main/java/com/jmstudios/redmoon/tv/TransitionSession.kt).
- [Workflows](https://github.com/cbkii/red-moon-tv/tree/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/.github/workflows), [release scripts](https://github.com/cbkii/red-moon-tv/tree/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/scripts/ci), [release documentation](https://github.com/cbkii/red-moon-tv/blob/d3b003300754051ad7dc87a4e5cebbdd91d37a0b/docs/RELEASES.md).

Primary platform references checked during this revision:

- **A1:** [AOSP native-library namespaces and public vendor libraries](https://source.android.com/docs/core/permissions/namespaces_libraries).
- **A2:** [Android PathClassLoader API](https://developer.android.com/reference/dalvik/system/PathClassLoader).
- **A3:** [Android uses-native-library manifest element](https://developer.android.com/guide/topics/manifest/uses-native-library-element).
- **A4:** [AOSP HIDL services and data transfer](https://source.android.google.cn/docs/core/architecture/hidl/services?hl=en), [HIDL Java](https://source.android.com/docs/core/architecture/hidl-java).
- **A5:** [Android background execution limits](https://developer.android.com/about/versions/oreo/background).
- **A6:** [Android AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager).
- **A7:** [Android broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts).
- **A8:** [Android ABIs](https://developer.android.com/ndk/guides/abis).
- **G1:** [GitHub workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).
- **G2:** [GitHub securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target). Current text describes evaluation mode and enforcement on 2 December 2026 for affected repositories; it does not prove this repository's configured policy.

Platform documentation supports the architecture rules. It does not establish A4 vendor compatibility. That claim requires the physical acceptance gates above.
