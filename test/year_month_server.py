#!/usr/bin/env python3
"""Synthetic year/month navigation scenarios, never evidence of real NAS semantics.

Ordinary album 7001 keeps raw order: 210 non-September rows, then 35 September
rows. Capture dates deliberately move backward and forward inside the first 210
rows. Month queries for gallery/person/shared actually filter their own sources.
Use local ``control`` files for delays, one-shot failures, repetition and deletion.
"""
import argparse
import asyncio
from copy import deepcopy
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import sys
import tempfile
import time

from aiohttp import web
import qa_server
import test_server as mock

DEFAULT_STATE = qa_server.DEFAULT_OUTPUT / "year-month-state"
ALBUM_PATH = "/p/api/v1/album/photos"
GALLERY_PATH = "/p/api/v1/gallery/getList"
PERSON_PATH = "/p/api/v1/ai-person/photoLibrary/list"
SHARED_PATH = "/p/api/v1/album/normal/getList"
DATE_TEXT = re.compile(r"[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\Z")


def control_state(state_dir, *, delay_ms=0, fail_once_offset=None,
                  repeat_album_offset=None, delete_id=None):
    """Replace fixture controls locally; no HTTP administration endpoint exists."""
    value = dict(delay_ms=delay_ms, fail_once_offset=fail_once_offset,
                 repeat_album_offset=repeat_album_offset, delete_id=delete_id,
                 revision=time.time_ns())
    validate_state(value)
    directory = Path(state_dir)
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, delete=False) as out:
        json.dump(value, out)
        temporary = Path(out.name)
    temporary.replace(directory / "year-month.json")


def validate_state(value):
    if not isinstance(value, dict) or type(value.get("delay_ms")) is not int or not 0 <= value["delay_ms"] <= 10000:
        raise ValueError("delay_ms must be 0..10000")
    for key in ("fail_once_offset", "repeat_album_offset", "delete_id"):
        if value.get(key) is not None and (type(value[key]) is not int or value[key] < 0):
            raise ValueError(key + " must be non-negative")
    if type(value.get("revision")) is not int:
        raise ValueError("Invalid control revision")


def read_state(state_dir):
    path = Path(state_dir) / "year-month.json"
    if not path.exists():
        return dict(delay_ms=0, fail_once_offset=None, repeat_album_offset=None, delete_id=None, revision=0)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        validate_state(value)
        return value
    except (OSError, ValueError, TypeError) as error:
        raise web.HTTPServiceUnavailable(text="Invalid synthetic control file; reset with control") from error


def configure_fixture():
    qa_server.configure_fixture("large")
    template = deepcopy(mock.PHOTOS[0])

    def photo(identifier, capture, index=0):
        value = deepcopy(template)
        category = "video" if index % 10 == 9 else "photo"
        value.update(id=identifier, ownerId=1, category=category,
                     fileType="mp4" if category == "video" else "jpg",
                     fileName="month_%04d.%s" % (identifier, "mp4" if category == "video" else "jpg"),
                     photoDateTime=capture,
                     dateTime=(datetime(2026, 11, 1, 12) - timedelta(seconds=index)).strftime("%Y-%m-%d %H:%M:%S"),
                     photoUUID="month-%d" % identifier, isCollect=int(index % 7 == 0),
                     filePath="/photos/synthetic-month/%d" % identifier,
                     showFilePath="/photos/synthetic-month/%d" % identifier, geo="",
                     width=1280, height=720, rotation=0)
        value["additional"]["thumbnail"] = {
            "mUrl": "/p/api/v1/stream/p/t/%d/o/month-%d?size=m" % (identifier, identifier),
            "sUrl": "/p/api/v1/stream/p/t/%d/o/month-%d?size=s" % (identifier, identifier),
            "xsUrl": "/p/api/v1/stream/p/t/%d/o/month-%d?size=xs" % (identifier, identifier),
            "originalUrl": "/p/api/v1/stream/p/t/%d/o/month-%d" % (identifier, identifier),
            "videoUrl": "/p/api/v1/stream/v/%d" % identifier if category == "video" else None,
        }
        return value

    ordinary = [photo(1001 + index, "2026-09-15 12:00:00" if index >= 210
                      else "2026-08-20 12:00:00" if index % 2 else "2026-10-30 12:00:00", index)
                for index in range(245)]
    unknown = [photo(2001, None), photo(2002, "2026-02-30 12:00:00"),
               photo(2003, "2026-09-15 12:00:00")]
    # These values demonstrate why upload time must not replace missing capture time.
    for item in unknown:
        item["dateTime"] = "2026-09-15 12:00:00"
    shared = [photo(11001 + index, "2026-10-30 12:00:00" if index < 60
                    else "2026-09-15 12:00:00", index) for index in range(85)]
    for item in shared:
        item.pop("additional")  # Exercise shared UUID media handling.
    mock.PHOTOS[:] = ordinary
    mock.PHOTO_MAP.clear()
    mock.PHOTO_MAP.update({item["id"]: item for item in ordinary + unknown + shared})
    mock.TIMELINE_ITEMS[:] = [dict(year=2026, month=month, day=day,
        itemCount=sum(p["photoDateTime"].startswith("2026-%02d" % month) for p in ordinary), previewThumbs=[])
        for month, day in ((10, 30), (9, 15), (8, 20))]
    mock.ALBUMS.clear()
    mock.ALBUM_MAP.clear()
    for identifier, title, items in ((7001, "Month QA - scan past five pages", ordinary),
                                    (7002, "Month QA - unknown and matching dates", unknown),
                                    (7003, "Month QA - empty album", []),
                                    (7004, "Month QA - unknown dates only", unknown[:2])):
        cover = items[0]["additional"]["thumbnail"]["mUrl"] if items else ""
        album = dict(albumId=identifier, albumName=title, photoCount=sum(p["category"] == "photo" for p in items),
                     videoCount=sum(p["category"] == "video" for p in items), posterUrl=cover, posterImgUrl=cover)
        mock.ALBUMS.append(album)
        mock.ALBUM_MAP[identifier] = dict(album=album, photos=items)
    person_photos = ordinary[::3]
    person = dict(id=71, name="Month QA Person", faceId=171, itemCount=len(person_photos), isHide=False)
    mock.PERSONS[:] = [person]
    mock.PERSON_MAP.clear()
    mock.PERSON_MAP[71] = dict(person=person, photos=person_photos, faceId=171)
    mock.PERSONS_BY_ID.clear()
    mock.PERSONS_BY_ID[71] = person
    mock.PERSON_PHOTOS.clear()
    mock.PERSON_PHOTOS[71] = person_photos
    mock.GEO_ITEMS[:] = []
    return ordinary, shared


def paging(request, expected):
    if set(request.query) != set(expected) | {"offset", "limit"}:
        raise web.HTTPBadRequest(text="Unexpected synthetic query names")
    if any(request.query[key] != str(value) for key, value in expected.items()):
        raise web.HTTPBadRequest(text="Unexpected synthetic query values")
    try:
        offset, limit = int(request.query["offset"]), int(request.query["limit"])
        if offset < 0 or not 1 <= limit <= 1000:
            raise ValueError("Invalid paging")
        return offset, limit
    except ValueError as error:
        raise web.HTTPBadRequest(text="Invalid synthetic offset/limit") from error


def date_range(start, end):
    try:
        if not DATE_TEXT.fullmatch(start) or not DATE_TEXT.fullmatch(end):
            raise ValueError("Expected complete NAS text")
        first = datetime.strptime(start, "%Y:%m:%d %H:%M:%S")
        last = datetime.strptime(end, "%Y:%m:%d %H:%M:%S")
        if first > last:
            raise ValueError("Reversed date range")
        return first, last
    except (ValueError, TypeError) as error:
        raise web.HTTPBadRequest(text="Invalid synthetic date range") from error


def filtered(items, first, last):
    return [item for item in items if first <= datetime.strptime(item["photoDateTime"], "%Y-%m-%d %H:%M:%S") <= last]


def request_trace(path):
    """Only known metadata routes, numeric coordinates and validated date text."""
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)

    async def trace(request, response):
        if request.path not in (ALBUM_PATH, GALLERY_PATH, PERSON_PATH, SHARED_PATH):
            return
        event = dict(time=time.time(), route=request.path, status=response.status)
        for key in ("offset", "limit", "album_id", "albumId", "personId"):
            value = request.query.get(key, "")
            if re.fullmatch(r"[0-9]{1,10}", value):
                event[key] = int(value)
        for key in ("start_time", "end_time", "startTime", "endTime"):
            value = request.query.get(key, "")
            if DATE_TEXT.fullmatch(value):
                event[key] = value
        with destination.open("a", encoding="utf-8") as out:
            out.write(json.dumps(event, sort_keys=True) + "\n")
    return trace


def build_app(state_dir=DEFAULT_STATE, album_page_cap=None, omit_album_paging=False, trace_file=None):
    if album_page_cap is not None and not 1 <= album_page_cap <= 1000:
        raise ValueError("album_page_cap must be 1..1000")
    ordinary, shared = configure_fixture()
    failed_revision = None

    @web.middleware
    async def month_routes(request, handler):
        nonlocal failed_revision
        if request.path not in (ALBUM_PATH, GALLERY_PATH, PERSON_PATH, SHARED_PATH):
            return await handler(request)
        state = read_state(state_dir)
        if state["delay_ms"]:
            await asyncio.sleep(state["delay_ms"] / 1000)
        if request.path == ALBUM_PATH:
            album_id = request.query.get("album_id", "")
            offset, limit = paging(request, dict(album_id=album_id, sort_by="date_time", sort_direction="desc"))
            if album_id not in {str(key) for key in mock.ALBUM_MAP}:
                raise web.HTTPNotFound(text="Unknown synthetic ordinary album")
            fingerprint = (state["revision"], state["fail_once_offset"])
            if offset == state["fail_once_offset"] and failed_revision != fingerprint:
                failed_revision = fingerprint
                raise web.HTTPServiceUnavailable(text="Intentional one-shot synthetic album failure")
            items = mock.ALBUM_MAP[int(album_id)]["photos"]
        else:
            camel = request.path == SHARED_PATH
            start_key, end_key = ("startTime", "endTime") if camel else ("start_time", "end_time")
            start, end = request.query.get(start_key, ""), request.query.get(end_key, "")
            expected = {start_key: start, end_key: end}
            if request.path == GALLERY_PATH:
                expected["mode"] = "index"
                items = ordinary
            elif request.path == PERSON_PATH:
                expected.update(personId="71", album_id="71", startTime=start, endTime=end)
                items = mock.PERSON_PHOTOS[71]
            else:
                expected.update(albumId="9001", sortDirection="desc")
                items = shared
            offset, limit = paging(request, expected)
            first, last = date_range(start, end)
            if camel and (first.year < 2000 or last.year > 2099):
                raise web.HTTPBadRequest(text="Shared fixture preserves the 2000..2099 contract")
            items = filtered(items, first, last)
        items = [item for item in items if item["id"] != state["delete_id"]]
        effective_limit = min(limit, album_page_cap) if request.path == ALBUM_PATH and album_page_cap else limit
        start_offset = 0 if request.path == ALBUM_PATH and offset == state["repeat_album_offset"] else offset
        page = items[start_offset:start_offset + effective_limit]
        data = dict(list=page)
        if not (request.path == ALBUM_PATH and omit_album_paging):
            data.update(count=len(page), hasNext=offset + len(page) < len(items))
        return mock.ok_v2(data)

    app = mock.build_app()
    app.middlewares.append(month_routes)

    async def shared_listing(request):
        offset, limit = paging(request, dict(sort_by="share_mod_time", sort_direction="desc"))
        albums = [dict(albumId=9001, albumName="Month QA - shared source", photoCount=77,
                       videoCount=8, ownerName="Synthetic Owner",
                       posterUrl="/p/api/v1/stream/p/t/11001/s/month-11001")]
        return mock.ok_v2(dict(list=albums[offset:offset + limit], total=len(albums)))

    async def content_route(request):
        raise web.HTTPInternalServerError(text="Month middleware must handle this route")

    app.router.add_get("/p/api/v1/album_grant/list_to_me", shared_listing)
    app.router.add_get(SHARED_PATH, content_route)
    app.router.add_get("/p/api/v1/stream/p/t/{id}/m/{uuid}", mock.handle_stream_thumbnail)
    if trace_file:
        app.on_response_prepare.append(request_trace(trace_file))
    return app


def main(argv=None):
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments[:1] == ["control"]:
        parser = argparse.ArgumentParser(description="Replace local synthetic month controls (omitted controls reset)")
        parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
        parser.add_argument("--delay-ms", type=int, default=0)
        parser.add_argument("--fail-once-offset", type=int)
        parser.add_argument("--repeat-album-offset", type=int)
        parser.add_argument("--delete-id", type=int)
        args = parser.parse_args(arguments[1:])
        try:
            control_state(args.state_dir, delay_ms=args.delay_ms, fail_once_offset=args.fail_once_offset,
                          repeat_album_offset=args.repeat_album_offset, delete_id=args.delete_id)
        except ValueError as error:
            parser.error(str(error))
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5668)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--album-page-cap", type=int)
    parser.add_argument("--omit-album-paging", action="store_true")
    parser.add_argument("--trace-file", type=Path, default=qa_server.DEFAULT_OUTPUT / "year-month-requests.jsonl")
    args = parser.parse_args(arguments)
    if args.host not in ("127.0.0.1", "::1", "localhost"):
        parser.error("Synthetic month service is loopback only")
    if not 1 <= args.port <= 65535:
        parser.error("port must be 1..65535")
    try:
        app = build_app(args.state_dir, args.album_page_cap, args.omit_album_paging, args.trace_file)
    except ValueError as error:
        parser.error(str(error))
    print("SYNTHETIC ONLY: login test/test. No real NAS contract is verified.")
    print("Album 7001: September starts at raw offset 210 / ID 1211. July is empty.")
    web.run_app(app, host=args.host, port=args.port, access_log=None)


if __name__ == "__main__":
    main()
