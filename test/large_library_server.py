#!/usr/bin/env python3
"""Synthetic large-library boundary scenarios. Never a real-NAS contract proof."""
import argparse
import asyncio
from copy import deepcopy
from pathlib import Path
from aiohttp import web
import qa_server
import test_server as mock

PROFILES = ("sparse-video", "large-place", "place-incomplete", "place-unknown", "place-oversize")


def configure_fixture(profile="sparse-video", count=2400):
    if profile not in PROFILES or not 1 <= count <= 100000:
        raise ValueError("Expected known synthetic profile and 1..100000 items")
    qa_server.configure_fixture("large")
    template = deepcopy(mock.PHOTOS[0])
    photos = []
    for identifier in range(1, count + 1):
        photo = deepcopy(template)
        photo.update(id=identifier, category="video" if profile == "sparse-video" and identifier == 1 else "photo",
                     fileName="boundary_%06d.jpg" % identifier,
                     dateTime="2026-10-30 12:00:00", photoDateTime="2026-10-30 12:00:00",
                     geo="Boundary City, Synthetic Country", isCollect=0)
        photo["additional"]["thumbnail"] = {
            "mUrl": "/p/api/v1/stream/p/t/%d/o/boundary?size=m" % identifier,
            "sUrl": "/p/api/v1/stream/p/t/%d/s/boundary" % identifier,
            "originalUrl": "/p/api/v1/stream/p/t/%d/o/boundary" % identifier,
            "videoUrl": "/p/api/v1/stream/v/%d" % identifier if photo["category"] == "video" else None,
        }
        photos.append(photo)
    if profile == "place-oversize":
        # Deliberately exceeds the application's 8 MiB metadata-response budget.
        photos[0]["description"] = "synthetic-byte-limit-" * (9 * 1024 * 1024 // 20 + 1)
    mock.PHOTOS[:] = photos
    mock.PHOTO_MAP.clear()
    mock.PHOTO_MAP.update({p["id"]: p for p in photos})
    mock.TIMELINE_ITEMS[:] = [{"year": 2026, "month": 10, "day": 30, "itemCount": count, "previewThumbs": []}]
    album = mock.ALBUMS[0]
    album.update(albumName="Synthetic boundary %d" % count,
                 photoCount=count - (profile == "sparse-video"), videoCount=int(profile == "sparse-video"))
    mock.ALBUM_MAP[album["albumId"]]["photos"] = photos
    mock.GEO_ITEMS[:] = [{"country": "Synthetic Country", "city": "Boundary City", "itemCount": count,
                          "posterUrl": photos[0]["additional"]["thumbnail"]["mUrl"]}]
    mock.GEO_RESPONSE_LIMIT = 35 if profile in ("place-incomplete", "place-unknown") else None
    mock.GEO_REPORT_PAGING = profile != "place-unknown"


def gallery_boundary_response(request, gallery_page_cap=None, omit_gallery_paging=False):
    """Model a server-side page cap while keeping the client's requested raw offset."""
    offset, requested_limit = mock.extract_paging(request)
    effective_limit = requested_limit
    if gallery_page_cap is not None:
        effective_limit = gallery_page_cap if requested_limit == -1 else min(requested_limit, gallery_page_cap)
    ordered = mock.ordered_photos(request, mock.filter_dates(request, mock.PHOTOS))
    page, total = mock.paginate(ordered, offset, effective_limit)
    data = {"list": page} if omit_gallery_paging else mock.make_list(page, total, offset + len(page) < total)
    return mock.ok_v2(data)


def fault_middleware(fail_once_offset=None, fail_first_location=False, gallery_delay_ms=0,
                     gallery_page_cap=None, omit_gallery_paging=False, *,
                     album_delay_ms=0, fail_once_album_offset=None, thumbnail_delay_ms=0):
    """One-shot failures and cancellation delays apply only to this synthetic process."""
    failed_gallery = False
    failed_album = False
    failed_location = False

    @web.middleware
    async def faults(request, handler):
        nonlocal failed_gallery, failed_album, failed_location
        if request.path == "/p/api/v1/gallery/getList":
            if gallery_delay_ms:
                await asyncio.sleep(gallery_delay_ms / 1000)
            if fail_once_offset is not None and not failed_gallery and request.query.get("offset", "0") == str(fail_once_offset):
                failed_gallery = True
                raise web.HTTPServiceUnavailable(text="Intentional one-shot synthetic page failure")
            if gallery_page_cap is not None or omit_gallery_paging:
                return gallery_boundary_response(request, gallery_page_cap, omit_gallery_paging)
        if request.path == "/p/api/v1/album/photos":
            if album_delay_ms:
                await asyncio.sleep(album_delay_ms / 1000)
            if (fail_once_album_offset is not None and not failed_album
                    and request.query.get("offset", "0") == str(fail_once_album_offset)):
                failed_album = True
                raise web.HTTPServiceUnavailable(text="Intentional one-shot synthetic album page failure")
        if thumbnail_delay_ms and request.path.startswith("/p/api/v1/stream/p/t/"):
            # Main-list mUrl uses /o/...?...size=m; no-size /o/ remains an original.
            variant = request.path.rsplit("/", 2)[-2]
            if variant in ("s", "m") or (variant == "o" and request.query.get("size") in ("m", "s", "xs", "xxs")):
                await asyncio.sleep(thumbnail_delay_ms / 1000)
        if request.path == "/p/api/v1/search/results" and fail_first_location and not failed_location:
            failed_location = True
            raise web.HTTPServiceUnavailable(text="Intentional one-shot synthetic location failure")
        return await handler(request)
    return faults


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, default="sparse-video")
    parser.add_argument("--count", type=int, default=2400)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--fail-once-offset", type=int, help="First gallery request at this offset fails with HTTP 503")
    parser.add_argument("--fail-first-location", action="store_true", help="First location request fails with HTTP 503")
    parser.add_argument("--gallery-delay-ms", type=int, default=0, help="Synthetic gallery delay for cancellation tests, 0..10000")
    parser.add_argument("--album-delay-ms", type=int, default=0, help="Synthetic album/photos delay, independent of gallery and thumbnails, 0..10000")
    parser.add_argument("--fail-once-album-offset", type=int, help="First album/photos request at this offset fails with HTTP 503")
    parser.add_argument("--thumbnail-delay-ms", type=int, default=0, help="Synthetic thumbnail response delay (/s/, /m/, or /o/?size=...), independent of metadata, 0..10000")
    parser.add_argument("--gallery-page-cap", type=int, help="Synthetic server returns at most this many gallery rows despite requested limit")
    parser.add_argument("--omit-gallery-paging", action="store_true", help="Omit gallery hasNext/count/total to test unknown end-of-list")
    parser.add_argument("--port", type=int, default=5666)
    parser.add_argument("--trace-file", type=Path, default=qa_server.DEFAULT_OUTPUT / "boundary-requests.jsonl")
    args = parser.parse_args(argv)
    if not 1 <= args.count <= 100000 or not 1 <= args.port <= 65535:
        parser.error("count must be 1..100000 and port must be 1..65535")
    if (any(value is not None and value < 0 for value in (args.fail_once_offset, args.fail_once_album_offset))
            or any(not 0 <= value <= 10000 for value in (args.gallery_delay_ms, args.album_delay_ms, args.thumbnail_delay_ms))):
        parser.error("failure offset must be non-negative and delay must be 0..10000 ms")
    if args.gallery_page_cap is not None and not 1 <= args.gallery_page_cap <= 100000:
        parser.error("gallery page cap must be 1..100000")
    configure_fixture(args.profile, args.count)
    print("SYNTHETIC ONLY: login test/test, never use real credentials. No real-NAS contract is verified.")
    print("Profile %s, %d records; gallery order places the lone video last." % (args.profile, args.count))
    if args.host not in ("127.0.0.1", "::1", "localhost"):
        print("WARNING: mock has no access control; non-loopback is only for an isolated test network.")
    app = mock.build_app()
    app.middlewares.append(fault_middleware(args.fail_once_offset, args.fail_first_location,
        args.gallery_delay_ms, args.gallery_page_cap, args.omit_gallery_paging,
        album_delay_ms=args.album_delay_ms, fail_once_album_offset=args.fail_once_album_offset,
        thumbnail_delay_ms=args.thumbnail_delay_ms))
    app.on_response_prepare.append(qa_server.request_trace(args.trace_file))
    web.run_app(app, host=args.host, port=args.port, access_log=None)


if __name__ == "__main__":
    main()
