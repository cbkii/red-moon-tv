# Implementation ledger — 30 October 2026

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
