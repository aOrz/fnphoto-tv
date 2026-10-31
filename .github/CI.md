# Android CI

The `Android CI` workflow runs for pushes to `main`, pull requests targeting
`main` (including drafts), and manual dispatches. It does not publish a release,
deploy the app, or use release-signing credentials.

## Checks and artifacts

The single `Debug build and unit tests` job installs Android SDK platform 29 and
Build Tools 30.0.3, then uses Temurin JDK 11 and the checked-in Gradle 7.4 wrapper.
The SDK installer uses the hosted runner's JDK 17 before switching to JDK 11.

It runs the existing JVM tests and builds a debug APK:

```bash
bash ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest
bash ./gradlew --no-daemon --stacktrace :app:assembleDebug
bash ./gradlew --no-daemon --stacktrace :app:lintDebug
python -m pip install -r test/requirements-contract.txt
python -m unittest discover -s test -p 'test_*.py' -v
```

No emulator, NAS, or account credentials are needed. The Python contract suite
checks pagination, date/person/folder names, idempotent collection writes, the
fixed 360-item fixture over ephemeral local HTTP/WebSocket connections, video
failure controls and byte ranges, media generation, and the screenshot helper
with synthetic images (no device). CI installs FFmpeg/ffprobe and exercises short
H264/AAC generation; generated media stays temporary and is not uploaded.
See [test/README.md](../test/README.md) for local dependencies and device scenarios.

Open a run in the repository's **Actions** tab to download the debug APK and
JUnit XML/HTML test reports. Artifacts expire after 14 days. Test reports are
uploaded even when tests fail, if Gradle produced them. A failed test or build
fails the job; errors are not ignored. Lint errors also fail the job; its HTML/XML
reports are uploaded with test results. Device/instrumentation testing is not
part of this workflow.

## Security and reproducibility

- The workflow token has only `contents: read`; checkout does not retain it.
- PRs run with `pull_request`, never privileged `pull_request_target`.
- Actions are pinned to full commit SHAs. Update pins deliberately when upgrading.
- The Gradle wrapper JAR is validated by `setup-gradle`; the distribution is
  downloaded from the official Gradle service with its published SHA-256 checksum.
- Gradle plugins and dependencies resolve through Google's HTTPS Maven repository
  and Maven Central. The legacy ExoPlayer 2.11.8 artifacts are absent from those
  repositories, so only `com.google.android.exoplayer` uses the existing Aliyun
  archive mirror over HTTPS. No HTTP mirrors or local Maven repository are used.
- Only `main` writes the shared Gradle cache; other refs restore read-only.
- Runs have a 30-minute timeout and newer runs cancel superseded ones on the same
  branch or PR.

## First run on a fork

GitHub may disable inherited workflows in a new fork. If the **Actions** tab
shows that workflows are disabled, a repository owner must explicitly enable
them there. This change does not alter repository Actions/security settings,
branch protections, or fork-PR approval rules. A run requiring approval must be
approved by an authorized maintainer before it can execute.

Once the workflow is on the default branch, use **Actions → Android CI → Run
workflow** to start a manual run. Before merging this PR, the PR event runs the
proposed workflow when Actions is enabled. If Actions was enabled after opening
the PR, an authorized maintainer can reopen the PR or push a new commit to
generate a fresh event.

## Local reproduction

Install JDK 11 and Android SDK packages `platforms;android-29` and
`build-tools;30.0.3`, set `JAVA_HOME` and `ANDROID_HOME`, then run the commands
above from the repository root. No global Gradle installation or CI init script
is required. The Gradle/AGP versions are intentionally unchanged by this CI work.

The temporary stacked-PR trigger was removed after the performance PR merged.
