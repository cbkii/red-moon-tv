# Red Moon TV v106 final integration review

Repository: `cbkii/red-moon-tv`. Mode: complete open-PR and prerelease review. Primary review: Android Total Audit, with PR-hardening and release qualification. Review date: 31 October 2026 (Australia/Sydney).

## Exact scope

| PR | Reviewed input base | Reviewed input head | Integration |
|---|---|---|---|
| #1, A4 display/continuity | `749ab97be6ef12e83c2cafd56189badec7838569` (`master`) | `300fda36797838c7c74e0b85536e283e1d082d55` | Native controller, boot/wake/process recovery, TV interface and ADB qualification |
| #6, paired automation | `3b1c4ee52f3839eab589b18d06566db0170583d8` | `09f9608f1ee3bd02d4ca3246b62b8ddfa12588a1` | tvQuickActions, temporary scopes, strict IPC and bounded results |

The final integration incorporates #1's later `300fda3` runner changes into #6 before release. Final source/build provenance is the immutable SHA embedded in each APK and its published BUILD_INFO. CI must pass on the final PR head and merged default branch; historical green runs do not qualify a different tree.

Initial preflight returned no submitted reviews or inline threads; a later Sourcery review supplied 15 findings on #6. Their disposition is recorded below. Bot subscription/size/base-branch skips are unavailable review evidence, not approvals. GitHub rulesets returned an empty list; the integration cannot read the administration-only classic branch-protection endpoint. Normal SHA-guarded merges must respect GitHub's actual server-side merge checks; no bypass is requested.

## Target context

| Context | Status and evidence | Consequence |
|---|---|---|
| A4 / Toshiba 50C350KP, Hisense HiSmartTV, `xiaoyushan` | Confirmed from supplied device evidence and repository qualification contract | Device-specific native adapter; no generic Android brightness fallback |
| Android 9 / API 28, 32-bit `armeabi-v7a` | Confirmed device facts | Installed `/vendor/lib` JNI and `app_process32`; do not use 64-bit paths |
| App min/target/compile SDK 26/32/32 | Confirmed Gradle | API guards and manifest review cover the declared build; physical qualification targets API 28 |
| TV landscape, D-pad/remote, mains/standby, long media sessions | Confirmed product intent; exact resolution/RAM/CPU/GPU are unknown here | No touch-only essential editing, no frame-driven native setters, bounded queues and awake/non-wakeup checks |
| Installed MediaTek TVNative service/JNI; optional Magisk root | Confirmed observed native access on an earlier APK; exact v106 process/permissions unverified | Isolated APK/root transports share single-writer and recovery policy; root selected explicitly |
| Offline local display control; no required network or Play Services | Confirmed active code/manifest | Diagnostics remain local; no location permission or telemetry |
| debug / snapshot TESTING / release | Confirmed build types, separate application IDs | Check all three; sign release separately; qualification and permissions remain per package |

## Findings and disposition

1. **[P2][high][lifecycle][confirmed] Native restart missed temporary automation ownership.** In #6, `read_restart` tested only the saved schedule and probe flags. A temporary brightness target can hold the worker while saved scheduling is off; restarting it invalidates the live claim and interrupts that target. Fixed by sharing a worker-busy predicate covering schedule, probe, phase and temporary ownership. Regression coverage includes temporary-only ownership. Session diagnostics now count a temporary value as active native control.
2. **[P2][high][integration][confirmed] The stacked automation branch omitted #1's last validation corrections.** #6 contained `3b1c4ee`, whereas #1 advanced to `300fda3`. Merged the latter into the automation branch so fresh-read/expected-overlay recovery assertions and inactive-supervisor handling survive the final integration.
3. **[P2][high][release][confirmed] No public prerelease operation existed.** The existing publisher accepted drafts only, with full physical acceptance required for stable promotion. Added a distinct public prerelease operation using current default source, isolated signing and remote digest checks; preserved stable acceptance. Tests reject untrusted requests, wrong/moved source, argument injection, TESTING publication and stable bypass. v106 metadata and Gradle/workflow defaults agree on name/code 106.

All confirmed findings above are addressed in the final integration. They do not establish physical A4 compatibility.

### Late review disposition (PR #6)

| Finding | Classification and resolution |
|---|---|
| 1, shared shortcut credential | Valid P2 security boundary. Generated shortcuts now carry an HMAC capability tied to the selected parsed command/arguments, excluding only per-invocation ID. The shared broadcast credential is not returned by the picker. Rotation revokes both. Pure cryptographic tests reject argument/scope/key changes. |
| 2, failed/cancelled temporary target reapplies | Valid P2 lifecycle/result inconsistency. Terminal failed/rejected/cancelled scopes retire only their own matching persisted target. Successful desired output can survive restart; standby suspends running requests without falsely terminalising them. Interrupted unknown operations retire their target, preserving native recovery. |
| 3, controls marked applied before visible | Valid bounded reporting issue, especially on newer Android. `open_controls` completes only when TvActivity resumes; a five-second timeout reports failure if OS policy blocks visibility. A live control request prevents idle service teardown before acknowledgement. |
| 4, expired target reused | Valid P2 correctness. Admission reconciles expiry before relative-read/target selection. Warmth without an active temporary target uses the saved scheduled target, not an expired overlay transition. Exact expiry edges are regression-tested. |
| 5, Android 12+ background FGS restrictions | Outside qualified A4 API28 context. Android 12 restrictions and Android 10 activity restrictions are documented platform boundaries; unsupported launches return failure, never fabricated application. Use the visible shortcut/controls route and granted overlay exemption where the newer platform permits it. No background-policy bypass is introduced. |
| 6, evicted IDs replay | Valid P2 idempotence. Explicit caller IDs have a fixed 32-KiB persisted tombstone filter separate from short result history. An evicted ID is rejected instead of executed. Rare filter collisions also reject safely. Rotation starts a new credential/replay epoch. Shortcuts without caller IDs represent fresh button presses. |
| 7, misleading aggregate error scope | Valid P2 reporting. Aggregate error follows the part with the aggregate terminal state, independent of completion order. Per-part results remain authoritative. |
| 8, older native failure attributed to newer request | Valid P2 attribution/safety issue. Worker exceptions retain their captured transition generation. A newer target is explicitly rejected as unattempted due to the earlier failure; it is not reported as executing/failing. The global native safety stop still invalidates queued writes because an unknown previous setter must never be ignored. |
| 9, deferred older read undoes newer controls | Valid P2 ordering. Per-scope admission tickets survive deferred identity/read callbacks. New controls invalidate older tickets; read-only status does not. Tests cover pause/new target, independent warmth and combined scopes. |
| 10, control result survives service teardown | Valid P2 lifecycle. Persisted execution session supplements process identity. Teardown terminalises owned active/accepted work; same-process recreation detects stale execution ownership. |
| 11, unauthorised broadcast work/log flooding | Hardening. Bounded credential/enabled checks precede extras materialisation; receiver admits at most 32 requests/second and throttles rejection logs to once per ten seconds. OS IPC delivery itself cannot be prevented by app-side code. |
| 12, redaction test uses nonexistent directory | False premise: TemporaryDirectory already exists and the original test writes there. Strengthened it to require an actual persisted file and a redaction marker, preventing a vacuous pass. |
| 13–15, weak matching/pairing/action assertions | Valid test improvements, no demonstrated runtime defect. Tests now place an unrelated terminal row first, enforce exactly one read-only pairing call and require the exact broadcast action/component in both unit and fake-ADB integration tests. |

The additional pure JVM guards cover capability restriction/revocation, persisted replay tombstones, deferred ordering, expiry, aggregate errors, earlier-failure attribution, temporary retirement and service-epoch recovery. Python still exercises the actual ADB CLI/archive path; these tests do not claim physical tvQuickActions or panel execution.

## Coverage and domain completion

Method: semantic/symbol/control-flow review, structural XML/JSON/YAML parsing, source diff/caller/contract expansion, Python transport/publication tests and canonical Android compiler/JVM/Lint/APK evidence. This is not described as AST-backed analysis. High-risk paths were reviewed in full; unchanged disabled phone-era code was sampled for reachable integration effects rather than audited as a separate phone product.

| Domain | Result |
|---|---|
| Functional requirements | Checked: native control, bands/fades, independent warmth, automation and enabled intent; findings 1–2 resolved |
| Persistence/migration/recovery | Checked: device-protected migration, atomic pending/confirmed journal, same-boot adoption, new-boot baseline and manual holds; physical interruption tests outstanding |
| Lifecycle/process death | Checked: early foreground promotion, Direct Boot, sticky recreation, wake, standby cleanup, health/boundary alarms; finding 1 resolved |
| Concurrency/cancellation | Checked: serial controller, generation checks, coalesced bounded work, per-scope completion and no uncertain-setter replay |
| Security/privacy | Checked: private worker/display services, DUMP reports plus shell/root-only mutation calls, paired exported entry points, bounded primitive extras, token redaction and fixed root protocol |
| Architecture/maintainability | Checked: one policy/controller/journal authority shared across transports; no copied unlicensed vendor implementation or redistributed vendor binary |
| Runtime performance | Structural checks: quantised changes, reused worker, no idle animation ticker, 60-second awake read checks and five-minute non-wakeup liveness; CPU/memory/thermal/playback measurements not verifiable here |
| TV UX/accessibility | Checked remote number/time editing, focus retention, cancel/recovery controls, shortcut completion and nonfocusable overlay; actual remote/playback interaction not verifiable here |
| Localisation/resources | Checked default English TV/automation resources and format arguments; TV-only English scope explicit, translations not claimed |
| Platform compatibility | Checked declared API guards and Android 9 paths; other OEM/native platforms and newer OS behaviour are not qualified |
| Manifest/components | Checked Direct Boot/private workers, optional native library, launcher/shortcut discovery, disabled old entry points and no unused location permission |
| Gradle/build/variants | Checked three variants, source/native compatibility identities, Java 11/Gradle/SDK CI contract; no R8 minification enabled |
| Dependencies/supply chain | No new runtime dependencies; pinned workflow actions and isolated build/sign jobs inspected; exhaustive ecosystem vulnerability scanning unavailable |
| Testing/QA | JVM policy/transport/recovery plus Python real-CLI/fake-ADB suites; exact results recorded by final CI. No device is attached to this review workspace |
| CI/release | Finding 3 resolved; read-only PR build, trusted default release resolver, fixed metadata, signer/checksum/remote digest validation, no stable-gate bypass |
| Operability/rollback | Checked ADB collection/continuity/automation plans, bounded logs, permission failures and optional inactive supervisor. Pause/Warmth Off, conditional Restore/native Settings, and forward-version repair remain recovery paths |
| Legacy/dead code | Active TV initialisation avoids credential-only phone configuration; disabled location/filter/background entry points do not compete with native control |
| Documentation | Current guides and release operations updated; old implementation ledger retains historical provenance |

## Merge and release posture

Static/code-readiness score: **94/100**, high confidence for reviewed source paths and deterministic checks; physical/performance confidence remains limited. No remaining confirmed P0/P1/P2 source finding after the bounded integration passes. Merge only after the exact final heads pass canonical CI and no new material feedback appears. The user explicitly requests the dependency-ordered merges and v106 prerelease; this is not stable promotion or an assertion that A4 acceptance is complete.

Physical v106 validation remains: own-package install/permissions; restored read/probe; cold boot and standby timing; process/worker death; manual/source/HDR changes; vendor write persistence before fades; actual tvQuickActions invocation/focus; Android apps, tuner, HDMI and protected playback optical coverage; combined process memory/CPU and overnight behaviour. These are verification gaps, not fabricated passes. All required application and ADB test paths are implemented. Fresh-install schedules remain disabled until setup; native/fade qualification protections remain enabled.
