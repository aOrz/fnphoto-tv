# Functional completion / 功能修复说明

This local change builds on `80e3390` (the progressive-loading change), rather than
reimplementing its paging, cancellation, image-cache, and fallback-player work.

## Implemented

- API 19/20: guard runtime letter spacing; move API 21 style attributes to
  `values-v21`; remove the unused layout referencing an absent PreviewGridView.
  Settings uses the actual BuildConfig version.
- Favorites: date thumbnails and date contents filter actual `isCollect` values,
  retain raw server offsets across empty filtered pages, and isolate preview
  cache keys. Toggling reads current server state first, writes the opposite
  value, rejects HTTP/business errors, and refreshes favorites on returning.
- Media types: the menu now offers photos/videos. Existing timeline dates bound
  an existing `gallery/getList` query; category filtering is client-side, paged,
  cancellable and retryable. It adds no speculative NAS endpoint or parameter.
  Sparse categories can require scanning many pages; this is not a server-side
  index or an AI classifier.
- Detail recreation: a random snapshot ID and index travel in saved state; the
  list is stored in app-private cache for at most 24 hours. The owner is tied to
  the login session, not a rotating access token. A normal exit removes it.
  Configuration recreation retains paging; after process death only already
  loaded media are restored, with an explicit message to return for more.
- FN ID reconnect: preserve the user's original FN ID separately from the last
  resolved address. Remembered FN IDs rediscover on launch and reauthentication.
  GET transport failures can attempt one rediscovery/relogin; uncertain writes
  are never replayed after a transport error. Origin rerouting is restricted to
  known NAS `/p/api/` paths and the same login identity; unrelated URLs are not
  rerouted. POST 401 retries sign the actual replayable form/JSON body.
- Mock: collection writes accept the real form contract, update state, and are
  deliberately idempotent. Favorites timelines reflect that state. Existing
  progressive-loading tests cover offset, album, person, date and folder names.
- CI now includes lint and the mock contract suite, not just JVM/build checks.

## Not implemented: a precise contract is still needed

The Shared, Tags and Smart Classification entries remain visible, now explaining
that their protocol is missing rather than changing the old screen's title or
silently masquerading as another list. We did **not** invent REST paths, guessed
filter names, shared-permission semantics, tag schemas or AI labels.

README.md and README_CN.md link to:

1. This repository's `API.md`: only timeline, date-range list, app version and
   photo statistics are documented as photo-service endpoints. Its final
   "Feiniu NAS Photo Gallery API Documentation" is plain text, not a link.
2. [FNOSP/fnnas-api](https://github.com/FNOSP/fnnas-api), checked at commit
   `c2cb8b3697f04045b91929eabaaff85596755382` on its `master` branch. Its
   [module index](https://github.com/FNOSP/fnnas-api/blob/c2cb8b3697f04045b91929eabaaff85596755382/docs/_sidebar.md)
   lists authentication, system, user, storage and files; its API notes describe
   WebSocket authentication, not a photo smart-classification API.
3. The README clone URL is a source repository, not additional API documentation.

[Official sharing help](https://help.fnnas.com/articles/v1/photo/photo-share)
explains the product's sharing/permissions UI, but supplies no REST request,
response or pagination contract. Existing `NewAlbum.shared` and untyped
`GalleryPhotoAdditional.tags` fields are insufficient to infer those contracts.

To complete these three entries, provide the target NAS photo-service version
and either authoritative API documentation or sanitized request/response samples
for listing categories/tags/shared albums and listing their media, including
paging and permissions. Do not include passwords, tokens, cookies or photo bytes.

## Verification

From a JDK 11 / Android SDK 29 / Build Tools 30.0.3 environment:

```sh
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m pip install -r test/requirements-contract.txt
python -m unittest discover -s test -p test_mock_contract.py -v
```

Unit tests prove helper/contract behavior, not a live NAS implementation.
Device testing must cover DPAD search/result navigation, collection add/remove,
background/recreation, category cancellation and repeated presses. API 19/20
runtime testing remains distinct from lint's API compatibility check.

Local verification on 2026-10-31: 172 JVM tests passed, 14 mock contract tests
passed, debug APK assembled, lint reported 0 errors / 87 warnings, actionlint and
`git diff --check` passed. Final device checks are tracked separately; these
numbers do not claim live-NAS or device coverage.

Device regression (synthetic NAS only, 2026-10-31):

- Official Android TV API 29 emulator, integrated APK `1eaebb91...`: normal
  login, 360-item paging, fast next/previous, focus/scroll restoration, repeated
  person-date Back navigation without the headers-transition crash, no stale
  recycled-card subtitle, photo/video picker and Cancel, collection write/read,
  and actual process-death detail restoration (old PID killed; new onCreate
  restored the same photo from the disk snapshot).
- This found one remaining stale favorites-date count on Back. The final APK
  `4dab7e53de86f910f17aa5782b8d80e3b72622bb2479661f151b551922569df9`
  was installed and its device APK hash verified; uncollecting the only photo
  on a date then returning issued a fresh `timeline?is_collect=1`, removed that
  date card, and retained usable focus. No FATAL exception was observed.
- Official Android 4.4.2 API 19 **ordinary Android** emulator, APK `1eaebb91...`:
  install/login, folder contents, search, settings and clearing image cache
  passed without NoSuchMethodError/InflateException/VerifyError/FATAL. This is
  not an Android TV image or physical TV. The final favorites-only delta was
  not re-run on API 19 after that emulator was stopped.

No live NAS, live FN Connect relocation, physical-TV decode/performance, or
end-to-end Shared/Tags/Smart Classification behavior is claimed as verified.
