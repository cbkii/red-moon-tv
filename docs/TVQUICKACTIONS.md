# tvQuickActions integration (automation API 1)

Automation shares the A4 native controller, device-protected settings, single-writer lease, recovery journal, context checks and qualification gates. It adds no network server, root-command bridge, foreground-app poller or second native worker. Physical A4/tvQuickActions acceptance remains separate from CI.

## Quick setup without ADB

1. Open Red Moon TV → Settings → **Automation · tvQuickActions**.
2. Enable paired automation. This is explicit consent; installing an APK does not enable it.
3. In tvQuickActions → Custom actions → Shortcuts → Add → select the installed Red Moon TV variant.
4. Choose an action. Select phase/scope/value/fade/expiry as prompted, using Left/Right/OK. Save.
5. Assign the saved action in Mappings → App Shortcut, or use it in a menu/macro.

Ready actions include brightness Day/Dusk/Dim, brighter/dimmer, warmer/cooler (overlay strength), brightness pause/resume, warmth on/off, a combined saved phase preset, scoped snooze, resume automatic, cancel and hold, reconcile, status and controls. Generated names include phase/direction/value; icons distinguish brightness/warmth/general controls. Both modern shortcut results and legacy shortcut extras are returned. No static request ID is embedded: each button press must be a new request.

The finishing dispatch activity may briefly affect focus. Validate it during real playback. Use custom **broadcast** actions when playback or focus is affected; they invoke no activity except the explicit `open_controls` command. No claims about actual tvQuickActions invocation follow from successful ADB broadcasts alone.

Disable automation to revoke the token and generated shortcuts. Token rotation also invalidates existing shortcuts; recreate them. Revocation cancels temporary actions, retains the current native value, pauses brightness scheduling and lets saved warmth settings reconcile. Ordinary legacy `CONTROL` commands require the separate **Legacy tokenless controls** switch as well as automation being enabled. That switch permits any installed app to send those legacy commands; it is off by default after upgrade.

## Broadcast fields for TESTING, stable and debug

Generated shortcuts contain a capability restricted to the selected command and arguments, not the shared broadcast token. Changing phase/scope/value/fade/expiry invalidates that capability. Recreate shortcuts to change their action. Copying the shared broadcast token remains a separate explicit action for trusted macro apps.

Explicit caller request IDs remain replay-protected after their detailed result leaves the bounded history: retired IDs are rejected, never executed again. A fixed 32-KiB tombstone filter bounds memory; a rare filter collision can also reject a new ID safely. Use a fresh ID for a new press. Token rotation resets this credential/replay epoch and invalidates old credentials. Generated shortcut presses omit IDs and are always new actions.

The receiver limits work to 32 requests/second and rejection logging to one entry per ten seconds. Failed/cancelled requests cannot leave a pending temporary target that silently applies later. Successful desired output may persist; running output suspends across standby. A newer action or manual pause cancels an older deferred identity/read callback for that scope. Native failure from an earlier operation may reject a queued newer target for safety, explicitly reporting that the newer setter was never attempted.

`open_controls` reports applied only when the controls activity resumes, with a five-second failure timeout. A4 Android 9/API28 is the qualification target. Android 10+ background activity restrictions and Android 12+ background foreground-service restrictions may require a visible shortcut/controls flow or a platform-permitted overlay exemption. Unsupported background launches return failure; this API does not bypass OS policy. See [Android background activity policy](https://developer.android.com/guide/components/activities/background-starts) and [foreground service policy](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Create tvQuickActions → Custom actions → Intent. Use these exact fields:

| Field | TESTING | Stable | Debug |
|---|---|---|---|
| Type | Broadcast receiver | Broadcast receiver | Broadcast receiver |
| Action | `com.cbkii.redmoontv.AUTOMATION` | same | same |
| Package | `com.cbkii.redmoontv.testing` | `com.cbkii.redmoontv` | `com.cbkii.redmoontv.debug` |
| Class | `com.jmstudios.redmoon.tv.TvControlReceiver` | same | same |
| Categories / Data / MIME | empty | empty | empty |
| Flags | none required | none required | none required |

Extras always include **integer** `api_version=1`, **string** `command`, and **string** `token`. Copy the token from that variant's Automation page. Tokens are independently generated per package; TESTING credentials cannot control stable. Optional **boolean** `feedback=true` produces a concise terminal toast. Use tvQuickActions' typed extras, not string representations of numbers/booleans.

For a button or repeating macro, omit `request_id`. For a caller that retries an uncertain operation, supply a string request ID, 1–64 ASCII letters/digits/`_`/`.`/`-`, and reuse it only for identical arguments. IDs are deduplicated across recreation within a bounded recent history (up to 32 recent rows, with at most 16 unfinished requests). Reusing an ID with a different payload rejects it. Delivery does not prove application; inspect completion history.

### Command-specific extras

| Command | Additional typed extras | Behaviour |
|---|---|---|
| `brightness_pause` | none | Disable saved brightness automation; retain current level and recovery evidence |
| `brightness_resume` | none | Explicitly enable saved brightness schedule and clear temporary/manual holds |
| `brightness_phase` | string `phase=DAY/DUSK/DIM` | Temporary saved brightness phase |
| `brightness_set` | integer `value=1..100` | Temporary absolute native target; out-of-band targets rejected |
| `brightness_adjust` | integer `delta=-20..20`, nonzero | Relative target, bounded by the selected phase's saved limits |
| `warmth_enable` | boolean `enabled` | Explicitly enable/disable saved warmth automation; off clears temporary warmth |
| `warmth_set` | integer `value=0..100` | Temporary overlay strength; saved colour/profile unchanged |
| `warmth_adjust` | integer `delta=-20..20`, nonzero | Relative overlay strength, clamped to 0–100 |
| `preset_apply` | string `phase`, optional string `scope` | Saved phase brightness and/or matching warmth |
| `snooze` | integer `duration_seconds=1..86400`, optional string `scope` | Hold native value; suppress warmth; resume saved intent at expiry |
| `resume_auto` | optional string `scope` | Clear selected temporary overrides; respect saved enable flags |
| `cancel_transition` | optional string `scope` | Cancel fade and retain confirmed/current value until next relevant boundary |
| `reconcile` | none | Recheck owned native context/readback and schedule, with no arbitrary input/HDR parameters |
| `show_status` | none | Cached status toast; does not force a native read |
| `open_controls` | none | Open Red Moon TV Home explicitly |

`scope` is `brightness`, `warmth` or `both` (default for shared commands). Single-feature commands have a fixed scope and reject the `scope` extra. Unknown fields, commands, types and bounds are rejected before work starts.

The phase, set, adjust and preset commands additionally accept:

- integer `fade_seconds=0..888`. Zero is instant. Omission uses the saved phase/warmth fade, except relative adjustments default to instant. Native fades require existing qualification; this API cannot grant it.
- string `expiry=next_boundary` (default), `duration`, or `until_resumed`.
- integer `duration_seconds=1..86400`, required only with `expiry=duration`.

`cancel_transition` always expires at the next relevant boundary; `snooze` always uses the supplied duration. Next boundary means the brightness phase clock for brightness/linked warmth, or the independent warmth window boundary when unlinked. On a cold start, native requests wait up to ten seconds for the installed-vendor identity check; failure becomes an explicit rejection. Overrides and absolute expiry persist through process recreation and standby. Wake catches up; missed fade steps are never replayed.

Temporary actions do not rewrite saved phase values, schedule times or enable flags. An action may create temporary output while the saved schedule is disabled; expiry restores saved intent, not a silently enabled schedule. The native original remains available through Diagnostics → Restore until explicit Keep, an established external adjustment or context/boot policy retires ownership. Never automatically restore into another input/HDR context. Pending uncertain writes always require recovery.

Brightness uses the explicit temporary phase if selected, otherwise the clock's current phase. Day's allowed band is 70..saved Day cap; Dusk is saved Dusk floor..80; Dim is saved Dim floor..20. Relative changes clamp to that band; absolute targets outside it reject. Choosing a temporary target does not redefine the saved cap/floor. Manual native adjustments retain the existing A4 override policy; optional schedule enforcement follows the base branch's explicit user setting.

The first relative brightness press refreshes a missing/stale native sample (older than five seconds). Subsequent relative presses accumulate from the latest desired target (including during a fade). Absolute requests supersede queued targets. Superseded requests end as `cancelled`; a request is `applied` only after native target readback or warmth-window application. Cancellation has guaranteed cleanup admission, never clears an uncertain write, and cannot assert an in-flight vendor call was undone.

## Results and conditional macros

Ordered broadcasts can return an admission/result record. Normal broadcasts and shortcuts use the same persistent ledger. States are `accepted → running → applied / cancelled / rejected / failed / partial`. Stable `error_code` fields distinguish `NATIVE_FAILED`, `OVERLAY_FAILED`, scoped rejection, `CANCELLED`, `PARTIAL_APPLICATION` and `PROCESS_RESTARTED_OUTCOME_UNKNOWN`; successful records use null. Unauthenticated/malformed ordered sends return `CONTRACT_OR_PAIRING_REJECTED`. Human-readable `detail` explains the current cause. Combined requests contain independent `parts.brightness` and `parts.warmth` results; one successful scope cannot hide another's failure. A crash marks unfinished results failed with `process_restarted_outcome_unknown`; it does not replay the request. A rejected unauthenticated/malformed request is returned to an ordered sender, without filling trusted history.

In tvQuickActions' ADB command action, query cached state:

```sh
content query --uri content://com.cbkii.redmoontv.testing.diagnostics/automation
```

For stable, replace the package with `com.cbkii.redmoontv`; for debug, use `com.cbkii.redmoontv.debug`. This remains protected by `android.permission.DUMP`; ordinary apps cannot read it. tvQuickActions needs its existing ADB connection for this optional macro route. It does not need ADB to invoke generated shortcuts or paired broadcasts.

The one-row JSON report includes API version, enabled/legacy flags, independent temporary overrides and expiry, saved enable flags, manual hold, native level/freshness, recovery state, runtime target/overlay state, request history and per-scope details. It never contains the pairing token. For a macro, use Set variable → ADB output → regex extraction; e.g. `"brightness_enabled":(true|false)` with group 1. For dependent commands, supply a unique request ID and check that same row's terminal state rather than assuming the latest row belongs to it. Do not interpret a stale level, `accepted`, or `running` as success.

The existing shell/root-only provider adds `automation` (same typed JSON contract) and `automation_pairing` (protected token retrieval for the runner). These do not grant integration consent, backend selection, native/fade qualification or arbitrary root execution. Report queries start no display service/JNI. Bounded terminal logcat uses `RedMoonAutomation`; no per-fade-step automation logs or token output.

## Recipes

Use explicit states in macros; toggles are intentionally omitted.

| Trigger/control | Command and typed parameters |
|---|---|
| Five-way Up | `brightness_adjust`, int `delta=5` |
| Five-way Down | `brightness_adjust`, int `delta=-5` |
| Five-way Left | `warmth_adjust`, int `delta=-5` |
| Five-way Right | `warmth_adjust`, int `delta=5` |
| Five-way OK | `open_controls` |
| Double press Dim | `preset_apply`, string `phase=DIM`, string `scope=both` |
| Long press Resume | `resume_auto`, string `scope=both` (or explicit `brightness_resume` to enable it) |
| Pause both 30 minutes | `snooze`, string `scope=both`, int `duration_seconds=1800` |
| Screen on / HDMI event | `reconcile` |
| Playback starts | `preset_apply`, string `phase=DIM`, string `scope=both`, string `expiry=until_resumed` |
| Playback ends | `resume_auto`, string `scope=both` |

Use the package/class/action/token fields above for **every** broadcast recipe. Native schedules already reconcile boot/wake independently; event macros should not duplicate periodic polling. HDMI connection state in tvQuickActions is not proof of the selected input or HDR state; Red Moon reads its own vendor context before native operations.

## ADB validation

From Termux/workstation **outside** adb shell, with Python 3.8+ and existing adb:

```sh
# Dry-run: does not touch the TV.
python3 scripts/a4-automation.py -s TV_SERIAL --suite contracts
# Enable paired automation once on the TV, then:
python3 scripts/a4-automation.py -s TV_SERIAL --execute --suite contracts
python3 scripts/a4-automation.py -s TV_SERIAL --execute --suite controls
python3 scripts/a4-automation.py -s TV_SERIAL --execute --suite rapid
python3 scripts/a4-automation.py -s TV_SERIAL --execute --suite fade_cancel
# Actual tvQuickActions setup/invocation, operator remote actions and focus observation:
python3 scripts/a4-automation.py -s TV_SERIAL --execute --suite tvqa
# One explicit command; longer fades need an appropriate bounded deadline:
python3 scripts/a4-automation.py -s TV_SERIAL --execute --command brightness_phase --data '{"phase":"DIM","fade_seconds":5}'
```

Default package is TESTING. Pass `--package com.cbkii.redmoontv` or `.debug` explicitly for another variant. The runner checks the selected A4 identity, obtains its own package token through the protected query, sends exact typed broadcasts, polls only bounded request deadlines, retains JSON/APK hashes/logs and redacts the token. Unknown outcomes are never automatically replayed. Controls/rapid/fade-cancel suites save configuration and attempt guarded restoration; cleanup failure leaves the backup and recovery evidence in the app and is recorded in the archive. They require existing native qualification; no optical acknowledgement is invented.

Contract tests cover typed arguments, invalid phase/token/fields, duplicate IDs, collision rejection and the provider route. Controls exercise all commands. Rapid presses check accumulation; fade-cancel checks that writes stop after completion. Use PR #1's existing `a4-validate.py` plans for boundary, standby, process recreation and input/HDR changes, paired with `/automation` snapshots. Test disabled integration, token revocation and cross-variant isolation using the Automation page and contract runner. ADB can launch `ACTION_CREATE_SHORTCUT` and inspect its UI; return-to-tvQuickActions acceptance must be tested with the actual caller.

Complete actual acceptance for each package: shortcut discovery, parameter selection/save, repeated invocation, labelled action/icon, Back/cancel, revocation/recreation, broadcasts while playback continues, status-based macro branch, cold start, standby/wake, process death, override expiry, changed input/HDR, root denial and combined partial failure. Record physical output and playback/focus observations separately from software results. No tvQuickActions backup format is invented; an import bundle requires inspection of a real user export.

## Primary references

- tvQuickActions shortcut workflow: https://tvdevinfo.com/tvquickactions/custom/shortcuts/
- Intent fields and broadcast type: https://tvdevinfo.com/tvquickactions/custom/intent/
- ADB variables and branching: https://tvdevinfo.com/tvquickactions/macros_variables_logic/
- Android shortcut result: https://developer.android.com/reference/android/content/pm/ShortcutManager#createShortcutResultIntent(android.content.pm.ShortcutInfo)
