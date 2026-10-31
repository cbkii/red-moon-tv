# A4 implementation and ADB validation — 31 October 2026

Continue PR #1, `codex/a4-backlight-tv`, based on `a85ddf1aa3335f5e2617db1caf1e2e60bda33c99`. All work below is source implementation. No physical TV execution or new Android build is asserted by this document.

## Behaviour implemented

- Journal access uses a shared process lock across UI/provider/controller instances, so a report cannot race AtomicFile backup recovery against a write.
- Session classification distinguishes live schedules, paused confirmed ownership, in-flight writes, manual holds, probes and interrupted/uncertain recovery. A normal confirmed journal no longer disables Home. Saved pending writes and interrupted probes still require explicit recovery. A confirmed schedule can be adopted after process recreation only after checking native value and complete context; otherwise it yields or invalidates ownership.
- Optional native work is bounded and reads are coalesced. Restore, pause, standby cleanup and ownership release bypass the optional-work limit. Duplicate restoration is coalesced, and the latest requested transition replaces a stale queued destination. Generation and screen checks apply when work runs. A cancellation arriving during a read is checked before starting the setter. An already executing vendor call cannot be cancelled.
- While an awake brightness schedule owns the panel, a serial, read-only check every five seconds detects steady-state manual changes. No reliable vendor callback was established in the available source; this bounded active-only mechanism is used instead of pretending a Settings observer detects native changes. It stops during standby, pause and manual hold. Fades also validate ownership before writes.
- Manual adjustment relinquishes restoration ownership, persists the next boundary time, and closes the native transport. It survives process recreation and standby within that phase. Neither stale Restore nor cleanup can overwrite the adjustment. The next phase discards confirmed stale bookkeeping and starts from the actual native reading. A pending uncertain transaction is never discarded by this path.
- Context contains firmware, boot, picture mode, current input name and raw HDR flag. The no-argument `TVNative.getCurrentInputSourceName_native(): String` and the `g_consumer_disp__hdr_flag` getter are present in the supplied TvSettingsPlus DEX. These are read-only additions; setter keys/input/update arguments are unchanged. Missing source/HDR identity leaves reads available and prohibits writes. The numeric HDR enum is reported without guessing its format. Context changes invalidate old restoration ownership and rebase the schedule in the new context; they are not labelled manual adjustments. This is not an atomic vendor compare-and-set: a physical input change between the last check and setter remains a hardware race to qualify.
- Unclaimed native transports close after two idle seconds even when warmth keeps the display service alive. The journal, latest native snapshot and diagnostics survive transport closure. Steady active ownership remains connected for its monitor.
- Diagnostics schema 4 separates operation/queue state, freshness, current error, historical error, normal context/manual events, manual-hold expiry and recovery. A 1,024-entry in-memory trace is bounded; reports include its latest 64 entries and structured call/write/error/latency counters. No trace is persisted per animation step.
- Scheduled and test fades share elapsed-time integer interpolation with no replay of missed steps. Scheduled writes remain at most two per second; strict mode enforces the established incoming phase band, while opt-in smooth mode uses an endpoint-bounded corridor. Missing fade qualification now pauses a configured non-zero fade rather than silently substituting an instant transition. A separate bounded fade experiment can run before qualification, so qualification does not depend on enabling untested production scheduling.
- NewApi lint checking is restored. The existing SDK predicate is annotated for lint. TV page labels, phase names, controls, dialogs and prose templates are resource-backed. English is the initial translation; text expansion/RTL and physical D-pad ergonomics remain device acceptance tests.

### Cap/fade conflict resolved with explicit selectable semantics

The existing policy imposes the incoming phase band immediately: Day 70..user maximum; Dusk user minimum..80; Dim user minimum..20. A smooth transition from an out-of-band current reading cannot simultaneously satisfy those hard limits. This implementation preserves the existing limits: it reads the actual brightness, clamps the first scheduled step into the incoming band, then fades inside that band. For example, Dim 40→5 first becomes 20, then fades to 5. The diagnostic five-point experiment is not a scheduled phase and starts at the actual value without that clamp.

An opt-in **Smooth phase fades** setting is also implemented, including the same `configure` ADB field `transition_mode: "smooth"` (or `"strict"`). Smooth mode starts from the actual native reading and follows a monotonic corridor to the capped target without overshooting either endpoint. It deliberately permits transient values outside the incoming phase band. Its explanatory confirmation makes that changed meaning explicit. Strict mode remains the default for existing capped schedules. Both modes are ready for ADB testing; no further implementation or decision is needed before validating them. Do not claim either mode simultaneously provides both incompatible interpretations. The configuration backup/restoration includes this setting.

## ADB interface

The existing `/report` query stays read-only and DUMP-protected. `ContentProvider.call` additionally checks the actual caller UID (shell 2000 or root 0), explicitly enforces DUMP for shell, and clears caller identity before dispatching to the private foreground service. Calls are fixed operations with bounded JSON, not arbitrary vendor keys, shell code or file paths. Normal installed apps cannot use them. The public, opt-in tvQuickActions receiver is unchanged.

Run on Termux/workstation, outside `adb shell`, with existing Python 3.8+ and adb. No packages are installed by these scripts. Select the exact TV transport and package. These scripts do not stage files in TV temporary directories.

```sh
# Existing read-only capture still accepts old schema 2/3 APKs.
python3 scripts/a4-collect.py -s TV_SERIAL --package com.cbkii.redmoontv.testing --apk

# Preview operations, without sending anything.
python3 scripts/a4-validate.py -s TV_SERIAL --backend root

# Full default 0, 5 and 888 second experiments, each with a five-second endpoint
# observation and automatic restoration. Settings are backed up before testing.
python3 scripts/a4-validate.py -s TV_SERIAL --backend root --execute

# One maintenance operation, with no implicit pause or configuration reset.
python3 scripts/a4-validate.py -s TV_SERIAL --command read --execute
python3 scripts/a4-validate.py -s TV_SERIAL --command restore --execute

# Input/playback tests; operate the TV with the terminal's fixed ADB key menu.
# Numeric results, native context and errors are collected automatically.
python3 scripts/a4-validate.py -s TV_SERIAL --fade-seconds --plan docs/a4-validation-plan.json --execute

# Standby/wake and ordinary process recreation.
python3 scripts/a4-validate.py -s TV_SERIAL --fade-seconds --plan docs/a4-lifecycle-plan.json --execute

# Only after observing native change/restoration and independently checking
# persistence: these are explicit operator statements, not automatic verdicts.
python3 scripts/a4-validate.py -s TV_SERIAL --command acknowledge --data '{"native":true,"fades":true,"persistence_checked":true}' --execute

# Manual brightness change through native Settings, using ADB remote keys.
python3 scripts/a4-validate.py -s TV_SERIAL --fade-seconds --plan docs/a4-manual-override-plan.json --execute
```

The TESTING APK must first be built from this implementation. Older APKs cannot accept the new call interface. Root connection may still require the existing Magisk authorisation. Stable `com.cbkii.redmoontv` and `.testing` have independent identities/settings/permissions; repeat acceptance against the exact stable APK.

### Plan actions and evidence

Plans support fixed app commands, bounded waits, named checkpoints, report assertions, exact activity launches with an optional content URI, fixed remote keys, an interactive ADB remote, optical observations, selected-app force-stop/relaunch, and an explicitly requested reboot. Unknown actions/fields and arbitrary shell operations are rejected. `launch` can start user-selected SDR/HDR media; there is no bundled copyrighted sample or assumed user media URL. TV/HDMI key handling is firmware-dependent; use INPUT and the ADB remote menu if direct keys do not select the intended source. Verify `sample_context`, not the plan's label.

All executed actions and their return codes are timestamped. Reports are obtained through ADB even with the display service stopped. The ZIP contains the exact installed APK and hash, reports, action records, process/PSS/CPU samples, bounded logs, software results, separate optical observations and a hash manifest. Validation manifests explicitly state `read_only=false`.

`test_fade` accepts only 0..888 seconds and a non-zero delta of -20..20; the default runner uses five points. It serialises through the real controller, journal, context checks and cross-variant lease, reads back writes, retains uncertain recovery, and schedules a restoration deadline even if optional fade work cannot continue. The runner checks target evidence, restoration, write count and spacing. Collection/provider polling does not itself initiate JNI. Failures stop later experiments. Unknown outcomes are not retried. If cleanup cannot safely finish, the configuration backup remains in the app; use the single `restore_config` operation after reconciling native ownership.

The manual-override plan creates a temporary nearby schedule from the TV's actual clock (Day already active, Dusk in three minutes, Dim in six), checks a hold before and after process recreation, waits for the reported next boundary, then checks hold expiry and the new target. Numeric times/readback require no transcription. Its five-second production fades require the explicit native/fade acknowledgement first. `schedule_nearby` and `wait_boundary` are bounded plan actions; configuration is restored afterwards. Do not change the TV clock just to accelerate a test. Schedule pure tests cover midnight/DST; physical phase/standby tests use the TV's real time.

Playback/input coverage is an independent optical fact. ADB counts/readback, a successful setter, screenshot or source label do not establish that warmth reaches a hardware video plane. Each path requires separate brightness/warmth observations. `observe` asks only for those observations; the remote menu requires only key choices, never copied native numbers.

## Validation status and release boundary

Locally verified: Python contracts, Python syntax, XML structure, resource references and patch hygiene. Android/JUnit/lint/assembly and actual vendor execution are **not yet run for this patch**: the local environment lacks Gradle/SDK caches and javac, and automatic approval review rejected the GitHub push. No new signed APK exists for this patch. Earlier green CI and the old signed APK are not evidence for it.

Added JVM regression cases cover queue saturation/restore, latest transition coalescing, live/paused/manual/pending session classification, confirmed schedule adoption, steady manual detection, context changes, cancellation during native reads and 888-second elapsed interpolation. They must run in canonical CI before the candidate is called validated.

Promotion now requires independent gates for brightness and warmth on Android, tuner, HDMI and HDR, plus manual override and fade persistence. Existing exact APK/source/signer binding, stable-package installation, unchanged-byte promotion, signing-secret isolation and draft-release workflow remain intact. No merge or release publication is authorised by this implementation alone. Prepare the stable candidate only after the owning PR is merged with approval, using the existing default-branch workflow.

Repeated writes use the established stock update=3 contract. Its actual persistence/flash behaviour and physical input/HDR effects remain unproven. No alternate update flag, raw HIDL parcel or SurfaceFlinger transaction is invented to bypass those unknowns. Missing hardware acceptance remains release-blocking; production fades are not silently downgraded.
