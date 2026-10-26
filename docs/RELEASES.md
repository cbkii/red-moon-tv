# Builds, TESTING and release publication

## Package identity

- Stable: `com.cbkii.redmoontv`, initially versionName `4.0.1-tv.1`, versionCode `40`.
- TESTING: `com.cbkii.redmoontv.testing`, fixed versionName `0.0.0-testing`, versionCode `999999` in the requested testing workflow. This permits repeated installs with `adb install -r` without colliding with stable.
- CI debug: `com.cbkii.redmoontv.debug`; uses the runner's debug key, so debug signing continuity across runs is not promised. Prefer the consistently signed TESTING channel for device updates.

Android CI runs tests, lint and builds a debug diagnostic APK and an **unsigned** testing APK. The unsigned file is a signing input, not an installable TESTING release. The debug APK is installable but is not a physical A4 qualification result. CI artifacts bind to their run/attempt; source SHA is the run head.

## Signing secrets

The signing job uses the same names as the user's ts-theme/Auxio workflows:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Secret values are never read into source, documentation or PR comments. Existing repository/organisation secrets must already grant this repository access; workflows cannot copy secrets from another repository. Source-build jobs never receive them. A separate signing job downloads only a closed two-file hand-off, inspects identity/version/minimum SDK and TV launcher metadata, aligns/signs/verifies the APK, records the signer and hashes, and publishes those exact verified bytes. Signing material exists only in a private temporary directory and is removed after use. Gradle never runs in that job.

## Permanent TESTING draft

After these workflows are merged and Actions is enabled, run **Refresh Testing APK Draft** from the default branch. Choose a PR number (`1` or `#1`), branch, tag or SHA using `source_to_build`. Alternatively, a trusted collaborator can post `/testing-apk` in a PR, add `testing-apk`, or include `/testing-apk` as a separate command token in the latest PR commit message. Signed requests are restricted to same-repository source heads.

One owner-accessible draft is maintained:

- Title: `000 Testing Only Version`
- Tag name: `000-testing-only`
- Managed marker: `<!-- red-moon-tv-testing-managed -->`

Each snapshot contains an APK, BUILD_INFO JSON, SIGNER report and SHA256SUMS. Unique names include source SHA and build run ID. The newest two complete managed groups are retained. Unmanaged releases/assets are never overwritten. The workflow uses the trusted workflow snapshot for request resolution/signing/publication and an immutable requested source for the build. A draft is not a publicly downloadable prerelease.

## Simple manual release

Run **Manual Release** from current default-branch HEAD. Supply versionName, increasing stable versionCode and state (`draft`, `prerelease`, `stable`). The tag is exactly `v` + versionName. Default state is draft. Stable requires physical A4 acceptance of that candidate. Build input and qualified signed output are retained as separate workflow artifacts before publication.

The publisher creates/uses a managed draft, uploads and verifies remote SHA-256 digests, then sets the requested state. It rejects published/unmanaged releases, conflicting existing tags or mismatched existing assets. A completed published release is immutable; a new version is the normal recovery path. Draft runs can be resumed with identical qualified bytes; an uncertain API write is reread before deciding what remains.

No merge, tag/release creation or signing/publication workflow is automatically requested by implementation PR #1. Invoking the Engineering Orchestrator does not replace its immediate approval boundary for those operations. A human can explicitly authorise the prepared workflow once its source/build gate is established.

## Validation commands

Use JDK 11 with Gradle wrapper 7.2 and SDK platform/build-tools 32. SDK manager itself runs using the runner's JDK 17.

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleTesting
```

The local task environment can compile the reflection-only Java bridge and run Python contract tests. Full Android compilation requires the Android SDK/dependencies or a functioning GitHub Actions runner. Do not report a successful APK build from syntax checks alone.
