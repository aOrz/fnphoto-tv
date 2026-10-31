# Large-library API evidence and deliberate limits

Checked against `main@26e6b765` on 2026-10-31. These findings distinguish an
existing client contract from proof about a particular NAS version.

## Confirmed existing request shapes

- `API.md:139-183` documents `GET /p/api/v1/gallery/getList` with `start_time`,
  `end_time`, `limit`, `offset`, and `mode=index`. `category` is shown only as a
  **response** field. It is not evidence that category filtering is supported
  as a request parameter.
- Upstream [API.md](https://github.com/ljmljz/fnphoto-tv/blob/main/API.md) has the
  same date-range shape. The referenced
  [FNOSP/fnnas-api project](https://github.com/FNOSP/fnnas-api) documents NAS
  authentication; it was not a verified photo-search pagination contract.
- Existing `MainFragment.loadPhotosByGeo` and its introducing commit `5655cd8`
  POST `/p/api/v1/search/results` with `keyword`, `filters` containing
  `photo_location` and a country/city subfilter, and `antiFilters`. There is no
  established cursor/offset/limit request shape for this endpoint in the repo.
  A targeted public search found no additional reliable contract. This negative
  search is not proof that the server lacks such a feature.

No new server parameters are guessed. Testing against a synthetic handler is
not proof that a real NAS accepts pagination or returns every matching item.
The old mock location handler ignored the location filter and returned the
first 20 items while declaring `hasNext=false`; it must not be cited as evidence
of result completeness.

## Bounded location response

`GeoPhotoResponseReader` serializes the existing JSON filter rather than
interpolating country/city strings. It streams JSON from a bounded input stream,
retains at most the caller's `maxItems` unique display records, and discards
unused metadata (including tags/description). It reads at most `maxBytes + 1`
bytes; the extra byte detects overflow. Malformed, interrupted, oversized, or
unsuccessful responses fail explicitly and close the stream. It never reports a
partially parsed or failed response as an empty successful result.

The result exposes `CLIENT_LIMIT`, `SERVER_HAS_MORE`, `UNKNOWN`, or
`SERVER_REPORTED_END`. Even the last value means **only what this one response
reported**, not independent proof of full real-NAS results. `count` is not
assumed to be a total. A larger `total` overrides a contradictory `hasNext=false`.
There is no automatic follow-on location request because a reliable continuation
contract is absent. UI must show the appropriate limitation and offer retry or
Back; it must not display “all photos loaded” based only on the returned array.

Unit fixtures cover 10,000 rows, item/byte bounds, missing/conflicting metadata,
server errors after a data payload, malformed/truncated JSON, interruption,
stream closure, duplicate IDs, and quote-safe requests. These are client safety
checks, not physical-TV memory measurements or real-NAS compatibility tests.

## Reproducible synthetic boundary server

From repository root, using the existing Python QA dependencies:

```sh
python test/large_library_server.py --profile sparse-video --count 10000
python test/large_library_server.py --profile large-place --count 2400
python test/large_library_server.py --profile place-incomplete
python test/large_library_server.py --profile place-unknown
python test/large_library_server.py --profile place-oversize
```

Run one process at a time; default loopback port is 5666 and fake login is
`test` / `test`. Never enter real credentials. `sparse-video` sorts one video
behind all photos, so an automatic no-match page loop is observable. All items
belong to one synthetic date, album and `Boundary City, Synthetic Country`.
`large-place` returns more than the UI's 1,200-record budget; `place-incomplete`
returns 35 with truthful synthetic `hasNext=true`/`total`; `place-unknown` omits
those two fields; `place-oversize` adds over 9 MiB of discarded metadata to test
safe byte-limit failure. Unknown offset/limit fields deliberately do not make
this unverified location endpoint pageable. Request traces omit credentials and
are written under ignored `test/.generated/`.

Optional synthetic fault switches:

```sh
python test/large_library_server.py --profile sparse-video --fail-once-offset 35
python test/large_library_server.py --profile large-place --fail-first-location
python test/large_library_server.py --profile sparse-video --gallery-delay-ms 3000
python test/large_library_server.py --profile sparse-video --count 2400 \
  --album-delay-ms 3000 --thumbnail-delay-ms 8000 \
  --trace-file test/.generated/footer-requests.jsonl
python test/large_library_server.py --profile sparse-video --fail-once-album-offset 35
```

A one-shot HTTP 503 is followed by success at the **same** offset/request, without
restarting the server. Delay is bounded to 10 seconds and enables Back/new-screen
cancellation checks. These switches do not add API request parameters or expose
remote fixture controls.

For the slow-album scenario, open the synthetic album and press Down rapidly
until the loading footer is focused. Stop pressing keys while the metadata page
returns. Ordinary continuation should append photos and move focus from the
footer to the newly available content without requiring OK. Thumbnails can
still be pending: metadata pagination must not wait for image completion.
`--thumbnail-delay-ms` delays only thumbnail routes, independently of gallery
and album metadata. The gallery and album one-shot failures have separate
offsets; a failed page must remain retryable at the same offset without an
automatic retry loop. These scenarios require the current APK on a dedicated
device; fixture contract tests alone do not verify Leanback focus.

### Short page without pagination metadata

```sh
python test/large_library_server.py --profile sparse-video --count 20 \
  --gallery-page-cap 7 --omit-gallery-paging \
  --trace-file test/.generated/short-page-requests.jsonl
```

This synthetic server receives the unchanged client request `limit=35` but
returns at most 7 rows starting at the **requested raw offset**, with no
`hasNext`, `count`, or `total`. The correct sequence is offsets `0, 7, 14`
(the sole video is on the third page); a further offset `20` returns an empty
list to establish end-of-list. A short nonempty page is not an end signal when
`limit` means a maximum and paging metadata is absent. These CLI switches are
local fixture behavior, not additional NAS query parameters.
