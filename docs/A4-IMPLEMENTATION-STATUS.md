# Implementation ledger — 30 October 2026

## v106 integration review — 31 October 2026

PR #1's final continuity-runner corrections are integrated with PR #6's paired automation. Native-worker restart/backend selection now also recognise temporary brightness ownership while saved scheduling is disabled; diagnostics classify that live ownership correctly. v106 defaults to versionName/versionCode 106 and adds an explicit maintainer-authorised public prerelease path. Historical entries below retain their original source/run context. The current startup/recovery contract is [A4-STARTUP-CONTINUITY.md](A4-STARTUP-CONTINUITY.md), and integration coverage/remaining device gates are [A4-FINAL-REVIEW-v106.md](A4-FINAL-REVIEW-v106.md).

## Device-result follow-up — 31 October 2026

The supplied results identify the older signed 22f APK and a real `0..0` range-query failure before native level read; see [device analysis and corrective implementation](A4-DEVICE-RESULTS-2026-10-31.md). The updated implementation uses installed TVNative signatures and explicit min/max queries, permits read-only diagnosis without a validated range, and still blocks setters without actual range/context. Home/three-phase cards and separate Schedule/Warmth/Diagnostics/Settings pages replace the long form. The protected read-only ADB provider works independently of display-service lifetime, and the collector derives read/restore results automatically. Current protocol is `apk-jni-v4/root-pipe-v1`; physical acceptance remains open until an updated installed APK is tested.

The entries below retain their original source/run evidence; they are not results for this follow-up.

Target: cbkii/red-moon-tv PR #1 (`codex/a4-backlight-tv`), evolving from d3b003300754051ad7dc87a4e5cebbdd91d37a0b. Governing specification: revision 2 of the A4 engineering plan, 30 October 2026.

Implemented source changes:

- APK-UID vendor loader in a private process; live cross-variant lease, bounded IPC and worker failure containment.
- Durable pending/confirmed recovery record, read-before-write/manual-change detection, explicit restart reconciliation and safe release.
- Boot/package replacement establishes the enabled lifecycle owner while asleep; screen-off suppresses native operations, wake recomputes current phase.
- Quantised next-change fade scheduling, serial setters and no per-frame/per-half-second preference persistence.
- Warmth setup/animation/teardown failure containment and independent custom schedule validation.
- Atomic three-phase schedule draft, fade presets, separate diagnostics and bounded report copy.
- Embedded immutable source/build identity, vendor identity qualification invalidation.
- Manual prepare/promote release separation, run-attempt asset naming, signer continuity/closed handoff validation and hash-bound stable acceptance.
- 29 Python script contract tests pass locally and in canonical CI. CI for application source `bedf885a280f8b8688a56b87021890f4924df5a4` completed debug, TESTING and release unit tests, lint and assembly ([run 41061991363](https://github.com/cbkii/red-moon-tv/actions/runs/41061991363)). The PR records the final integrated head and its validation.

Infrastructure PR #2 merged as 43f8dfc4330795561bd2765537edf3f523d54203 after successful run 41040212225. PR #3 repaired permanent draft identity and interrupted-upload retention. PR #4 removes temporary draft download URLs from release notes. The native TV manifest remains authoritative. Phone-era location collection is disabled, and per-step progress/readback no longer rewrites the preferences XML.

Signed TESTING [run 41062555297](https://github.com/cbkii/red-moon-tv/actions/runs/41062555297) completed build, signature verification and permanent draft publication for source `bedf885`. Its signed APK SHA-256 is `9484940b365a676fe7c8af02f0cc6ebe7d2855abc0824b00dec4bf93ab601c02`; certificate SHA-256 is `c1c298db86ad8f269c33a69389b297b7ebd4859c07054928201fcdd0b3495522`, matching the prior signed snapshot. It is a diagnostic candidate, not physical acceptance. The PR and per-artifact BUILD_INFO identify later integrated builds and their exact hashes.

The supplied `rmd.txt` is a real failed native read from the earlier `9fa0afac` build. Loader lifetime, worker binding identity, persisted write prohibitions, delayed-task cancellation and background vendor hashing are corrected; warmth now uses quantised next-change scheduling. See [review resolution](A4-REVIEW-RESOLUTION.md). The corrected native path still requires a fresh physical read.

External gates remain open: installed native read/probe/restore; context/source/HDR behaviour; lifecycle and cross-variant lease; persistence/fades; physical remote and playback/warmth matrix; exact stable-package acceptance, including explicit first-use certificate acceptance. No stable candidate is promoted without that evidence. CI and signing do not establish panel compatibility.

The lifecycle design conservatively pauses after an interrupted journaled session because complete source/HDR context is unavailable. This is a real qualification boundary, not an assertion of unattended recovery across every firmware state.

## 31 October: root fallback and low-overhead diagnostics

Added an opt-in persistent 32-bit root pipe worker using installed APK code, fixed typed commands, peer/response identity checks, bounded startup/native operations and self-exit. Default APK and experimental root share the controller, recovery and live lease. Contextless native reads are useful diagnostics but never enable writes; backend selection resets qualification and is blocked by saved recovery. Standby disposes the worker. Private APK initialisation skips unused legacy UI setup. In-memory call metrics, a schema-3 report, service dumpsys and a device-explicit read-only collector support physical and playback qualification. See [implementation, reference reuse and acceptance](A4-ROOT-AND-PERFORMANCE.md). No root/vendor/optical execution was available in the development workspace; the PR records exact current-head build validation.
