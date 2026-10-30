# A4 vendor backend, APK JNI v3 and optional root pipe v1

Decision: default to installed MediaTek bindings from a private `:backlight` Android process under the application's UID. `A4NativeBridge` resolves the system/shared classpath first, then installed Settings/shared-library APK/JAR paths with a deliberately configured `PathClassLoader`. It calls the confirmed `MtkTvConfig` signatures and key `g_disp__disp_back_light`. An explicitly selected experimental root backend uses the same bridge/controller, a persistent 32-bit pipe worker loaded from the installed APK and the same live lease. No proprietary binary, UI automation, manually staged executable or shared-storage ownership file is used.

`A4BacklightBackend` binds through private Messenger IPC, identifies the owned process before JNI initialisation, serialises calls, and bounds connection/call waits. A timeout kills only that identified same-UID worker. This cannot retract a vendor-side call: setter outcomes remain uncertain until readback. Source-level API compatibility is not physical JNI/service qualification.

`BacklightController` owns capture, write/readback, external-change detection and conditional restoration. Its dedicated `AtomicFile` journal records original, confirmed, pending target, session, operation kind and boot/picture context before each setter. A failed/late result never upgrades a pending target to confirmed. Only one controller executor calls the backend; generation cancellation prevents future steps, never claims to cancel JNI already running.

The worker holds an abstract local socket lease across the session. Process death cleans up the kernel lease. Stable/TESTING/debug must not run concurrent writers; another occupant yields a conflict. API 28 cross-UID socket exclusivity and release timing require physical qualification. An unknown occupant is never treated as authenticated, and the socket exposes no command interface. Explicit transfer is Pause and transfer control in the old variant, wait for completion, then Resume in the new variant; no automatic takeover.

Input/HDR identity is **not** established. Only boot/picture mode are observed. Therefore restart recovery stops scheduling and requires explicit Read then Restore or Keep; no automatic restoration across app death/reboot. Same-session probe restoration still requires unchanged observed context/value; the native interface has no proven atomic compare-and-set. A source/manual change between read and set remains a documented race. Full release requires device tests that establish whether this context is adequate or identify a vendor source callback before wider context claims.

There is no automatic failover or speculative HIDL transaction. The optional root backend is selected for an explicit diagnostic attempt, never in response to an uncertain setter. A public JNI library, root grant and listed HIDL interface are discovery evidence; none proves access from the installed app. Read-only diagnostics include actual UID, ABI, binding source/loader, native range, library path, load latency, firmware and build identity. See [root/performance design and acceptance](A4-ROOT-AND-PERFORMANCE.md).

The per-install qualification acknowledgement is invalidated by firmware, binding/JNI identity, backend protocol, source SHA and build/run identity. It is an operator safeguard, not release evidence. A separate acceptance record must identify the exact signed stable-package APK hash and signer.

Impact map:

| Contract | Producer / owner | Consumers / verification |
|---|---|---|
| Native read/write and live lease | A4NativeService / A4NativeBridge | A4BacklightBackend; installed APK read/probe, native-death tests |
| Pending versus confirmed write | BacklightController / RecoveryJournal | TvDisplayService; fake backend crash/lost-response/manual-change tests |
| Phase time/band/fade | BrightnessPolicy / TransitionSession | Service, atomic UI draft; JVM policy/transition tests |
| Lifecycle and error isolation | TvReceiver / TvDisplayService | Boot asleep, wake, warmth errors and playback physical matrix |
| Artifact identity | Gradle BuildConfig / envelope / qualify | Diagnostics, signed metadata, publisher/promote checksum and acceptance tests |

Known incomplete gates: installed vendor access, source/HDR completeness, repeated-write persistence, physical lease/lifecycle behaviour, remote UI, overlay video-plane coverage and playback cost. Their exact evidence belongs in A4-VALIDATION.md and the candidate acceptance record. No source test can mark these passed.

Runtime revision after rmd/early review: one pinned vendor defining loader and first initialisation result per worker; binding nonce and fresh server identity on every connection; no client-side numeric-PID termination. Forced recalculation cannot clear a persisted failure/manual hold. Qualification hashes are prepared asynchronously, delayed native futures are cancelled/removed, and warmth schedules only changed integers. APK JNI v3 permits contextless diagnostics but still requires context for writes. Backend/protocol/source/build/vendor identity invalidates earlier acknowledgements. See A4-REVIEW-RESOLUTION.md for classifications and physical limits.
