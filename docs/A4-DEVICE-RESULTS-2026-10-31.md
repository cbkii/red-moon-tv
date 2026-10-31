# A4 results and corrective implementation — 31 October 2026

Inputs: `a4-results.zip` and `a4-read-20261030T221954226795Z.zip`. Their installed APK hashes both equal `dd812610977e9823055f19f8d4aeade5ef11c7de0d83956c476a5732ae1623ce`, the signed source `22f3423` / build `41064849019-1`. These are real tests of that APK, not the later root-backend source. Device: xiaoyushan, API 28, armeabi-v7a, Toshiba firmware `01.03.00.03`, APK UID 10078.

| Observation | Conclusion and change |
|---|---|
| App log at 08:32–08:33 on 31 October: `Unexpected vendor range 0..0`, first stage `unpack native range` | The bridge reached the installed API but aborted before a native level read. Do not classify this as the earlier missing-library error. The updated bridge calls the installed TVNative signatures directly, tries separate min/max selectors when the packed query is zero/unsupported, and keeps Read available if metadata remains missing. Writes still require an actual 0–100 native range and picture/boot context. |
| Every service snapshot says `No services match`; no extracted app reports | A one-shot read can stop its display service. A successful dumpsys exit is not a report. A read-only provider protected by Android DUMP permission now exports status, level/context, first error, recovery, source/build and counters independently of service lifetime. No JNI or setter is started by querying it. |
| Operator: “extract this yourself … don't ask me to copy available info manually” | The collector now derives read success, native level, context and restoration from the app report. It asks only for remote actions and optical observations which ADB cannot measure. App-tag logs are collected separately so firmware channel noise cannot displace the first error. |
| Operator: “nothing happens, and UI is … for TV” | Replace the single long form and positional focus tags with a fixed navigation rail and separate Home, Schedule, Warmth, Diagnostics and Settings pages. Use dp-sized targets, high-contrast focus, stable semantic focus keys, three phase cards and remote-only numeric/time editing. Connection/probe/confirmation/recovery are visible Home actions, not hidden checkboxes. |
| `playback-native-steady` and `playback-qualified-fade` labels, but no successful read/probe/report | Labels are not proof that either mode ran. No native/fade/persistence/playback acceptance is established. |
| Later CPU accounting shows approximately 0.1% each for the app and APK worker; app PSS is around 21–26 MiB | These samples do not establish a leak, a performance regression or a benchmark. They also do not prove steady-state behaviour of the newer backend. |
| The APK worker remains present across one-shot service captures | Review found cleanup queued immediately before scheduler shutdown, whose Android policy can cancel queued zero-delay tasks. Teardown now runs close before shutdown; a blocked-call/queued-cleanup regression proves the ordering. This is a verified source defect, not proof that every retained PID in the archive had that cause. |
| Stremio MEDIA_BUTTON receiver ANRs in the global log | These belong to Stremio. The archive does not establish that Red Moon caused them. |

## Native signature evidence

Non-executing DEX inspection of the supplied TvSettingsPlus APK confirms protected static native methods:

- `getConfigValue_native(int, String)`
- `getMinMaxConfigValue_native(int, String)`
- `setConfigValue_native(int, String, int, int)`

The actual A4 `MtkTvConfigBase.setConfigValue(String,int)` passes input `-1` and update flag `3`. The bridge uses those exact arguments for the fixed backlight key; it exposes no generic key/shell command. Separate min/max selectors are read-only queries; no guessed range is substituted if they fail. Reflection/regression fixtures cover zero packed range, selector discovery, missing metadata/read-only behaviour, getter errors, exact setter arguments and no setter retry. No vendor binary or third-party implementation is redistributed.

The updated signed APK must be installed before evaluating these fixes. CI proves build/test behaviour; it does not establish a successful native panel change on the A4. Use Home → connection → small test → visible confirmation, and run the updated collector. Native scheduling stays disabled until confirmation; repeated-write fades retain their separate persistence gate.

## Newer capture: successful root access (supersedes the earlier failure baseline)

The later `a4-read-20261031T013536627296Z.zip` contains the signed `a85ddf1aa333` APK, SHA-256 `fd89fb413007247618b081e38ac922a7f941570187c1ea3731ad02dd0293111f`. The preceding review verified all 30 manifest hashes. Root reports UID 0, range 0..100, level 70, 29 counted calls/four writes/zero errors in that process, native acknowledgement and restored-probe flags, and the operator's observation that dimming and overlays work. Those counters include earlier operations; this is not a new four-write experiment. Historical `Unexpected vendor range 0..0` text is not the current root result. Input/HDR, persistence/fades and exact stable-package acceptance remain open. See [ADB implementation and validation](A4-ADB-IMPLEMENTATION.md) for the subsequent source work; it has not yet produced a new APK.
