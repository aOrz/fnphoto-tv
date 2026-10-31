# Large-library safety boundaries

This follow-up is based on `main@26e6b765`. It bounds client work without
inventing NAS request parameters. See [API evidence](large-library-api-contract.md)
for the distinction between the existing client contract and real-NAS proof.

## Browsing and retained metadata

- Album directories and album/date/person media use explicit windows of at most
  1,200 displayed records (35-record media pages stop at 1,190). A whole page must
  fit before it is requested. The footer offers **next batch**, which discards
  the old window, invalidates old detail sources, and continues the raw offset.
- Folder/search grids allow at most 1,200 raw rows per window. Their displayed
  list and deduplication set cannot exceed that number. The next batch preserves
  the raw offset and clears old rows only after an explicit user action.
- Sparse media/category/favorite scans retain only a two-page raw-ID history;
  the display window separately deduplicates retained matching IDs. Raw scans
  therefore do not accumulate an ID for every nonmatching photo in the library.
- Unpaged timelines, people, places and subfolder directories keep at most 1,200
  entries. If larger, the UI explicitly says that the list is incomplete and
  suggests another narrower browsing route; there is no guessed continuation.
- Folder descent stays in one Activity instead of retaining a stack of parent
  media lists. A bounded path history allows up to 32 descents; Back reloads the
  parent. Paths are limited to 4,096 characters and display names to 256.
- Details retain at most 1,200 records and never automatically replace their
  window. At a window/protocol boundary they stop with the source's explanation,
  not “all photos finished.” Process-restoration snapshots have the same item
  limit, an 8 MiB serialized limit, a 24-hour lifetime and at most eight files.

This is **not** an infinite, seamless, bidirectional paging cache. Previous
windows and parent-folder scroll positions are deliberately discarded. Browsing
older windows requires reopening the list/query. Offset pagination over a
changing NAS dataset cannot guarantee global cross-window deduplication or a
snapshot-consistent ordering without a server cursor/snapshot contract.

## Sparse filters and finite work

The known gallery request does not establish a server-side `category` filter.
The client retains local filtering but scans at most **five 35-row pages per
action**. If none match, it shows the scanned raw offset and a **continue scan**
action. It does not call this an empty library or silently keep fetching while
idle. Focus-triggered continuation is suppressed after a budget pause; an
explicit action grants another budget. Favorite-date covers use the same finite
five-page bound and retain at most four thumbnail URLs.

Paged photo walls also cap each automatic focus demand at five raw pages.
Demand survives an in-flight request and is rechecked after appending rows, but
the footer remains anchored to the count when it was first focused. Newly
appended content receives focus only if that footer still owns actual focus;
this handoff does not start a fresh budget. Thus short pages or pages with a few
matches cannot continuously replenish the sparse-scan budget through response
callbacks. Failure, exhausted budgets and full windows retain explicit actions.
The metadata request does not wait for pending thumbnail downloads.

A detail boundary uses a distinct pause callback rather than an empty terminal
page. Waiting is released, slideshow is stopped, and the right key can explicitly
continue. Back or a newer root/query invalidates old callbacks. Failed pages can
retry at the same offset; oversize/null-row pages are rejected atomically. Empty
pages end a scan when no continuation metadata exists; a nonempty short page
does **not** imply the end, because the documented `limit` is only a maximum and
`hasNext` may be null. This may cost one final empty-page probe. Empty
pages that still claim `hasNext=true`, duplicate-only pages and integer-offset
limits are surfaced as incomplete/protocol boundaries rather than confirmed ends.

## Response and location limits

Metadata requests for gallery/album/person/folder/search/place endpoints are
limited to **8 MiB of decompressed response bytes**, before unrestricted Gson
materialization. Both known Content-Length and unknown/chunked-length bodies are
checked. This budget does not cut off photo/video byte streams.

Location search preserves its established POST filter and safely serializes
country/city strings. A streaming reader retains at most 1,200 photos, discards
unused fields, closes failed responses and exposes server-reported end, unknown
completeness, server-has-more, or client-limit states. An oversized/invalid reply
is a retryable error, never a successful empty result. No location next-page
request is sent until a reliable NAS continuation contract is available.

Item-count limits and a per-response byte limit are structural bounds, **not a
measured peak-heap or OOM-free guarantee**. Java object overhead, very long fields,
several simultaneously retained bounded screens, Glide caches and video buffers
still affect peak memory. Representative TV/NAS profiling remains necessary.

## Reproduction and validation

```sh
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m unittest discover -s test -p 'test_*.py' -v
git diff --check
```

Tests cover 100,000-row traversal with bounded sets/windows, sparse 100,000-row
scans with finite user-action budgets, rejected/duplicate/empty pages, retry at
the same offset, cancellation, detail pause/retry, snapshot count/bytes, bounded
folder history, and known/unknown response-length limits. Pure state tests do
not instrument Activities or establish real-NAS compatibility.

Use `test/large_library_server.py` with synthetic `test` / `test` credentials.
For a short pause/resume device check, `--profile sparse-video --count 200` puts
the only video beyond the first 175 scanned rows. Use `--count 2400` for window
replacement; the place profiles and one-shot failure/delay switches are described
in the [contract/fixture guide](large-library-api-contract.md). Record exact APK
hash, profile, request trace, UI screenshots and observed failures. Synthetic
pagination tests are not a measurement of NAS throughput, hardware FPS or memory.

### Local candidate check (2026-10-31)

- 242 JVM tests and 62 Python tests passed; no skips.
- `assembleDebug` and `lintDebug` passed; lint reports 0 errors / 93 warnings.
- Final code candidate APK SHA-256:
  `86e6080233f5ff30fa6847660b056e0028bcba116d1b40e5013c5e48b41e30ae`.
- Targeted official API 29 TV emulator checks are recorded below. They are not
  a complete device-validation pass or a real-NAS benchmark.

The initial device candidate `89a9eb29` proved five-page sparse-scan pause, no
extra requests during 52 seconds idle, explicit resume at offset 175, and place
unknown/has-more explanations. It exposed clipped action hints on long status
cards. Candidate `72a83dcf` made action titles short/readable and kept complete
boundary explanations in a dialog; place byte-limit/HTTP 503/retry recovery was
also exercised on that candidate. The final candidate additionally fixes the
missing-`hasNext` short-page contract; results must be attributed to the APK that
was actually installed rather than claiming all earlier tests ran on the final
binary.

Completeness notices are placed before location and capped unpaged results, so
the user need not scroll through 1,200 photos to discover that more may exist.

## Targeted emulator results (2026-10-31)

Official Android TV API 29 x86, software acceleration (no KVM), synthetic data
only. Every installed candidate was pulled back from the emulator and its hash
verified. The final APK is `86e60802…` as identified above.

| Candidate | Observed result |
| --- | --- |
| Final `86e60802` | PASS: `limit=35`, synthetic cap 7, absent continuation metadata; offsets 0/7/14 found the unique twentieth video; explicit continuation to offset 20 returned empty and ended. |
| Final `86e60802` | PASS: 2,400-item place result displays at most 1,200; first row exposes the complete truncation/unknown-continuation explanation in a dialog. |
| Final `86e60802` | PASS: switch to Places while an older gallery request is delayed ten seconds; after the late response and over 40 seconds observation, UI stayed on Places and no offset-35 follow-on scan appeared. This tests new-category cancellation, not Back-during-scan. |
| Earlier `89a9eb29` | PASS: sparse 200-item fixture requests exactly five 35-row pages, waits 52 seconds without continuing, then explicit confirmation requests offset 175 and finds the tail video. |
| Earlier `89a9eb29` / `72a83dcf` | PASS on their respective builds: unknown/has-more place warnings; oversized metadata error; same-screen HTTP 503 retry followed by recovery and Back to directory. |
| Earlier `2a9c620e` | PARTIAL only: album offsets 0/35/70 load 105 items and present an explicit load-more footer. This is not a full-window pass. |

No AndroidRuntime crash was observed in these targeted checks. Final short-page
and place fixtures rendered synthetic thumbnails; an earlier fixture thumbnail
route bug caused 404s and was corrected, so those earlier stages do not establish
decoded-image memory behavior.

**Not run end-to-end on a device:** reaching the 1,190/1,200 paged boundary and
replacing it with the next batch; folder/search full windows; all favorites
flows; detail-right continuation; Back during a pending scan; final-binary rerun
of the entire earlier-candidate matrix. The 100,000-row window/offset checks are
JVM state tests, not substitutes for these UI tests. No valid near-full versus
post-window heap pair was obtained; no measured memory improvement is claimed.

One test-infrastructure interruption terminated the persistent emulator shell
while cancelling a long key sequence. It was not an application crash. The AVD
was restored without wiping data, and final checks used the restored session.
Software emulator startup/input latency is not reported as app performance.
Evidence for this run is kept outside Git in
`/workspace/shared/fnphoto-large-library-qa/RESULT.txt`, adjacent request traces,
installed-APK hashes, screenshots and crash log. The device was handed to the
separate remember-preference QA task after these checks.
