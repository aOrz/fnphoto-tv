#!/usr/bin/env python3
"""Reproducible, synthetic-only TV QA scenarios built on the existing mock API.

One fixture per process; this is not a NAS proxy or a production auth server.
Default listen address is loopback. Generated media/state/traces stay untracked.
"""
import argparse
import asyncio
import copy
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import io
import itertools
import json
from pathlib import Path
import re
import tempfile
import time

from aiohttp import web
from PIL import Image, ImageDraw, ImageFont
import test_server as mock

ROOT = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / ".generated"
FIXED_NOW = datetime(2026, 10, 31, 12, tzinfo=timezone.utc)
VIDEO_MODES = ("missing", "restored", "invalid")
SHARED_MODES = ("normal", "empty", "list-failure", "page-failure", "revoked")
# RequestKey is available on newer aiohttp; older supported versions use string keys.
VIDEO_MODE_KEY = web.RequestKey("qa_video_mode", str) if hasattr(web, "RequestKey") else "qa_video_mode"


def thumbnail(width, height, label="", size_label=""):
    """Fast real JPEGs with stable, readable labels; no user images or RNG state."""
    digest = hashlib.sha256(f"{width}:{height}:{label}:{size_label}".encode()).digest()
    image = Image.new("RGB", (width, height), tuple(45 + b % 130 for b in digest[:3]))
    caption = f"{label} {size_label}".strip()
    font = ImageFont.load_default()
    box = font.getbbox(caption)
    text = Image.new("RGBA", (max(1, box[2] - box[0] + 4), max(1, box[3] - box[1] + 4)))
    ImageDraw.Draw(text).text((2 - box[0], 2 - box[1]), caption, font=font, fill="white")
    scale = min(width * .88 / text.width, height * .22 / text.height)
    text = text.resize((max(1, int(text.width * scale)), max(1, int(text.height * scale))))
    image.paste(text, ((width - text.width) // 2, (height - text.height) // 2), text)
    output = io.BytesIO()
    image.save(output, format="JPEG", quality=75)
    return output.getvalue()


def configure_fixture(profile="large"):
    if profile not in ("demo", "large", "video", "shared"):
        raise ValueError("Unknown synthetic fixture profile")
    for name in ("PHOTOS", "PHOTO_MAP", "ALBUMS", "ALBUM_MAP", "PERSONS", "PERSON_MAP",
                 "TIMELINE_ITEMS", "PERSONS_BY_ID", "PERSON_PHOTOS", "GEO_ITEMS", "PLACEHOLDER_CACHE"):
        getattr(mock, name).clear()
    mock.PHOTO_COUNT = 80 if profile == "demo" else 360
    mock.ALBUM_COUNT = 6 if profile == "demo" else 1
    mock.NOW = FIXED_NOW
    mock.RNG.seed(42)
    identifiers = itertools.count(1)
    # A separate deterministic ID stream preserves the original seed-42 photo choices.
    mock._rng_uuid = lambda: hashlib.sha256(f"fnphoto-qa-42-{next(identifiers)}".encode()).hexdigest()[:16]
    mock.init_data()
    mock._make_thumbnail = thumbnail
    for photo in mock.PHOTOS:
        if photo["id"] in (124, 151, 314):
            photo["category"], photo["fileType"] = "video", "mp4"
        photo["fileName"] = ("test_video_%04d.mp4" if photo["category"] == "video" else "test_photo_%04d.jpg") % photo["id"]
        photo["width"], photo["height"], photo["rotation"] = 1920, 1080, 0
        # Stable single-favorite date for the remove-last-item / Back regression.
        if photo["id"] == 29:
            photo["category"], photo["fileType"], photo["fileName"] = "photo", "jpg", "test_photo_0029.jpg"
            photo["isCollect"] = 1
            photo["dateTime"] = photo["photoDateTime"] = "2026-10-30 12:00:00"
        elif photo["photoDateTime"][:10] >= "2026-10-30":
            photo["dateTime"] = photo["photoDateTime"] = "2026-10-29 " + photo["photoDateTime"][11:]
        photo["filePath"] = photo["showFilePath"] = "/photos/synthetic/" + photo["fileName"]
    groups = defaultdict(list)
    for photo in mock.PHOTOS:
        groups[photo["photoDateTime"][:10]].append(photo)
    mock.TIMELINE_ITEMS = []
    for date, photos in sorted(groups.items()):
        year, month, day = map(int, date.split("-"))
        mock.TIMELINE_ITEMS.append({"year": year, "month": month, "day": day,
            "itemCount": len(photos), "previewThumbs": [p["additional"]["thumbnail"]["xsUrl"] for p in photos[:4]]})
    if profile != "demo":
        album = mock.ALBUMS[0]
        album["albumName"] = "Paging QA - 360 synthetic items"
        mock.ALBUM_MAP[album["albumId"]]["photos"] = list(mock.PHOTOS)
    for entry in mock.ALBUM_MAP.values():
        photos, album = entry["photos"], entry["album"]
        album["photoCount"] = sum(p["category"] == "photo" for p in photos)
        album["videoCount"] = sum(p["category"] == "video" for p in photos)
        album["startDateTime"] = min(p["dateTime"] for p in photos)
        album["endDateTime"] = max(p["dateTime"] for p in photos)


def set_shared_mode(state_dir, mode, delay_ms=0):
    """Local-only control; never expose fixture controls over HTTP."""
    if mode not in SHARED_MODES or not 0 <= delay_ms <= 30000:
        raise ValueError("Unknown shared mode or delay outside 0..30000 ms")
    directory = Path(state_dir)
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, delete=False) as out:
        json.dump({"mode": mode, "delay_ms": delay_ms}, out)
        temporary = Path(out.name)
    temporary.replace(directory / "shared-mode.json")


def shared_routes(app, state_dir):
    # Dedicated received media do not appear in the user's own gallery/album.
    photos = []
    for index, original in enumerate(mock.PHOTOS[:125]):
        photo = copy.deepcopy(original)
        photo["id"] = 10001 + index
        photo["photoUUID"] = f"shared-fixture-{photo['id']}"
        photo["category"] = "video" if index % 10 == 9 else "photo"
        photo["fileName"] = f"shared_{photo['id']}." + ("mp4" if photo["category"] == "video" else "jpg")
        photo["fileType"] = "mp4" if photo["category"] == "video" else "jpg"
        photo["filePath"] = photo["showFilePath"] = "/photos/synthetic-shared/" + photo["fileName"]
        photo.pop("additional", None)  # Exercise documented UUID stream fallback, not ordinary metadata.
        mock.PHOTO_MAP[photo["id"]] = photo
        photos.append(photo)
    albums = [{"albumId": 9001, "albumName": "Shared QA - 125 received items",
               "photoCount": 113, "videoCount": 12,
               "posterUrl": "/p/api/v1/stream/p/t/10001/s/shared-fixture-10001",
               "ownerName": "Synthetic Other Owner"},
              {"albumId": 9002, "albumName": "Shared QA - empty", "photoCount": 0,
               "videoCount": 0, "posterUrl": "", "ownerName": "Synthetic Other Owner"}]

    async def state():
        path = Path(state_dir) / "shared-mode.json"
        try:
            value = json.loads(path.read_text()) if path.exists() else {"mode": "normal", "delay_ms": 0}
            mode, delay = value["mode"], value["delay_ms"]
            if mode not in SHARED_MODES or not isinstance(delay, int) or not 0 <= delay <= 30000:
                raise ValueError("Invalid state")
        except (OSError, KeyError, TypeError, ValueError) as error:
            raise web.HTTPServiceUnavailable(text="Invalid shared fixture state; reset with shared-control") from error
        await asyncio.sleep(delay / 1000)
        return mode

    def paging(request, expected):
        if set(request.query) != set(expected) | {"offset", "limit"}:
            raise web.HTTPBadRequest(text="Unexpected shared query names")
        if any(request.query[key] != value for key, value in expected.items()):
            raise web.HTTPBadRequest(text="Unexpected shared query values")
        try:
            offset, limit = int(request.query["offset"]), int(request.query["limit"])
            if offset < 0 or not 1 <= limit <= 1000:
                raise ValueError("Invalid paging")
        except ValueError as error:
            raise web.HTTPBadRequest(text="Invalid shared offset/limit") from error
        return offset, limit

    async def listing(request):
        offset, limit = paging(request, {"sort_by": "share_mod_time", "sort_direction": "desc"})
        mode = await state()
        if mode == "list-failure":
            raise web.HTTPServiceUnavailable(text="Intentional synthetic shared list failure")
        items = [] if mode == "empty" else albums
        return mock.ok_v2({"total": len(items), "list": items[offset:offset + limit]})

    async def content(request):
        album_id = request.query.get("albumId", "")
        offset, limit = paging(request, {"albumId": album_id, "startTime": "2000:01:01 00:00:00",
            "endTime": "2099:12:31 23:59:59", "sortDirection": "desc"})
        mode = await state()
        if mode == "revoked":
            raise web.HTTPForbidden(text="Synthetic permission revocation; not a NAS error contract")
        if mode == "page-failure" and offset >= 50:
            raise web.HTTPServiceUnavailable(text="Intentional synthetic shared next-page failure")
        if album_id not in ("9001", "9002"):
            raise web.HTTPNotFound(text="No such synthetic shared album")
        items = photos if album_id == "9001" else []
        page = items[offset:offset + limit]
        return mock.ok_v2({"list": page, "count": len(page), "hasNext": offset + len(page) < len(items)})

    app.router.add_get("/p/api/v1/album_grant/list_to_me", listing)
    app.router.add_get("/p/api/v1/album/normal/getList", content)
    app.router.add_get("/p/api/v1/stream/p/t/{id}/m/{uuid}", mock.handle_stream_thumbnail)


def set_video_mode(state_dir, mode):
    if mode not in VIDEO_MODES:
        raise ValueError("Unknown video mode")
    directory = Path(state_dir)
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, delete=False) as out:
        json.dump({"video_mode": mode}, out)
        temporary = Path(out.name)
    temporary.replace(directory / "video-mode.json")


def get_video_mode(state_dir):
    path = Path(state_dir) / "video-mode.json"
    if not path.exists():
        return "missing"
    try:
        mode = json.loads(path.read_text(encoding="utf-8"))["video_mode"]
    except (ValueError, KeyError, TypeError, OSError) as error:
        raise web.HTTPServiceUnavailable(text="Invalid local fixture state; reset with the control command") from error
    if mode not in VIDEO_MODES:
        raise web.HTTPServiceUnavailable(text="Unknown local fixture state; reset with the control command")
    return mode


def video_handler(profile, media_dir, state_dir):
    async def serve_video(request):
        try:
            media_id = int(request.match_info["id"])
        except ValueError as error:
            raise web.HTTPBadRequest(text="Expected numeric synthetic media id") from error
        if media_id not in mock.PHOTO_MAP:
            raise web.HTTPNotFound(text="No such synthetic media")
        mode = get_video_mode(state_dir) if profile == "video" and media_id == 124 else "restored"
        request[VIDEO_MODE_KEY] = mode
        if mode == "missing":
            raise web.HTTPNotFound(text="Intentional synthetic missing-video scenario", headers={"Cache-Control": "no-store"})
        if mode == "invalid":
            return web.Response(body=b"not an mp4; intentional synthetic decoder failure\n" * 100,
                                content_type="video/mp4", headers={"Cache-Control": "no-store"})
        path = Path(media_dir) / ("clip_b.mp4" if media_id == 314 else "clip_a.mp4")
        if not path.is_file():
            raise web.HTTPServiceUnavailable(text="Generate synthetic clips with test/generate_qa_media.py first")
        # FileResponse implements real byte ranges without reading the whole movie into RAM.
        return web.FileResponse(path, headers={"Cache-Control": "no-store"})
    return serve_video


def request_trace(path):
    """Record only synthetic numeric paging information, never auth/WS query values."""
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)

    async def trace(request, response):
        # on_response_prepare sees FileResponse's final 206 status after Range handling.
        resource = request.match_info.route.resource
        event = {"time": time.time(), "method": request.method,
                 "route": resource.canonical if resource else "/unmatched", "status": response.status}
        identifier = request.match_info.get("id", "")
        if identifier.isdigit():
            event["media_id"] = int(identifier)
        for key in ("offset", "limit", "album_id", "albumId", "personId", "id", "is_collect"):
            value = request.query.get(key)
            if value is not None and re.fullmatch(r"-?\d+", value):
                event[key] = int(value)
        if VIDEO_MODE_KEY in request:
            event["video_mode"] = request[VIDEO_MODE_KEY]
        with destination.open("a", encoding="utf-8") as out:
            out.write(json.dumps(event, sort_keys=True) + "\n")
    return trace


def build_qa_app(profile="large", media_dir=DEFAULT_OUTPUT / "media",
                 state_dir=DEFAULT_OUTPUT / "state", trace_file=None):
    configure_fixture(profile)
    app = mock.build_app(video_handler(profile, media_dir, state_dir))
    if profile == "shared":
        shared_routes(app, state_dir)
    if trace_file:
        app.on_response_prepare.append(request_trace(trace_file))
    return app


def main(argv=None):
    import sys
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments[:1] == ["shared-control"]:
        parser = argparse.ArgumentParser(description="Local synthetic shared-album state control")
        parser.add_argument("--state-dir", type=Path, default=DEFAULT_OUTPUT / "state")
        parser.add_argument("--mode", choices=SHARED_MODES, required=True)
        parser.add_argument("--delay-ms", type=int, default=0)
        args = parser.parse_args(arguments[1:])
        if not 0 <= args.delay_ms <= 30000:
            parser.error("delay-ms must be between 0 and 30000")
        set_shared_mode(args.state_dir, args.mode, args.delay_ms)
        print("Synthetic shared-album mode:", args.mode, "delay-ms:", args.delay_ms)
        return 0
    if arguments[:1] == ["control"]:
        parser = argparse.ArgumentParser(description="Change the local synthetic video124 scenario; no remote control API")
        parser.add_argument("--state-dir", type=Path, default=DEFAULT_OUTPUT / "state")
        parser.add_argument("--video-mode", choices=VIDEO_MODES, required=True)
        args = parser.parse_args(arguments[1:])
        set_video_mode(args.state_dir, args.video_mode)
        print("Synthetic video 124 mode:", args.video_mode)
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("demo", "large", "video", "shared"), default="large")
    parser.add_argument("--host", default="127.0.0.1", help="Default loopback; expose only on an explicitly isolated test network")
    parser.add_argument("--port", type=int, default=5666)
    parser.add_argument("--media-dir", type=Path, default=DEFAULT_OUTPUT / "media")
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_OUTPUT / "state")
    parser.add_argument("--trace-file", type=Path, default=DEFAULT_OUTPUT / "requests.jsonl")
    args = parser.parse_args(arguments)
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if args.profile == "video" and not all((args.media_dir / name).is_file() for name in ("clip_a.mp4", "clip_b.mp4")):
        parser.error("Generate clips first: python test/generate_qa_media.py --output-dir " + str(args.media_dir))
    print("SYNTHETIC ONLY: accepts fake login values (use test/test); never enter a real account.")
    print("Fixed seed=42, clock=2026-10-31T12:00:00Z; 80 demo or 360 large/video items.")
    print("Video profile: 124=controlled missing/restored/invalid; 151=A; 314=B.")
    if args.host not in ("127.0.0.1", "::1", "localhost"):
        print("WARNING: exposed mock has no access control. Use only an isolated test network.")
    app = build_qa_app(args.profile, args.media_dir, args.state_dir, args.trace_file)
    # The structured trace omits credentials; aiohttp's default URL access log can include them.
    web.run_app(app, host=args.host, port=args.port, access_log=None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
