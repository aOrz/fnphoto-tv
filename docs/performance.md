# Progressive media loading

## Behavior

- Album/date/person photo screens render the first 35-item page immediately, then
  request the next page near the selected item. Album directories use 60-item
  pages; folder/search grids use 100-item pages. A footer/status exposes errors
  and explicit retry instead of silently presenting a partial list as complete.
- Paging is single-flight. Offsets count raw server rows, while displayed items
  are deduplicated by media ID. An empty or all-duplicate page stops pagination.
  A nullable `hasNext` is authoritative when present; otherwise page length is
  the fallback. Folder responses may also provide a total.
- Navigation/search generations reject old callbacks and cancel superseded
  calls. Returning to a parent list preserves its loaded prefix and TV focus.
- Detail navigation and slideshows can request subsequent pages from their
  parent list. A per-detail delivery cursor also delivers a final page that
  finished prefetching after the detail screen copied its initial list.
- Date previews use a +/-6-day window, at most two metadata requests in flight,
  small thumbnails, and a 512-date URL LRU. Failed preview requests can retry.

## Images and playback

- Glide owns asynchronous decoding, memory caching, disk caching and image
  dimensions. There is no synchronous `BitmapFactory` disk decode on binding,
  redundant JPEG recompression or directory scan for every loaded image.
- Standard `ImageView` targets replace previous loads. Custom composite targets
  have explicit cancellation scopes and clear Glide-owned bitmap references.
  Full-photo and thumbnail requests are one Glide chain, so a slow thumbnail
  cannot overwrite the full image.
- Detail views cancel old targets on navigation/stop/destroy and preload only
  one adjacent **photo**, at low priority and no larger than 1920x1080. They never
  submit a video stream URL to an image decoder.
- Video preview uses a small overlay view, not another full-screen bitmap.
  Both ExoPlayer and its MediaPlayer fallback are released; fallback transport
  keys wait for preparation. A stopped slideshow cannot advance because an old
  page finished. Each loaded photo gets a fresh interval; videos advance on end.
- Cache size/clear controls operate asynchronously and include Glide plus legacy
  disk files. The legacy URL-only transformed JPEG cache is no longer reused.
- Concurrent 401 responses for one session share a token refresh, with a short
  failure backoff. A failed refresh preserves the readable original 401 response.

## Reproduction and regression checks

With the JDK 11 / SDK 29 / Build Tools 30.0.3 toolchain described in
[`../.github/CI.md`](../.github/CI.md):

```sh
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s test -p 'test*.py'
git diff --check
```

The 2026-10-31 candidate passes **151 JVM tests** (52 new tests) and **13 Python
mock contract tests**, and produces a debug APK. New JVM tests cover paging
locks, raw offsets, duplicate/empty/short pages, failure retry, cancellation,
destroyed/old-query callbacks, buffered final-page delivery, image scopes,
detail navigation, preparation-key state and refresh concurrency. They exercise
production state helpers; they are **not instrumented Activity/View tests**.

Python tests invoke the mock handlers without a NAS connection, including
0/34/35/36/100/1,000/10,000-item pagination. The mock understands the client's
`offset`, `limit`, `album_id`, `folderPath`, `start_time` and `end_time` contract;
legacy aliases remain accepted. The manual smoke script is opt-in:

```sh
python3 test/test_mock.py --help
```

An additional `:app:lintDebug` run reports 11 existing errors and 91 warnings.
All 11 error locations already exist on the base `5f10154`: the unused
`item_date_preview.xml` references missing `PreviewGridView`, and letter-spacing
calls/style attributes are not all guarded for API 19. This change does not
disable lint checks or claim that lint passes.

## Device validation and remaining limits

Measure on a representative TV/NAS before claiming frame-time, memory or
network-speed improvements. Record cold/warm first-visible time, requests and
bytes before first interaction, dropped frames, heap/native bitmap usage and
file descriptors. Include fast DPAD navigation, cancellation/back, repeated
folder-boundary binding, rapid search changes, a slow final page while detail
is open, failed/retried pages, and mixed photo/video slideshows.

The code removes the all-pages-before-first-render dependency; no measured
latency or FPS improvement is claimed. Real NAS pagination/signature behavior,
TV focus and hardware decoder behavior still need device validation. Metadata
already visited remains in memory (not a bounded moving window); geographic
search retains its existing non-paged backend contract. Original photos may
still require transferring original bytes even when Glide downsamples decoding.
The API-19 lint issues above and process-death restoration of the in-memory
detail handoff are separate follow-up work.
