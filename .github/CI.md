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
  and Maven Central. ExoPlayer is pinned to 2.13.3, the first release published to
  Google Maven, which retains API 19 support (upstream minSdk 16). No archive
  mirrors or local Maven repository are used. See the
  [compatibility notes](../docs/development/workflow.md#exoplayer-来源与兼容边界).
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

## Delivery templates and proposed merge gate

The [PR checklist](pull_request_template.md), [requirements template](../docs/requirements/TEMPLATE.md)
and [delivery report](../docs/reports/TEMPLATE.md) implement the reporting process in
[AGENTS.md](../AGENTS.md). They do not enforce repository settings or prove that
checks, independent review, device QA, or real NAS validation occurred.

### Read-only snapshot — 2026-11-02 UTC

At `main` commit `5500226225e889d69a4b9626e72603e65952d84f`:

- `GET /repos/aOrz/fnphoto-tv/branches/main` returned `protected: false`,
  protection `enabled: false`, required status checks `enforcement_level: off`
  and empty `contexts`/`checks`.
- `GET /repos/aOrz/fnphoto-tv/rulesets` returned `[]`.
- The integration could not read `branches/main/protection`: HTTP 403,
  `Resource not accessible by integration`. Detailed administrative settings
  were therefore **not verified**. A separate effective branch-rules endpoint
  was not supported by the connector. No protection/security settings were changed.
- The checked-in workflow defines one check, `Debug build and unit tests`, covering
  JVM tests, debug build, lint and Python contracts. This snapshot does not assert
  any particular run passed. Inspect the current PR head's check runs, not an old
  green run. No device/instrumentation or real NAS validation runs in this CI.

These observations are a dated snapshot, not a promise about current settings.

### Proposed settings — owner confirmation required, not applied

For `main`, propose an active protection rule/ruleset with:

1. Require a pull request and **one approval from an eligible independent GitHub
   reviewer**; dismiss stale approvals on new commits. An agent's review report
   is useful evidence but does not satisfy GitHub approval enforcement by itself.
   Confirm an eligible reviewer is available before activation, especially for a
   single-maintainer repository; otherwise this gate can block all merges.
2. Require resolution of review conversations.
3. Require the exact check context **`Debug build and unit tests`**, restricted to
   the GitHub Actions app after confirming the actual check-run source in a recent
   successful PR. Require branches to be up to date before merging. Do not use
   workflow title `Android CI` as the required context or invent separate lint/
   Python contexts: they are steps inside this one job.
4. Disallow force pushes and branch deletion. Apply to administrators too; no
   routine bypass. The owner must explicitly decide any emergency bypass policy
   before activation. Do not silently grant new access or change security settings.

The existing workflow runs on all PRs targeting `main`, including docs-only and
Draft PRs; retain that behavior when making its check required. Do not add path
filters that leave a required check pending. No merge queue is proposed (the
workflow currently has no `merge_group` trigger).

After owner approval, an authorized maintainer should inspect the live settings
and existing rules, apply only the confirmed policy, then verify the effective
rule and a fresh PR check. Test that a failing/missing check and missing approval
block merging without merging a test PR. Record the actual result and remaining
access limits. Until then, the checklist remains procedural rather than a
technical merge gate; a green CI run alone is not permission to merge/deploy.
