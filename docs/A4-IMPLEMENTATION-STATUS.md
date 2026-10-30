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
- Manual prepare/promote release separation, run-attempt asset naming, signing identity/closed handoff validation and hash-bound stable acceptance.

External gates remain deliberately open: APK compile/lint on current-head CI; bootstrap infrastructure on master; signing secrets/certificate continuity; installed native read/probe/restore; context/source/HDR behaviour; lifecycle and cross-variant lease; persistence/fades; physical remote and playback/warmth matrix; exact stable-package acceptance. Update with actual run/asset evidence; never treat this ledger as a test result.

The lifecycle design conservatively pauses after an interrupted journaled session because complete source/HDR context is unavailable. This is a real qualification boundary, not an assertion of unattended recovery across every firmware state.
