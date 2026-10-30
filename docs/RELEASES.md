# Red Moon TV build and release operations

Packages: stable `com.cbkii.redmoontv`, TESTING `.testing` (fixed 0.0.0-testing/999999), debug `.debug`. Every APK embeds full source SHA, build/run-attempt identity and backend protocol; diagnostics expose these. Never reuse a published stable versionCode. SDK 26 minimum / target 32 includes the API 28 A4, with 32-bit vendor compatibility still requiring physical tests.

## Infrastructure bootstrap

The reviewed infrastructure prerequisite must be on master before the dispatch workflows can be selected. It builds/signs a chosen application SHA without enabling native control on master. Keep application PR #1 separate until its physical gates are satisfied. Once infrastructure is merged, run **Refresh Testing APK Draft** from master and select `#1`, a same-repository branch, tag or full SHA. A trusted PR comment `/testing-apk` is optional. No pull_request_target commit-message trigger is used.

Build jobs check out immutable source without persisted credentials and receive no signing secrets. The signing job uses trusted workflow source and only a closed unsigned APK + BUILD_INFO handoff; it never executes scripts from the chosen app source. APK package/version/minimum SDK/launcher, native ABI and proprietary-JNI exclusion, alignment, signing certificate, checksums and remote digests are checked.

Secrets retain existing names: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. No new key is generated. Optionally pin `EXPECTED_SIGNER_SHA256` as a repository variable to the accepted certificate. If not yet pinned, metadata labels the certificate first-use; explicitly accept its exact digest in the stable physical acceptance record. Existing draft snapshots must retain signer continuity; compare against accepted prior builds before upgrading. Secret names alone do not prove availability.

The permanent owner-accessible draft is `000-testing-only`. Managed snapshot names include source prefix, run ID and attempt. Keep the newest two complete managed groups, preserving unrelated assets. Published/unmanaged releases, mismatched assets and conflicting tags are rejected. Download the complete group: APK, BUILD_INFO, SIGNER, SHA256SUMS.

## Stable prepare and promote

Run **Manual Release** from current master:

1. `operation=prepare`: choose monotonically increasing versionCode/versionName. Tests/lint/build run without signing secrets; sign once and prepare a managed draft `v<versionName>`. Download the signed **stable-package** APK and metadata. This does not publish it.
2. Physically qualify those bytes using A4-VALIDATION.md. Fill A4-ACCEPTANCE.json with package, source, signed APK hash, accepted signer, physical device/build, reviewer/date and passed evidence for every gate.
3. `operation=promote`: enter that draft's numeric release ID and completed acceptance JSON. Promotion downloads/validates existing assets, remote hashes/checksums, source/tag, unchanged current master and physical record. It publishes those same bytes without rebuilding/re-signing. An obsolete or altered candidate is rejected.

Promotion requires full acceptance, including qualified repeated-write fades and declared warmth/playback coverage. A useful diagnostic or backlight-only draft does not establish the complete product. There is no boolean bypass. A different stable UID may expose permissions not seen in TESTING.

The source commit and GPL notices are provided with the distribution metadata. Preserve the tag/commit as corresponding source and retain reproducible Gradle/JDK/SDK build information. Pause or Warmth Off is the first rollback; conditional restore/native TV Settings recovers picture levels. Android may refuse downgrades: prefer a forward-version release containing known-good code, rather than promising data-preserving rollback.

## Checks

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:testSnapshotUnitTest :app:lintSnapshot :app:assembleSnapshot :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

CI checks source/build behaviour only. Record workflow/run/attempt, source and workflow SHA, unsigned/signed hash, certificate and physical acceptance separately. Never publish stable from CI success alone.
